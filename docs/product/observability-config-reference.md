# 可观测性栈 · 配置参数参考

> **更新于**：2026-04-29（Phase A 完成时）
> **撰写依据**：[spec §11](../superpowers/specs/2026-04-29-observability-stack-design.md)（配置参考）+ [spec §16](../superpowers/specs/2026-04-29-observability-stack-design.md)（部署要求）+ Task A4 实际落地文件
> **受众**：实施工程师 / SRE

---

## 1. 文件结构（spec §11.1）

观测栈所有配置集中在 `ops/observability/` 目录，和产品栈 `docker-compose.yml` 独立，生命周期互不影响。

```
ops/observability/
├── docker-compose.obs.yml          # 观测栈主 compose 文件
├── .env.obs.example                # 环境变量示例（复制为 .env.obs 后填写）
├── README.md                       # 该目录索引
├── prometheus/
│   ├── prometheus.yml              # 抓取配置 + alertmanager 引用
│   └── rules/
│       ├── slo-availability.yml    # 可用性 SLO 燃烧率规则
│       ├── slo-latency.yml         # API p99 延迟规则
│       ├── slo-freshness.yml       # 数据新鲜度规则
│       ├── slo-scheduler-drift.yml # 调度漂移规则
│       ├── critical-alerts.yml     # 5 条 critical 报警
│       ├── warning-alerts.yml      # 11 条 warning 报警
│       ├── burn-rate-alerts.yml    # 燃烧率报警
│       └── _tests/                 # promtool test rules 用例
│           ├── critical-alerts_test.yml
│           ├── warning-alerts_test.yml
│           └── burn-rate_test.yml
├── alertmanager/
│   └── alertmanager.yml            # 路由 + 接收方 + 抑制规则
├── loki/
│   └── loki-config.yml             # 日志存储配置
├── promtail/
│   └── promtail-config.yml         # 日志抓取配置（挂 docker socket）
├── tempo/
│   └── tempo.yml                   # 分布式追踪存储配置
├── grafana/
│   ├── provisioning/
│   │   ├── datasources/datasources.yml   # 数据源自动注册
│   │   └── dashboards/dashboards.yml     # dashboard 目录注册
│   └── dashboards/
│       ├── slo-overview.json       # D1 SLO 总览
│       ├── infra-overview.json     # D2 基础设施
│       ├── jvm-overview.json       # D3 JVM
│       ├── http-overview.json      # D4 HTTP
│       ├── ems-collector.json      # D5 采集模块
│       ├── ems-alarm.json          # D6 报警模块
│       └── ems-meter.json          # D7 能源计量模块
├── webhook-bridge/
│   ├── Dockerfile                  # distroless + Go binary
│   ├── go.mod
│   └── main.go                     # Alertmanager → 钉钉/企微 格式适配
└── scripts/
    ├── obs-up.sh                   # 幂等启动（含 network 创建）
    ├── obs-smoke.sh                # 5 服务 ready 检查 + 端到端 alert 验证
    ├── obs-down.sh                 # 停止观测栈
    └── grafana-init.sh             # 首次启动生成随机 admin 密码
```

---

## 2. 环境变量（`.env.obs`）（spec §11.2）

把 `.env.obs.example` 复制成 `.env.obs`，按下表填写。`.env.obs` 不要提交到 git（已在 `.gitignore` 中）。

> **空值行为总则**：报警通道相关变量（SMTP / 钉钉 / 企微 / 通用 webhook）留空时，Alertmanager 会静默跳过该接收方，不会导致启动失败。至少配一个报警通道才能收到通知。

