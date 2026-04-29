# 采集中断告警（Acquisition Interruption Alarm）— 设计规格

- **作者**：factory-ems team
- **日期**：2026-04-29
- **状态**：Draft（待 writing-plans 阶段）
- **范围**：新增 `ems-alarm` Maven 模块；扩展 `ems-collector`、`ems-app`、`frontend`

---

## 0. 背景与目标

`factory-ems` 商业化前的最低门槛功能：当采集设备（电表 / 采集器）数据中断时，系统能够**主动检测、通知、留痕、恢复**，避免客户因数据缺失而对系统失去信任。

**核心需求确认（来自 brainstorming 11 轮问答）**

| # | 维度 | 决议 |
|---|------|------|
| 1 | 检测口径 | 静默超时 OR 连续采集失败 |
| 2 | 粒度 | 单设备 |
| 3 | 通知通道 | 站内通知 + Webhook（首版单端点） |
| 4 | 生命周期 | 触发 → 确认 → 自动恢复 + 历史留痕 |
| 5 | 检测频率 | 分钟级（默认 60s） |
| 6 | 阈值 | 全局默认 + 设备级覆盖 |
| 7 | 维护抑制 | 手动开关 |
| 8 | 通知聚合 | 单一 webhook + 站内全员（ADMIN/OPERATOR） |
| 9 | Webhook 协议 | 通用 JSON + Adapter 扩展点 |
| 10 | UI 入口 | 铃铛 + 通知中心 + 历史页 + 设备状态 + 仪表盘卡 + 健康总览 |
| 11 | 默认值 | 抑制窗口 5min / 永久保留 / 3 次重试指数退避 / 固定模板 / 单一严重程度 / 1min 轮询 |

---

## 1. 架构总览

```
┌─────────────────┐                    ┌──────────────────────┐
│  ems-collector  │ —— 写读数 ————>   │  Postgres            │
│  (失败计数内存) │                    │  meter_reading       │
└─────────────────┘                    │                      │
        ▲                              │  alarms              │
        │ getFailCount()               │  alarm_rules_override│
        │                              │  webhook_config      │
┌─────────────────┐                    │  webhook_delivery_log│
│  ems-alarm      │ —— 读 last_seen —> │                      │
│  @Scheduled     │                    └──────────────────────┘
│  scan()         │
│                 │ ——> InAppChannel ——> alarm_inbox
│                 │ ——> WebhookChannel ——> [外部 HTTP 端点]
└─────────────────┘                                  ▲
                                                     │ 重试 3 次
                                                     │ [10s,60s,300s]
```

### 1.1 模块职责

| 模块 | 职责 |
|------|------|
| `ems-alarm`（新） | 检测调度、状态机、阈值解析、派发器、API、Repo |
| `ems-collector`（扩） | 暴露 `CollectorHealthService.getFailCount(deviceId)` |
| `ems-app`（集成） | 装配 `ems-alarm`，加入主程序 |
| `frontend`（扩） | 铃铛、通知中心、健康总览、历史页、规则页、Webhook 页、设备状态列 |

### 1.2 数据流（一次完整生命周期）

1. `@Scheduled` 每 60s 触发 `AlarmDetector.scan()`
2. 对每个启用设备解析阈值 → 检查静默超时 + 连续失败
3. 命中且无活动告警 → 写入 `alarms`，调用 `AlarmDispatcher`
4. Dispatcher 同步写 `alarm_inbox`，异步触发 webhook（带 HMAC 签名 + 重试）
5. 下一轮检测发现条件不再满足 → 自动 RESOLVED，站内补一条"已恢复"

---

## 2. 数据模型

### 2.1 新增表

#### `alarms` — 告警事件主表

