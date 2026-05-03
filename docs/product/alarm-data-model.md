# 采集中断报警 · 数据模型说明

> **更新于**：2026-04-29（Phase B 完成时）
> **撰写依据**：spec §2 数据模型 + V2.2.0 migration

---

## 1. 表关系总览图

报警系统共涉及 5 张表，以及与既有业务表 `meters`（设备）和 `users`（用户）的软关联。

```
meters (既有表)
  │
  ├──[device_id, 软关联]──► alarms ──[alarm_id, 软关联]──► webhook_delivery_log
  │                            │
  │                            └──[alarm_id, 软关联]──► alarm_inbox ◄──[user_id, 软关联]── users (既有表)
  │
  └──[device_id, 软关联]──► alarm_rules_override

webhook_config (系统级单行表，无关联约束)
```

### 关联说明

| 关联 | 类型 | 说明 |
|------|------|------|
| `alarms.device_id` → `meters.id` | 软关联，无 FK | 报警关联触发它的设备 |
| `alarm_rules_override.device_id` → `meters.id` | 软关联，无 FK（同为 PK）| 设备级阈值覆盖，一对一关系 |
| `webhook_delivery_log.alarm_id` → `alarms.id` | 软关联，无 FK | 记录每次报警的 Webhook 派发结果 |
| `alarm_inbox.alarm_id` → `alarms.id` | 软关联，无 FK | 站内通知关联到触发它的报警 |
| `alarm_inbox.user_id` → `users.id` | 软关联，无 FK | 站内通知归属到具体用户 |

**为什么不用外键约束？** 报警模块（`ems-alarm`）与设备模块（`ems-meter`）、用户模块（`ems-user`）属于不同有界上下文。数据库层 FK 会让模块之间强耦合，带来跨模块事务、迁移顺序依赖、测试隔离困难等问题。软关联由应用层保证引用完整性，是微服务和模块化单体架构里的常见取舍。

---

## 2. `alarms` — 报警事件主表

每一行是一次完整的报警事件，从设备触发开始，到被确认或自动恢复结束。历史记录不重写，状态流转通过更新本表字段实现。

### 字段说明

| 字段名 | 类型 | 可空 | 默认值 | 业务含义 |
|--------|------|------|--------|---------|
| `id` | BIGSERIAL | 否 | 自动递增 | 报警唯一标识符，在整个系统中不重复，用于关联派发日志、站内通知等下游记录 |
| `device_id` | BIGINT | 否 | — | 触发本次报警的设备 ID，软关联 `meters.id`；结合 `device_type` 确定设备身份 |
| `device_type` | VARCHAR(32) | 否 | — | 触发报警的设备类型，首版取值：`METER`（电表）、`COLLECTOR`（采集器） |
| `alarm_type` | VARCHAR(32) | 否 | — | 报警触发原因类型：`SILENT_TIMEOUT`（设备长时间无数据上报）、`CONSECUTIVE_FAIL`（采集器连续失败达阈值） |
| `severity` | VARCHAR(16) | 否 | `'WARNING'` | 报警级别，首版固定为 `WARNING`；预留字段，Phase E 可扩展 `CRITICAL` / `INFO` |
| `status` | VARCHAR(16) | 否 | — | 报警当前状态，三值状态机：`ACTIVE`（未处理）→ `ACKED`（已确认）→ `RESOLVED`（已解决） |
| `triggered_at` | TIMESTAMPTZ | 否 | — | 报警首次触发的时刻，是整个报警生命周期的起点，用于 SLA 计算和报表统计 |
| `acked_at` | TIMESTAMPTZ | 是 | NULL | 运维人员确认报警的时刻；`NULL` 表示尚未确认；与 `triggered_at` 之差即"响应时延" |
| `acked_by` | BIGINT | 是 | NULL | 执行确认操作的用户 ID，软关联 `users.id`；用于审计"谁确认了这条报警" |
| `resolved_at` | TIMESTAMPTZ | 是 | NULL | 报警消除的时刻；`NULL` 表示尚未解决；与 `triggered_at` 之差即"故障持续时长" |
| `resolved_reason` | VARCHAR(32) | 是 | NULL | 报警消除方式：`AUTO`（检测引擎自动判定设备恢复）、`MANUAL`（运维人员手动关闭） |
| `last_seen_at` | TIMESTAMPTZ | 是 | NULL | 最近一次检测引擎确认该设备仍处于异常状态的时刻；用于区分"已彻底失联"与"偶发断联"，也是 AUTO 恢复的判断依据 |
| `detail` | JSONB | 是 | NULL | 触发时的上下文快照，内容因 `alarm_type` 而异（见下文）；用于排障时还原现场，不参与查询过滤 |
| `created_at` | TIMESTAMPTZ | 否 | `NOW()` | 数据库写入时刻，通常与 `triggered_at` 极为接近，用于内部审计和排查消息延迟问题 |

