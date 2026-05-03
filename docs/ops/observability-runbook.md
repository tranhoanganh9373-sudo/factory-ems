# factory-ems 可观测性栈 Runbook

> **适用版本**：v1.7.0-obs 起
> **最近更新**：2026-04-30（Phase F 落实）
> **受众**：值班工程师 / 客户 SRE / 实施工程师 —— 当 Alertmanager 推送报警、Grafana 出现异常曲线、或观测栈本身故障时使用本文档定位与处置
>
> **配套文档**：
> - 部署 / 升级 / 卸载：[observability-deployment.md](./observability-deployment.md)
> - 配置参考：[../product/observability-config-reference.md](../product/observability-config-reference.md)
> - 指标字典：[../product/observability-metrics-dictionary.md](../product/observability-metrics-dictionary.md)
> - SLO 与报警（客户视角）：[../product/observability-slo-rules.md](../product/observability-slo-rules.md)
> - Dashboard 使用指南：[../product/observability-dashboards-guide.md](../product/observability-dashboards-guide.md)
> - 业务报警（采集中断）：[alarm-runbook.md](./alarm-runbook.md)
> - 设计规格：[../superpowers/specs/2026-04-29-observability-stack-design.md](../superpowers/specs/2026-04-29-observability-stack-design.md)

---

## 1. 系统架构（速览）

观测栈是一个独立 docker-compose 编排的进程组，与产品栈共享 `ems-net` 网络。生命周期、发版、替换三方面完全解耦。

```
┌──────── 产品栈 (docker-compose.yml) ────────┐
│ nginx → factory-ems (Spring Boot)            │
│              │ actuator/prometheus           │
│              │ stdout JSON logs              │
│              │ OTLP HTTP :4318               │
└──────────────┼───────────────────────────────┘
               │
┌──────── 观测栈 (docker-compose.obs.yml) ────────┐
│  prometheus :9090 ─┐                             │
│  loki :3100        ├─► grafana :3000 (UI)        │
│  tempo :3200/:4318 ┘                             │
│  promtail (边车，读 docker.sock + 容器日志)       │
│  alertmanager :9093 ──► obs-webhook-bridge :9094 │
│                              │                   │
│                              ├─► email (SMTP)    │
│                              ├─► 钉钉 webhook     │
│                              ├─► 企微 webhook     │
│                              └─► generic webhook │
└──────────────────────────────────────────────────┘
```

**数据流**：

- **Metrics**：factory-ems 的 `:8080/actuator/prometheus` → Prometheus 抓取（15s 间隔）→ Grafana 展示 / Alertmanager 评估
- **Logs**：容器 stdout → promtail（订阅 docker.sock）→ Loki → Grafana Explore
- **Traces**：factory-ems OTLP HTTP → tempo:4318 → Grafana Explore（trace ID 与 logs 联动）
- **Alerts**：Prometheus 规则评估 → Alertmanager 分组路由 → email / webhook-bridge → 钉钉/企微/generic

**与 ems-alarm 的边界**：

| 维度 | ems-alarm（产品功能） | observability stack（基础设施） |
|------|---------------------|-------------------------------|
| 关注对象 | 单设备数据是否中断（业务侧） | 应用 / JVM / 数据流 / 资源（系统侧） |
| 触发条件 | 数据库扫描 silent_timeout / consecutive_fail | Prometheus 规则评估 PromQL |
| 通知通道 | webhook_config（每个客户独立配置） | Alertmanager 全局路由 |
| 受众 | 客户操作员 / 现场维护 | 运维 / SRE / 实施工程师 |
| 文档归属 | [alarm-runbook.md](./alarm-runbook.md) | 本文档 |

> 同一类问题可能同时触发两边报警（如设备掉线 → ems-alarm 触发 SILENT_TIMEOUT，同时 collector poll 失败率上升触发 EmsCollectorOfflineDevices）。处置时先按本 Runbook 排查基础设施，确认没有系统层问题后再进入 alarm-runbook 处理业务侧。

---

## 2. 启停（链 deployment 文档）

完整启停流程见 [observability-deployment.md §4–§8](./observability-deployment.md)。本节仅给出**值班高频命令**，不复制部署文档内容。

### 三个核心脚本

```bash
cd ops/observability

./scripts/obs-up.sh        # 启动全栈（含 ems-net 网络幂等创建 + smoke 检查）
./scripts/obs-smoke.sh     # 健康检查 prometheus/alertmanager/grafana/loki/tempo
./scripts/obs-down.sh      # 停止（保留 volumes 和 .env.obs）
./scripts/obs-down.sh -v   # 停止并删除所有 volumes（不可逆）
```

### 5 个最常用故障排查命令

```bash
# 1. 当前栈状态（容器健康、运行时长）
docker compose --env-file .env.obs -f docker-compose.obs.yml ps

# 2. 实时日志（某个组件）
docker compose --env-file .env.obs -f docker-compose.obs.yml logs -f prometheus

# 3. 重启单个组件（保留其他容器）
docker compose --env-file .env.obs -f docker-compose.obs.yml restart alertmanager

# 4. 资源占用快照（CPU / mem / 网络）
docker stats --no-stream $(docker ps -q --filter name=factory-ems-obs)

# 5. 检查 OOM 或异常退出
docker inspect $(docker ps -aq --filter name=factory-ems-obs) \
  --format '{{.Name}} state={{.State.Status}} oom={{.State.OOMKilled}} exit={{.State.ExitCode}}'
```