```sql
CREATE TABLE alarms (
    id              BIGSERIAL PRIMARY KEY,
    device_id       BIGINT NOT NULL,
    device_type     VARCHAR(32) NOT NULL,        -- METER / COLLECTOR
    alarm_type      VARCHAR(32) NOT NULL,        -- SILENT_TIMEOUT / CONSECUTIVE_FAIL
    severity        VARCHAR(16) NOT NULL DEFAULT 'WARNING',
    status          VARCHAR(16) NOT NULL,        -- ACTIVE / ACKED / RESOLVED
    triggered_at    TIMESTAMPTZ NOT NULL,
    acked_at        TIMESTAMPTZ,
    acked_by        BIGINT,
    resolved_at     TIMESTAMPTZ,
    resolved_reason VARCHAR(32),                 -- AUTO / MANUAL
    last_seen_at    TIMESTAMPTZ,
    detail          JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_alarms_device_status   ON alarms (device_id, status);
CREATE INDEX idx_alarms_status_trig     ON alarms (status, triggered_at DESC);
CREATE INDEX idx_alarms_triggered_at    ON alarms (triggered_at DESC);
```

#### `alarm_rules_override` — 设备级阈值覆盖

```sql
CREATE TABLE alarm_rules_override (
    device_id              BIGINT PRIMARY KEY,
    silent_timeout_seconds INT,
    consecutive_fail_count INT,
    maintenance_mode       BOOLEAN NOT NULL DEFAULT FALSE,
    maintenance_note       VARCHAR(255),
    updated_at             TIMESTAMPTZ NOT NULL,
    updated_by             BIGINT
);
```

#### `webhook_config` — 单端点配置

```sql
CREATE TABLE webhook_config (
    id            BIGSERIAL PRIMARY KEY,
    enabled       BOOLEAN NOT NULL DEFAULT FALSE,
    url           VARCHAR(512) NOT NULL,
    secret        VARCHAR(255),
    adapter_type  VARCHAR(32) NOT NULL DEFAULT 'GENERIC_JSON',
    timeout_ms    INT NOT NULL DEFAULT 5000,
    updated_at    TIMESTAMPTZ NOT NULL,
    updated_by    BIGINT
);
```

#### `webhook_delivery_log` — 下发流水

```sql
CREATE TABLE webhook_delivery_log (
    id          BIGSERIAL PRIMARY KEY,
    alarm_id    BIGINT NOT NULL,
    attempts    INT NOT NULL,
    status      VARCHAR(16) NOT NULL,           -- SUCCESS / FAILED
    last_error  VARCHAR(512),
    response_status INT,
    response_ms INT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_wdl_alarm  ON webhook_delivery_log (alarm_id);
CREATE INDEX idx_wdl_status ON webhook_delivery_log (status, created_at DESC);
```

#### `alarm_inbox` — 站内通知收件箱

```sql
CREATE TABLE alarm_inbox (
    id        BIGSERIAL PRIMARY KEY,
    alarm_id  BIGINT NOT NULL,
    user_id   BIGINT NOT NULL,
    kind      VARCHAR(16) NOT NULL,             -- TRIGGERED / RESOLVED
    read_at   TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_inbox_user_unread ON alarm_inbox (user_id, read_at);
```

### 2.2 全局默认（`application.yml`）

```yaml
ems.alarm:
  default-silent-timeout-seconds: 600
  default-consecutive-fail-count: 3
  poll-interval-seconds: 60
  suppression-window-seconds: 300
  webhook-retry-max: 3
  webhook-retry-backoff-seconds: [10, 60, 300]
  webhook-timeout-default-ms: 5000
```

### 2.3 设计取舍

- 不分活动 / 历史两表，用 `status` 字段切；索引覆盖主查询路径
- `alarm_rules_override.device_id` 直接做 PK（一对一）；不跨模块加 FK
- `webhook_config` 单行（`SELECT … LIMIT 1`），首版不支持多端点
- `detail JSONB` 存阈值快照，便于审计回溯
- 不动 `ems-collector` 表 schema，避免侵入采集器

---

## 3. 检测逻辑

