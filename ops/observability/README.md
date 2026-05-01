# Observability Stack — factory-ems 可观测性栈

> **定位**：factory-ems on-prem 部署的统一观测平面（metrics + logs + traces + alerting），与产品栈生命周期完全独立，独立 docker-compose 管理。

**价值**：为工程值班、客户管理、数据分析提供 "看得见的线上系统" — JVM / HTTP / DB / 业务模块的 metrics + 结构化日志 + 分布式追踪 + 多通道告警，让故障诊断从 SSH 黑箱变成图表实时观测。

---

## 一句话架构

- **两个独立 docker-compose**：`docker-compose.yml`（产品栈：nginx + app + postgres + influx）+ `docker-compose.obs.yml`（观测栈：prometheus + loki + tempo + grafana + alertmanager + webhook-bridge + promtail）
- **共享 docker network**：`ems-net`（by default，可通过 `.env.obs` 配置）
- **零应用层改造**：观测全部通过 app 既有的 `/actuator/prometheus` + stdout JSON logs + OTLP 4318 endpoint

---

## 目录结构

```
ops/observability/
├── .env.obs.example                      # 环境变量模板（复制为 .env.obs 后填写）
├── README.md                             # 本文件
├── docker-compose.obs.yml                # 观测栈容器编排（C6 任务）
│
├── prometheus/                           # Prometheus 配置
│   ├── prometheus.yml                    # 抓取 + 规则 inclusion（C2 任务）
│   └── rules/                            # SLO + 告警规则
│
├── alertmanager/                         # Alertmanager 配置（C4 任务）
├── loki/                                 # Loki 日志存储（C3 任务）
├── promtail/                             # Promtail 日志采集（C3 任务）
├── tempo/                                # Tempo 分布式追踪（C4 任务）
│
├── grafana/                              # Grafana UI + 7 个 dashboard
│   ├── provisioning/                     # datasources + dashboards 定义（E1 任务）
│   └── dashboards/                       # slo-overview / infra / jvm / http / collector / alarm / meter（E1-E3 任务）
│
├── webhook-bridge/                       # Alert 路由适配器 Go 服务（C5 任务）
│
└── scripts/                              # 启停 + 初始化脚本（C6 任务）
    ├── obs-up.sh                         # 启动观测栈
    ├── obs-smoke.sh                      # 健康检查
    ├── obs-down.sh                       # 停止观测栈
    └── grafana-init.sh                   # 生成 admin 密码
```

---

## 快速启动（4 步）

### Step 1: 复制环境变量模板

```bash
cd ops/observability
cp .env.obs.example .env.obs
```

### Step 2: 填写关键变量

编辑 `.env.obs`：
```env
OBS_GRAFANA_ADMIN_PASSWORD=YourStrongPassword
```

可选（留空则相应告警通道被禁用）：
```env
OBS_SMTP_HOST=smtp.example.com:587
OBS_DINGTALK_WEBHOOK=https://oapi.dingtalk.com/robot/send?...
OBS_WECHAT_WEBHOOK=https://qyapi.weixin.qq.com/cgi-bin/webhook/send?...
```

### Step 3: 创建 docker network（首次）

```bash
docker network create ems-net 2>/dev/null || true
```

> **主机端依赖**：`docker compose v2` + `curl` + `jq`（smoke 脚本端到端 alert 校验需要）。macOS：`brew install jq`；Linux：`apt-get install jq` / `yum install jq`。

### Step 4: 启动观测栈

```bash
./scripts/obs-up.sh && ./scripts/obs-smoke.sh
```

访问 Grafana：**http://localhost:3000**（admin / 密码在 `.env.obs` 中）

---

## 文档入口

| 用途 | 文档 | 受众 | Phase |
|---|---|---|---|
| 概念 / 4 SLO / dashboard 说明 | `docs/product/observability-feature-overview.md` | 销售 / 客户 | G |
| 配置参考 / env vars / 启停 | `docs/product/observability-config-reference.md` | 实施工程师 | A ✅ |
| 17 个指标 + Spring Boot metrics | `docs/product/observability-metrics-dictionary.md` | 数据 / 集成工程师 | B ✅ |
| 4 SLO + 16 条告警规则 | `docs/product/observability-slo-rules.md` | 客户管理 / 运维 | D ⏳ |
| 7 个 dashboard 布局 / 下钻流程 | `docs/product/observability-dashboards-guide.md` | 客户 / 工程值班 | E ⏳ |
| 用户手册：看 dashboard / 处理告警 | `docs/product/observability-user-guide.md` | 客户运维 | G ⏳ |
| 部署指南：硬件 / 安装 / 排查 | `docs/ops/observability-deployment.md` | 装机工程师 | C ⏳ |
| 故障排查 / 备份 / 升级 runbook | `docs/ops/observability-runbook.md` | 运维 | F ⏳ |

---

## 占位说明

以下目录在后续 Phase 完成前仍为空或占位，**勿删**：

| 目录 | 期望内容 | Phase |
|---|---|---|
| `prometheus/rules/` | 8 个 SLO + 告警规则 | D |
| `alertmanager/alertmanager.yml` | Alert 路由配置 | C4 |
| `loki/loki-config.yml` | Loki 配置 | C3 |
| `promtail/promtail-config.yml` | promtail 采集配置 | C3 |
| `tempo/tempo.yml` | Tempo OTLP 接收配置 | C4 |
| `grafana/dashboards/*.json` | 7 个 dashboard | E |
| `webhook-bridge/` | Go webhook 适配器 | C5 |
| `scripts/` | 启停脚本 | C6 |

---

## 三大解耦原则

1. **生命周期解耦**：obs 栈崩溃不影响产品栈
2. **发版解耦**：app 升级无需碰 obs，反之亦然
3. **替换解耦**：日后可换成 Zabbix / ELK，产品栈零改动

---

## 快速参考

| 组件 | UI | 抓取源 | 用途 |
|---|---|---|---|
| Prometheus | http://127.0.0.1:9090 | `/actuator/prometheus` | metrics + SLO 规则 |
| Grafana | http://127.0.0.1:3000 | Prom/Loki/Tempo | 7 个 dashboard |
| Loki | http://127.0.0.1:3100 | promtail 推送 | 日志检索 |
| Tempo | http://127.0.0.1:3200 | OTLP 4318 | 链路追踪 |
| Alertmanager | http://127.0.0.1:9093 | 规则评估 | Alert 路由 + 多通道 |

---

**状态**：Phase C1 完成（目录 + .env.obs.example + README）  
**下一步**：C2–C6 配置文件，D–G 规则 / dashboard / 文档
