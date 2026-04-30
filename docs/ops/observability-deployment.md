# 可观测性栈部署指南

> **更新于**：2026-04-29（Phase C 完成时）
> **撰写依据**：[spec §6](../superpowers/specs/2026-04-29-observability-stack-design.md)（资源预算）、[spec §11](../superpowers/specs/2026-04-29-observability-stack-design.md)（配置参考）、[spec §16](../superpowers/specs/2026-04-29-observability-stack-design.md)（部署要求）、[spec §17](../superpowers/specs/2026-04-29-observability-stack-design.md)（升级路径）
> **受众**：装机工程师 / 实施工程师（负责在客户服务器上首次安装、升级、卸载）

---

## 1. 概述

本文档描述如何在客户服务器上部署 factory-ems 可观测性栈（Prometheus + Loki + Tempo + Promtail + Alertmanager + webhook-bridge + Grafana），该栈以独立 Docker Compose 文件运行，与产品栈（`docker-compose.yml`）生命周期互不干扰，共享同一个 `ems-net` 网络。

**与产品栈的关系**：观测栈抓取产品栈暴露的指标（`:8080/actuator/prometheus`）并收集其容器日志；产品栈无需感知观测栈是否在线，两侧可独立启停。

---

## 2. 资源预算

以下数字来自 spec §16.1，适用于单节点生产部署（日均 10 万条 metric、500 MB 日志、中等 trace 量）。

| 服务 | 内存上限 | 磁盘（稳态） | CPU（平均） |
|------|---------|------------|------------|
| Prometheus | 512 MB | ~5 GB（30d） | 0.20 vCPU |
| Loki | 256 MB | ~8 GB（14d） | 0.10 vCPU |
| Tempo | 256 MB | ~2 GB（3d） | 0.10 vCPU |
| Promtail | 64 MB | — | 0.05 vCPU |
| Alertmanager | 64 MB | <100 MB | 0.02 vCPU |
| webhook-bridge | 32 MB | — | 0.02 vCPU |
| Grafana | 256 MB | ~200 MB | 0.15 vCPU |
| **合计** | **~1.44 GB** | **~15.3 GB** | **~0.64 vCPU** |

> **保守预留**：建议为突发峰值预留 20% 余量，实际总占用约 1.8 GB 内存 / 15 GB 磁盘 / 0.85 vCPU。

### 客户最低服务器配置

| 资源 | 最低要求 | 说明 |
|------|---------|------|
| RAM | 8 GB | 含产品栈 ~4 GB + 观测栈 ~2 GB + 系统 ~2 GB |
| 磁盘 | 50 GB SSD | 含产品栈数据库 + 观测栈 volume + OS |
| CPU | 4 vCPU | 2 vCPU 给产品栈，0.85 给观测栈，余量给系统 |

---

## 3. 前置条件

在开始部署前，确认客户服务器满足以下所有条件：

| 条件 | 验证命令 | 期望输出 |
|------|---------|---------|
| Docker Engine 24+ | `docker version --format '{{.Server.Version}}'` | `24.x` 或更高 |
| Docker Compose v2（插件模式） | `docker compose version` | `Docker Compose version v2.x.x` |
| bash 3.2+ | `bash --version` | `GNU bash, version 3.x` 或更高 |
| curl | `curl --version` | 版本信息行 |
| openssl | `openssl version` | 版本信息行 |

> **注意**：必须使用 `docker compose`（v2 插件），不接受旧的 `docker-compose`（v1 独立二进制）。可用 `which docker-compose` 检查是否误装了 v1。

### 端口可用性

以下端口在启动前必须未被占用：

| 端口 | 服务 | 监听地址 |
|------|------|---------|
| 9090 | Prometheus UI | 127.0.0.1（仅本机） |
| 9093 | Alertmanager UI | 127.0.0.1（仅本机） |
| 3000 | Grafana UI | 127.0.0.1（仅本机） |
| 4318 | Tempo OTLP HTTP | 0.0.0.0（产品栈 trace 推送入口） |