| 变量 | 必填 | 默认值 | 空值行为 |
|------|------|--------|---------|
| `OBS_GRAFANA_ADMIN_USER` | **是** | 无 | 容器启动失败（Grafana 强依赖） |
| `OBS_GRAFANA_ADMIN_PASSWORD` | **是** | 无（首次启动由 `grafana-init.sh` 随机生成并打印一次） | 容器启动失败 |
| `OBS_PROMETHEUS_RETENTION` | 否 | `30d` | 使用 30 天保留期；磁盘占用约 5 GB（见 §6 资源预算） |
| `OBS_LOKI_RETENTION` | 否 | `336h`（14 天） | 使用 14 天保留期；磁盘占用约 8 GB |
| `OBS_TEMPO_RETENTION` | 否 | `72h`（3 天） | 使用 3 天保留期；磁盘占用约 2 GB |
| `OBS_SMTP_HOST` | 否 | 空 | 邮件报警通道不可用；其他通道不受影响 |
| `OBS_SMTP_USER` | 否 | 空 | 同上（SMTP 三项需同时配置才有效） |
| `OBS_SMTP_PASSWORD` | 否 | 空 | 同上 |
| `OBS_ALERT_RECEIVER_EMAIL` | 否 | 空 | 无邮件接收者；SMTP 配置也不生效 |
| `OBS_DINGTALK_WEBHOOK` | 否 | 空 | 钉钉通道跳过；其他通道不受影响 |
| `OBS_DINGTALK_SECRET` | 否 | 空 | 钉钉加签验证关闭（部分机器人需要此字段） |
| `OBS_WECHAT_WEBHOOK` | 否 | 空 | 企微通道跳过 |
| `OBS_GENERIC_WEBHOOK` | 否 | 空 | 通用 webhook 通道跳过（可对接客户内部 IT 工单系统） |
| `OBS_NETWORK_NAME` | 否 | `ems-net` | 沿用默认 docker network 名称；改名后需同步更新产品栈 compose |

### 配置示例（`.env.obs` 最小可用配置）

```bash
# 必填
OBS_GRAFANA_ADMIN_USER=admin
OBS_GRAFANA_ADMIN_PASSWORD=XK8h3jq2mPqL9vNs  # 请替换为强密码

# 可选：保留期（按实际磁盘调整）
OBS_PROMETHEUS_RETENTION=30d
OBS_LOKI_RETENTION=336h
OBS_TEMPO_RETENTION=72h

# 可选：邮件报警
OBS_SMTP_HOST=smtp.example.com:587
OBS_SMTP_USER=alerts@example.com
OBS_SMTP_PASSWORD=your-smtp-password
OBS_ALERT_RECEIVER_EMAIL=oncall@example.com,ops@example.com

# 可选：钉钉报警（机器人 webhook + 加签密钥）
OBS_DINGTALK_WEBHOOK=https://oapi.dingtalk.com/robot/send?access_token=xxx
OBS_DINGTALK_SECRET=SECxxx

# 可选：企微报警
OBS_WECHAT_WEBHOOK=https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx
```

---

## 3. 应用层配置（application-prod.yml 增量）（spec §11.3）

下面是 Task A4 实际提交到 `ems-app/src/main/resources/application-prod.yml` 的完整内容。可观测性相关配置集中在 `management:` 节点，其他部分不用改。

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${EMS_DB_HOST:postgres}:5432/${EMS_DB_NAME:factory_ems}
    username: ${EMS_DB_USER:ems}
    password: ${EMS_DB_PASSWORD}
    hikari:
      maximum-pool-size: 30
      connection-timeout: 10000

