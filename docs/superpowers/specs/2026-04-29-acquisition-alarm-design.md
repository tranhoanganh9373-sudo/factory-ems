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
- 包结构：`com.ems.alarm.{controller, dto, entity, repository, service, service.impl, exception, config}`（沿用 `ems-floorplan` 既有约定，**不**新造 domain/repo/api）
- 异常处理：沿用 `GlobalExceptionHandler` + `@Audited` 注解
- 鉴权：`@PreAuthorize("hasRole('ADMIN')")` 等
- Migration：Flyway 集中在 `ems-app/src/main/resources/db/migration/`，文件名 `V2.2.0__init_alarm.sql`（沿用 `V<X.Y.Z>__init_<feature>.sql` 既有版本号约定）
- JaCoCo：模块级 70% 阈值（参考 `ems-floorplan` 实践）
- 时区：所有时间字段 `TIMESTAMPTZ`，前端 ISO8601 含 `+08:00`

---

## 11. 用户角色权限矩阵

> 沿用 `ems-auth` 既有角色：`ADMIN`、`OPERATOR`、`VIEWER`、`FINANCE`。VIEWER/FINANCE 与告警无关，仅 ADMIN/OPERATOR 涉入。

### 11.1 端点权限

| 端点 | 方法 | ADMIN | OPERATOR | VIEWER | 匿名 |
|------|------|:---:|:---:|:---:|:---:|
| `/alarms`（列表） | GET | ✅ | ✅ | ❌ | 401 |
| `/alarms/{id}` | GET | ✅ | ✅ | ❌ | 401 |
| `/alarms/{id}/ack` | POST | ✅ | ❌ 403 | ❌ 403 | 401 |
| `/alarms/{id}/resolve` | POST | ✅ | ❌ 403 | ❌ 403 | 401 |
| `/alarms/active/count` | GET | ✅ | ✅ | ❌ | 401 |
| `/alarms/health-summary` | GET | ✅ | ✅ | ❌ | 401 |
| `/alarm-rules/defaults` | GET | ✅ | ✅ | ❌ | 401 |
| `/alarm-rules/overrides`（列表） | GET | ✅ | ✅ | ❌ | 401 |
| `/alarm-rules/overrides/{deviceId}` | GET | ✅ | ✅ | ❌ | 401 |
| `/alarm-rules/overrides/{deviceId}` | PUT | ✅ | ❌ 403 | ❌ 403 | 401 |
| `/alarm-rules/overrides/{deviceId}` | DELETE | ✅ | ❌ 403 | ❌ 403 | 401 |
| `/webhook-config` | GET/PUT | ✅ | ❌ 403 | ❌ 403 | 401 |
| `/webhook-config/test` | POST | ✅ | ❌ 403 | ❌ 403 | 401 |
| `/webhook-deliveries` | GET | ✅ | ❌ 403 | ❌ 403 | 401 |
| `/webhook-deliveries/{id}/retry` | POST | ✅ | ❌ 403 | ❌ 403 | 401 |

### 11.2 站内通知接收人

每条 ACTIVE / RESOLVED 告警写一行 `alarm_inbox` 给：
- `role IN ('ADMIN','OPERATOR') AND enabled=true` 的所有用户
- VIEWER / FINANCE 不接收，前端铃铛对其隐藏（`@PreAuthorize` 覆盖）

### 11.3 设计取舍

- **OPERATOR 能查看不能写**：值班场景下避免误确认；如需修改阈值或确认告警，找 ADMIN
- **首版无"告警值班角色"**：商业化阶段如客户要求，再加 `ALARM_RESPONDER` 细分角色
- **审计**：所有写操作（ack / resolve / override / webhook）通过 `@Audited` 写 `audit_logs`

---

## 12. 配置参数详解

> 文件位置：`ems-app/src/main/resources/application.yml`，前缀 `ems.alarm`