检查端口占用：

```bash
for p in 9090 9093 3000 4318; do
  ss -tlnp | grep ":$p " || echo "port $p free"
done
```

### 默认安装路径

观测栈配置文件位于：

```
<repo-root>/ops/observability/
```

以下安装步骤均在该目录下执行，除非另有说明。

---

## 4. 安装步骤

### 步骤 1：确认 ems-net 网络

观测栈与产品栈共享 `ems-net` docker 网络。若产品栈已先启动，该网络已存在；若单独部署观测栈，`obs-up.sh` 会幂等创建。

```bash
# 检查网络是否存在（可选，仅供确认）
docker network inspect ems-net 2>/dev/null && echo "网络已存在" || echo "网络不存在，obs-up.sh 将自动创建"
```

无论哪种情况，步骤 4 的 `obs-up.sh` 均会处理，此处无需手动干预。

---

### 步骤 2：准备环境变量文件

```bash
cd ops/observability
cp .env.obs.example .env.obs
```

用编辑器打开 `.env.obs`，按下表填写必填项和需要启用的告警通道：

| 变量 | 必填 | 说明 |
|------|------|------|
| `OBS_GRAFANA_ADMIN_USER` | 是 | Grafana 管理员用户名，通常保持 `admin` |
| `OBS_GRAFANA_ADMIN_PASSWORD` | 是 | 步骤 3 自动生成，此处可留空 |
| `OBS_PROMETHEUS_RETENTION` | 否 | 默认 `30d` |
| `OBS_LOKI_RETENTION` | 否 | 默认 `336h`（14 天） |
| `OBS_TEMPO_RETENTION` | 否 | 默认 `72h`（3 天） |
| `OBS_SMTP_HOST` | 否 | 邮件告警，格式 `smtp.example.com:587` |
| `OBS_SMTP_USER` | 否 | SMTP 用户名 |
| `OBS_SMTP_PASSWORD` | 否 | SMTP 密码 |
| `OBS_ALERT_RECEIVER_EMAIL` | 否 | 告警接收邮箱 |
| `OBS_DINGTALK_WEBHOOK` | 否 | 钉钉机器人 Webhook URL |
| `OBS_DINGTALK_SECRET` | 否 | 钉钉加签密钥（建议配置） |
| `OBS_WECHAT_WEBHOOK` | 否 | 企业微信机器人 Webhook URL |
| `OBS_GENERIC_WEBHOOK` | 否 | 通用 Webhook URL（客户内部 IT 系统） |
| `OBS_NETWORK_NAME` | 否 | 默认 `ems-net` |

> **告警通道说明**：所有告警通道变量均为可选。留空时对应接收方被 Alertmanager 静默跳过，不影响启动。**至少配置一个告警通道**，否则告警无处发送。

**失败回退**：若 `.env.obs.example` 不存在，确认当前目录是否为 `ops/observability/`，或确认 git 分支已包含 Phase C 代码。

---

### 步骤 3：生成 Grafana 管理员密码

```bash
cd ops/observability   # 确保在此目录
./scripts/grafana-init.sh
```

**预期输出**：

```
Created .env.obs from .env.obs.example    # 若 .env.obs 尚不存在时显示
====================================================
Grafana admin password generated and saved to .env.obs
Password: Xk9mPqR2nLvA8dTz
Save it now — this is the only time it is printed.
====================================================
```

> **重要**：密码仅打印一次。立即记录到密码管理器或客户交付文档中。之后只能通过重置流程恢复（见 FAQ §10.1）。

**失败回退**：若报 `openssl: command not found`，安装 openssl 后重试；若 `.env.obs` 权限不足，检查 `ls -l .env.obs`。

---

### 步骤 4：启动观测栈

```bash
cd ops/observability
./scripts/obs-up.sh
```

脚本按顺序执行：