> 上线 / 升级 / 卸载流程不在本文范围；遇到首次部署或大版本升级，直接跳到 [observability-deployment.md](./observability-deployment.md)。

---

## 3. Critical Alert 一键处置（5 条）

Critical 报警 → Alertmanager 路由到 `multi-channel`（email + 钉钉 + 企微 + generic webhook）。响应时间目标 5 分钟。

每条报警按统一格式：**触发条件** / **业务影响** / **初步排查 5 步** / **升级路径** / **误报模式**。

### EmsAppDown

**触发条件**：`up{job="factory-ems"} == 0`，持续 2 分钟。Prometheus 抓取 `actuator/prometheus` 失败。

**业务影响**：factory-ems 实例不可达 → HTTP 请求全失败、采集任务停止、报警检测停止。等同服务整体宕机。

**初步排查 5 步**：

```bash
# 1. 确认产品栈容器是否存活
docker ps --filter name=factory-ems --format '{{.Names}}\t{{.Status}}'

# 2. 容器存在但 actuator 不通 → 进容器自查端口
docker exec factory-ems curl -sf http://127.0.0.1:8080/actuator/health || echo FAIL

# 3. 看应用日志最后 100 行（关键字 ERROR / OutOfMemory / Connection refused）
docker logs --tail=100 factory-ems 2>&1 | grep -E 'ERROR|Exception|OOM|Killed' | tail -20

# 4. 看是否被 OOM kill
docker inspect factory-ems --format 'OOM={{.State.OOMKilled}} Exit={{.State.ExitCode}} Restarted={{.RestartCount}}'

# 5. 从观测栈侧确认：Prometheus targets 是否真的失败
curl -s 'http://127.0.0.1:9090/api/v1/targets?state=active' | \
  python3 -c "import sys,json; [print(t['scrapeUrl'], t['health'], t.get('lastError','')) for t in json.load(sys.stdin)['data']['activeTargets'] if t['labels'].get('job')=='factory-ems']"
```

**升级路径**：

- 容器频繁重启（`RestartCount > 3`）+ OOM → 立刻联系 backend 开发，准备扩容内存或回滚
- 容器在但 actuator 504 / 连接超时 → JVM 卡死，尝试 `kill -3 <pid>` dump 后再重启；超过 15 分钟未恢复，回滚到上一版本
- Prometheus 自身故障导致误报 → 见下方误报模式

**误报模式**：

- Prometheus 容器刚启动（< 30s），首次抓取尚未完成 → 等待下一个 scrape 周期（默认 15s）后重新检查
- 网络分区（observability stack 与 product stack 在不同主机时）→ 检查 `docker network inspect ems-net`
- factory-ems 短暂 GC 暂停超过 scrape timeout（10s）→ 单次抓取失败但不到 2 分钟阈值；若连续命中，结合 `EmsJvmGcPressure` 一起处理

---

### EmsAppHighErrorRate

**触发条件**：`rate(ems_app_exception_total[5m]) > 1`，持续 5 分钟。应用每秒抛出 1 次以上未捕获异常。

**业务影响**：用户请求大量失败。可能是某接口集中崩、上游依赖异常或数据问题。

**初步排查 5 步**：

```bash
# 1. 按异常类型 / endpoint 聚合（PromQL）
# Grafana Explore 或 curl Prometheus API
curl -s --data-urlencode \
  'query=topk(10, sum by (exception, endpoint) (rate(ems_app_exception_total[5m])))' \
  http://127.0.0.1:9090/api/v1/query | python3 -m json.tool

# 2. Loki 拉最近 5 分钟 ERROR 日志
# Grafana Explore 选 Loki：
#   {container_name="factory-ems"} |= "ERROR" | json | line_format "{{.timestamp}} {{.message}}"

# 3. 直接看容器日志最近 200 行 ERROR
docker logs --tail=500 factory-ems 2>&1 | grep -A3 ERROR | tail -50

# 4. 关联 trace（异常通常带 trace_id）
# Grafana Explore Tempo：搜索 service.name=factory-ems status=ERROR

# 5. 上游依赖：DB / influxdb / collector adapter
docker exec factory-ems curl -sf http://127.0.0.1:8080/actuator/health/db
docker exec factory-ems curl -sf http://127.0.0.1:8080/actuator/health/influxdb
```

**升级路径**：

- 错误集中在单一 endpoint → backend owner（参考 spec §11.4）
- 错误来自 collector 模块 → collector owner，同时检查 `EmsCollectorPollSlow`
- 错误率 > 5/s 持续 → 紧急回滚到上一版本镜像

**误报模式**：

