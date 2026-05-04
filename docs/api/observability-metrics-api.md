# Observability Metrics API

> 适用版本：v1.7.0+ ｜ 受众：第三方集成 / BI / 自定义 alerting ｜ 最近更新：2026-04-29

本文档面向自建 Prometheus 抓取器、BI 看板、第三方报警系统的集成方，描述 factory-ems 暴露的 metrics 端点协议、17 个业务指标速查表、抓取建议与可直接 copy-paste 的集成示例脚本。**客户运维侧请改读 [observability-user-guide.md](../product/observability-user-guide.md)；工程团队部署侧请改读 [observability-deployment.md](../ops/observability-deployment.md)。**

---

## §0 通用约定

### 协议

- **端点**：`GET http://<server>:8080/actuator/prometheus`
- **格式**：Prometheus text format / OpenMetrics 0.0.1（`Content-Type: text/plain; version=0.0.4`）
- **HTTP 方法**：仅 GET
- **响应大小**：典型 ~80 KB（≈ 2000 行），含 17 业务指标 + Spring Boot 默认指标
- **变更策略**：指标名称 + label 集合视为 stable contract；新增指标走 minor version，破坏性改动走 major version

### 鉴权（v1）

- **默认**：内网无鉴权（spec §14：obs 端口默认 loopback；远程访问走 nginx 反代）
- **生产强烈建议**：在 nginx 反代上加 Basic Auth（见 §2.2）
- **不要**：把 `:8080/actuator/prometheus` 直接暴露在公网或客户访问路径

### 抓取节奏

- **推荐间隔**：15 秒（与 factory-ems 默认 `prometheus.yml` 一致）
- **可接受范围**：7s ~ 30s
- **scrape_timeout**：≤ 10s（spec §11.3）

### 命名约定

Micrometer 注册名包含 `.`，Prometheus 抓取时 `.` 自动转 `_`，并按指标类型展开：

| Micrometer 名 | Prometheus 命名 |
|---|---|
| `ems.collector.poll.duration` (Timer) | `ems_collector_poll_duration_seconds_count` / `_sum` / `_bucket` |
| `ems.collector.devices.online` (Gauge) | `ems_collector_devices_online` |
| `ems.collector.read.success.total` (Counter) | `ems_collector_read_success_total` |

> **重要**：本文 §3 的"PromQL"列已使用 Prometheus 命名（下划线形式），可直接复制运行。

### 公共 label（自动注入）

所有指标 ——**包括 Spring Boot 默认指标** —— 都会带：

| Label | 值 | 说明 |
|---|---|---|
| `application` | `factory-ems` | `MeterRegistryCustomizer` 静态注入 |
| `instance` | `${HOSTNAME:unknown}` | 容器/主机名 |

集成方在 PromQL 里可放心按 `{application="factory-ems"}` 过滤多套环境。

---

## §1 端点详情

### §1.1 GET `/actuator/prometheus`

#### 请求

```bash
curl -sS http://<server>:8080/actuator/prometheus
```

#### 响应（200 OK，节选 ~30 行）