1. 幂等创建 `ems-net` docker 网络（已存在则跳过）
2. 检查 `.env.obs` 是否存在，不存在则报错退出
3. 检查 `OBS_GRAFANA_ADMIN_PASSWORD` 是否已设置，未设置则自动调用 `grafana-init.sh`
4. 执行 `docker compose up -d --build`（含 webhook-bridge 本地构建）
5. 等待 10 秒后自动运行 `obs-smoke.sh`

**预期输出**（成功）：

```
Creating docker network: ems-net          # 若网络不存在时显示
Starting observability stack...
[+] Running 7/7
 ✔ Container factory-ems-obs-prometheus-1         Started
 ✔ Container factory-ems-obs-loki-1               Started
 ✔ Container factory-ems-obs-tempo-1              Started
 ✔ Container factory-ems-obs-promtail-1           Started
 ✔ Container factory-ems-obs-alertmanager-1       Started
 ✔ Container factory-ems-obs-obs-webhook-bridge-1 Started
 ✔ Container factory-ems-obs-grafana-1            Started
Waiting 10s for services to start...
Smoke check observability stack...
  ✓ prometheus ready (http://127.0.0.1:9090/-/ready)
  ✓ alertmanager ready (http://127.0.0.1:9093/-/ready)
  ✓ grafana ready (http://127.0.0.1:3000/api/health)
  ✓ loki ready (internal)
  ✓ tempo ready (internal)
All exposed services ready.
```

**失败回退**：若某服务未就绪，查看日志：

```bash
docker compose --env-file .env.obs -f docker-compose.obs.yml logs <服务名>
# 例：
docker compose --env-file .env.obs -f docker-compose.obs.yml logs grafana
```

常见原因见 §9（与产品栈联调常见冲突）。

---

### 步骤 5：验收

```bash
# 1. 检查所有容器在运行
docker ps --filter name=factory-ems-obs

# 2. Grafana 健康
curl -s http://127.0.0.1:3000/api/health | python3 -m json.tool

# 3. Prometheus 已抓取到目标
curl -s 'http://127.0.0.1:9090/api/v1/targets' | \
  python3 -c "import sys,json; t=json.load(sys.stdin); print(len(t['data']['activeTargets']), 'targets')"

# 4. Alertmanager 状态
curl -s http://127.0.0.1:9093/api/v2/alerts
```

> **远程访问**：UI 端口仅绑定 `127.0.0.1`，远程访问需建立 SSH tunnel：
> ```bash
> ssh -L 3000:127.0.0.1:3000 -L 9090:127.0.0.1:9090 user@server
> ```
> 然后在本地浏览器打开 `http://127.0.0.1:3000`。

Grafana 登录用户名 / 密码即步骤 3 中记录的值。

---

## 5. 数据保留与磁盘规划

### 默认保留期

| 服务 | 保留期 | 环境变量 | 稳态磁盘占用 |
|------|--------|---------|------------|
| Prometheus | 30 天 | `OBS_PROMETHEUS_RETENTION` | ~5 GB |
| Loki | 14 天（336h） | `OBS_LOKI_RETENTION` | ~8 GB |
| Tempo | 3 天（72h） | `OBS_TEMPO_RETENTION` | ~2 GB |

来源：spec §11.2；默认值在 `.env.obs.example` 中已注释说明。

### 业务峰值估算

以日均 10 万条 metric 样本、500 MB 日志、中等 trace 量估算：

- Prometheus 30d：~5 GB（含 WAL）
- Loki 14d：~8 GB（含压缩块）
- Tempo 3d：~2 GB（含块索引）
- **合计**：~15 GB，加操作系统 / 产品栈数据库约占 50 GB SSD 的 50% 以内

若客户业务量显著高于估算（如日志量达平均值 3 倍以上），建议缩短 Loki 保留期至 `168h`（7 天），或挂载额外磁盘并调整 volume 挂载路径。

### 磁盘告警

Phase D（D2 任务）将配置 `EmsDiskSpaceCritical` 告警规则，当磁盘使用率超过阈值时触发 critical 级别告警。建议在客户 SRE 手册中记录响应流程：

