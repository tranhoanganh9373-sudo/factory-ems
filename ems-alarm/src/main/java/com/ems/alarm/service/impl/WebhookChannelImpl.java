package com.ems.alarm.service.impl;

import com.ems.alarm.config.AlarmProperties;
import com.ems.alarm.entity.Alarm;
import com.ems.alarm.entity.DeliveryStatus;
import com.ems.alarm.entity.WebhookConfig;
import com.ems.alarm.entity.WebhookDeliveryLog;
import com.ems.alarm.repository.AlarmRepository;
import com.ems.alarm.repository.WebhookConfigRepository;
import com.ems.alarm.repository.WebhookDeliveryLogRepository;
import com.ems.alarm.service.WebhookChannel;
import com.ems.alarm.service.WebhookSigner;
import com.ems.alarm.service.adapter.WebhookAdapter;
import com.ems.meter.entity.Meter;
import com.ems.meter.repository.MeterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class WebhookChannelImpl implements WebhookChannel {
    private static final Logger log = LoggerFactory.getLogger(WebhookChannelImpl.class);

    private final WebhookConfigRepository cfgRepo;
    private final WebhookDeliveryLogRepository deliveryRepo;
    private final AlarmRepository alarmRepo;
    private final MeterRepository meterRepo;
    private final Map<String, WebhookAdapter> adaptersByType;
    private final AlarmProperties props;
    private final ScheduledExecutorService retryScheduler;
    private final HttpClient http;

    public WebhookChannelImpl(WebhookConfigRepository cfgRepo,
                              WebhookDeliveryLogRepository deliveryRepo,
                              AlarmRepository alarmRepo,
                              MeterRepository meterRepo,
                              List<WebhookAdapter> adapters,
                              AlarmProperties props,
                              ScheduledExecutorService webhookRetryScheduler,
                              HttpClient webhookHttpClient) {
        this.cfgRepo = cfgRepo;
        this.deliveryRepo = deliveryRepo;
        this.alarmRepo = alarmRepo;
        this.meterRepo = meterRepo;
        this.adaptersByType = adapters.stream()
                .collect(Collectors.toMap(WebhookAdapter::getType, a -> a));
        this.props = props;
        this.retryScheduler = webhookRetryScheduler;
        this.http = webhookHttpClient;
    }

    @Override
    @Async("webhookExecutor")
    public void sendTriggered(Alarm a) {
        cfgRepo.findFirstByOrderByIdAsc()
                .filter(WebhookConfig::isEnabled)
                .ifPresent(cfg -> attemptDelivery(a, cfg, 1));
    }

    private void attemptDelivery(Alarm a, WebhookConfig cfg, int attempt) {
        WebhookAdapter adapter = adaptersByType.getOrDefault(cfg.getAdapterType(),
                adaptersByType.get("GENERIC_JSON"));
        Meter m = meterRepo.findById(a.getDeviceId()).orElse(null);
        String code = m != null ? m.getCode() : "unknown";
        String name = m != null ? m.getName() : "";
        String body = adapter.buildPayload(a, code, name);
        String sig = WebhookSigner.sign(cfg.getSecret(), body);

        long start = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.getUrl()))
                    .header("Content-Type", "application/json")
                    .header("X-EMS-Event", "alarm.triggered")
                    .header("X-EMS-Signature", sig)
                    .timeout(Duration.ofMillis(cfg.getTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            long dur = System.currentTimeMillis() - start;
            if (res.statusCode() / 100 == 2) {
                writeDeliveryLog(a, attempt, DeliveryStatus.SUCCESS, null,
                        res.statusCode(), (int) dur, body);
            } else {
                scheduleRetry(a, cfg, attempt, "HTTP " + res.statusCode(),
                        res.statusCode(), (int) dur, body);
            }
        } catch (Exception e) {
            long dur = System.currentTimeMillis() - start;
            scheduleRetry(a, cfg, attempt,
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    null, (int) dur, body);
        }
    }

    private void scheduleRetry(Alarm a, WebhookConfig cfg, int attempt, String error,
                               Integer status, int durMs, String body) {
        if (attempt > props.webhookRetryMax()) {
            writeDeliveryLog(a, attempt, DeliveryStatus.FAILED, error, status, durMs, body);
            log.error("Webhook delivery failed after {} attempts: alarm_id={}, last_error={}",
                    attempt, a.getId(), error);
            return;
        }
        int backoffSec = props.webhookRetryBackoffSeconds().get(attempt - 1);
        retryScheduler.schedule(
                () -> attemptDelivery(a, cfg, attempt + 1),
                backoffSec,
                TimeUnit.SECONDS);
    }

    private void writeDeliveryLog(Alarm a, int attempts, DeliveryStatus status, String error,
                                  Integer respStatus, Integer respMs, String body) {
        WebhookDeliveryLog row = new WebhookDeliveryLog();
        row.setAlarmId(a.getId());
        row.setAttempts(attempts);
        row.setStatus(status);
        row.setLastError(error);
        row.setResponseStatus(respStatus);
        row.setResponseMs(respMs);
        row.setPayload(body);
        deliveryRepo.save(row);
    }

    @Override
    public WebhookTestResult test(WebhookConfig cfg, Alarm sample, String code, String name) {
        WebhookAdapter adapter = adaptersByType.getOrDefault(cfg.getAdapterType(),
                adaptersByType.get("GENERIC_JSON"));
        String body = adapter.buildPayload(sample, code, name);
        long start = System.currentTimeMillis();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.getUrl()))
                    .header("Content-Type", "application/json")
                    .header("X-EMS-Event", "alarm.test")
                    .header("X-EMS-Signature", WebhookSigner.sign(cfg.getSecret(), body))
                    .timeout(Duration.ofMillis(cfg.getTimeoutMs()))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return new WebhookTestResult(res.statusCode(), System.currentTimeMillis() - start, null);
        } catch (Exception e) {
            return new WebhookTestResult(0, System.currentTimeMillis() - start, e.toString());
        }
    }

    @Override
    public void retryDelivery(Long deliveryLogId) {
        WebhookDeliveryLog old = deliveryRepo.findById(deliveryLogId).orElseThrow();
        Alarm a = alarmRepo.findById(old.getAlarmId()).orElseThrow();
        cfgRepo.findFirstByOrderByIdAsc().ifPresent(cfg -> attemptDelivery(a, cfg, 1));
    }
}