```
# HELP ems_collector_devices_online  
# TYPE ems_collector_devices_online gauge
ems_collector_devices_online{application="factory-ems",instance="prod-ems-01",} 42.0

# HELP ems_collector_devices_offline  
# TYPE ems_collector_devices_offline gauge
ems_collector_devices_offline{application="factory-ems",instance="prod-ems-01",} 3.0

# HELP ems_collector_poll_duration_seconds  
# TYPE ems_collector_poll_duration_seconds histogram
ems_collector_poll_duration_seconds_bucket{adapter="modbus-tcp",application="factory-ems",instance="prod-ems-01",le="0.005",} 12.0
ems_collector_poll_duration_seconds_bucket{adapter="modbus-tcp",application="factory-ems",instance="prod-ems-01",le="0.01",} 47.0
ems_collector_poll_duration_seconds_bucket{adapter="modbus-tcp",application="factory-ems",instance="prod-ems-01",le="+Inf",} 512.0
ems_collector_poll_duration_seconds_count{adapter="modbus-tcp",application="factory-ems",instance="prod-ems-01",} 512.0
ems_collector_poll_duration_seconds_sum{adapter="modbus-tcp",application="factory-ems",instance="prod-ems-01",} 6.873

# HELP ems_meter_reading_lag_seconds  
# TYPE ems_meter_reading_lag_seconds gauge
ems_meter_reading_lag_seconds{application="factory-ems",instance="prod-ems-01",} 187.0

# HELP ems_alarm_active_count  
# TYPE ems_alarm_active_count gauge
ems_alarm_active_count{application="factory-ems",instance="prod-ems-01",type="silent_timeout",} 4.0
ems_alarm_active_count{application="factory-ems",instance="prod-ems-01",type="consecutive_fail",} 1.0

# HELP jvm_memory_used_bytes The amount of used memory
# TYPE jvm_memory_used_bytes gauge
jvm_memory_used_bytes{application="factory-ems",area="heap",id="G1 Eden Space",instance="prod-ems-01",} 1.34217728E8
...
# HELP http_server_requests_seconds  
# TYPE http_server_requests_seconds histogram
http_server_requests_seconds_count{application="factory-ems",instance="prod-ems-01",method="GET",outcome="SUCCESS",status="200",uri="/api/v1/alarms",} 318.0
...
```

#### 响应字段说明

| 行类型 | 说明 |
|---|---|
| `# HELP <name> <text>` | 指标描述（人类可读） |
| `# TYPE <name> <type>` | 指标类型（`counter` / `gauge` / `histogram` / `summary`） |
| `<name>{<labels>} <value>` | 实际数据点；histogram/summary 自动展开 `_count` / `_sum` / `_bucket` |

#### 错误码

| HTTP | 含义 | 处理建议 |
|---|---|---|
| 200 | 正常 | 抓取成功 |
| 401 / 403 | 鉴权失败（仅当配置 nginx Basic Auth） | 检查 Authorization header |
| 404 | 端点未启用 | 确认 `application-prod.yml` 中 `management.endpoints.web.exposure.include` 包含 `prometheus` |
| 503 | 应用未就绪 | 等待应用启动完成（首次 ~15s） |

---

## §2 鉴权

### §2.1 v1 默认：内网无鉴权

spec §14 / §16：obs 端口默认 loopback，远程访问走 nginx 反代。`/actuator/prometheus` **不暴露在公网**，仅供同 docker 网络（`ems-net`）内的 Prometheus / 集成抓取器访问。

### §2.2 推荐：nginx 反代 + Basic Auth

生产部署强烈建议在 nginx 反代加一层 Basic Auth。示例 `nginx.conf` 片段：

```nginx
# 生成密码：htpasswd -c /etc/nginx/.htpasswd_metrics scraper
location /metrics {
    auth_basic           "factory-ems metrics";
    auth_basic_user_file /etc/nginx/.htpasswd_metrics;

    # 仅允许内网 + 抓取器白名单 IP
    allow 10.0.0.0/8;
    allow 172.16.0.0/12;
    allow 192.168.0.0/16;
    deny  all;

    proxy_pass         http://factory-ems:8080/actuator/prometheus;
    proxy_set_header   Host $host;
    proxy_read_timeout 10s;
}
```

抓取端配置（Prometheus）：

```yaml
- job_name: factory-ems
  metrics_path: /metrics
  scheme: https
  basic_auth:
    username: scraper
    password_file: /etc/prometheus/secrets/factory-ems-metrics.txt
  static_configs:
    - targets: ['nginx.example.com:443']
```

### §2.3 可选：在应用内用 Spring Security 限制 `/actuator/**`

若不便于反代加鉴权，可以在 ems-app 内启用 Spring Security 限制 `/actuator/**`（v1 默认未启用）。

> **注意**：Spring Boot 3.x 已**移除** `management.security.enabled` 属性（旧 Boot 1.x 用法），仅靠 yaml 是无法启用的；必须显式定义 `SecurityFilterChain` Bean。Spring Security 6 起也不再支持 `WebSecurityConfigurerAdapter`。下例为 Spring Boot 3.3 / Spring Security 6 的最小骨架，未在 ems-app 中实际启用，集成方需自行验证。

