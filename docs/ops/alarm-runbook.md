# Alarm Runbook (ems-alarm)

> 适用版本：v1.6.0-alarm 起
> 最近更新：2026-04-29
> 受众：运维、值班工程师、客户实施
>
> 配套文档：
> - 用户操作: ../product/alarm-user-guide.md
> - 业务规则: ../product/alarm-business-rules.md
> - 检测算法: ../product/alarm-detection-rules.md
> - 配置参数: ../product/alarm-config-reference.md
> - Webhook 集成: ../product/alarm-webhook-integration.md
> - API 参考: ../api/alarm-api.md

## 1. 模块速览

- 模块名: ems-alarm
- 数据表: alarms / alarm_inbox / alarm_rule_overrides / webhook_config / webhook_delivery_log
- Flyway: V2.2.0__init_alarm.sql（在 ems-app/src/main/resources/db/migration/）
- 调度: AlarmDetector（默认 60s 扫一轮）+ AlarmStateMachine 自动恢复（5min 抑制窗）
- 异步: webhookExecutor (core 2 / max 4 / queue 100)，重试 ScheduledExecutorService
- 配置 prefix: `ems.alarm.*`（detection / dispatch / webhook 三个子命名空间）

核心参数一览：

| 参数 | 默认值 | 含义 |
|------|--------|------|
| `default-silent-timeout-seconds` | 600 | 全局静默超时阈值（秒） |
| `default-consecutive-fail-count` | 3 | 全局连续失败次数阈值 |
| `poll-interval-seconds` | 60 | 检测扫描周期（秒） |
| `suppression-window-seconds` | 300 | 告警抑制窗口（秒） |
| `webhook-retry-max` | 3 | Webhook 最大重试次数 |
| `webhook-retry-backoff-seconds` | [10, 60, 300] | 重试退避间隔数组（秒） |
| `webhook-timeout-default-ms` | 5000 | Webhook 请求超时（毫秒） |

---

## 2. 配置 Webhook

逐步：

1. 进入 **系统健康 → Webhook 配置**（仅 ADMIN 角色可见）
2. 启用开关 ON（`enabled=false` 时所有派发完全跳过，仅保留站内通知）
3. 填写配置字段：
   - **URL**：必须 `http://` 或 `https://`（强烈建议 HTTPS）
   - **Secret**：任意字符串，建议 32 位以上随机串（`openssl rand -hex 32`），用于 HMAC-SHA256 签名
   - **Adapter**：默认 `GENERIC_JSON`（标准 JSON 格式）；可扩展 DINGTALK、WECHAT_WORK 等
   - **Timeout**：1000–30000 毫秒，建议内网 2000ms、外网 5000ms
4. 点击 **测试发送**：构造一条假告警立即同步发送，2xx 视为通过；否则页面显示返回 statusCode / error 提示（测试发送不写 delivery_log，不计重试次数）
5. 点击 **保存**

Webhook 请求携带的 HTTP Headers：

| Header | 含义 |
|--------|------|
| `Content-Type` | `application/json`（UTF-8） |
| `X-EMS-Event` | 事件类型，与 payload `event` 字段相同 |
| `X-EMS-Signature` | `sha256=<hex>`，HMAC-SHA256(secret_utf8, body_utf8) |

返回字段说明参考 `../api/alarm-api.md#webhook-config-test`。

> 首版说明：`alarm.resolved`（恢复）事件仅写站内通知，**不发送** Webhook。

---

## 3. 静默超时 / 连续失败 阈值调优

**默认值**（参考 alarm-config-reference.md）：

- `silentTimeoutSeconds = 600`（10 分钟）
- `consecutiveFailCount = 3`
- `suppressionWindowSeconds = 300`（5 分钟）

**全局调整**（修改后需重启 ems-app 生效）：

```yaml
# ems-app/src/main/resources/application.yml
ems:
  alarm:
    default-silent-timeout-seconds: 600
    default-consecutive-fail-count: 3
    suppression-window-seconds: 300
```

**单设备覆盖**（立即写库，无需重启，≤ 60s 生效）：