**`detail` JSONB 字段示例：**

```json
// alarm_type = SILENT_TIMEOUT
{
  "lastReadAt": "2026-04-29T10:00:00Z",
  "silentSeconds": 720,
  "threshold": 600
}

// alarm_type = CONSECUTIVE_FAIL
{
  "consecutiveCycleErrors": 3,
  "threshold": 3,
  "lastError": "Connection timed out"
}
```

### 三个时刻点的关系

```
triggered_at ──► acked_at ──► resolved_at
     │               │              │
  报警产生        运维确认        故障消除
  (必填)         (可选)          (可选)

响应时延 = acked_at - triggered_at
故障时长 = resolved_at - triggered_at
```

状态 `ACTIVE` 时：`acked_at`、`resolved_at` 均为 NULL。  
状态 `ACKED` 时：`acked_at` 已填，`resolved_at` 仍为 NULL。  
状态 `RESOLVED` 时：`resolved_at` 已填，`acked_at` 可能为 NULL（直接 AUTO 解决跳过确认步骤）。

### 索引说明

| 索引名 | 字段 | 服务的查询场景 |
|--------|------|--------------|
| `idx_alarms_device_status` | `(device_id, status)` | 查询某台设备的所有 ACTIVE 报警（最高频场景：监控大屏、设备详情页） |
| `idx_alarms_status_trig` | `(status, triggered_at DESC)` | 按状态+时间倒序分页列出报警（报警列表页主查询） |
| `idx_alarms_triggered_at` | `(triggered_at DESC)` | 不过滤状态、仅按时间范围查报警（月报统计、历史回溯） |

---

## 3. `alarm_rules_override` — 设备级阈值覆盖

对单台设备设置独立的检测阈值或开启维护模式，覆盖全局默认值，修改后立即生效（不用重启）。未设置覆盖的字段沿用 `application.yml` 中的全局默认值。

### 字段说明

| 字段名 | 类型 | 可空 | 默认值 | 业务含义 |
|--------|------|------|--------|---------|
| `device_id` | BIGINT | 否 | — | 设备唯一标识，同时作为本表的主键（PK）；与 `meters.id` 一对一对应，一台设备最多一条覆盖记录 |
| `silent_timeout_seconds` | INT | 是 | NULL | 该设备的静默超时阈值（秒）；`NULL` 表示沿用全局 `default-silent-timeout-seconds` 值 |
| `consecutive_fail_count` | INT | 是 | NULL | 该设备的连续采集失败阈值（次数）；`NULL` 表示沿用全局 `default-consecutive-fail-count` 值 |
| `maintenance_mode` | BOOLEAN | 否 | `FALSE` | 维护模式开关；`TRUE` 时检测引擎跳过该设备所有检测，不产生任何报警，适用于计划停机、设备检修期间 |
| `maintenance_note` | VARCHAR(255) | 是 | NULL | 维护原因或说明（如"更换电表 CT 互感器"），供值班人员交接时参考，不参与业务逻辑 |
| `updated_at` | TIMESTAMPTZ | 否 | — | 本条覆盖规则的最后修改时刻，用于审计和判断规则是否过期 |
| `updated_by` | BIGINT | 是 | NULL | 最后修改此覆盖规则的用户 ID，软关联 `users.id`，用于操作审计 |

### 业务规则

- 一对一关系：`device_id` 直接作为 PK，从数据库层保证每台设备最多一条覆盖规则，不用额外加唯一约束。
- NULL 即继承：`silent_timeout_seconds` 和 `consecutive_fail_count` 为 NULL 时，检测引擎用全局默认值，应用层不用做特殊处理。
- 维护模式优先于阈值：当 `maintenance_mode = TRUE` 时，即使阈值字段有值，检测引擎也跳过该设备，不触发报警。
- 无覆盖记录等同全默认：若某设备没有覆盖行，效果与所有字段均为 NULL 完全一致。