```java
// 新增类（仅 prod profile）：ems-app/src/main/java/.../security/ActuatorSecurityConfig.java
@Configuration
@Profile("prod")
public class ActuatorSecurityConfig {

    @Bean
    SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/actuator/**")
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            .csrf(c -> c.disable());
        return http.build();
    }
}
```

```yaml
# application-prod.yml 增量 — 配套上面 SecurityFilterChain
spring:
  security:
    user:
      name: scraper
      password: ${METRICS_BASIC_AUTH_PASSWORD}     # 来自 .env，不要硬编码
      roles: ACTUATOR
```

> 推荐做法仍是 §2.2 nginx 反代 + Basic Auth：与产品栈现有 nginx 同栈，不需要改动应用代码、不影响 jar 体积、运维交接更直观。Spring Security 内置鉴权适合无反代场景或公网直连场景。

> **不要**：把生产 metrics 端点完全开放在公网而不带任何鉴权。

---

## §3 指标速查表

> **完整定义、cardinality、操作场景**请见 [observability-metrics-dictionary.md](../product/observability-metrics-dictionary.md)（480 行字典）。本节是集成方查询用的速查卡片。

### §3.1 ems-collector 采集模块（5 个）

| 指标名（Prometheus 命名） | 类型 | 单位 | 描述 | Labels | 典型 PromQL |
|---|---|---|---|---|---|
| `ems_collector_poll_duration_seconds` | histogram | seconds | 一轮设备采集耗时分布 | `adapter`, `application`, `instance` | `histogram_quantile(0.95, sum(rate(ems_collector_poll_duration_seconds_bucket[10m])) by (le, adapter))` |
| `ems_collector_devices_online` | gauge | count | 当前在线设备数 | — | `ems_collector_devices_online` |
| `ems_collector_devices_offline` | gauge | count | 当前离线设备数 | — | `ems_collector_devices_offline` |
| `ems_collector_read_success_total` | counter | count | 单设备读取成功累计 | `device_id` | `sum(rate(ems_collector_read_success_total[5m]))` |
| `ems_collector_read_failure_total` | counter | count | 单设备读取失败累计 | `device_id`, `reason` | `sum by (reason) (rate(ems_collector_read_failure_total[5m]))` |

`adapter`：`modbus-tcp` / `modbus-rtu`
`reason`：`timeout` / `crc` / `format` / `disconnected` / `other`

### §3.2 ems-alarm 报警模块（5 个）

| 指标名 | 类型 | 单位 | 描述 | Labels | 典型 PromQL |
|---|---|---|---|---|---|
| `ems_alarm_detector_duration_seconds` | histogram | seconds | 一轮检测扫描耗时 | — | `histogram_quantile(0.95, rate(ems_alarm_detector_duration_seconds_bucket[10m]))` |
| `ems_alarm_active_count` | gauge | count | 当前 ACTIVE+ACKED 报警数 | `type` | `sum by (type) (ems_alarm_active_count)` |
| `ems_alarm_created_total` | counter | count | 累计触发报警数 | `type` | `rate(ems_alarm_created_total[1h])` |
| `ems_alarm_resolved_total` | counter | count | 累计恢复报警数 | `reason` | `rate(ems_alarm_resolved_total{reason="auto"}[1h])` |
| `ems_alarm_webhook_delivery_duration_seconds` | histogram | seconds | webhook 单次调用耗时 | `outcome`, `attempt` | `sum(rate(ems_alarm_webhook_delivery_duration_seconds_count{outcome="failure"}[5m])) / sum(rate(ems_alarm_webhook_delivery_duration_seconds_count[5m]))` |

`type`：`silent_timeout` / `consecutive_fail`
`reason`：`auto` / `manual`
`outcome`：`success` / `failure`
`attempt`：`1` / `2` / `3`

### §3.3 ems-meter 计量模块（3 个）