| 参数 | 类型 | 默认 | 范围 | 含义 | 调优建议 |
|------|------|------|------|------|---------|
| `default-silent-timeout-seconds` | int | `600` | ≥ 1 | 全局静默超时阈值（秒）。设备无新数据超过此时长触发告警 | 高频采集（≤ 5s）：调到 60-120s；低频（≥ 60s）：调到 1800-3600s。一般为采集周期的 5-10 倍 |
| `default-consecutive-fail-count` | int | `3` | ≥ 1 | 全局连续失败次数阈值。collector 连错此次数触发告警 | 网络稳定环境调 2-3；高干扰环境调 5-10 避免误报 |
| `poll-interval-seconds` | int | `60` | ≥ 10 | 检测引擎扫描周期（秒）。每隔此时长扫一次所有设备 | < 100 设备：可调到 30；> 1000 设备：建议 120-180 减小 DB 压力 |
| `suppression-window-seconds` | int | `300` | ≥ 0 | 抑制窗口（秒）。RESOLVED 后此时长内不再触发同类告警；ACTIVE 触发后此时长内不允许 AUTO 恢复 | 抖动设备调到 600-1800；稳定设备可调到 60-120 |
| `webhook-retry-max` | int | `3` | ≥ 0 | Webhook 失败重试最大次数 | 接收方 SLA 高可调到 1-2；接收方不稳定可调到 5 |
| `webhook-retry-backoff-seconds` | List<int> | `[10, 60, 300]` | 长度 ≥ retry-max，每项 ≥ 1 | 重试退避秒数数组。第 N 次重试等待此数组第 N-1 项秒数 | 长度必须 ≥ retry-max；常见 `[5,30,120]` 快重试或 `[60,600,3600]` 长退避 |
| `webhook-timeout-default-ms` | int | `5000` | 1000-30000 | Webhook 默认超时（毫秒），`webhook_config.timeout_ms` 未设置时使用 | 内网接收方调 1000-2000；外网调 5000-10000 |

### 12.1 设备级覆盖（运行时通过 `/alarm-rules/overrides`）

每个 `meter.id` 可单独覆盖：

| 字段 | 类型 | 含义 | 留空（NULL）行为 |
|------|------|------|-----------------|
| `silent_timeout_seconds` | Integer | 该设备的静默超时阈值 | 沿用全局 default |
| `consecutive_fail_count` | Integer | 该设备的连续失败阈值 | 沿用全局 default |
| `maintenance_mode` | boolean | 维护模式开关 | 默认 false |
| `maintenance_note` | String(255) | 维护备注，便于审计 | NULL |

### 12.2 启动校验

应用启动时 `@Validated` 校验上述参数；任一非法直接 fail-fast，日志输出 `ConfigurationProperties` 校验错误。

### 12.3 配置示例（按场景）

**场景 A：高可靠工控（电力/水务）**
```yaml
ems.alarm:
  default-silent-timeout-seconds: 120        # 2 分钟无数据即告警
  default-consecutive-fail-count: 2          # 2 次失败立即告警
  poll-interval-seconds: 30
  suppression-window-seconds: 60             # 短抑制窗，快速响应
  webhook-retry-max: 5
  webhook-retry-backoff-seconds: [5, 15, 60, 300, 900]
```

**场景 B：成本敏感型一般工厂**（默认配置）
```yaml
ems.alarm:
  default-silent-timeout-seconds: 600
  default-consecutive-fail-count: 3
  poll-interval-seconds: 60
  suppression-window-seconds: 300
  webhook-retry-max: 3
  webhook-retry-backoff-seconds: [10, 60, 300]
```

**场景 C：低频采集（仪表 5min 一次）**
```yaml
ems.alarm:
  default-silent-timeout-seconds: 1800       # 30 分钟无数据
  default-consecutive-fail-count: 3
  poll-interval-seconds: 300                 # 5 分钟扫一次
  suppression-window-seconds: 1800
  webhook-retry-max: 3
  webhook-retry-backoff-seconds: [60, 300, 1800]
```

---

## 13. Webhook Payload 字段词典 + 对接示例

### 13.1 完整字段说明