- UI 路径：系统健康 → 阈值规则 → 搜索设备 → 编辑
- API：`PUT /api/v1/alarm-rules/overrides/{deviceId}`

覆盖字段：

| 字段 | 类型 | 留 NULL 时 |
|------|------|-----------|
| `silent_timeout_seconds` | Integer | 沿用全局默认 |
| `consecutive_fail_count` | Integer | 沿用全局默认 |
| `maintenance_mode` | boolean | 默认 false |

**生效延迟**：全局参数需重启；设备级覆盖最长等待一个调度周期（默认 ≤ 60s）。

典型调优场景：

| 采集周期 | 推荐 silentTimeout | 推荐 consecutiveFailCount |
|----------|--------------------|--------------------------|
| 5s（高频） | 60–120s | 2–3（网络稳定） |
| 30s（中频） | 300–600s | 3–5 |
| 60s（标准） | 600–1800s | 3–5 |
| 5min（低频仪表） | 1800–3600s | 3 |

---

## 4. 排查 Webhook 失败

**入口**：UI → 系统健康 → Webhook 配置 → 下发流水（最近 N 条）

逐条 `status=FAILED` 行可点击 **重试** 按钮（仅 ADMIN）。等价 API：

```bash
POST /api/v1/webhooks/deliveries/{id}/retry
```

SQL 查询最近失败记录：

```sql
SELECT alarm_id, attempts, response_status, last_error, created_at
FROM webhook_delivery_log
WHERE status = 'FAILED'
ORDER BY created_at DESC
LIMIT 50;
```

持续失败排查清单：

1. **接收方 URL 可达性**：`curl -X POST <url>` 直接测试
2. **签名校验**：接收方是否验证 `X-EMS-Signature`（HMAC-SHA256；body 全文为输入）；EMS Secret 与接收方共享密钥是否一致
3. **Timeout 是否过短**：调大 `timeout_ms`（最大 30000ms）；接收方建议先返回 200 再异步处理耗时操作
4. **接收方返回码**：非 2xx（含 4xx）均视为失败，触发重试 [10s, 60s, 300s] 三次后标记 FAILED
5. **中间代理修改 Body**：gzip 压缩或去除空白会导致签名不一致；检查代理层配置
6. **delivery_log 无记录**：检查 ems-app 日志（搜索 `WebhookChannel`）；确认 `webhook_config.enabled=true`

delivery_log 表保留全部尝试记录（含 `attempts` 计数和 `lastError`）。

---

## 5. 维护期抑制

**路径**：UI → 系统健康 → 阈值规则 → 设备 → 勾选"维护模式" ON → 填写 maintenance_note → 保存

**备注规范**（建议格式）：

```
{原因} YYYY-MM-DD HH:mm-HH:mm 操作人：{姓名}
示例："生产线 B 轴承更换，2026-04-29 09:00-11:00，操作人：张三"
```

**维护期间系统行为**：

| 行为维度 | 维护模式开启时 |
|---------|--------------|
| 检测逻辑 | 完全跳过，不触发新告警 |
| 已有 ACTIVE/ACKED 告警 | 保持原状（维护模式不自动关闭历史告警） |
| 站内通知 | 不发送 |
| Webhook | 不触发 |

**生效**：配置读取在每轮扫描时实时查询，最长延迟一个检测周期（≤ 60s）。

**关闭维护模式**：取消勾选并保存，下一轮检测（≤ 60s）立即恢复监控。维护期间错过的告警**不补发**；若关闭时设备仍处于故障状态，下一轮正常触发告警。

> 注意：维护模式只能按设备逐台开启，系统不支持全局默认维护模式。

---

## 6. 状态机

```
触发 → ACTIVE → (用户 ACK) → ACKED → (5min + 数据恢复) → RESOLVED (AUTO)
            └──────── 手动恢复 ────────────────────────────→ RESOLVED (MANUAL)
ACTIVE → 手动恢复 → RESOLVED (MANUAL)
```

状态说明：