| 指标名 | 类型 | 单位 | 描述 | Labels | 典型 PromQL |
|---|---|---|---|---|---|
| `ems_meter_reading_lag_seconds` | gauge | seconds | 最新读数与当前时间差（按设备聚合最大值） | — | `max(ems_meter_reading_lag_seconds)` |
| `ems_meter_reading_insert_rate_total` | counter | count | 累计入库读数行数（按能源类型） | `energy_type` | `sum by (energy_type) (rate(ems_meter_reading_insert_rate_total[5m]))` |
| `ems_meter_reading_dropped_total` | counter | count | 因校验失败被丢弃的读数 | `reason` | `sum by (reason) (rate(ems_meter_reading_dropped_total[5m]))` |

`energy_type`：`elec` / `water` / `gas` / `steam`
`reason`：`duplicate` / `out_of_range` / `format_error` / `other`

### §3.4 ems-app 跨模块（4 个）

| 指标名 | 类型 | 单位 | 描述 | Labels | 典型 PromQL |
|---|---|---|---|---|---|
| `ems_app_scheduled_duration_seconds` | histogram | seconds | 所有 `@Scheduled` 任务耗时（AOP 统一埋点） | `task` | `histogram_quantile(0.95, sum(rate(ems_app_scheduled_duration_seconds_bucket[10m])) by (task, le))` |
| `ems_app_scheduled_drift_seconds` | gauge | seconds | 实际触发时间与期望时间偏差 | `task` | `max(abs(ems_app_scheduled_drift_seconds))` |
| `ems_app_audit_write_total` | counter | count | audit_log 写入累计 | `action` | `rate(ems_app_audit_write_total[1h])` |
| `ems_app_exception_total` | counter | count | GlobalExceptionHandler 兜底捕获次数 | `type` | `rate(ems_app_exception_total[5m])` |

### §3.5 Spring Boot Actuator 默认指标（不重复发明，直接用）

| 类别 | 关键指标 | 典型用途 |
|---|---|---|
| **JVM** | `jvm_memory_used_bytes` (`area`, `id`) | heap 占比：`sum(jvm_memory_used_bytes{area="heap"}) / sum(jvm_memory_max_bytes{area="heap"})` |
| | `jvm_gc_pause_seconds_*` | GC 压力：`rate(jvm_gc_pause_seconds_sum[5m])` |
| | `jvm_threads_live_threads` | 线程数监控 |
| **HTTP** | `http_server_requests_seconds_*` (`method`, `uri`, `status`, `outcome`) | RPS / latency / 错误率 |
| **HikariCP** | `hikaricp_connections_active` / `_max` / `_idle` / `_pending` | 连接池：`active / max` |
| **Logback** | `logback_events_total{level="ERROR"}` | 日志错误率 |
| **Tomcat** | `tomcat_sessions_active_current_sessions` | 会话数 |
| **Process** | `process_uptime_seconds` / `process_cpu_usage` | 进程级监控 |
| **可用性** | `up{job="factory-ems"}` | scrape 健康（Prometheus 自动注入，非 actuator） |

完整列表参见 Spring Boot Actuator 官方文档（v1 不修改默认行为）。

---

## §4 抓取间隔建议

| 间隔 | 评价 | 适用场景 |
|---|---|---|
| < 5s | **不推荐** | 应用 GC 影响 + 抓取开销过大；端点 ~80 KB 网络 IO 累积 |
| 5–7s | 谨慎 | 仅在极低延迟报警场景下使用 |
| **15s** | **推荐**（默认） | factory-ems 自带 `prometheus.yml` 默认值；与 spec §11.3 一致 |
| 7–30s | 推荐范围 | 兼顾分辨率与开销 |
| 30–60s | 可接受 | BI 看板、低频报表 |
| > 60s | **不推荐** | 错过短暂 spike（如 GC 抖动、连接池瞬时打满） |

**抓取超时（scrape_timeout）建议 ≤ 10s**（spec §11.3）。factory-ems 在冷启动 / 大量 series 重启场景下首次抓取可能 > 5s，留足缓冲。

**示例 Prometheus 抓取配置：**

```yaml
- job_name: factory-ems
  scrape_interval: 15s
  scrape_timeout:  10s
  metrics_path:    /actuator/prometheus
  static_configs:
    - targets: ['factory-ems:8080']
      labels:
        module: app
```

> **Cardinality 警告**：详见 §5。