1. 收到 `EmsDiskSpaceCritical` 告警
2. 确认哪个 volume 增长（`docker system df -v`）
3. 根据业务情况缩短保留期，或清理旧数据后重启对应容器

### Volume 备份与恢复

**v1 阶段无热备份**，采用停服 + tar 方案：

```bash
# 备份（停服后执行）
cd ops/observability
./scripts/obs-down.sh

docker run --rm \
  -v factory-ems-obs_prom-data:/src \
  -v $(pwd)/backup:/backup \
  alpine tar czf /backup/prom-data-$(date +%Y%m%d).tar.gz -C /src .

# 同理备份 loki-data、tempo-data、grafana-data、alertmanager-data

# 恢复
docker run --rm \
  -v factory-ems-obs_prom-data:/dst \
  -v $(pwd)/backup:/backup \
  alpine tar xzf /backup/prom-data-20260101.tar.gz -C /dst

./scripts/obs-up.sh
```

> 无自动定时备份；建议结合客户现有备份策略（如 cron + rsync）安排定期执行。

---

## 6. 升级路径

### 仅升级观测栈（镜像版本更新）

```bash
cd ops/observability
docker compose --env-file .env.obs -f docker-compose.obs.yml pull
docker compose --env-file .env.obs -f docker-compose.obs.yml up -d
```

此操作不影响产品栈（不同 compose 文件，不同容器生命周期）。

### 仅升级产品栈

产品栈由 `docker-compose.yml` 管理，与观测栈完全独立：

```bash
cd <repo-root>
docker compose pull
docker compose up -d
```

观测栈保持运行，无需停机。

### 跨大版本升级注意事项（如 Prometheus 2.x → 3.x）

跨主版本升级存在 volume 格式不兼容风险：

1. **先阅读 release notes**：确认 TSDB / WAL 格式兼容性
2. **备份 volumes**（参见 §5 备份流程）
3. 修改 `docker-compose.obs.yml` 中的镜像 tag
4. 重建容器：`docker compose --env-file .env.obs -f docker-compose.obs.yml up -d --force-recreate prometheus`
5. 观察 30 分钟，确认指标抓取正常后再升级其他服务

> Grafana 大版本升级（如 10.x → 11.x）通常向后兼容，但 dashboard JSON 格式可能变化，建议先在测试环境验证。

### webhook-bridge 升级

`obs-webhook-bridge` 为本地构建（来自 `./webhook-bridge/`）。代码更新后重建：

```bash
cd ops/observability
docker compose --env-file .env.obs -f docker-compose.obs.yml build obs-webhook-bridge
docker compose --env-file .env.obs -f docker-compose.obs.yml up -d obs-webhook-bridge
```

---

## 7. 故障定位入口

遇到问题时，按以下顺序查阅文档：

| 问题类型 | 参考文档 | 状态 |
|---------|---------|------|
| 观测栈服务本身故障（容器崩溃、告警不发等） | `docs/ops/observability-runbook.md` | Phase F 待完成 |
| 业务告警触发但无法定位根因 | dashboard 使用指南 | Phase E 待完成 |
| 环境变量 / 配置参数含义 | [`docs/product/observability-config-reference.md`](../product/observability-config-reference.md) | Phase A 已完成 |
| 指标名称 / 含义 / PromQL 示例 | [`docs/product/observability-metrics-dictionary.md`](../product/observability-metrics-dictionary.md) | Phase B 已完成 |

快速日志检查命令：

```bash
# 查看所有观测栈容器状态
docker compose --env-file .env.obs -f docker-compose.obs.yml ps

# 查看某服务最近 50 行日志
docker compose --env-file .env.obs -f docker-compose.obs.yml logs --tail=50 <服务名>

# 实时跟踪所有服务日志
docker compose --env-file .env.obs -f docker-compose.obs.yml logs -f
```

---

## 8. 卸载与清理

### 停止服务（保留数据和配置）

```bash
cd ops/observability
./scripts/obs-down.sh
```

