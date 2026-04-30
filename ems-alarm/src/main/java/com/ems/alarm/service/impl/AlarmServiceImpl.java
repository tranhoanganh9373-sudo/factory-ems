package com.ems.alarm.service.impl;

import com.ems.alarm.dto.AlarmDTO;
import com.ems.alarm.dto.AlarmListItemDTO;
import com.ems.alarm.dto.HealthSummaryDTO;
import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.AlarmStatus;
import com.ems.alarm.entity.AlarmType;
import com.ems.alarm.entity.ResolvedReason;
import com.ems.alarm.exception.AlarmNotFoundException;
import com.ems.alarm.observability.AlarmMetrics;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.service.AlarmService;
import com.ems.alarm.service.AlarmStateMachine;
import com.ems.alarm.service.ThresholdResolver;
import com.ems.collector.poller.DeviceSnapshot;
import com.ems.collector.service.CollectorService;
import com.ems.core.dto.PageDTO;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.MeterRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AlarmServiceImpl implements AlarmService {

    private final AlarmRepository alarmRepo;
    private final AlarmStateMachine stateMachine;
    private final MeterRepository meterRepo;
    private final CollectorService collectorService;
    private final ThresholdResolver thresholds;
    private final AlarmMetrics metrics;

    public AlarmServiceImpl(AlarmRepository alarmRepo,
                            AlarmStateMachine stateMachine,
                            MeterRepository meterRepo,
                            CollectorService collectorService,
                            ThresholdResolver thresholds,
                            AlarmMetrics metrics) {
        this.alarmRepo = alarmRepo;
        this.stateMachine = stateMachine;
        this.meterRepo = meterRepo;
        this.collectorService = collectorService;
        this.thresholds = thresholds;
        this.metrics = metrics == null ? AlarmMetrics.NOOP : metrics;
    }

    @Override
    public PageDTO<AlarmListItemDTO> list(AlarmStatus status, Long deviceId, AlarmType type,
                                          OffsetDateTime from, OffsetDateTime to,
                                          int page, int size) {
        PageRequest pr = PageRequest.of(page - 1, size, Sort.by("triggeredAt").descending());
        Page<Alarm> p = alarmRepo.search(status, deviceId, type, from, to, pr);
        List<AlarmListItemDTO> items = p.getContent().stream().map(this::toListItem).toList();
        return PageDTO.of(items, p.getTotalElements(), page, size);
    }

    @Override
    public AlarmDTO getById(Long id) {
        Alarm a = alarmRepo.findById(id).orElseThrow(() -> new AlarmNotFoundException(id));
        Meter m = meterRepo.findById(a.getDeviceId()).orElse(null);
        String code = m != null ? m.getCode() : "unknown";
        String name = m != null ? m.getName() : "";
        return new AlarmDTO(
                a.getId(), a.getDeviceId(), code, name,
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
     * 手动解除告警。除业务流程外，刷新 {@code ems.alarm.resolved.total{reason="manual"}} counter
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
        List<DeviceSnapshot> snaps = collectorService.snapshots();
        long online = snaps.stream().filter(s -> s.lastReadAt() != null).count();
        long offline = snaps.size() - online;
        long alarmCount = alarmRepo.countByStatus(AlarmStatus.ACTIVE)
                + alarmRepo.countByStatus(AlarmStatus.ACKED);

        // 统计维护中设备数：通过 ThresholdResolver 按 snapshot 设备 id 逐一检查
        long maintenance = snaps.stream()
                .filter(s -> {
                    try {
                        Long deviceId = Long.parseLong(s.deviceId());
                        return thresholds.resolve(deviceId).maintenanceMode();
                    } catch (NumberFormatException e) {
                        return false;
                    }
                })
                .count();

        // top offenders：各设备 ACTIVE+ACKED 告警数前 5
        Map<Long, Long> activeByDevice = new HashMap<>();
        countByDevice(activeByDevice, AlarmStatus.ACTIVE);
        countByDevice(activeByDevice, AlarmStatus.ACKED);

        List<HealthSummaryDTO.TopOffender> top = activeByDevice.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(5)
                .map(e -> {
                    Meter m = meterRepo.findById(e.getKey()).orElse(null);
                    String code = m != null ? m.getCode() : "unknown";
                    return new HealthSummaryDTO.TopOffender(e.getKey(), code, e.getValue());
                })
                .toList();

        return new HealthSummaryDTO(online, offline, alarmCount, maintenance, top);
    }

    private void countByDevice(Map<Long, Long> acc, AlarmStatus status) {
        Page<Alarm> p = alarmRepo.search(status, null, null, null, null,
                PageRequest.of(0, 1000));
        for (Alarm a : p.getContent()) {
            acc.merge(a.getDeviceId(), 1L, Long::sum);
        }
    }

    private AlarmListItemDTO toListItem(Alarm a) {
        Meter m = meterRepo.findById(a.getDeviceId()).orElse(null);
        String code = m != null ? m.getCode() : "unknown";
        String name = m != null ? m.getName() : "";
        return new AlarmListItemDTO(
                a.getId(), a.getDeviceId(), code, name,
                a.getAlarmType(), a.getSeverity(), a.getStatus(),
                a.getTriggeredAt(), a.getLastSeenAt(), a.getAckedAt()
        );
    }
}