| 状态 | UI 表现 | 铃铛角标 | Webhook | 站内通知 |
|------|---------|---------|---------|---------|
| ACTIVE | 红色 Tag | +1 计数 | 触发（TRIGGERED） | 推送 TRIGGERED |
| ACKED | 黄色 Tag | 不计入 | 无 | 无 |
| RESOLVED | 绿色 Tag | 不计入 | 无 | 推送 RESOLVED（AUTO 路径） |

关键规则：

- `alarms` 表只由 AlarmDetector + AlarmStateMachine 写入
- 同设备同类型的 ACTIVE/ACKED 状态下**不会创建新告警**（去重）
- AUTO 恢复条件：数据恢复正常 + 距 `triggered_at` 超过 `suppressionWindowSeconds`（默认 5min）
- RESOLVED 后 5min 内不会再次触发同类型告警（抑制窗口防抖）

详细状态图参考 alarm-business-rules.md §1。

---

## 7. 检测口径

**两种告警类型（OR 关系，任一命中即触发；同轮 SILENT_TIMEOUT 优先）**：

| 类型 | 触发公式 |
|------|---------|
| SILENT_TIMEOUT | `lastReadAt != null` AND `(now - lastReadAt) > silentTimeoutSeconds` |
| CONSECUTIVE_FAIL | `consecutiveErrors >= consecutiveFailCount` |

**不触发场景**：

| 场景 | 原因 |
|------|------|
| 设备 `lastReadAt` 为 NULL（从未上报） | 无基准时间点，不触发 SILENT_TIMEOUT；CONSECUTIVE_FAIL 仍可触发 |
| `maintenance_mode = true` | 完全跳过该设备 |
| 同类型已有 ACTIVE/ACKED 告警 | 去重，不重复创建 |
| 刚 RESOLVED（< 抑制窗口内） | 防抖，等待窗口结束后下一轮才允许新 ACTIVE |
| 设备未在 collector 配置中 | alarm 模块完全不知道该设备 |

验证 collector 当前监控范围：

```bash
curl http://localhost:8080/api/v1/collector/status | jq '.devices[].meterCode'
```

**collector 重启影响**：CONSECUTIVE_FAIL 内存计数清零，需重新积累 N 个失败周期（最长 N × poll-interval 秒延迟）；SILENT_TIMEOUT 依赖数据库时间戳，不受影响。

详细规则参考 alarm-detection-rules.md。

---

## 8. 健康总览解读

- **在线设备**：activeAlarmCount = 0 且 collector 报告最近一轮成功
- **离线设备**：在 collector 中但最近一轮失败（不必触发 alarm，取决于阈值）
- **告警中**：当前 `alarms` 表状态为 ACTIVE + ACKED 的设备数
- **维护中**：`alarm_rule_overrides.maintenance_mode = true` 的设备数
- **Top10**：ACTIVE + ACKED 数倒序，前 10 条（未解决告警最多的设备）

**预警信号**（建议人工介入）：

- 在线率 < 95%
- 告警中设备数 > 总设备数的 5%
- Top10 单设备 ≥ 5 条活跃告警

快速查询当前活跃告警数：

```sql
SELECT COUNT(*) FROM alarms WHERE status IN ('ACTIVE', 'ACKED');
```

---

## 9. 备份 / 恢复

**写入量较大的表**：alarms / alarm_inbox / webhook_delivery_log

使用 EMS 通用 PostgreSQL 备份策略（参考 ../ops/runbook-2.0.md）。

**紧急回滚**（重新计算所有告警）：

```sql
-- detector 是无状态的，清空后重启即可重新计算
TRUNCATE TABLE alarms RESTART IDENTITY CASCADE;
-- 若不希望重启后对用户重发历史站内通知，先 truncate alarm_inbox
TRUNCATE TABLE alarm_inbox;
```

然后重启 ems-app，detector 从下一个周期（≤ 60s）开始重新计算所有设备状态。

> 注意：`TRUNCATE alarms ... CASCADE` 会级联删除 alarm_inbox 和 webhook_delivery_log 中关联记录。若需保留 delivery_log 历史，去掉 CASCADE 并手动处理外键。

**Flyway 迁移失败**：若 V2.2.0__init_alarm.sql 半提交，执行 `flyway repair` 后重启 ems-app。

---