---

## §5 自定义集成示例

### §5.1 一次抓取 + grep 关键指标 + 简单 alert（bash）

适用：cron 每分钟跑一次，超阈值发送报警。

```bash
#!/usr/bin/env bash
# check-meter-lag.sh — 数据新鲜度阈值报警
set -euo pipefail

ENDPOINT="${ENDPOINT:-http://factory-ems:8080/actuator/prometheus}"
THRESHOLD_SECONDS="${THRESHOLD_SECONDS:-300}"   # 5 min
ALERT_WEBHOOK="${ALERT_WEBHOOK:-}"

lag=$(curl -sS --max-time 10 "$ENDPOINT" \
  | grep '^ems_meter_reading_lag_seconds{' \
  | awk '{print $2}' \
  | head -n1)

if [ -z "$lag" ]; then
  echo "ERROR: metric not found" >&2
  exit 2
fi

# 浮点比较
awk -v l="$lag" -v t="$THRESHOLD_SECONDS" \
  'BEGIN { exit !(l > t) }' && {
  msg="factory-ems meter reading lag = ${lag}s (threshold ${THRESHOLD_SECONDS}s)"
  echo "ALERT: $msg"
  [ -n "$ALERT_WEBHOOK" ] && curl -sS -X POST \
    -H 'Content-Type: application/json' \
    -d "{\"text\":\"$msg\"}" "$ALERT_WEBHOOK"
  exit 1
}

echo "OK: lag=${lag}s"
```

cron 用法：

```cron
* * * * * ENDPOINT=http://10.0.0.5:8080/actuator/prometheus \
  THRESHOLD_SECONDS=300 \
  ALERT_WEBHOOK=https://hooks.slack.com/... \
  /opt/scripts/check-meter-lag.sh >> /var/log/ems-lag-check.log 2>&1
```

### §5.2 jq 解析 + 写入第三方 BI（伪代码）

OpenMetrics 文本格式不能直接喂给 `jq`，先用 `prom2json` 或自写 awk 转 JSON。

```bash
#!/usr/bin/env bash
# scrape-to-bi.sh — 抓取 → JSON → BI 入库
set -euo pipefail

ENDPOINT="http://factory-ems:8080/actuator/prometheus"
BI_URL="https://bi.example.com/api/v1/ingest"
BI_TOKEN="${BI_TOKEN:?missing BI_TOKEN}"

# prom2json 是 Prometheus 官方工具：https://github.com/prometheus/prom2json
curl -sS --max-time 10 "$ENDPOINT" \
  | prom2json \
  | jq -c '.[] | select(.name | startswith("ems_"))
                | {ts: now|floor, name, type, metrics}' \
  | curl -sS -X POST "$BI_URL" \
      -H "Authorization: Bearer $BI_TOKEN" \
      -H 'Content-Type: application/x-ndjson' \
      --data-binary @-
```

### §5.3 Python `prometheus-client` parser → dict

适用：BI ETL 任务、自定义聚合脚本。

```python
#!/usr/bin/env python3
# scrape_factory_ems.py
import urllib.request
from prometheus_client.parser import text_string_to_metric_families

ENDPOINT = "http://factory-ems:8080/actuator/prometheus"

def scrape(endpoint: str = ENDPOINT, timeout: int = 10) -> dict:
    """返回 {metric_name: [{labels: {...}, value: float}, ...]}"""
    with urllib.request.urlopen(endpoint, timeout=timeout) as resp:
        text = resp.read().decode("utf-8")

    out: dict = {}
    for family in text_string_to_metric_families(text):
        if not family.name.startswith(("ems_", "jvm_", "http_", "hikaricp_")):
            continue
        out[family.name] = [
            {"labels": dict(s.labels), "value": s.value}
            for s in family.samples
        ]
    return out

if __name__ == "__main__":
    data = scrape()
    # 例：取最大 meter lag
    lags = [s["value"] for s in data.get("ems_meter_reading_lag_seconds", [])]
    print(f"max meter lag: {max(lags) if lags else 'n/a'}s")

    # 例：按 reason 汇总采集失败
    fails = data.get("ems_collector_read_failure_total", [])
    by_reason: dict = {}
    for s in fails:
        r = s["labels"].get("reason", "unknown")
        by_reason[r] = by_reason.get(r, 0) + s["value"]
    for r, v in sorted(by_reason.items(), key=lambda x: -x[1]):
        print(f"  {r}: {int(v)}")
```