- 单元测试 / 健康检查接口异常被纳入计数（应过滤 `outcome="SUCCESS"`，但 ems_app_exception_total 不带该 label）
- 业务侧批量重试导致瞬时尖峰（如调度任务 retry storm）→ 配合 logs 看是否同一 trace 重复
- 应用刚启动期间的初始化异常（如 Flyway 锁冲突）→ 通常 5 分钟内自愈

---

### EmsDataFreshnessCritical

**触发条件**：`max(ems_meter_reading_lag_seconds) > 600`，持续 2 分钟。最大数据延迟超过 10 分钟。

**业务影响**：计费 / 报表 / 报警检测都依赖最新读数。lag 过大意味着仪表数据没法实时反映现场状态，会导致计费滞后、SLA 违约、客户工单。

**初步排查 5 步**：

```bash
# 1. 找到 lag 最大的设备
curl -s --data-urlencode \
  'query=topk(10, ems_meter_reading_lag_seconds)' \
  http://127.0.0.1:9090/api/v1/query | python3 -m json.tool

# 2. 看采集成功率（按 adapter）
curl -s --data-urlencode \
  'query=sum by (adapter) (rate(ems_collector_poll_total{outcome="success"}[5m])) / sum by (adapter) (rate(ems_collector_poll_total[5m]))' \
  http://127.0.0.1:9090/api/v1/query | python3 -m json.tool

# 3. 业务库直查：lag 排名前 10
docker exec factory-ems-db psql -U postgres -d ems -c \
  "SELECT meter_code, EXTRACT(EPOCH FROM (NOW() - last_read_at))::int AS lag_s
   FROM meters ORDER BY last_read_at NULLS LAST LIMIT 10;"

# 4. 检查 collector status
curl -s http://127.0.0.1:8080/api/v1/collector/status | jq '.devices[] | select(.online==false) | .meterCode'

# 5. 与 EmsCollectorPollSlow / EmsCollectorOfflineDevices 联动
# Grafana 打开 D5 (Collector dashboard) 看 adapter 维度
```

**升级路径**：

