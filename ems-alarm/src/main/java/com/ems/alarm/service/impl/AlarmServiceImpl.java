package com.ems.alarm.service.impl;

import com.ems.alarm.dto.AlarmDTO;
import com.ems.alarm.dto.AlarmListItemDTO;
import com.ems.alarm.dto.HealthSummaryDTO;
import com.ems.alarm.dto.MeterOnlineState;
import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.alarm.entity.ResolvedReason;
import com.ems.alarm.exception.AlarmNotFoundException;
import com.ems.alarm.observability.AlarmMetrics;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.repository.AlarmSpecifications;
import com.ems.alarm.service.AlarmService;
import com.ems.alarm.service.AlarmStateMachine;
import com.ems.alarm.service.ThresholdResolver;
import com.ems.collector.channel.Channel;
import com.ems.collector.channel.ChannelRepository;
import com.ems.collector.sink.DiagnosticRingBuffer;
import com.ems.core.dto.PageDTO;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.MeterRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AlarmServiceImpl implements AlarmService {

    /**
     * "看到样本才算在线" 的窗口阈值：超过该时长未收到 GOOD 样本即视为离线。
     * 比典型采集周期（5s–60s）大一个数量级，给慢轮询协议（Modbus 5min 心跳）留余量。
     */
    static final Duration ONLINE_FRESHNESS_WINDOW = Duration.ofMinutes(5);

    private final AlarmRepository alarmRepo;
    private final AlarmStateMachine stateMachine;
    private final MeterRepository meterRepo;
    private final ChannelRepository channelRepo;
    private final DiagnosticRingBuffer ring;
    private final ThresholdResolver thresholds;
    private final AlarmMetrics metrics;

    public AlarmServiceImpl(AlarmRepository alarmRepo,
                            AlarmStateMachine stateMachine,
                            MeterRepository meterRepo,
                            ChannelRepository channelRepo,
                            DiagnosticRingBuffer ring,
                            ThresholdResolver thresholds,
                            AlarmMetrics metrics) {
        this.alarmRepo = alarmRepo;
        this.stateMachine = stateMachine;
        this.meterRepo = meterRepo;
        this.channelRepo = channelRepo;
        this.ring = ring;
        this.thresholds = thresholds;
        this.metrics = metrics == null ? AlarmMetrics.NOOP : metrics;
    }

    @Override
    public PageDTO<AlarmListItemDTO> list(AlarmStatus status, Long deviceId, AlarmType type,
                                          OffsetDateTime from, OffsetDateTime to,
                                          int page, int size) {
        PageRequest pr = PageRequest.of(page - 1, size, Sort.by("triggeredAt").descending());
        Page<Alarm> p = alarmRepo.findAll(AlarmSpecifications.matching(status, deviceId, type, from, to), pr);
        List<AlarmListItemDTO> items = p.getContent().stream().map(this::toListItem).toList();
        return PageDTO.of(items, p.getTotalElements(), page, size);
    }

    @Override
    public AlarmDTO getById(Long id) {
        Alarm a = alarmRepo.findById(id).orElseThrow(() -> new AlarmNotFoundException(id));
        DeviceLabel label = resolveDeviceLabel(a);
        return new AlarmDTO(
                a.getId(), a.getDeviceId(), a.getDeviceType(), label.code, label.name,
                a.getAlarmType(), a.getSeverity(), a.getStatus(),
                a.getTriggeredAt(), a.getAckedAt(), a.getAckedBy(),
                a.getResolvedAt(), a.getResolvedReason(),
                a.getLastSeenAt(), a.getDetail()
        );
    }

    @Override
    @Transactional
    public void ack(Long id, Long userId) {
        Alarm a = alarmRepo.findById(id).orElseThrow(() -> new AlarmNotFoundException(id));
        stateMachine.ack(a, userId);
        alarmRepo.save(a);
    }

    /**
     * 手动解除报警。除业务流程外，刷新 {@code ems.alarm.resolved.total{reason="manual"}} counter
     * 以及 {@code ems.alarm.active.count{type}} gauge（同步刷新该 type 的最新计数；其他 type 等
     * 下一轮 detector scan 触发的 refresh）。
     */
    @Override
    @Transactional
    public void resolve(Long id) {
        Alarm a = alarmRepo.findById(id).orElseThrow(() -> new AlarmNotFoundException(id));
        stateMachine.resolve(a, ResolvedReason.MANUAL);
        alarmRepo.save(a);
        metrics.incrementResolved("manual");
        AlarmType type = a.getAlarmType();
        if (type != null) {
            metrics.setActive(type.name().toLowerCase(Locale.ROOT),
                    alarmRepo.countActiveByType(type));
        }
    }

    @Override
    public long countActive() {
        return alarmRepo.countByStatus(AlarmStatus.ACTIVE);
    }

    @Override
    public HealthSummaryDTO healthSummary() {
        // V2.4: 在线判定基于 meter 的最近 GOOD 样本（看到样本才算在线），不再依赖
        // 遗留 YAML CollectorService.snapshots()——后者在新部署里始终为空。
        // computeMeterOnlineStatuses() 的逻辑同时被 meterOnlineStatuses() 端点复用，
        // 保证表计列表页与 health 卡的口径一致。
        Map<Long, MeterOnlineState> states = computeMeterOnlineStatuses();
        long total = states.size();
        long online = states.values().stream().filter(s -> s == MeterOnlineState.ONLINE).count();
        long maintenance = states.values().stream().filter(s -> s == MeterOnlineState.MAINTENANCE).count();
        long offline = total - online - maintenance;
        // 与"报警中心" drawer 口径一致：仅统计 ACTIVE（未确认/未处理）。ACKED 是"已知未修"，
        // 用户在 drawer 里清掉之后健康总览的"报警中"也应同步归零，否则视觉上数字对不上。
        long alarmCount = alarmRepo.countByStatus(AlarmStatus.ACTIVE);

        // top offenders：仅统计 ACTIVE，与上方"报警中"卡对齐——避免出现卡=0 / 表非空的视觉割裂。
        Map<Long, Long> activeByDevice = new HashMap<>();
        countByDevice(activeByDevice, AlarmStatus.ACTIVE);

        List<HealthSummaryDTO.TopOffender> top = activeByDevice.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(5)
                .map(e -> {
                    Meter m = meterRepo.findById(e.getKey()).orElse(null);
                    String code = m != null ? m.getCode() : "unknown";
                    return new HealthSummaryDTO.TopOffender(e.getKey(), code, e.getValue());
                })
                .toList();

        return new HealthSummaryDTO(online, offline, alarmCount, maintenance, total, top);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, MeterOnlineState> meterOnlineStatuses() {
        return computeMeterOnlineStatuses();
    }

    /**
     * 按 meter id 计算 ONLINE / OFFLINE / MAINTENANCE。
     *
     * <p>口径：
     * <ul>
     *   <li>thresholds.resolve(id).maintenanceMode()=true → MAINTENANCE（独立分支，避免维护期被误计离线）</li>
     *   <li>否则 ring.lastGoodSampleAt 在 freshness 窗口内 → ONLINE</li>
     *   <li>其余（含从未上报样本）→ OFFLINE</li>
     * </ul>
     *
     * <p>包含所有 meter（不过滤 enabled）。前端按 meter.enabled 单独渲染"未启用"。
     */
    private Map<Long, MeterOnlineState> computeMeterOnlineStatuses() {
        Instant freshnessCutoff = Instant.now().minus(ONLINE_FRESHNESS_WINDOW);
        List<Meter> allMeters = meterRepo.findAll();
        Map<Long, MeterOnlineState> result = new HashMap<>(allMeters.size() * 2);
        for (Meter m : allMeters) {
            if (thresholds.resolve(m.getId()).maintenanceMode()) {
                result.put(m.getId(), MeterOnlineState.MAINTENANCE);
                continue;
            }
            Instant lastGood = ring.lastGoodSampleAt(m.getChannelId(), m.getChannelPointKey());
            if (lastGood != null && lastGood.isAfter(freshnessCutoff)) {
                result.put(m.getId(), MeterOnlineState.ONLINE);
            } else {
                result.put(m.getId(), MeterOnlineState.OFFLINE);
            }
        }
        return result;
    }

    private void countByDevice(Map<Long, Long> acc, AlarmStatus status) {
        Page<Alarm> p = alarmRepo.findAll(AlarmSpecifications.matching(status, null, null, null, null),
                PageRequest.of(0, 1000));
        for (Alarm a : p.getContent()) {
            acc.merge(a.getDeviceId(), 1L, Long::sum);
        }
    }

    private AlarmListItemDTO toListItem(Alarm a) {
        DeviceLabel label = resolveDeviceLabel(a);
        return new AlarmListItemDTO(
                a.getId(), a.getDeviceId(), a.getDeviceType(), label.code, label.name,
                a.getAlarmType(), a.getSeverity(), a.getStatus(),
                a.getTriggeredAt(), a.getLastSeenAt(), a.getAckedAt()
        );
    }

    /**
     * 按 deviceType 分支查询 device 元数据。
     * <p>"CHANNEL" → 查 channel.name 作为 code（channel 表无 code 列，name 已是 unique 业务键）。
     * 其他（缺省/"METER"）→ 查 meter.code / meter.name。
     */
    private DeviceLabel resolveDeviceLabel(Alarm a) {
        if ("CHANNEL".equals(a.getDeviceType())) {
            Channel ch = channelRepo.findById(a.getDeviceId()).orElse(null);
            return new DeviceLabel(ch != null ? ch.getName() : "unknown", "");
        }
        Meter m = meterRepo.findById(a.getDeviceId()).orElse(null);
        return new DeviceLabel(m != null ? m.getCode() : "unknown",
                               m != null ? m.getName() : "");
    }

    private record DeviceLabel(String code, String name) {}
}