### 3.1 触发条件（OR）

**A. 静默超时（SILENT_TIMEOUT）**
- `last_seen = MAX(meter_reading.ts) WHERE device_id = X`
- 若 `NOW() - last_seen > silent_timeout_seconds` → 触发
- `last_seen IS NULL`（设备从未上报）→ **不触发**

**B. 连续失败（CONSECUTIVE_FAIL）**
- `ems-collector` 在每次采集失败时累加 per-device 内存计数；成功清零
- `getFailCount(deviceId) >= threshold` → 触发

### 3.2 调度

```java
@Scheduled(fixedDelayString = "${ems.alarm.poll-interval-seconds:60}000")
public void scan() { ... }
```
- 单实例运行（首版不引入 ShedLock；商业化阶段再加）
- 一轮内串行扫描，单设备耗时 < 5ms

### 3.3 状态机

```
[无告警] —(命中)—> ACTIVE —(用户确认)—> ACKED
                     |                       |
                     +——(数据恢复+过抑制窗)—> RESOLVED (AUTO)
```

- **触发去重**：同 `(device_id, alarm_type)` 处于 ACTIVE/ACKED 不再创建新行，仅更新 `detail`
- **抑制窗口**：从 RESOLVED 恢复后 5 分钟内不再触发同类告警（防抖）
- **自动恢复**：每轮反查 ACTIVE/ACKED 行，若条件不满足且距 `triggered_at > 抑制窗口` → RESOLVED + `AUTO`
- **维护模式**：`maintenance_mode=true` 整段跳过

### 3.4 伪代码

```java
public void scan() {
    for (Device d : enabledDevices()) {
        try {
            scanOne(d);
        } catch (Exception e) {
            log.warn("Scan failed for device {}: {}", d.id(), e.getMessage(), e);
        }
    }
}

private void scanOne(Device d) {
    Override ov = overrideRepo.find(d.id()).orElse(Override.EMPTY);
    if (ov.maintenanceMode()) return;
    Threshold t = thresholds.resolve(d, ov);

    boolean silentHit = checkSilent(d, t.silentTimeout());
    boolean failHit   = checkConsecutiveFail(d, t.failCount());

    Optional<Alarm> active = alarmRepo.findActive(d.id());
    if ((silentHit || failHit) && active.isEmpty()) {
        Alarm a = create(d, pickType(silentHit, failHit), snapshot(t));
        dispatcher.dispatch(a);
    } else if (active.isPresent() && !silentHit && !failHit) {
        autoResolve(active.get());
    }
}
```

---

## 4. 通知派发

### 4.1 站内通知（InAppChannel）

- 同步写 `alarm_inbox`（每个 ADMIN/OPERATOR 用户一行）
- 前端通过 `/alarms/active/count` 30s 轮询刷新铃铛角标
- DB 操作不重试

### 4.2 Webhook（WebhookChannel）

#### Payload（GENERIC_JSON）

```json
{
  "event": "alarm.triggered",
  "alarm_id": 12345,
  "device_id": 88,
  "device_type": "METER",
  "device_code": "M-A01-001",
  "device_name": "一号车间总表",
  "alarm_type": "SILENT_TIMEOUT",
  "severity": "WARNING",
  "triggered_at": "2026-04-29T08:15:30+08:00",
  "last_seen_at": "2026-04-29T08:00:12+08:00",
  "detail": { "threshold_seconds": 600 }
}
```

#### Headers
- `Content-Type: application/json`
- `X-EMS-Event: alarm.triggered`
- `X-EMS-Signature: sha256=<HMAC(secret, body)>`

#### 异步执行
- `@Async` + 专用线程池（core=2 / max=4 / queue=100）
- 不阻塞主检测循环

#### 重试
- 最大 3 次，间隔 `[10s, 60s, 300s]`
- 触发：HTTP 非 2xx / 网络异常 / 超时（默认 5s）
- 实现：内存延迟队列（首版不引入持久化队列）
- 全失败：写 `webhook_delivery_log.status=FAILED`，ERROR 级日志