- 单设备 lag → 现场维护检查物理连接（属于业务侧 ems-alarm 的范围，参见 [alarm-runbook.md §13](./alarm-runbook.md#13-故障决策树)）
- 全部 adapter 数据停滞 → collector 模块整体异常，检查 `EmsAppDown` / 数据库连接 / influxdb 写入
- > 10 分钟未恢复且影响计费 → 通知客户业务联系人 + 准备人工补录数据

**误报模式**：

- 维护模式启用期间 lag 自然增长（业务侧可接受）→ 此报警不区分 maintenance_mode；可在 Alertmanager 临时静默该 instance/meter
- 系统时间漂移导致 `NOW() - last_read_at` 异常 → 检查容器 NTP（`docker exec factory-ems date -u`）
- 低频仪表（5 分钟一报）正常 lag 接近 600s → 阈值需按客户调整或将该仪表排除（label 过滤）

---

### EmsDbConnectionPoolExhausted

**触发条件**：`hikaricp_connections_active / hikaricp_connections_max > 0.95`，持续 3 分钟。HikariCP 连接池接近耗尽。

**业务影响**：新请求等待获取连接 → HTTP 超时 → 错误率上升。是 `EmsAppHighErrorRate` 的常见前置征兆。

**初步排查 5 步**：

```bash
# 1. 当前 active / idle / max 实时数据
curl -s --data-urlencode \
  'query={__name__=~"hikaricp_connections.*"}' \
  http://127.0.0.1:9090/api/v1/query | python3 -m json.tool

# 2. PostgreSQL 端：哪些查询持有连接（identify long-running）
docker exec factory-ems-db psql -U postgres -d ems -c \
  "SELECT pid, usename, application_name, state, query_start, LEFT(query, 100) AS q
   FROM pg_stat_activity
   WHERE state != 'idle' AND datname='ems'
   ORDER BY query_start LIMIT 20;"

# 3. 应用端 thread dump（找到持连接但未释放的线程）
# Spring Boot 默认日志走 stdout（ConsoleAppender + JSON），SIGQUIT 后 thread dump 会出现在 docker logs：
docker exec factory-ems kill -3 1 && sleep 1 && docker logs --tail 400 factory-ems 2>&1 | grep -A 200 'Full thread dump'

# 4. 检查最近 5 分钟慢查询（PromQL）
curl -s --data-urlencode \
  'query=histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket[5m]))) > 5' \
  http://127.0.0.1:9090/api/v1/query | python3 -m json.tool

# 5. 看是否最近修改了池配置或新增大查询
grep -n 'hikari\|maximum-pool-size' ems-app/src/main/resources/application*.yml
```

**升级路径**：

- 池已是默认 10 但请求量正常 → 调大 `spring.datasource.hikari.maximum-pool-size` 至 20，重启应用
- 池耗尽伴随长事务（pg_stat_activity 中 state=`idle in transaction` 行 > 5 分钟）→ backend owner 排查事务边界 / `@Transactional` 漏配
- 与 critical disk full 同时出现 → DB 写阻塞导致连接堆积，先解决磁盘

**误报模式**：

- 启动瞬时 active=max（应用 warm-up 阶段批量初始化）→ 通常 < 1 分钟，不触发 3 分钟阈值
- HikariCP 上报指标短暂跳变 → 连续 3 分钟 > 95% 才触发，单点跳变不会误报
- 多实例部署但 pool label 重复 → 检查 `pool` label，避免聚合错误

---

### EmsDiskSpaceCritical

**触发条件**：`node_filesystem_avail_bytes / node_filesystem_size_bytes < 0.10`（排除 tmpfs / overlay / squashfs），持续 5 分钟。任一非临时文件系统可用空间 < 10%。

**业务影响**：磁盘耗尽 → PostgreSQL / influxdb / Loki / Tempo 写失败 → 数据丢失 + 应用崩溃。基础设施级最严重故障。

**初步排查 5 步**：

```bash
# 1. 找到爆掉的 mountpoint
df -h | sort -k5 -hr | head -10

# 2. 哪些 docker volume 占地最大
docker system df -v | grep -E 'VOLUME|factory-ems' | head -20

# 3. 各观测栈 volume 用量
for v in prom-data loki-data tempo-data grafana-data alertmanager-data; do
  size=$(docker run --rm -v factory-ems-obs_${v}:/d alpine du -sh /d 2>/dev/null | cut -f1)
  echo "$v: $size"
done

# 4. 是否有失控的日志文件
find /var/log /var/lib/docker/containers -type f -size +500M 2>/dev/null | head -10

# 5. PostgreSQL / influxdb 大表
docker exec factory-ems-db psql -U postgres -d ems -c \
  "SELECT relname, pg_size_pretty(pg_total_relation_size(relid)) AS size
   FROM pg_catalog.pg_statio_user_tables ORDER BY pg_total_relation_size(relid) DESC LIMIT 10;"
```

**紧急释放空间**：

```bash
# 选项 A：缩短观测栈保留期（不丢正在写的数据）
# 编辑 ops/observability/.env.obs：
#   OBS_PROMETHEUS_RETENTION=15d
#   OBS_LOKI_RETENTION=72h
docker compose --env-file .env.obs -f docker-compose.obs.yml restart prometheus loki

# 选项 B：清理 docker 镜像 / 缓存（保留容器和 volume）
docker image prune -af
docker builder prune -af

# 选项 C（最后手段，丢历史数据）：
# cd ops/observability && ./scripts/obs-down.sh -v
# 仅当客户接受历史指标 / 日志清空时使用
```

**升级路径**：

- 持续增长且无法定位单一 volume → 联系运维 owner，准备扩容磁盘
- DB / influxdb 表暴涨（新业务上线导致写入翻倍）→ backend owner 评估归档策略
- < 5% 时立即升级到客户负责人 + 业务方，避免数据写失败

**误报模式**：

- node_exporter 把 `/var/lib/docker/overlay2` 上报为独立 mount → 默认 fstype `overlay` 已被规则排除；若仍计入，检查 cadvisor 配置
- 临时编译 / 测试目录被监控（如 `/tmp` tmpfs）→ 已通过 `fstype!~"tmpfs|overlay|squashfs"` 过滤
- 单次大文件复制后立刻清理 → 5 分钟阈值过滤掉了瞬时尖峰

---

## 4. Warning Alert 处置思路（11 条 = 9 + 2 burn-rate）

Warning 报警 → Alertmanager 路由到 `default-email`（仅邮件）。响应时间目标 24 小时。

每条按紧凑格式：**触发场景 / 调查方向 / 何时升级 critical**。

### EmsAppLatencyHigh

**触发场景**：HTTP p99 延迟 > 1s（仅统计 outcome=SUCCESS），持续 10 分钟。

**调查方向**：Grafana D4（HTTP dashboard）按 endpoint 拆 p99；Tempo 找慢 trace；DB 慢查询日志（`pg_stat_statements`）。常见根因是某个新接口缺索引、外部 HTTP 调用变慢、批量查询无分页。

**升级 critical**：若 p99 > 5s 持续 30 分钟，或同时出现 `EmsDbConnectionPoolExhausted` / `EmsAppHighErrorRate`，按 critical 处理。

---

### EmsCollectorPollSlow

**触发场景**：单 adapter poll p95 > 30s，持续 15 分钟。`{{ $labels.adapter }}` 指明哪个 adapter。

**调查方向**：D5（Collector dashboard）选定 adapter 看 success_ratio 与 latency 时序；检查现场网络（Modbus TCP 网关延迟）；查日志（任选其一）：`docker logs --tail 1000 factory-ems 2>&1 | grep -i "adapter" | grep -i "timeout"`，或 Grafana Explore Loki：`{container_name="factory-ems"} |= "adapter" |= "timeout" | json`。RTU 链路串口波特率 / 光纤异常常见。

**升级 critical**：若 poll 失败率同时 > 50% 且 lag > 10 分钟，触发 `EmsDataFreshnessCritical`。

---

### EmsAlarmDetectorSlow

**触发场景**：AlarmDetector 扫描 p95 > 10s，持续 15 分钟。默认 60s 一轮，扫描 10s 已是池子告急的信号。

**调查方向**：检查 `alarms` 表行数（`SELECT COUNT(*) FROM alarms WHERE status IN ('ACTIVE','ACKED')`）；查询计划是否走索引；设备数是否激增；DB CPU / IO 是否瓶颈。详见 [alarm-runbook.md §10](./alarm-runbook.md#10-性能与容量)。

**升级 critical**：检测扫描超过 poll-interval（60s）→ 任务堆积，需要立刻扩容 DB 或调大 poll-interval。

---

### EmsWebhookFailureRate

**触发场景**：webhook 派发失败率 > 20%，持续 15 分钟。

**调查方向**：UI → 系统健康 → Webhook 配置 → 下发流水（`lastError` 字段）；接收方 URL 可达性（`curl -X POST <url>`）；签名校验是否一致；超时设置是否过短。完整排查清单见 [alarm-runbook.md §4](./alarm-runbook.md#4-排查-webhook-失败)。

**升级 critical**：失败率 > 80% 持续 30 分钟 → 客户报警通知中断，立即通知客户业务联系人。

---

### EmsSchedulerDrift

**触发场景**：调度漂移 > 60s。**v1 placeholder**：`ems_app_scheduled_drift_seconds` 暂未埋点，规则 expr 为 `ems:slo:scheduler_drift:max_seconds > 60`，恒为 0 不会触发。等待后续版本启用。

**调查方向**：v1 阶段忽略；规则保留是为了 spec / promtool 用例对齐。若收到此报警说明 SLO 录制规则已修改，先核对是否新版本启用了真实埋点。

**升级 critical**：N/A（v1 不会触发）。

---

### EmsJvmMemoryHigh

**触发场景**：JVM heap 使用率 > 85%，持续 15 分钟。

**调查方向**：D3（JVM dashboard）看 heap usage 时序；触发 GC：`docker exec factory-ems jcmd 1 GC.run`；若依然居高 → 取 heap dump：`docker exec factory-ems jcmd 1 GC.heap_dump /tmp/heap.hprof`，复制到主机后用 Eclipse MAT 分析；常见根因：缓存无上限 / Metric label 基数爆炸 / 线程局部存储泄漏。

**升级 critical**：若紧接着触发 OOM kill（`docker inspect ... OOMKilled=true`），按 `EmsAppDown` 流程处置。

---

### EmsJvmGcPressure

**触发场景**：`rate(jvm_gc_pause_seconds_sum[5m]) > 0.5`，持续 10 分钟。即 5 分钟内 GC 暂停累计时间 > 50%。

**调查方向**：D3 dashboard 看 GC 类型分布（young / old / full）；老年代 full GC 频繁 → 内存压力（结合 `EmsJvmMemoryHigh`）；young GC 过频 → 临时对象分配速率高，看是否有大查询返回 List<DTO>。调整 `-Xms / -Xmx` 或 `-XX:+UseG1GC` 参数。

**升级 critical**：连续触发 → 应用即将 OOM；与 `EmsAppLatencyHigh` 同时触发说明 GC stop-the-world 已影响请求处理，准备重启。

---

### EmsCollectorOfflineDevices

**触发场景**：离线设备占比 > 10%，持续 10 分钟。

**调查方向**：D5 dashboard 看 `ems_collector_devices_offline` 时序；区分是单 adapter 故障还是全局；现场维护是否未上报维护模式（`alarm_rule_overrides.maintenance_mode` 应该开启）；网络层 ping 测试。

**升级 critical**：> 30% 持续 → 现场大面积掉线，立即通知客户现场负责人 + 检查物理网络 / 电源。

---

### EmsAlarmBacklog

**触发场景**：silent_timeout 类型 ACTIVE 报警 > 50 条，持续 30 分钟。

**调查方向**：用户没在 ACK / RESOLVE 报警，或者短时间内大量设备掉线导致积压。SQL：`SELECT COUNT(*), MIN(triggered_at) FROM alarms WHERE status='ACTIVE' AND type='silent_timeout';`。看是否最早一条已经超过 1 小时未处理。

**升级 critical**：> 200 条或最早一条 > 4 小时未处理 → 联系客户操作员；可能预示 alarm 模块本身故障（detector 反复重建报警）。

---

### EmsBudgetBurnFast

**完整 alert 名**：`EmsBudgetBurnFastAvailability`。

**触发场景**：可用性 SLO 1h 燃烧率 > 14.4×（即 1 小时内不可用率 > 7.2%），持续 5 分钟。30 天错误预算约 2 天耗尽。

**调查方向**：与 `EmsAppDown` 联动 —— 通常先触发 `EmsAppDown`（critical）再触发本规则确认预算被深度消耗。看 1 小时内 `up{job="factory-ems"}` 时序，确认是否真有连续 down；此报警走 critical 路由（multi-channel）。

**升级 critical**：本身已是 critical 级；预算 < 50% 时通知客户业务方，触发 SLO review。

---

### EmsBudgetBurnSlow

**完整 alert 名**：`EmsBudgetBurnSlowAvailability`。

**触发场景**：可用性 SLO 6h 燃烧率 > 6×（即 6 小时内不可用率 > 3%），持续 30 分钟。30 天错误预算约 5 天耗尽。

**调查方向**：通常是间歇性故障的累积信号 —— 没有任何单次 `EmsAppDown` 命中阈值，但短暂闪断累计起来已显著消耗预算。看过去 6 小时所有 `up == 0` 的事件分布。

**升级 critical**：转为 fast burn → 自动触发 `EmsBudgetBurnFast`；若两者同时活跃，按 fast 处置。

---

## 5. 数据保留与备份

### 默认保留期 / 备份方式

| 组件 | 保留期 | 环境变量 | 稳态磁盘 | 备份方式 |
|------|--------|---------|---------|---------|
| Prometheus | 30d | `OBS_PROMETHEUS_RETENTION` | ~5 GB | volume tar 离线快照 |
| Loki | 14d (336h) | `OBS_LOKI_RETENTION` | ~8 GB | volume tar 离线快照 |
| Tempo | 3d (72h) | `OBS_TEMPO_RETENTION` | ~2 GB | volume tar 离线快照（trace 是辅助数据，保留期短） |
| Grafana | 永久 | — | ~200 MB | grafana-data volume 备份（含 dashboard / datasource / 用户） |
| Alertmanager | 5d 静默记录 | — | < 100 MB | 含 alertmanager-data volume |

### 备份命令

完整步骤见 [observability-deployment.md §5](./observability-deployment.md#5-数据保留与磁盘规划)。简化命令：

```bash
cd ops/observability
./scripts/obs-down.sh

for v in prom-data loki-data tempo-data grafana-data alertmanager-data; do
  docker run --rm \
    -v factory-ems-obs_${v}:/src \
    -v $(pwd)/backup:/backup \
    alpine tar czf /backup/${v}-$(date +%Y%m%d).tar.gz -C /src .
done

./scripts/obs-up.sh
```

### 调整保留期生效

```bash
# 编辑 .env.obs 后
docker compose --env-file .env.obs -f docker-compose.obs.yml up -d \
  prometheus loki tempo
```

> 缩短保留期不会立即清理已有数据；prometheus 在下一个 head compaction 时清理，loki 在下一个 retention sweep 时清理（最长 24 小时延迟）。

---

## 6. Grafana 用户管理 / 密码遗忘恢复

### 初次部署 admin 密码生成

由 `grafana-init.sh` 生成并写入 `.env.obs`，仅打印一次：

```bash
cd ops/observability
./scripts/grafana-init.sh
```

详见 [observability-deployment.md §4 步骤 3](./observability-deployment.md#步骤-3生成-grafana-管理员密码)。

### 添加新用户（Web UI）

1. admin 登录 → Configuration → Users → Invite
2. 填写 Email / Role（Viewer / Editor / Admin）
3. 客户内网无 SMTP 时，邀请邮件无法投递 → 直接选 "Pending Invite"，手动复制邀请链接发送

### 添加新用户（CLI）

```bash
docker exec factory-ems-obs-grafana-1 grafana-cli admin reset-admin-password '<new-pass>'
# 添加普通用户使用 API：
curl -u admin:<adminpass> -X POST http://127.0.0.1:3000/api/admin/users \
  -H 'Content-Type: application/json' \
  -d '{"name":"alice","email":"alice@x.com","login":"alice","password":"Init#1234"}'
```

### 重置 admin 密码（密码遗忘）

**优先**：检查 `.env.obs` 中是否仍保存原密码：

```bash
grep OBS_GRAFANA_ADMIN_PASSWORD ops/observability/.env.obs
```

**若 `.env.obs` 已删除或值丢失**，使用容器内 CLI 重置（不影响数据 volume）：

```bash
docker exec factory-ems-obs-grafana-1 \
  grafana-cli admin reset-admin-password 'NewStrongPass123!'

# 同步更新 .env.obs，避免下次 obs-up.sh 与容器密码不一致
sed -i.bak 's|^OBS_GRAFANA_ADMIN_PASSWORD=.*|OBS_GRAFANA_ADMIN_PASSWORD=NewStrongPass123!|' \
  ops/observability/.env.obs
```

> 如果 `grafana-data` volume 也丢失（最坏情况），按 [observability-deployment.md FAQ Q1](./observability-deployment.md#q1grafana-密码忘了怎么办) 重新跑 `obs-down.sh && grafana-init.sh && obs-up.sh`，dashboard / datasource 由 provisioning 自动恢复，但用户列表会重置。

### LDAP / SSO

**v1 不启用**。所有用户在 Grafana 内部数据库管理。后续若客户要求接入企业 LDAP / SAML，参考 Grafana 官方文档 `grafana.ini → [auth.ldap]` / `[auth.generic_oauth]`，在 grafana provisioning 中追加配置。

---

## 7. 升级与回滚

### 镜像版本固定策略

参见 spec §15。`docker-compose.obs.yml` 中所有镜像 tag 显式 pin 到次版本（如 `prom/prometheus:v2.54.x`），**禁止使用 `latest`**。升级须显式修改 tag 后 `docker compose up -d`。

### 仅升级观测栈

```bash
cd ops/observability
docker compose --env-file .env.obs -f docker-compose.obs.yml pull
docker compose --env-file .env.obs -f docker-compose.obs.yml up -d
```

不影响产品栈。

### 仅升级单个组件

```bash
# 例：仅升级 prometheus
# 1. 修改 docker-compose.obs.yml 的 prometheus.image tag
# 2. 拉镜像 + 重建该容器
docker compose --env-file .env.obs -f docker-compose.obs.yml pull prometheus
docker compose --env-file .env.obs -f docker-compose.obs.yml up -d --force-recreate prometheus

# 3. 观察 5 分钟，确认抓取正常
curl -s 'http://127.0.0.1:9090/api/v1/targets?state=active' | \
  python3 -c "import sys,json; t=json.load(sys.stdin)['data']['activeTargets']; print('healthy:', sum(1 for x in t if x['health']=='up'), '/', len(t))"
```

### 大版本升级注意事项

| 组件 | 风险点 | 推荐做法 |
|------|--------|---------|
| Prometheus 2.x → 3.x | TSDB 格式可能不兼容 | 先备份 prom-data，先在测试环境验证 |
| Loki 2.x → 3.x | 索引格式变化（schema 升级） | 阅读 release notes 的 schema migration 章节 |
| Tempo 2.x | 默认配置项 / block 格式变化 | trace 保留期短，必要时直接清空 volume |
| Grafana 10.x → 11.x | dashboard JSON 通常向后兼容 | 备份 grafana-data；保留 provisioning 配置作为回滚 |

### webhook-bridge 升级

`obs-webhook-bridge` 是本地构建（`./webhook-bridge/`）。代码变更后：

```bash
cd ops/observability
docker compose --env-file .env.obs -f docker-compose.obs.yml build obs-webhook-bridge
docker compose --env-file .env.obs -f docker-compose.obs.yml up -d obs-webhook-bridge
```

详见 [observability-deployment.md §6](./observability-deployment.md#webhook-bridge-升级)。

### 回滚

镜像 tag pinning 是回滚的关键 —— 把 `docker-compose.obs.yml` 的 image tag 改回上一版本，再 `up -d --force-recreate <service>`。

**volume 数据兼容性**：

- 同 minor 版本（如 v2.54 ↔ v2.55）回滚通常无问题
- 跨 major 回滚（v3 → v2）有概率失败，需要从备份恢复 volume：

```bash
# 删除有问题的 volume 后从备份还原
docker volume rm factory-ems-obs_prom-data
docker volume create factory-ems-obs_prom-data
docker run --rm \
  -v factory-ems-obs_prom-data:/dst \
  -v $(pwd)/backup:/backup \
  alpine tar xzf /backup/prom-data-YYYYMMDD.tar.gz -C /dst
docker compose --env-file .env.obs -f docker-compose.obs.yml up -d prometheus
```

---

## 8. 与产品栈联调时的常见冲突

### 8.1 ems-net 网络冲突 / 不存在

**症状**：`network ems-net declared as external, but could not be found`，或两套 compose 互不可见。

```bash
docker network ls | grep ems-net
docker network inspect ems-net --format '{{range $k,$v := .Containers}}{{$v.Name}} {{end}}'
```

**处置**：`obs-up.sh` 幂等创建；若手动执行 compose 报错，先 `docker network create ems-net` 再启动。

---

### 8.2 端口冲突（产品栈 nginx vs obs grafana 等）

| 产品栈端口 | 观测栈端口 | 冲突点 |
|----------|----------|--------|
| nginx 80 / 443 | grafana 3000（仅 127.0.0.1） | 默认不冲突，监听地址不同 |
| factory-ems 8080 | prometheus 9090 | 不冲突 |
| postgres 5432 | — | 不冲突 |
| influxdb 8086 | — | 不冲突 |
| — | tempo 4318 (0.0.0.0) | 若客户已有 OTLP 收集器占 4318 → 冲突 |

```bash
lsof -i :3000 -i :4318 -i :9090 -i :9093

# 修改方案：编辑 docker-compose.obs.yml ports
#   "127.0.0.1:3001:3000"   # 改 grafana 主机端口
#   "127.0.0.1:4319:4318"   # 改 tempo OTLP 主机端口（同时改产品栈 application-prod.yml otlp endpoint）
```

---

### 8.3 内存不足（共 5 GB 内含产品栈 + 观测栈，OOM）

**症状**：容器频繁被 OOM kill，特别是 prometheus / loki。

```bash
docker inspect $(docker ps -aq --filter name=factory-ems) \
  --format '{{.Name}} OOM={{.State.OOMKilled}}'

free -h  # 主机可用内存
docker stats --no-stream
```

**处置**（按风险递增）：

1. 缩短 `OBS_LOKI_RETENTION=72h` / `OBS_PROMETHEUS_RETENTION=15d` → 重启对应容器
2. 调低 collector poll-interval（更少 metric 写入）
3. 客户机器扩容 RAM；建议 obs 栈独立部署到第二台机器（共享 ems-net via overlay 网络）

详见 [observability-deployment.md §9.3](./observability-deployment.md#93-内存不足oom-kill)。

---

### 8.4 OTLP 4318 端口被占（trace 推送失败）

**症状**：factory-ems 日志报 `OTLP exporter failed: connection refused 127.0.0.1:4318`，Tempo dashboard 无 trace。

```bash
# 检查 4318 是否被其他容器占用
lsof -i :4318
docker ps --format '{{.Names}}\t{{.Ports}}' | grep 4318

# tempo 自身是否监听
docker exec factory-ems-obs-tempo-1 wget -qO- http://127.0.0.1:3200/ready

# 应用侧 OTLP 配置（application-prod.yml）
grep -n 'otlp\|tracing' ems-app/src/main/resources/application-prod.yml
```

**处置**：tempo 默认绑 `0.0.0.0:4318`；若此端口被占，改 docker-compose.obs.yml 中 tempo ports 至 `4319:4318`，同时把 ems-app `management.otlp.tracing.endpoint` 改为 `http://obs-tempo:4318`（容器内网走 service name 不受主机端口影响）。

---

### 8.5 时区不一致（容器 UTC vs 主机本地）

**症状**：Grafana 图表与本地时间相差 N 小时；Loki 查询时间范围错位。

容器内默认 UTC，**这是预期行为**。

```bash
docker exec factory-ems date -u    # 应用容器
docker exec factory-ems-obs-prometheus-1 date -u   # prometheus
docker exec factory-ems-obs-grafana-1 date -u     # grafana
```

**处置**：

- Grafana UI 调整：Profile → Preferences → Timezone → 选择客户时区（如 Asia/Shanghai）
- 单 dashboard 调整：Dashboard Settings → Timezone
- **不建议**修改容器 TZ 环境变量 → 会导致日志时间戳与 Loki 查询窗口错位

详见 [observability-deployment.md §9.5](./observability-deployment.md#95-时区不一致容器-utc-vs-浏览器本地时区)。

---

## 附录 A：alert 名 → 章节锚点速查表

供 alertmanager `runbook_url` 反向链接核对：

| Alert 名 | 锚点 | 严重度 |
|---------|------|--------|
| EmsAppDown | [#emsappdown](#emsappdown) | critical |
| EmsAppHighErrorRate | [#emsapphigherrorrate](#emsapphigherrorrate) | critical |
| EmsDataFreshnessCritical | [#emsdatafreshnesscritical](#emsdatafreshnesscritical) | critical |
| EmsDbConnectionPoolExhausted | [#emsdbconnectionpoolexhausted](#emsdbconnectionpoolexhausted) | critical |
| EmsDiskSpaceCritical | [#emsdiskspacecritical](#emsdiskspacecritical) | critical |
| EmsAppLatencyHigh | [#emsapplatencyhigh](#emsapplatencyhigh) | warning |
| EmsCollectorPollSlow | [#emscollectorpollslow](#emscollectorpollslow) | warning |
| EmsAlarmDetectorSlow | [#emsalarmdetectorslow](#emsalarmdetectorslow) | warning |
| EmsWebhookFailureRate | [#emswebhookfailurerate](#emswebhookfailurerate) | warning |
| EmsSchedulerDrift | [#emsschedulerdrift](#emsschedulerdrift) | warning (placeholder) |
| EmsJvmMemoryHigh | [#emsjvmmemoryhigh](#emsjvmmemoryhigh) | warning |
| EmsJvmGcPressure | [#emsjvmgcpressure](#emsjvmgcpressure) | warning |
| EmsCollectorOfflineDevices | [#emscollectorofflinedevices](#emscollectorofflinedevices) | warning |
| EmsAlarmBacklog | [#emsalarmbacklog](#emsalarmbacklog) | warning |
| EmsBudgetBurnFastAvailability | [#emsbudgetburnfast](#emsbudgetburnfast) | critical |
| EmsBudgetBurnSlowAvailability | [#emsbudgetburnslow](#emsbudgetburnslow) | warning |

---

## 附录 B：相关文档

| 文档 | 用途 |
|------|------|
| [observability-deployment.md](./observability-deployment.md) | 部署 / 升级 / 卸载详细步骤 |
| [../product/observability-config-reference.md](../product/observability-config-reference.md) | 环境变量 / 配置项参考 |
| [../product/observability-metrics-dictionary.md](../product/observability-metrics-dictionary.md) | 17 个业务指标字典 + cardinality 控制 |
| [../product/observability-slo-rules.md](../product/observability-slo-rules.md) | 4 SLO + 16 报警（客户视角） |
| [../product/observability-dashboards-guide.md](../product/observability-dashboards-guide.md) | 7 dashboard + 下钻路径 |
| [alarm-runbook.md](./alarm-runbook.md) | 业务报警（采集中断）运维 |
| [runbook-2.0.md](./runbook-2.0.md) | 产品栈 v2.0 通用运维 |
| [../superpowers/specs/2026-04-29-observability-stack-design.md](../superpowers/specs/2026-04-29-observability-stack-design.md) | 完整设计规格 |