---

## 4. `webhook_config` — Webhook 配置

系统级 Webhook 配置，首版是单行表（只有一条记录，通过 `SELECT … LIMIT 1` 读取）。用于把报警事件推送到外部系统（钉钉、企业微信、自定义 HTTP 接收端等）。

### 字段说明

| 字段名 | 类型 | 可空 | 默认值 | 业务含义 |
|--------|------|------|--------|---------|
| `id` | BIGSERIAL | 否 | 自动递增 | 配置记录 ID，首版永远为 1 |
| `enabled` | BOOLEAN | 否 | `FALSE` | Webhook 总开关；`FALSE` 时系统不发送任何 Webhook，即使报警产生也不触发推送 |
| `url` | VARCHAR(512) | 否 | — | Webhook 推送目标 URL，由外部系统（如钉钉机器人、企业微信群机器人）提供 |
| `secret` | VARCHAR(255) | 是 | NULL | **敏感字段**：HMAC-SHA256 签名密钥，用于在 HTTP 请求头中附加签名以验证来源合法性。**此字段不在任何 API 响应中返回明文**，查询接口仅返回 `hasSecret: true/false` 布尔标志 |
| `adapter_type` | VARCHAR(32) | 否 | `'GENERIC_JSON'` | 推送载荷格式适配器：`GENERIC_JSON`（首版唯一支持，通用 JSON 格式）；`DINGTALK`、`WECHAT_WORK` 在 Phase E 扩展 |
| `timeout_ms` | INT | 否 | `5000` | 单次 HTTP 请求超时（毫秒）；若此字段有值则优先于全局 `webhook-timeout-default-ms` 配置；有效范围建议 1000–30000 |
| `updated_at` | TIMESTAMPTZ | 否 | — | 配置最后修改时刻 |
| `updated_by` | BIGINT | 是 | NULL | 最后修改配置的用户 ID，软关联 `users.id` |

### secret 字段安全说明

`secret` 字段存储 Webhook HMAC-SHA256 签名密钥，属于敏感凭据，安全规则：

1. 写入：通过 `PUT /api/v1/webhook-config` 设置，明文传输仅在 TLS 保护下进行。
2. 读取：`GET /api/v1/webhook-config` 响应中 `secret` 字段始终替换为 `"hasSecret": true/false`，不返回密钥原文。
3. 存储：数据库中以明文存储（供应用层签名使用），依赖数据库访问控制保护。
4. 清除：发送空字符串或 `null` 可清除密钥，此后 Webhook 请求不携带签名头。

### timeout_ms 与全局配置的关系

| 场景 | 生效超时值 |
|------|----------|
| `timeout_ms` 已设置（非 NULL） | 使用 `webhook_config.timeout_ms` |
| `timeout_ms` 为 NULL | 回落到 `application.yml` 中的 `webhook-timeout-default-ms`（默认 5000ms） |

---

## 5. `webhook_delivery_log` — 派发流水

记录每一次 Webhook 完整推送过程（含所有重试）的结果，用于运维监控和排障。一次报警对应一行派发日志，`attempts` 字段是含所有重试在内的总尝试次数。

### 字段说明

| 字段名 | 类型 | 可空 | 默认值 | 业务含义 |
|--------|------|------|--------|---------|
| `id` | BIGSERIAL | 否 | 自动递增 | 派发流水唯一 ID |
| `alarm_id` | BIGINT | 否 | — | 本次派发对应的报警 ID，软关联 `alarms.id`；可通过此字段反查是哪条报警触发了这次推送 |
| `attempts` | INT | 否 | — | 本次推送总尝试次数（含首次 + 所有重试）；例如重试 3 次全失败时值为 4 |
| `status` | VARCHAR(16) | 否 | — | 派发最终状态：`SUCCESS`（至少一次请求成功）、`FAILED`（所有尝试均失败） |
| `last_error` | VARCHAR(512) | 是 | NULL | 最后一次失败的错误摘要（如 `Connection refused`、`HTTP 500`），用于快速定位推送失败原因 |
| `response_status` | INT | 是 | NULL | 最后一次 HTTP 响应的状态码（如 `200`、`500`、`408`）；网络层失败时为 NULL |
| `response_ms` | INT | 是 | NULL | 最后一次 HTTP 请求的耗时（毫秒）；超时或网络错误时为 NULL |
| `created_at` | TIMESTAMPTZ | 否 | `NOW()` | 派发流水写入时刻，等于推送流程结束时刻，用于按时间查询推送历史 |