#### Adapter 扩展

```java
public interface WebhookAdapter {
    String getType();
    HttpRequest build(Alarm alarm, WebhookConfig cfg);
}
```
- 首版仅 `GenericJsonAdapter`
- 通过 `Map<String, WebhookAdapter>` 按 `webhook_config.adapter_type` 路由

### 4.3 派发触发矩阵

| 事件 | 站内 | Webhook |
|------|------|---------|
| 触发（CREATE） | ✅ | ✅ |
| 用户 ACK | ❌ | ❌ |
| 自动 / 手动恢复 | ✅ | ❌ |

### 4.4 关键解耦

**Webhook 失败不影响告警状态。** `alarms.status` 仅由检测逻辑驱动；下发结果仅在 `webhook_delivery_log` 留痕。

---

## 5. API 端点

所有路径前缀 `/api/v1`。沿用现有 JWT + 统一响应封装 + 1-indexed 分页。

### 5.1 告警

| Method | Path | 权限 | 说明 |
|--------|------|------|------|
| GET | `/alarms` | ADMIN/OPERATOR | 列表，filter: status/deviceId/alarmType/from/to + 分页 |
| GET | `/alarms/{id}` | ADMIN/OPERATOR | 详情 |
| POST | `/alarms/{id}/ack` | ADMIN | 确认 |
| POST | `/alarms/{id}/resolve` | ADMIN | 手动恢复 |
| GET | `/alarms/active/count` | ADMIN/OPERATOR | 角标计数 |
| GET | `/alarms/health-summary` | ADMIN/OPERATOR | 健康总览 |

### 5.2 阈值规则

| Method | Path | 权限 |
|--------|------|------|
| GET | `/alarm-rules/defaults` | ADMIN/OPERATOR |
| GET | `/alarm-rules/overrides` | ADMIN/OPERATOR |
| GET | `/alarm-rules/overrides/{deviceId}` | ADMIN/OPERATOR |
| PUT | `/alarm-rules/overrides/{deviceId}` | ADMIN |
| DELETE | `/alarm-rules/overrides/{deviceId}` | ADMIN |

> `defaults` 只读，修改需改 `application.yml` + 重启。

### 5.3 Webhook

| Method | Path | 权限 |
|--------|------|------|
| GET | `/webhook-config` | ADMIN（secret mask 为 `***`） |
| PUT | `/webhook-config` | ADMIN |
| POST | `/webhook-config/test` | ADMIN |
| GET | `/webhook-deliveries` | ADMIN |
| POST | `/webhook-deliveries/{id}/retry` | ADMIN |

> 重放仅重发原 payload，不刷新当前告警状态。

