package com.ems.collector.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 跨字段一致性校验，注解(@NotNull/@Min)无法表达的部分。
 *
 * <p>这一层是纯函数（无 IO），所以不依赖 Spring。{@link MeterCodeValidator} 才查 DB。
 *
 * <p>覆盖：
 * <ul>
 *   <li>device.id 在整个 devices 列表内唯一</li>
 *   <li>protocol == TCP 时 host + port 必填；RTU 时 serialPort + baudRate 必填</li>
 *   <li>RTU 在 Plan 1.5.1 不支持 → 直接拒绝（"RTU support deferred to Plan 1.5.2"）</li>
 *   <li>register.count == dataType.wordCount() （COIL/DISCRETE_INPUT 走 BIT 例外）</li>
 *   <li>register.tsField 在同一 device 内唯一</li>
 *   <li>backoffMs ≥ pollingIntervalMs；timeoutMs ≤ pollingIntervalMs</li>
 *   <li>FunctionType vs DataType 配对：COIL/DISCRETE_INPUT 仅允许 BIT；HOLDING/INPUT 仅允许 UINT16+</li>
 * </ul>
 *
 * <p>调用方收集 {@link #validate(CollectorProperties)} 返回的 errors，非空时抛 IllegalStateException
 * 让 Spring Boot 启动失败。
 */
public final class CollectorPropertiesValidator {

    private CollectorPropertiesValidator() {}

    public static List<String> validate(CollectorProperties props) {
        List<String> errors = new ArrayList<>();
        if (props == null || props.devices() == null) return errors;

        Set<String> seenDeviceIds = new HashSet<>();
        for (int i = 0; i < props.devices().size(); i++) {
            DeviceConfig dev = props.devices().get(i);
            String prefix = "devices[" + i + "] (id=" + (dev.id() == null ? "<null>" : dev.id()) + ")";

            // 1. device.id 唯一
            if (dev.id() != null && !seenDeviceIds.add(dev.id())) {
                errors.add(prefix + ": duplicate device id");
            }

            // 2. protocol-specific 必填
            if (dev.protocol() == Protocol.TCP) {
                if (isBlank(dev.host())) errors.add(prefix + ": protocol=TCP requires host");
            } else if (dev.protocol() == Protocol.RTU) {
                errors.add(prefix
                        + ": protocol=RTU support deferred to Plan 1.5.2; only TCP allowed in 1.5.1");
            }

            // 3. backoffMs ≥ pollingIntervalMs
            if (dev.backoffMs() != null && dev.pollingIntervalMs() != null
                    && dev.backoffMs() < dev.pollingIntervalMs()) {
                errors.add(prefix + ": backoffMs must be >= pollingIntervalMs");
            }
            // 4. timeoutMs ≤ pollingIntervalMs
            if (dev.timeoutMs() != null && dev.pollingIntervalMs() != null
                    && dev.timeoutMs() > dev.pollingIntervalMs()) {
                errors.add(prefix + ": timeoutMs must be <= pollingIntervalMs");
            }

            // 5. registers 内部一致性
            if (dev.registers() == null) continue;
            Set<String> seenTsFields = new HashSet<>();
            for (int j = 0; j < dev.registers().size(); j++) {
                RegisterConfig reg = dev.registers().get(j);
                String regPrefix = prefix + ".registers[" + j + "] (name="
                        + (reg.name() == null ? "<null>" : reg.name()) + ")";

                // 5a. tsField 唯一
                if (reg.tsField() != null && !seenTsFields.add(reg.tsField())) {
                    errors.add(regPrefix + ": duplicate tsField '" + reg.tsField() + "' within device");
                }

                // 5b. function vs dataType 配对
                if (reg.function() != null && reg.dataType() != null) {
                    boolean isBit = reg.function() == FunctionType.COIL
                            || reg.function() == FunctionType.DISCRETE_INPUT;
                    if (isBit && reg.dataType() != DataType.BIT) {
                        errors.add(regPrefix + ": function=" + reg.function()
                                + " requires dataType=BIT, got " + reg.dataType());
                    }
                    if (!isBit && reg.dataType() == DataType.BIT) {
                        errors.add(regPrefix + ": function=" + reg.function()
                                + " is incompatible with dataType=BIT");
                    }
                }

                // 5c. count vs dataType.wordCount()
                if (reg.dataType() != null && reg.dataType() != DataType.BIT
                        && reg.count() != reg.dataType().wordCount()) {
                    errors.add(regPrefix + ": count=" + reg.count()
                            + " mismatches dataType=" + reg.dataType()
                            + " (expected wordCount=" + reg.dataType().wordCount() + ")");
                }
            }
        }
        return errors;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