### 业务规则

- 每次完整推送写一行：无论是否重试，一条报警对应一行派发日志；重试过程由应用层管理，本表不体现中间状态。
- `attempts` 含首次：若首次即成功，`attempts = 1`；若重试 2 次后成功，`attempts = 3`。
- `status = FAILED` 含义：所有重试已耗尽，没有一次请求成功。此时 `last_error` 和 `response_status` 反映最后一次尝试的情况。
- 派发失败不影响报警状态：`webhook_delivery_log` 与 `alarms` 解耦，Webhook 推送失败不会回滚报警或标记为异常，报警状态机独立运行。

### 索引说明

| 索引名 | 字段 | 服务的查询场景 |
|--------|------|--------------|
| `idx_wdl_alarm` | `(alarm_id)` | 通过报警 ID 快速查找对应的派发记录（报警详情页展示推送状态） |
| `idx_wdl_status` | `(status, created_at DESC)` | 按状态+时间倒序查失败记录（运维监控：最近 24 小时失败统计） |

---

## 6. `alarm_inbox` — 站内通知收件箱

为平台内每位 ADMIN / OPERATOR 用户生成站内消息，支持"红点徽标"未读提示。每当报警发生关键状态变化（触发或解决），系统会给所有订阅用户各写入一条收件箱消息。

### 字段说明

| 字段名 | 类型 | 可空 | 默认值 | 业务含义 |
|--------|------|------|--------|---------|
| `id` | BIGSERIAL | 否 | 自动递增 | 收件箱消息唯一 ID |
| `alarm_id` | BIGINT | 否 | — | 本条通知关联的报警 ID，软关联 `alarms.id`；用户点击通知时通过此字段跳转到报警详情 |
| `user_id` | BIGINT | 否 | — | 通知收件人用户 ID，软关联 `users.id`；每个用户各收一条，不共享消息记录 |
| `kind` | VARCHAR(16) | 否 | — | 通知类型：`TRIGGERED`（报警产生）、`RESOLVED`（报警解决）；决定前端显示的图标和文案 |
| `read_at` | TIMESTAMPTZ | 是 | NULL | 用户阅读此通知的时刻；`NULL` 表示未读；首次点击即写入当前时间，不可重置为未读 |
| `created_at` | TIMESTAMPTZ | 否 | `NOW()` | 通知写入时刻，与触发/解决事件时刻几乎同步，用于排序和超期清理 |

### 业务规则

- 写入时机：每条报警在 `ACTIVE`（TRIGGERED）和 `RESOLVED` 时各写一轮，每个 ADMIN/OPERATOR 用户收到一条对应类型的消息；同一报警最多产生 `用户数 × 2` 条收件箱记录。
- 未读判断：`read_at IS NULL` 即为未读，已读后不可撤回。
- 红点徽标查询（前端实时轮询）：

```sql
SELECT COUNT(*) FROM alarm_inbox WHERE user_id = :userId AND read_at IS NULL;
```

- 索引：`idx_inbox_user_unread` 覆盖 `(user_id, read_at)`，保证上述未读计数查询毫秒级返回，不随收件箱总量增长而变慢。

---

## 7. 数据生命周期

首版所有表都采用永久保留策略，不做自动清理或归档。出于审计合规和报警历史回溯的需要，这是合理的初始选择。商业化部署时建议参考下表"商用归档建议"列制定归档方案。

| 表名 | 首版保留策略 | 商用归档建议 | 清理脚本 |
|------|------------|------------|---------|
| `alarms` | 永久保留 | 历史报警具有审计与合规价值，建议保留 ≥ 2 年；2 年以上可归档至冷存储或数据仓库 | 暂无 |
| `alarm_inbox` | 永久保留 | 已读消息超过 90 天意义有限，可定期清理以控制表体积 | 暂无 |
| `webhook_delivery_log` | 永久保留 | 仅用于短期排障，建议保留 90 天滚动窗口，超期自动删除 | 暂无 |
| `webhook_config` | 永久（单行） | 永久保留，仅有一条记录 | 暂无 |
| `alarm_rules_override` | 永久保留 | 永久保留，记录每台设备的阈值调优历史 | 暂无 |