| 字段 | 类型 | 必填 | 含义 | 示例值 |
|------|------|:---:|------|--------|
| `event` | string | ✅ | 事件类型枚举：`alarm.triggered` / `alarm.resolved` / `alarm.test` | `alarm.triggered` |
| `alarm_id` | int | ✅ | 告警唯一 ID，可用于关联同一告警的不同事件 | `12345` |
| `device_id` | int | ✅ | 设备数据库 ID（= `meter.id`） | `88` |
| `device_type` | string | ✅ | 设备类型枚举：`METER` / `COLLECTOR`（首版仅 METER） | `METER` |
| `device_code` | string | ✅ | 设备编码（人类可读，如 `M-A01-001`） | `M-A01-001` |
| `device_name` | string | ✅ | 设备名称（中文/工序描述） | `一号车间总表` |
| `alarm_type` | string | ✅ | 告警类型：`SILENT_TIMEOUT` / `CONSECUTIVE_FAIL` | `SILENT_TIMEOUT` |
| `severity` | string | ✅ | 严重程度（首版仅 `WARNING`） | `WARNING` |
| `triggered_at` | string | ✅ | ISO8601 触发时间（含时区） | `2026-04-29T08:15:30+08:00` |
| `last_seen_at` | string | 否 | ISO8601 设备最后一次有数据时间。SILENT_TIMEOUT 必带；CONSECUTIVE_FAIL 可能为空 | `2026-04-29T08:00:12+08:00` |
| `detail` | object | 否 | 触发上下文快照 | `{ "threshold_silent_seconds": 600, "snapshot_consecutive_errors": 5 }` |

### 13.2 HTTP Headers

| Header | 含义 | 示例 |
|--------|------|------|
| `Content-Type` | 固定 `application/json` | `application/json` |
| `X-EMS-Event` | 事件类型副本（便于不解析 body 即路由） | `alarm.triggered` |
| `X-EMS-Signature` | HMAC-SHA256 签名，格式 `sha256=<hex>`，秘钥为 `webhook_config.secret`，body 为完整 payload | `sha256=f7bc83f...` |

> **签名验证（接收方伪代码）**
> ```python
> import hmac, hashlib
> expected = "sha256=" + hmac.new(secret.encode(), body, hashlib.sha256).hexdigest()
> if not hmac.compare_digest(expected, request.headers["X-EMS-Signature"]):
>     return 403
> ```

### 13.3 对接示例

#### 13.3.1 钉钉自定义机器人（需要 markdown 包装）

接收 `alarm.triggered` 后转换：
```json
{
  "msgtype": "markdown",
  "markdown": {
    "title": "[采集告警] {{device_code}}",
    "text": "### ⚠️ 设备数据中断\n\n- **设备**：{{device_code}} {{device_name}}\n- **类型**：{{alarm_type}}\n- **触发**：{{triggered_at}}\n- **最后数据**：{{last_seen_at}}\n\n[查看详情](https://ems.example.com/alarms/history?id={{alarm_id}})"
  }
}
```

通常需中间适配层（API 网关 / FaaS 函数）做模板渲染。建议路由：
```
EMS → POST /your-adapter/dingtalk → 适配后 → 钉钉 webhook
```

#### 13.3.2 企业微信群机器人

类似钉钉，转 `text` 或 `markdown` 类型。同样需中间适配层。

#### 13.3.3 自定义后端（直接消费）

接收方应：
1. 校验 `X-EMS-Signature`（防伪）
2. 按 `event` + `alarm_id` 幂等去重（重试可能多次到达）
3. 按 `alarm_type` 路由到对应处理器
4. 返回 2xx 表示已接受（接收方失败应返回 5xx 让 EMS 进入重试）

#### 13.3.4 监控系统（Prometheus AlertManager）

不直接对接 — AlertManager 是被动接收方，需通过 webhook receiver 桥接。或者更简单：让运维订阅 alarm-inbox（首版需开发，建议商业化阶段做）。

### 13.4 新增 Adapter（后续迭代）

要接入新协议（钉钉直连 / 企微直连 / Slack / Teams）：
1. 实现 `WebhookAdapter` 接口（`getType()` 返回唯一标识，`buildPayload()` 返回该协议格式）
2. 标 `@Component` 即被 Spring 自动装配进 `Map<String, WebhookAdapter>`
3. 在前端 Webhook 配置页 `adapter_type` 下拉补该选项
4. 编写单测覆盖该协议的 payload 格式

不需要改主派发流程。

---

## 14. 完整状态机图（含 UI / 用户操作）

### 14.1 状态转换图