所有容器停止，volumes 和 `.env.obs` 保留。再次运行 `obs-up.sh` 可恢复。

### 卸载并保留 volumes（保留历史数据）

与停止服务命令相同：

```bash
./scripts/obs-down.sh
```

volumes（`prom-data`、`loki-data`、`tempo-data`、`grafana-data`、`alertmanager-data`）保留在 docker 中。

### 卸载并删除所有数据（不可恢复）

```bash
./scripts/obs-down.sh -v
```

`-v` 参数透传给 `docker compose down -v`，删除所有声明的 named volumes。**操作不可逆，执行前确认已备份。**

### 删除 docker 网络

仅当确认产品栈和观测栈均已停止，且无其他容器使用 `ems-net` 时，才删除网络：

```bash
# 先确认没有其他容器挂在该网络
docker network inspect ems-net --format '{{len .Containers}} containers'

# 若输出为 0，则可删除
docker network rm ems-net
```

### 完整清理流程

```bash
cd ops/observability
./scripts/obs-down.sh -v        # 停服 + 删 volumes
docker network rm ems-net       # 删网络（确认无其他使用者）
rm .env.obs                     # 删本地密钥文件
```

---

## 9. 与产品栈联调常见冲突

### 9.1 ems-net 网络不存在

**症状**：手动执行 `docker compose up` 时报错 `network ems-net declared as external, but could not be found`

`obs-up.sh` 会幂等创建该网络，但若跳过脚本直接执行 compose 命令：

```bash
# 排查
docker network ls | grep ems-net

# 解决
docker network create ems-net
# 然后重新执行 ./scripts/obs-up.sh
```

---

### 9.2 端口冲突（9090 / 9093 / 3000 已被占用）

**症状**：容器启动失败，日志显示 `bind: address already in use`

```bash
# 排查：找出占用端口的进程
lsof -i :9090 -i :9093 -i :3000

# 解决方案 A：停止冲突进程
kill -9 <PID>

# 解决方案 B：修改观测栈监听端口
# 编辑 docker-compose.obs.yml 中对应服务的 ports 配置
# 例：将 Grafana 改为 127.0.0.1:3001:3000
```

---

### 9.3 内存不足（OOM kill）

**症状**：容器频繁重启，`docker inspect` 显示 `OOMKilled: true`

```bash
# 排查
docker inspect $(docker ps -q --filter name=factory-ems-obs) \
  --format '{{.Name}} OOMKilled={{.State.OOMKilled}}'

# 查看系统可用内存
free -h

# 解决步骤
# 1. 停止观测栈
./scripts/obs-down.sh
# 2. 释放其他内存占用，确认产品栈配置是否过大
# 3. 缩短保留期以降低内存压力（修改 .env.obs 中 RETENTION 值后重启）
./scripts/obs-up.sh
```

---

### 9.4 /var/run/docker.sock 权限（Promtail）

**症状**：`promtail` 容器报错 `permission denied while trying to connect to the Docker daemon socket`

Promtail 需要只读访问 docker socket 以发现容器日志。

```bash
# 排查
ls -la /var/run/docker.sock
# 期望：srw-rw---- root docker（或 root root）

# 解决方案 A：将运行 docker compose 的用户加入 docker 组
sudo usermod -aG docker $USER
newgrp docker

# 解决方案 B（临时，不推荐用于生产）：
sudo chmod 666 /var/run/docker.sock
```

---

### 9.5 时区不一致（容器 UTC vs 浏览器本地时区）

**症状**：Grafana 图表时间轴与本地时间相差数小时

所有容器默认使用 UTC，这是正常且预期的行为。

```bash
# 确认容器时区
docker exec factory-ems-obs-grafana-1 date
```

**解决**：在 Grafana UI 中调整时区：Profile → Preferences → Timezone → 选择客户所在时区。也可在 Dashboard Settings → Timezone 中为单个 dashboard 设置。

> 不建议修改容器时区，会导致日志时间戳与 Loki 查询时间范围错位。

