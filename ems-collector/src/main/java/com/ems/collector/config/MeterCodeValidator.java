package com.ems.collector.config;

import com.ems.meter.repository.MeterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动期校验：CollectorProperties 里每个 device 的 meterCode 必须能在 meters 表查到。
 *
 * <p>挂在 ApplicationReadyEvent（不挂 @PostConstruct，因为 JPA repository / DB connection
 * 这时已经初始化完成）。任一 meterCode 找不到 → 抛 IllegalStateException 让进程退出 (fail-fast)。
 *
 * <p>同时跑 {@link CollectorPropertiesValidator} 的纯函数校验。
 *
 * <p>当 {@code ems.collector.enabled=false} 时整体跳过校验（允许加载残破配置但不启用）。
 */
@Component
public class MeterCodeValidator {

    private static final Logger log = LoggerFactory.getLogger(MeterCodeValidator.class);

    private final CollectorProperties props;
    private final MeterRepository meters;

    public MeterCodeValidator(CollectorProperties props, MeterRepository meters) {
        this.props = props;
        this.meters = meters;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void validateOnStartup() {
        if (!props.enabled()) {
            log.info("collector disabled (ems.collector.enabled=false); skipping startup validation");
            return;
        }

        List<String> errors = new ArrayList<>(CollectorPropertiesValidator.validate(props));

        for (DeviceConfig dev : props.devices()) {
            if (dev.meterCode() == null || dev.meterCode().isBlank()) continue; // @NotBlank already raised
            if (!meters.existsByCode(dev.meterCode())) {
                errors.add("device id=" + dev.id() + ": meterCode '"
                        + dev.meterCode() + "' not found in meters table");
            }
        }

        if (!errors.isEmpty()) {
            String msg = "collector configuration invalid:\n  - "
                    + String.join("\n  - ", errors);
            log.error(msg);
            throw new IllegalStateException(msg);
        }

        log.info("collector configuration validated: {} device(s) ready",
                props.devices().size());
    }
}