首版没有内置归档机制，相关需求已进运维 backlog，计划在 v1.7+ 引入定时任务或 pg_partman 分区归档方案。

---

## 8. 数据查询常见场景

以下 SQL 示例均为 PostgreSQL 语法，外部参数使用 `:paramName` 占位符标注。

### 场景 1：当前所有 ACTIVE 报警按设备分组

监控大屏展示各设备的未处理报警数量，快速找出问题集中的设备。

```sql
SELECT
    device_id,
    device_type,
    COUNT(*) AS active_count,
    MIN(triggered_at) AS earliest_triggered
FROM alarms
WHERE status = 'ACTIVE'
GROUP BY device_id, device_type
ORDER BY active_count DESC, earliest_triggered ASC;
```

### 场景 2：最近 7 天报警次数 Top 10 设备

找出高频故障设备，帮助运维团队排查系统性问题（网络不稳定、设备老化等）。

```sql
SELECT
    device_id,
    device_type,
    COUNT(*) AS alarm_count
FROM alarms
WHERE triggered_at >= NOW() - INTERVAL '7 days'
GROUP BY device_id, device_type
ORDER BY alarm_count DESC
LIMIT 10;
```

### 场景 3：某设备最近一次报警的完整生命周期

排障时查看指定设备最新报警从触发到解决的全部字段，包括触发上下文快照。

```sql
SELECT
    id,
    alarm_type,
    severity,
    status,
    triggered_at,
    acked_at,
    acked_by,
    resolved_at,
    resolved_reason,
    last_seen_at,
    detail,
    (resolved_at - triggered_at) AS duration
FROM alarms
WHERE device_id = :deviceId
ORDER BY triggered_at DESC
LIMIT 1;
```

### 场景 4：24 小时内 Webhook 失败统计

运维监控看板用，看 Webhook 推送的健康状况；`attempts` 用来评估重试压力。

```sql
SELECT
    COUNT(*) FILTER (WHERE status = 'FAILED') AS failed_count,
    COUNT(*) FILTER (WHERE status = 'SUCCESS') AS success_count,
    ROUND(
        100.0 * COUNT(*) FILTER (WHERE status = 'FAILED') / NULLIF(COUNT(*), 0),
        2
    ) AS failure_rate_pct,
    AVG(attempts) FILTER (WHERE status = 'FAILED') AS avg_attempts_on_failure
FROM webhook_delivery_log
WHERE created_at >= NOW() - INTERVAL '24 hours';
```

### 场景 5：某用户未读报警数（红点徽标）

前端页面轮询此查询以决定是否展示未读红点，要求毫秒级响应。

```sql
SELECT COUNT(*) AS unread_count
FROM alarm_inbox
WHERE user_id = :userId
  AND read_at IS NULL;
```

### 场景 6：当前处于维护模式的设备列表

值班交接时确认哪些设备正在维护中，避免把"设备正常、无报警"误判成故障。

```sql
SELECT
    device_id,
    maintenance_note,
    updated_at,
    updated_by
FROM alarm_rules_override
WHERE maintenance_mode = TRUE
ORDER BY updated_at DESC;
```

### 场景 7：某月每日报警量趋势

生成月度报告时统计每天新增报警数量，看系统稳定性走势。

```sql
SELECT
    DATE_TRUNC('day', triggered_at AT TIME ZONE 'Asia/Shanghai') AS day,
    COUNT(*) AS alarm_count
FROM alarms
WHERE triggered_at >= :monthStart    -- 示例: '2026-04-01 00:00:00+08'
  AND triggered_at <  :monthEnd      -- 示例: '2026-05-01 00:00:00+08'
GROUP BY day
ORDER BY day ASC;
```

---

## 更多资源

- **完整设计规格**：[2026-04-29-acquisition-alarm-design.md](../superpowers/specs/2026-04-29-acquisition-alarm-design.md)
- **业务规则详解**：[alarm-business-rules.md](./alarm-business-rules.md)
- **配置参数参考**：[alarm-config-reference.md](./alarm-config-reference.md)