---

## 10. FAQ

### Q1：Grafana 密码忘了怎么办？

`grafana-init.sh` 生成的密码仅打印一次，之后只存储在 `.env.obs` 中。

**优先检查**：

```bash
grep OBS_GRAFANA_ADMIN_PASSWORD ops/observability/.env.obs
```

**若 `.env.obs` 已删除或密码为空**，重置流程：

```bash
cd ops/observability
./scripts/obs-down.sh
./scripts/grafana-init.sh   # 重新生成密码，更新 .env.obs
./scripts/obs-up.sh
```

Grafana volume（`grafana-data`）保留 dashboard、数据源等配置，不受影响。

---

### Q2：我没有 SMTP，能跑吗？

可以。所有告警通道变量（`OBS_SMTP_*`、`OBS_DINGTALK_*`、`OBS_WECHAT_*`、`OBS_GENERIC_WEBHOOK`）均为可选。留空时 Alertmanager 静默跳过对应接收方，**不影响观测栈正常启动**。

若所有通道均未配置，Alertmanager 仍然工作，告警规则仍然持续评估，只是无通知发出。可通过 Alertmanager UI（`http://127.0.0.1:9093`）手动查看当前告警状态。

---

### Q3：钉钉 / 企微 Webhook 在内网不通怎么办？

观测栈通过 `obs-webhook-bridge` 服务转发告警到钉钉 / 企微。若客户服务器无法直连外网：

**方案 A（推荐）**：在有外网访问权限的跳板机上单独部署 `obs-webhook-bridge`，修改 Alertmanager 配置中的 webhook URL 指向该服务器。

**方案 B**：配置 HTTP 代理，在 `docker-compose.obs.yml` 的 `obs-webhook-bridge` 服务中添加 `HTTP_PROXY` / `HTTPS_PROXY` 环境变量。

**方案 C**：使用 `OBS_GENERIC_WEBHOOK` 对接客户内部 IT 工单系统（通常内网可达），替代钉钉 / 企微。

验证 bridge 服务内部健康：

```bash
docker exec factory-ems-obs-obs-webhook-bridge-1 \
  wget -q -O- http://127.0.0.1:8080/health
```

---

### Q4：磁盘占用越来越大，怎么办？

Prometheus / Loki / Tempo 在保留期内持续写入，到期后自动清理。若磁盘增长超出预期：

```bash
# 查看各 volume 实际占用
docker system df -v | grep factory-ems-obs
```

常见原因与处置：

| 原因 | 处置 |
|------|------|
| 保留期设置过长 | 修改 `.env.obs` 中 `OBS_PROMETHEUS_RETENTION` / `OBS_LOKI_RETENTION`，重启对应容器 |
| 日志量异常（程序 bug 刷日志） | 检查产品栈日志级别，临时将 `OBS_LOKI_RETENTION` 缩短至 `48h` |
| Tempo trace 量超预期 | 检查产品栈 OTLP 采样率配置（`application-prod.yml`） |
| Prometheus metric 基数爆炸 | 参照 `docs/product/observability-metrics-dictionary.md` 核查 cardinality 预估 |

紧急释放空间（注意：删除全部历史数据）：

```bash
cd ops/observability
./scripts/obs-down.sh -v   # 警告：删除所有 volume 中的历史数据
./scripts/obs-up.sh
```

---

### Q5：obs-smoke.sh 报 Loki / Tempo readiness failed 怎么办？

Loki 和 Tempo 冷启动需 15–30 秒，smoke 检查可能早于就绪。等待后手动重跑：

```bash
sleep 30
cd ops/observability && ./scripts/obs-smoke.sh
```

若仍失败，查看日志确认是否有异常：

```bash
docker compose --env-file .env.obs -f docker-compose.obs.yml logs loki tempo
```

若 Loki 报 `too many open files`，增大系统文件描述符限制：

```bash
ulimit -n 65536
# 生产环境建议在 /etc/security/limits.conf 中持久化该配置
```