### 5.4 响应示例

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": 12345,
        "deviceId": 88,
        "deviceCode": "M-A01-001",
        "deviceName": "一号车间总表",
        "alarmType": "SILENT_TIMEOUT",
        "severity": "WARNING",
        "status": "ACTIVE",
        "triggeredAt": "2026-04-29T08:15:30+08:00",
        "lastSeenAt": "2026-04-29T08:00:12+08:00",
        "ackedAt": null
      }
    ],
    "total": 3,
    "page": 1,
    "size": 20
  }
}
```

---

## 6. 前端 UI

### 6.1 新增页面

| 文件 | 路由 | 说明 |
|------|------|------|
| `components/AlarmBell.tsx` | 全局 | 顶栏铃铛 + 角标 |
| `components/AlarmCenterDrawer.tsx` | 全局 | 通知中心抽屉 |
| `pages/alarms/health.tsx` | `/alarms/health` | 健康总览（指标卡 + Top10 + 24h 折线） |
| `pages/alarms/history.tsx` | `/alarms/history` | 告警历史，全字段筛选 |
| `pages/alarms/rules.tsx` | `/alarms/rules` | 默认值 + 设备覆盖（含维护开关） |
| `pages/alarms/webhook.tsx` | `/alarms/webhook` | webhook 配置 + 测试 + 下发日志 |

新增一级菜单 **系统健康**（聚合上述 4 个 alarm 页面）。

### 6.2 现有页面改动

| 文件 | 改动 |
|------|------|
| `pages/meter/list.tsx` | 增加状态列：在线 / 离线 / 告警 / 维护（Tag 颜色） |
| `pages/meter/detail.tsx` | 顶部状态徽章 + 最近 5 条告警时间线 |
| `pages/dashboard/index.tsx` | 新增"采集健康"卡片 |
| `layouts/AppLayout.tsx` | 顶栏 `<AlarmBell />` + 菜单分组 |

### 6.3 API 客户端

新建 `src/api/alarm.ts`，导出 `alarmApi` / `alarmRuleApi` / `webhookApi`，全部用 `useQuery` / `useMutation`，与现有 `floorplanApi` / `meterApi` 风格一致。

### 6.4 状态色板

```ts
const STATUS_COLOR = {
  ONLINE:      'success',
  OFFLINE:     'default',
  ALARM:       'error',
  MAINTENANCE: 'warning',
};
```

### 6.5 取舍

- **30s 轮询**代替 WebSocket / SSE（架构无 WebSocket 基建）
- **不做单告警弹窗**，铃铛 + 抽屉足够
- 健康页（现状） vs 历史页（明细）拆分

---

## 7. 错误处理 & 测试策略

### 7.1 错误处理

#### 检测循环
- 单设备异常 try-catch + log.warn + 跳过；下一轮重试
- DB 异常：log.error，整轮放弃
- **绝不抛异常出 `@Scheduled`**

#### Webhook
- 网络/超时/非 2xx → 重试队列
- 3 次失败 → `webhook_delivery_log.status=FAILED` + ERROR 日志
- `@Async` 内 catch 干净，不抛回主线程

#### API 异常
- `AlarmNotFoundException` → 404
- `AlarmStateException`（状态非法转换）→ 409
- `WebhookConfigInvalidException`（URL 不合法）→ 400
- 沿用 `GlobalExceptionHandler`

#### 配置校验
- `webhook_config.url`：合法 URL，scheme ∈ {http, https}
- `webhook_config.timeout_ms` ∈ [1000, 30000]
- `alarm_rules_override` 阈值必须 > 0
- 启动时校验全局默认值，非法 fail-fast

### 7.2 测试策略

#### 单元测试（目标覆盖率 ≥ 70%）

| 测试类 | 关键用例 |
|--------|---------|
| `AlarmDetectorTest` | 静默超时命中/不命中、新设备不触发、连续失败累计、维护跳过、抑制窗口 |
| `AlarmStateMachineTest` | ACTIVE→ACKED→RESOLVED；非法转换抛 409 |
| `ThresholdResolverTest` | 设备覆盖优先 / 回落默认 |
| `WebhookSignatureTest` | HMAC 签名正确、Secret 缺失 |
| `GenericJsonAdapterTest` | Payload 字段完整、ISO8601 含时区 |

#### 集成测试（Testcontainers + PG，CI 跑）

| 测试类 | 关键用例 |
|--------|---------|
| `AlarmServiceIT` | 完整生命周期：触发→去重→ACK→自动恢复 |
| `AlarmRepoIT` | 索引生效（EXPLAIN 校验） |
| `WebhookDispatcherIT` | MockWebServer：2xx 成功 / 5xx 重试 / 超时 / 全败写 log |
| `AlarmApiIT` | 全 REST 端点 happy path + 权限 + 404/409 |

> **Testcontainers 本地例外**：macOS docker-java 兼容问题已知（`ems-floorplan` 已有先例）。允许本地 `mvn -DskipITs verify`，**CI 必须跑 IT 全集**。

#### 前端测试
- `AlarmBell.test.tsx`：角标显示 + 30s 轮询
- `health.test.tsx`：渲染 + 空数据兜底
- 关键 mutation：React Testing Library + msw

#### E2E 冒烟
1. 关 collector → 等 10min → 铃铛 +1，列表出现
2. 配 webhook → 触发 → 接收方拿到带签名 payload
3. 设备恢复 → 自动 RESOLVED + "已恢复"通知
4. 手动维护模式 → 告警停发

### 7.3 性能基线
- 1000 设备 / 1 分钟一轮：单轮 < 5s（基线）
- 首版不做自动化压测，spec 写明基线供后续回归参考

---

## 8. 交付清单

### 8.1 新增文件

**Backend (`ems-alarm`)**
- `pom.xml`
- `src/main/java/com/ems/alarm/AlarmModuleConfig.java`
- `src/main/java/com/ems/alarm/domain/{Alarm, AlarmStatus, AlarmType, ...}.java`
- `src/main/java/com/ems/alarm/repo/{AlarmRepo, OverrideRepo, WebhookConfigRepo, DeliveryLogRepo, InboxRepo}.java`
- `src/main/java/com/ems/alarm/detector/{AlarmDetector, ThresholdResolver}.java`
- `src/main/java/com/ems/alarm/dispatcher/{AlarmDispatcher, InAppChannel, WebhookChannel, WebhookAdapter, GenericJsonAdapter}.java`
- `src/main/java/com/ems/alarm/api/{AlarmController, AlarmRuleController, WebhookController}.java`
- `src/main/resources/db/migration/V20260429__alarm_tables.sql`
- 对应 `src/test/java/com/ems/alarm/...`

**Backend (`ems-collector` 扩展)**
- `CollectorHealthService.getFailCount(deviceId)`

**Backend (`ems-app`)**
- `pom.xml` 加依赖
- `application.yml` 加 `ems.alarm.*`

**Frontend**
- `src/api/alarm.ts`
- `src/components/AlarmBell.tsx`
- `src/components/AlarmCenterDrawer.tsx`
- `src/pages/alarms/{health, history, rules, webhook}.tsx`
- 改动：`src/pages/{meter/list, meter/detail, dashboard/index}.tsx`、`src/layouts/AppLayout.tsx`、路由表

### 8.2 不在范围

- 邮件 / 短信 / IM 直连（首版仅 webhook，IM 通过 webhook 桥接）
- 多 webhook 端点
- 严重程度分级
- 告警 SLA / 升级
- 用户级订阅过滤
- WebSocket / SSE 实时推送
- 多实例分布式锁（ShedLock）
- 自动化压力测试
- 阈值热更新

---

## 9. 风险与未决

| 项 | 风险 | 缓解 |
|----|------|------|
| 单实例调度 | 多实例部署重复触发 | 文档标注；商业化前引入 ShedLock |
| 失败计数内存丢失 | collector 重启后清零 | 静默超时仍兜底；可接受 |
| Webhook 重试用内存队列 | 进程崩溃丢失重试 | `webhook_delivery_log` 留痕，UI 手动重放 |
| Testcontainers 本地失败 | macOS docker-java 兼容 | 允许本地 skip，CI 强制跑 |

---

## 10. 与既有架构对齐点

- 模块命名：`ems-alarm`（与 `ems-meter` / `ems-billing` 一致）
- 包结构：`com.ems.alarm.{domain, repo, service, api, ...}`
- 异常处理：沿用 `GlobalExceptionHandler` + `@Audited` 注解
- 鉴权：`@PreAuthorize("hasRole('ADMIN')")` 等
- Migration：Flyway，文件名 `V20260429__alarm_tables.sql`
- JaCoCo：模块级 70% 阈值（参考 `ems-floorplan` 实践）
- 时区：所有时间字段 `TIMESTAMPTZ`，前端 ISO8601 含 `+08:00`