logging:
  level:
    root: INFO
    com.ems: INFO

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info,metrics
  endpoint:
    health:
      show-details: when-authorized
      probes:
        enabled: true
  metrics:
    tags:
      application: factory-ems
      instance: ${HOSTNAME:unknown}
  tracing:
    sampling:
      probability: 0.1
  otlp:
    tracing:
      endpoint: ${OTLP_TRACING_ENDPOINT:http://tempo:4318/v1/traces}
```

### 关键配置说明

| 配置项 | 值 | 说明 |
|--------|-----|------|
| `endpoints.web.exposure.include` | `health,prometheus,info,metrics` | 暴露 Prometheus 抓取端点 `/actuator/prometheus`；`health` 含 liveness/readiness 探针 |
| `metrics.tags.application` | `factory-ems` | 所有 metrics 自动附带此 label，Grafana 过滤时使用 |
| `metrics.tags.instance` | `${HOSTNAME:unknown}` | 取容器 hostname，多实例时区分来源 |
| `tracing.sampling.probability` | `0.1` | 10% 采样率，生产环境平衡成本；调试时可临时改为 `1.0` |
| `otlp.tracing.endpoint` | `${OTLP_TRACING_ENDPOINT:http://tempo:4318/v1/traces}` | traces 推送到 Tempo；默认走 docker 内网，可通过环境变量覆盖 |

> 改完要重启 ems-app 才生效。采样率改完 1-2 分钟后 Grafana Tempo 数据源能看到变化。

---

## 4. 启停命令（spec §16.2）

### 4.1 首次部署

```bash
# 第 1 步：生成 Grafana 管理员密码（仅首次）
# 脚本会随机生成强密码，写入 .env.obs，并在终端打印一次
cd ops/observability
bash scripts/grafana-init.sh

# 第 2 步：创建共享 docker network（幂等，已存在时无报错）
docker network create ems-net 2>/dev/null || true

# 第 3 步：启动观测栈
docker compose -f docker-compose.obs.yml --env-file .env.obs up -d

# 第 4 步：启动产品栈（如尚未启动）
cd ../..
docker compose -f docker-compose.yml up -d

# 第 5 步：健康检查（等待约 30 秒各服务就绪后执行）
bash ops/observability/scripts/obs-smoke.sh
```

成功输出示例：
```
[OK] prometheus:9090/ready
[OK] loki:3100/ready
[OK] tempo:3200/ready
[OK] grafana:3000/api/health
[OK] alertmanager:9093/ready
[OK] End-to-end: test alert delivered to mock receiver
```

### 4.2 日常启停

```bash
# 停止观测栈（不影响产品栈）
docker compose -f ops/observability/docker-compose.obs.yml down

# 启动观测栈（幂等，含 network 创建）
bash ops/observability/scripts/obs-up.sh

# 查看各服务状态
docker compose -f ops/observability/docker-compose.obs.yml ps

# 查看服务日志
docker compose -f ops/observability/docker-compose.obs.yml logs -f prometheus
docker compose -f ops/observability/docker-compose.obs.yml logs -f grafana
```

### 4.3 Alertmanager 维护期静默

```bash
# 在维护窗口内，临时静默所有 warning 级别报警（例如 2 小时）
# 需先进入 alertmanager 容器或在有 amtool 的机器上执行
amtool --alertmanager.url=http://localhost:9093 \
  silence add severity=warning \
  --duration=2h \
  --comment="计划维护"
```

---

## 5. 升级路径（spec §16.3）

观测栈和产品栈各自独立升级，互不影响。升级时 Grafana dashboard 数据不会丢（volume 持久化）。

### 5.1 仅升级观测栈

```bash
# 拉取最新镜像（不停机拉取）
docker compose -f ops/observability/docker-compose.obs.yml pull

# 滚动重启（逐容器替换，短暂中断）
docker compose -f ops/observability/docker-compose.obs.yml up -d

# 验证升级后健康状态
bash ops/observability/scripts/obs-smoke.sh
```

### 5.2 仅升级产品栈（ems-app）

```bash
# 产品栈独立升级，obs 栈不需要停止
docker compose -f docker-compose.yml pull
docker compose -f docker-compose.yml up -d
```

### 5.3 升级注意事项

| 组件 | 注意事项 |
|------|---------|
| Grafana | 跨大版本（如 10.x → 11.x）升级前备份 Grafana volume，或导出 dashboard JSON |
| Prometheus | 升级一般向前兼容；data 目录格式偶有变更，参考官方 migration guide |
| Loki | 大版本升级需检查 schema 变更（Loki 3.x 引入了新 TSDB index） |
| Tempo | 升级前确认 OTLP 协议版本兼容 |
| alertmanager | 配置格式向前兼容，升级后用 `amtool check-config` 验证 |
| webhook-bridge | 自构建镜像，每次更新 main.go 后需重新 build：`docker compose build webhook-bridge` |

---

## 6. 资源预算（spec §16.1）

下面是各服务稳态运行时的资源用量（不含启动峰值）。实际以客户数据量和设备规模为准。

| 服务 | 内存 | 磁盘 | CPU |
|------|------|------|-----|
| Prometheus | 512 MB | 5 GB（30 天 retention） | 0.2 vCPU |
| Loki | 512 MB | 8 GB（14 天 retention） | 0.2 vCPU |
| Tempo | 256 MB | 2 GB（3 天 retention） | 0.1 vCPU |
| Grafana | 256 MB | 100 MB | 0.1 vCPU |
| Alertmanager | 128 MB | 50 MB | 0.1 vCPU |
| webhook-bridge | 32 MB | — | 0.05 vCPU |
| promtail | 128 MB | — | 0.1 vCPU |
| **合计** | **~1.8 GB** | **~15 GB** | **~0.85 vCPU** |

> 加上产品栈（ems-app + nginx + postgres + influx，约 4-6 GB），客户最低服务器配置为 8 GB RAM / 50 GB SSD / 4 vCPU。客户有历史数据需要较长 retention 或设备规模 > 500 台时，建议 16 GB RAM / 100 GB SSD。

---

## 7. FAQ

### Q1：钉钉 / 企微 webhook 不通，报警收不到

**症状**：报警在 Alertmanager UI（`http://localhost:9093`）中能看到，但钉钉 / 企微 没收到消息。

**排查步骤**：

1. 检查 `obs_webhook_bridge` 容器日志：
   ```bash
   docker logs obs_webhook_bridge 2>&1 | tail -50
   ```
   关注关键词：`connection refused`、`dial tcp`、`handshake error`

2. 确认客户工厂内网能否访问外部 webhook 地址：
   ```bash
   # 进入 webhook-bridge 容器内测试连通性
   docker exec obs_webhook_bridge wget -O- --timeout=5 \
     "https://oapi.dingtalk.com/robot/send?access_token=xxx"
   ```

3. 内网无法访问公网时（工厂封闭网络常见）：
   - 改用 `OBS_GENERIC_WEBHOOK` 对接客户内网的 IT 工单或消息系统
   - 或者只留邮件（SMTP）通道，邮件服务器一般走内网中继

4. 钉钉加签验证失败（`sign check failed`）：确认 `.env.obs` 中的 `OBS_DINGTALK_SECRET` 和机器人配置一致，Secret 是以 `SEC` 开头的字符串。

---

### Q2：Grafana 忘记管理员密码

**方法 A（推荐）**：查 `.env.obs` 文件里的 `OBS_GRAFANA_ADMIN_PASSWORD` 字段。

**方法 B**：用 Grafana CLI 重置：

```bash
# 进入 Grafana 容器
docker exec -it obs_grafana bash

# 重置 admin 密码
grafana cli admin reset-admin-password 新密码

# 退出容器后，同步更新 .env.obs 中的 OBS_GRAFANA_ADMIN_PASSWORD
exit
```

> 重置后不用重启容器，密码立即生效。同步更新 `.env.obs`，避免下次 `up -d` 时密码被 compose 覆盖。

---

### Q3：docker network `ems-net` 不存在，启动报错

**症状**：

```
Error response from daemon: network ems-net not found
```

**原因**：`ems-net` 是 external docker network，必须在启动任一 compose 栈前手动创建（或者用脚本幂等创建）。

**修复**：

```bash
# 创建网络（已存在时无报错）
docker network create ems-net 2>/dev/null || true

# 确认已创建
docker network inspect ems-net

# 重新启动观测栈
docker compose -f ops/observability/docker-compose.obs.yml up -d
```

> 推荐用 `obs-up.sh` 脚本启动，脚本里已包含幂等的 network 创建逻辑。

---

### Q4：ems-app 没有注册到 Prometheus，metrics 采集不到

**症状**：Prometheus 中 `up{job="factory-ems"}` 为 0，或者 `/actuator/prometheus` 返回 404。

**排查步骤**：

1. 确认 `application-prod.yml` 的 `management.endpoints.web.exposure.include` 包含 `prometheus`（见 §3）。

2. 确认 ems-app 用 `prod` profile 启动：
   ```bash
   docker exec ems-app env | grep SPRING_PROFILES_ACTIVE
   # 应输出: SPRING_PROFILES_ACTIVE=prod
   ```

3. 确认 ems-app 和 Prometheus 在同一 docker network `ems-net` 内：
   ```bash
   docker network inspect ems-net | grep -A5 '"Name"'
   ```
   输出里应同时包含 `ems-app`（或产品栈容器名）和 `obs_prometheus`。

4. 在 Prometheus UI（`http://localhost:9090/targets`）确认 `factory-ems` target 状态为 `UP`。如果是 `DOWN`，点 target 名称看具体错误信息。

---

## 更多资源

- **完整设计规格**：[2026-04-29-observability-stack-design.md](../superpowers/specs/2026-04-29-observability-stack-design.md)
- **指标字典**（spec §8）：待 Phase B 落实 → `observability-metrics-dictionary.md`
- **SLO 与报警规则**（spec §9）：待 Phase D 落实 → `observability-slo-rules.md`
- **Dashboard 使用指南**（spec §10）：待后续 Phase 落实 → `observability-dashboards-guide.md`
- **运维 Runbook**（启停详解 / 排障 / 备份）：待后续 Phase 落实 → `docs/ops/observability-runbook.md`