## 10. 性能与容量

- 单实例 1000 设备 / 60s 一轮：CPU < 5%，单轮扫描 < 5s（基于 spec §16，未正式压测）
- 单设备处理 < 5ms（DB 查询均走索引）
- 设备处理异常被 catch 隔离，不影响其他设备扫描

**delivery_log 长期写入**：建议每月归档；50K 行以下查询无压力。

**alarm_inbox**：每用户每告警 1 行（ADMIN + OPERATOR），近 1 月单用户预期 < 1000 行。

检测周期调整参考：

| 设备规模 | 推荐 poll-interval | DB 压力 |
|----------|--------------------|--------|
| < 100 台 | 30–60s | 极低 |
| 100–1000 台 | 60s（默认） | 低 |
| > 1000 台 | 120–300s | 中（建议同时优化 alarms 表索引） |

---

## 11. 多实例 / 高可用

当前版本**单实例运行**；多实例部署时每个实例独立执行检测，会重复创建告警（无分布式锁）。

**多实例方案**：引入 ShedLock，在 shedlock 表加行锁，确保每轮仅一个实例执行 detector。

当前生产建议：**单实例运行** alarm 检测模块。详见 spec §16 部署要求。

---

## 12. 已知限制 / 路线图

| 限制 | 影响 | 计划版本 |
|------|------|---------|
| 监控范围仅限 collector 配置的设备 | 未在 collector 配置的设备无法告警 | v1.7+ |
| 重试队列在内存 | 进程崩溃丢失待重试任务（delivery_log 保留记录，可手动重放） | v1.7+ |
| 单实例运行（多实例 = 重复扫） | 水平扩展受限 | v1.7+（ShedLock） |
| 钉钉/企微/飞书原生 Adapter 未内置 | 需自建适配层转发 | v1.7+ 路线图 |
| 批量确认告警未支持 | 告警量大时需逐条操作 | v1.7+ 路线图 |

---

## 13. 故障决策树

| 现象 | 第一步排查 | 第二步 |
|------|-----------|--------|
| 用户没收到告警 | 角色检查（ADMIN/OPERATOR）+ 维护模式开关 + collector 配置是否含该设备 | 阈值是否过宽（silentTimeout / consecutiveFailCount）；查抑制窗口 |
| Webhook 一直失败 | UI → 下发流水 → `lastError` + `attempts` 字段 | 接收方日志 + 签名校验（Secret 是否一致）+ URL 可达性 |
| 告警一直 ACTIVE 不恢复 | 确认设备数据是否真实恢复（看 `meter_reading.ts` 或 collector status） | 是否在 5min 抑制窗内；可手动 ACK 后等待 AUTO 恢复 |
| 告警重复触发 / 频繁抖动 | 查抑制窗口配置（默认 300s）；查 alarms 历史行时间间隔 | 调大 `consecutive_fail_count` 或 `suppression-window-seconds` |
| 调度卡住 / 不扫 | `grep "AlarmDetector" /var/log/ems-app/ems-app.log \| tail -20` | JVM 线程 dump（`kill -3 <pid>`）；重启 ems-app |
| collector 重启后 CONSECUTIVE_FAIL 延迟 | 内存计数归零属正常；等待 N × poll-interval 秒重新积累 | 确认 collector 正常启动；SILENT_TIMEOUT 不受此影响 |
| 维护模式期间仍收到告警 | 确认告警 `triggered_at` 是否在维护模式生效前（历史遗留） | 查 `alarm_rules_override` 是否有记录且 `maintenance_mode=true` |

---

## 14. 故障联系 / 升级

- **L1 自助**：按本 Runbook §13 决策树排查
- **L2 模块 owner**：参考 spec §11.4 联系人列表；提供告警 ID、设备 meterCode、`webhook_delivery_log` 相关行截图
- **L3 紧急操作**：
  1. 备份：`pg_dump -t alarms -t alarm_inbox ems > /tmp/alarm_backup_$(date +%Y%m%d).sql`
  2. 重启 ems-app（detector 无状态，重启后重新计算）
  3. 观察下一个检测周期（≤ 60s），确认日志正常输出