```
                           ┌────────────────┐
                           │   (无告警)     │
                           └────────┬───────┘
                                    │ 检测命中（且无活动告警）
                                    ▼
                          ┌──────────────────┐
                          │     ACTIVE       │
                          │  🔴 红色 Tag      │
                          │  铃铛 +1          │
                          │  Webhook: 触发     │
                          │  站内: TRIGGERED  │
                          └─┬────────────┬───┘
                            │            │
        用户点击"确认"      │            │ 数据恢复 + 距 triggered > 5min
        （仅 ADMIN）        │            │
                            ▼            ▼
                  ┌──────────────┐  ┌──────────────────┐
                  │   ACKED      │  │   RESOLVED       │
                  │  🟡 黄色 Tag  │  │  🟢 绿色 Tag      │
                  │  铃铛不计数   │  │  resolved_reason │
                  │  Webhook:无   │  │   = AUTO         │
                  │  站内:无      │  │  Webhook: 无      │
                  └──────┬───────┘  │  站内: RESOLVED  │
                         │          └──────────────────┘
                         │ ① 数据恢复 + 距 triggered > 5min
                         │   → RESOLVED (AUTO)
                         │ ② 用户点击"手动恢复"（ADMIN）
                         │   → RESOLVED (MANUAL)
                         └─────────────► RESOLVED ◄──── 用户从 ACTIVE 直接"手动恢复"（ADMIN）
                                                        → RESOLVED (MANUAL)
```

### 14.2 状态语义对照

| 状态 | UI 显示 | 含义 | 用户能做什么 | 后端行为 |
|------|---------|------|-------------|---------|
| **ACTIVE** | 🔴 红色 Tag「告警」/ 铃铛角标 +1 | 告警刚触发，未被任何用户处理 | ADMIN: 确认 / 手动恢复；OPERATOR: 仅查看 | 触发即写 webhook + 站内推送 |
| **ACKED** | 🟡 黄色 Tag「已确认」/ 铃铛角标不再 +1 | 已被运维认领，正在处理 | ADMIN: 手动恢复；OPERATOR: 仅查看 | 等待自动恢复 |
| **RESOLVED** | 🟢 绿色 Tag「已恢复」/ 仅在历史页可见 | 终态，告警已结束 | 仅查看（无操作） | 5min 抑制窗口结束后才允许同类型告警再次触发 |

### 14.3 各页面对状态的展示

| 页面 | 显示哪些状态 | 排序 |
|------|-------------|------|
| 全局铃铛角标 | `count(ACTIVE)` | — |
| 通知中心抽屉 | 最新 20 条 ACTIVE | `triggered_at DESC` |
| 健康总览 → "告警中" 卡 | `count(ACTIVE) + count(ACKED)` | — |
| 告警历史页 | 全部状态（用筛选切） | `triggered_at DESC` |
| 设备详情 → 告警时间线 | 该设备最近 5 条全状态 | `triggered_at DESC` |

### 14.4 边界场景说明

| 场景 | 系统行为 |
|------|---------|
| 同设备同类型重复触发（已 ACTIVE/ACKED） | 不再创建新行，仅更新 `detail` JSONB |
| 触发后立即恢复（< 5min） | 不会自动恢复（抑制窗口生效），等满 5min |
| RESOLVED 后立即又中断（< 5min） | 不会触发新告警（抑制窗口生效），等满 5min |
| 设备从未上报（`last_seen IS NULL`） | 不触发 SILENT_TIMEOUT；连续失败仍可触发 |
| `maintenance_mode=true` | 完全跳过该设备（不触发 / 不自动恢复 / 现存告警保持原状） |
| collector 重启 | 内存 `consecutiveErrors` 清零，CONSECUTIVE_FAIL 类型告警可能延迟 N 个周期才再次触发；SILENT_TIMEOUT 不受影响 |

---

## 15. 错误码与中文用户提示

### 15.1 业务异常 → HTTP 状态码

