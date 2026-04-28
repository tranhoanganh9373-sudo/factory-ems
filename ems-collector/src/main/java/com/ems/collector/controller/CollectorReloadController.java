package com.ems.collector.controller;

import com.ems.audit.annotation.Audited;
import com.ems.collector.config.CollectorProperties;
import com.ems.collector.config.CollectorPropertiesValidator;
import com.ems.collector.service.CollectorService;
import com.ems.core.dto.Result;
import com.ems.meter.repository.MeterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Reload 收到 POST → 重读磁盘 {@code collector.yml} → 校验 → 调
 * {@link CollectorService#reload(CollectorProperties)}。
 *
 * <p>不接受 body — 防止 ADMIN 误把临时 inline YAML 提交到生产环境。源始终是磁盘文件。
 *
 * <p>权限 ADMIN-only。每次 reload 写 audit log。
 */
@RestController
@RequestMapping("/api/v1/collector")
public class CollectorReloadController {

    private static final Logger log = LoggerFactory.getLogger(CollectorReloadController.class);

    private final CollectorService collector;
    private final MeterRepository meters;
    private final String configPath;

    public CollectorReloadController(CollectorService collector,
                                     MeterRepository meters,
                                     @Value("${ems.collector.config-file:collector.yml}") String configPath) {
        this.collector = collector;
        this.meters = meters;
        this.configPath = configPath;
    }

    @PostMapping("/reload")
    @PreAuthorize("hasRole('ADMIN')")
    @Audited(action = "COLLECTOR_RELOAD", resourceType = "COLLECTOR",
             resourceIdExpr = "'collector'",
             summaryExpr = "'reload from disk'")
    public Result<CollectorService.ReloadResult> reload() throws IOException {
        CollectorProperties newProps = readFromDisk(configPath);

        // 跨字段校验
        List<String> errors = CollectorPropertiesValidator.validate(newProps);
        // meter-code 存在性校验
        if (newProps.devices() != null) {
            for (var dev : newProps.devices()) {
                if (dev.meterCode() != null && !meters.existsByCode(dev.meterCode())) {
                    errors.add("device " + dev.id() + ": meterCode '"
                            + dev.meterCode() + "' not found in meters table");
                }
            }
        }
        if (!errors.isEmpty()) {
            String msg = "reload validation failed: " + String.join("; ", errors);
            log.warn(msg);
            return Result.error(40000, msg);
        }

        var result = collector.reload(newProps);
        log.info("admin-triggered collector reload: +{} ~{} -{} ={}",
                result.added().size(), result.modified().size(),
                result.removed().size(), result.unchanged());
        return Result.ok(result);
    }

    /** 用 SnakeYAML + Spring Binder 解析；不依赖 ConfigurationPropertiesBinding 的 refresh 周期。 */
    @SuppressWarnings("unchecked")
    static CollectorProperties readFromDisk(String pathStr) throws IOException {
        Path p = Path.of(pathStr);
        if (!Files.exists(p)) {
            // 在 classpath 里找（资源默认）
            try (InputStream in = CollectorReloadController.class.getClassLoader()
                    .getResourceAsStream(pathStr)) {
                if (in == null) {
                    throw new IOException("collector config file not found: " + pathStr);
                }
                Object root = new Yaml().load(in);
                return bindFromYamlMap((Map<String, Object>) root);
            }
        }
        try (InputStream in = Files.newInputStream(p)) {
            Object root = new Yaml().load(in);
            return bindFromYamlMap((Map<String, Object>) root);
        }
    }

    @SuppressWarnings("unchecked")
    private static CollectorProperties bindFromYamlMap(Map<String, Object> yaml) {
        // 解析 ems.collector.* 路径
        Map<String, Object> ems = (Map<String, Object>) yaml.getOrDefault("ems", Map.of());
        Map<String, Object> col = (Map<String, Object>) ems.getOrDefault("collector", Map.of());
        // 用 Spring Binder 把 Map 平铺成 ems.collector.* 路径，再 bind 到 record
        Map<String, Object> flat = new java.util.LinkedHashMap<>();
        flatten("ems.collector", col, flat);
        ConfigurationPropertySource src = new MapConfigurationPropertySource(flat);
        return new Binder(src).bindOrCreate("ems.collector", Bindable.of(CollectorProperties.class));
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Object node, Map<String, Object> out) {
        if (node instanceof Map<?, ?> m) {
            for (var e : ((Map<String, Object>) m).entrySet()) {
                flatten(prefix + "." + e.getKey(), e.getValue(), out);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flatten(prefix + "[" + i + "]", list.get(i), out);
            }
        } else {
            out.put(prefix, node);
        }
    }
}