依赖：`pip install prometheus-client`。

### §5.4 Webhook 推送脚本（cron + curl + 阈值检查）

适用：客户内部 IT 工单系统对接，复用 §5.1 模式但格式化为 incident。

```bash
#!/usr/bin/env bash
# push-incident.sh — 多指标阈值检查 → IT 工单 webhook
set -euo pipefail

ENDPOINT="http://factory-ems:8080/actuator/prometheus"
INCIDENT_WEBHOOK="${INCIDENT_WEBHOOK:?missing}"

scrape=$(curl -sS --max-time 10 "$ENDPOINT")

# 提取数字（label 不限定）
val() {
  echo "$scrape" | grep -E "^$1(\{|\s)" | awk '{print $NF}' | head -n1
}

lag=$(val ems_meter_reading_lag_seconds)
offline=$(val ems_collector_devices_offline)
err_rate=$(echo "$scrape" \
  | grep -E '^ems_app_exception_total' \
  | awk '{s+=$NF} END {print s+0}')

problems=()
awk -v v="$lag"     'BEGIN { exit !(v > 600) }' && problems+=("数据新鲜度滞后 ${lag}s")
awk -v v="$offline" 'BEGIN { exit !(v > 5)   }' && problems+=("${offline} 台设备离线")
awk -v v="$err_rate" 'BEGIN { exit !(v > 100) }' && problems+=("应用累计异常 ${err_rate}")

[ ${#problems[@]} -eq 0 ] && exit 0

body=$(printf '"%s",' "${problems[@]}")
curl -sS -X POST "$INCIDENT_WEBHOOK" \
  -H 'Content-Type: application/json' \
  -d "{\"source\":\"factory-ems\",\"severity\":\"warning\",\"items\":[${body%,}]}"
```

### §5.5 Cardinality 警告（必读）

集成方在二次加工时**禁止**为 factory-ems 的指标添加自定义 label（如设备名、车间、客户标识等），原因：

- factory-ems 已限定 `device_id` 仅出现在 `ems_collector_read_success_total` / `ems_collector_read_failure_total` 上（spec §8.6）
- 设备数 v1 上限 ~5000，整体 active series < 50k，可控
- 若集成方的 BI / 抓取器再额外注入高基数 label（设备名 + 车间 + 客户 = 笛卡尔积），series 数会爆炸到百万级，Prometheus / 第三方 TSDB 都会 OOM

**正确做法**：

- 二次加工时在**查询时**（PromQL `label_replace` / `group_left`）拼装维度，不在抓取时
- 设备名等枚举映射放第三方 BI 的维度表，按 `device_id` JOIN
- 若必须注入新 label，先做 cardinality 估算：`(label_a 取值数) × (label_b 取值数) × ...`，确保 < 10k

---

## §6 集成 checklist

集成方上线前请逐项确认：

- [ ] 抓取端点 `/actuator/prometheus` 可达，返回 200
- [ ] 抓取间隔在 7–30s 之间，scrape_timeout ≤ 10s
- [ ] 反代加 Basic Auth + IP 白名单（生产环境）
- [ ] 不在公网暴露 `:8080`
- [ ] 二次加工不注入额外高基数 label
- [ ] 监控自身抓取健康：`up{job="factory-ems"}`、`scrape_duration_seconds`
- [ ] PromQL / BI 查询使用本文 §3 表中的"典型 PromQL"作为起点

---

## 相关文档

- [Metrics 字典完整版](../product/observability-metrics-dictionary.md) — 17 个业务指标的完整描述 + cardinality + 操作场景
- [SLO 与报警](../product/observability-slo-rules.md) — 4 SLO + 16 报警的客户视角解读
- [可观测性栈功能概览](../product/observability-feature-overview.md) — 销售视角
- [部署文档](../ops/observability-deployment.md) — 工程团队部署指引