| 异常类 | HTTP | 触发场景 | 后端 message | 前端 Toast 文案 |
|-------|------|---------|-------------|----------------|
| `AlarmNotFoundException` | 404 | `/alarms/{id}` 路径中 id 不存在 | `Alarm not found: {id}` | `告警不存在或已被删除` |
| `AlarmStateException` (ack from non-ACTIVE) | 409 | 对 ACKED/RESOLVED 告警调 ack | `Cannot ack alarm in status RESOLVED` | `该告警已确认或已恢复，无需重复操作` |
| `AlarmStateException` (resolve from RESOLVED) | 409 | 对已 RESOLVED 告警再 resolve | `Already resolved` | `该告警已恢复` |
| `WebhookConfigInvalidException` (scheme) | 400 | URL 不是 http/https | `url scheme must be http or https` | `Webhook URL 必须以 http:// 或 https:// 开头` |
| `WebhookConfigInvalidException` (timeout) | 400 | timeout < 1000 或 > 30000 | `timeoutMs must be in [1000, 30000]` | `超时时间必须在 1-30 秒之间` |
| `NotFoundException` (override deviceId) | 404 | 查询不存在的 override | `AlarmRuleOverride not found: {id}` | `该设备未配置个性化阈值（沿用全局默认）` |
| 字段校验失败（@Valid） | 400 | DTO 字段非法 | Spring 标准消息 | `请求参数错误：<字段名> <原因>` |
| 401 Unauthorized | 401 | 未登录 / token 过期 | — | `登录已过期，请重新登录` |
| 403 Forbidden | 403 | 角色权限不足 | — | `您没有权限执行此操作` |

### 15.2 Webhook 派发错误

派发错误不抛 HTTP（异步），全部进 `webhook_delivery_log.last_error` 字段：

| `last_error` 前缀 | 含义 | 接收方排查 |
|------------------|------|-----------|
| `HTTP 4xx` | 接收方拒绝（鉴权失败 / payload 不合法） | 检查 secret、URL 路径、payload 格式 |
| `HTTP 5xx` | 接收方内部错误 | 接收方日志 |
| `HttpTimeoutException: ...` | 超时（默认 5s） | 调高 `timeout_ms` 或检查接收方延迟 |
| `ConnectException: ...` | 连接被拒（端口未开 / 防火墙） | 检查接收方端口与 EMS 网络连通性 |
| `UnknownHostException: ...` | DNS 解析失败 | 检查 URL 域名是否正确 |
| `IOException: ...` | 网络 I/O 错误 | 检查网络稳定性 |

### 15.3 启动校验失败

| 错误模式 | 修复 |
|---------|------|
| `default-silent-timeout-seconds must be greater than or equal to 1` | application.yml 中该值 ≥ 1 |
| `webhook-retry-backoff-seconds must not be empty` | 数组至少 1 项 |
| `webhook-timeout-default-ms must be greater than or equal to 1000` | ≥ 1000 |
| Flyway `Migration V2.2.0__init_alarm.sql failed` | 检查 Postgres 连接、查 `flyway_schema_history` 是否半提交，必要时 `flyway repair` |

---

## 16. 部署前置条件

### 16.1 软件版本

| 项 | 版本要求 | 备注 |
|---|---------|------|
| Java | 21+（OpenJDK 21 已验证） | 与既有 ems-app 一致 |
| Spring Boot | 3.3.4（parent pom 已锁定） | — |
| Postgres | 15+（使用 JSONB + TIMESTAMPTZ） | 与既有 v1.x 一致 |
| Maven | 3.9+ | mvnw wrapper 可用 |
| Docker（可选） | 24+，docker-compose v2 | 用于 stack 部署 |
| 前端 | Node 20+，pnpm 9+ | 沿用 frontend 既有要求 |

### 16.2 硬件规格（首版预估）

| 设备规模 | CPU | RAM | 磁盘（年）| 网络 |
|---------|-----|-----|----------|------|
| ≤ 100 设备 | 2 核 | 4 GB（含 Postgres） | 10 GB | 内网即可 |
| 100–1000 设备 | 4 核 | 8 GB | 50 GB | 内网；webhook 出口若需外网另算 |
| 1000–5000 设备 | 8 核 | 16 GB（Postgres 单独 4 GB） | 200 GB | 同上；建议独立 Postgres 实例 |

> **存储估算依据**：每台设备每年最多触发 ~50 次告警 + 200 条派发日志；JSONB detail ~200 字节；inbox 按 5 个 admin/operator × 50 触发 × 365 天 ≈ 90k 行/年。

### 16.3 端口与网络

| 端口 | 协议 | 用途 | 是否必须开放 |
|------|------|------|------------|
| 8080 | HTTP | ems-app（含 alarm 模块）API + 健康检查 | 必须，nginx 反代 |
| 5432 | TCP | Postgres | 仅内网 |
| 8086 | HTTP | InfluxDB | 仅内网 |
| 出站 80/443 | HTTPS | Webhook 派发到外部接收方 | 仅当 webhook URL 是外网时 |

### 16.4 环境变量增量（在既有 `.env.example` 基础上）

无新增 env —— 所有 alarm 配置走 `application.yml`（首版不需要敏感信息走 env）。

> **如果需要给 Webhook secret 走 env**：将 `webhook_config.secret` 通过 `${EMS_ALARM_WEBHOOK_SECRET:}` 占位符注入，再 `.env.example` 加该项。首版不做（secret 在 UI 配置）。

### 16.5 启动健康检查清单

部署后按顺序确认：

```bash
# 1. Spring Boot 启动成功
curl -fsS http://localhost:8080/actuator/health/liveness
# 期望：{"status":"UP"}

# 2. Flyway 已跑 V2.2.0
docker exec factory-ems-postgres-1 psql -U ems -d ems -c \
  "SELECT version, description, success FROM flyway_schema_history WHERE version='2.2.0';"
# 期望：1 行，success=t

# 3. 5 张表存在
docker exec factory-ems-postgres-1 psql -U ems -d ems -c "\dt alarms alarm_rules_override webhook_config webhook_delivery_log alarm_inbox"
# 期望：5 张表全部列出

# 4. AlarmDetector @Scheduled 激活（看日志）
docker logs factory-ems-app | grep -i "AlarmDetector\|Scan"
# 期望：每 60s 一条 scan 日志（或 DEBUG 级，无 ERROR）

# 5. （可选）登录后访问 /alarms/health-summary 拿到 200
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/alarms/health-summary
# 期望：{"success":true,"data":{...}}
```

### 16.6 首次启用步骤（管理员视角）

1. 部署完毕（containers 全 UP）
2. 用 `admin/admin123` 登录前端
3. 访问 系统健康 → Webhook 配置：
   - 若不需要 webhook → 保持 `enabled=false`，跳过
   - 若需要 webhook → 填 URL + Secret + Adapter，点测试 → 期望 2xx → 启用 ON → 保存
4. 访问 系统健康 → 阈值规则：
   - 默认值适用大多数场景，可不动
   - 对特殊设备（如重要工序总表）可单独 PUT override 调短 timeout
5. （可选）模拟一次告警验证：在 collector.yml 临时改一个 device 的 host 为不可达地址 → 重启 → 等 silent-timeout 秒数 → 看到铃铛角标 +1 + 进入告警历史。验证完恢复配置即可。

### 16.7 备份与恢复

5 张新表纳入既有 Postgres 备份（沿用 `runbook-2.0.md` 的 pg_dump 流程）：
- 关键表：`alarms`（告警历史）、`alarm_rules_override`（覆盖配置）、`webhook_config`（webhook 接入配置含 secret）
- 可丢失：`webhook_delivery_log`（流水，重启后旧 alarm 不会重新派发；丢失只影响重放历史）、`alarm_inbox`（站内通知，丢失只影响未读列表）
- **secret 敏感**：`webhook_config.secret` 是 HMAC 秘钥，备份文件按敏感数据处理（参考 `runbook-2.0.md` 加密备份段）

### 16.8 监控接入（Prometheus / Grafana）

沿用既有 Micrometer 自动指标：
- `http_server_requests_seconds_count{uri="/api/v1/alarms*"}` — 告警 API 请求数
- `jvm_memory_used_bytes`、`process_cpu_usage` — 通用 JVM 指标

新增（可选，后续迭代加）：
- `ems_alarm_active_total`（gauge）— 当前活动告警数
- `ems_alarm_webhook_delivery_total{status="success|failed"}`（counter）— webhook 派发成功/失败计数
- `ems_alarm_scan_duration_seconds`（histogram）— 单次 scan 耗时

> 首版不强制实现这些指标；商业化前补。Plan 留 `Phase H` 一行占位。

---
