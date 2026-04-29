# 采集中断告警 · 配置参数参考

> **更新于**：2026-04-29（Phase A 完成时）
> **撰写依据**：[spec §12](../superpowers/specs/2026-04-29-acquisition-alarm-design.md) + 实际落地的 application.yml 默认值

---

## 1. 全局默认配置（application.yml）

采集中断告警系统的核心参数均在 `ems-app/src/main/resources/application.yml` 中集中配置，前缀 `ems.alarm`。下表列出所有 7 个全局参数的类型、默认值、有效范围及调优建议。

> **重要**：全部参数修改后**需重启 ems-app 才生效**（首版不支持热更新）。如需快速调试某一设备的阈值，请使用 **§2 设备级覆盖** 功能。

| 参数 | 类型 | 默认值 | 有效范围 | 含义 | 调优建议 |
|------|------|--------|--------|------|---------|
| `default-silent-timeout-seconds` | int | 600 | ≥ 1 | 全局静默超时阈值（秒）。设备无新数据超过此时长触发告警 | 高频采集（≤ 5s）：调到 60-120s；低频（≥ 60s）：调到 1800-3600s。一般为采集周期的 5-10 倍 |
| `default-consecutive-fail-count` | int | 3 | ≥ 1 | 全局连续失败次数阈值。collector 连错此次数触发告警 | 网络稳定环境调 2-3；高干扰环境调 5-10 避免误报 |
| `poll-interval-seconds` | int | 60 | ≥ 10 | 检测引擎扫描周期（秒）。每隔此时长扫一次所有设备 | < 100 设备：可调到 30；> 1000 设备：建议 120-180 减小 DB 压力 |
| `suppression-window-seconds` | int | 300 | ≥ 0 | 抑制窗口（秒）。RESOLVED 后此时长内不再触发同类告警；ACTIVE 触发后此时长内不允许 AUTO 恢复 | 抖动设备调到 600-1800；稳定设备可调到 60-120 |
| `webhook-retry-max` | int | 3 | ≥ 0 | Webhook 失败重试最大次数 | 接收方 SLA 高可调到 1-2；接收方不稳定可调到 5 |
| `webhook-retry-backoff-seconds` | List<int> | [10, 60, 300] | 长度 ≥ retry-max，每项 ≥ 1 | 重试退避秒数数组。第 N 次重试等待此数组第 N-1 项秒数 | 长度必须 ≥ retry-max；常见 `[5,30,120]` 快重试或 `[60,600,3600]` 长退避 |
| `webhook-timeout-default-ms` | int | 5000 | 1000-30000 | Webhook 默认超时（毫秒），`webhook_config.timeout_ms` 未设置时使用 | 内网接收方调 1000-2000；外网调 5000-10000 |

---

## 2. 设备级覆盖（运行时配置）

除了全局默认值，系统允许对单个设备进行运行时阈值覆盖，无需重启应用。覆盖通过两种方式设置：

1. **API 方式**：`PUT /api/v1/alarm-rules/overrides/{deviceId}` 
2. **UI 方式**：登录后访问 **系统健康 → 阈值规则**，在"设备级覆盖"卡片中找到目标设备并编辑

### 覆盖字段详解

| 字段 | 类型 | 含义 | 留空（NULL）行为 |
|------|------|------|-----------------|
| `silent_timeout_seconds` | Integer | 该设备的静默超时阈值（秒） | 沿用全局 `default-silent-timeout-seconds` 值 |
| `consecutive_fail_count` | Integer | 该设备的连续失败阈值（次数） | 沿用全局 `default-consecutive-fail-count` 值 |
| `maintenance_mode` | boolean | 维护模式开关。true 时该设备完全跳过检测 | 默认 false |
| `maintenance_note` | String(255) | 维护备注，便于审计（如"更换采集器"、"网络维护中"） | NULL（可留空） |

### 生效说明

覆盖修改**立即生效**（无需重启）。设定值在下一轮检测周期（最长等待 60s）即被应用。若设置了多个覆盖值，系统按优先级：
1. 设备级覆盖（最高优先）
2. 全局默认（最低优先）

---

## 3. 配置场景示例

以下三种场景涵盖工业场景的典型应用，可直接复制对应的 YAML 段落到 `application.yml` 中使用。

### 场景 A：高可靠工控（电力 / 水务）

适用于对可靠性要求极高的关键设备。特点：检测灵敏、快速告警、多重重试保证通知送达。

```yaml
ems.alarm:
  default-silent-timeout-seconds: 120        # 2 分钟无数据即告警
  default-consecutive-fail-count: 2          # 2 次失败立即告警
  poll-interval-seconds: 30                  # 30 秒扫一次，响应快
  suppression-window-seconds: 60             # 短抑制窗，快速响应
  webhook-retry-max: 5                       # 最多重试 5 次
  webhook-retry-backoff-seconds: [5, 15, 60, 300, 900]  # 快速指数退避
  webhook-timeout-default-ms: 3000           # 接收方超时 3 秒
```

### 场景 B：成本敏感型一般工厂（默认配置）

适用于中等规模工厂。平衡可靠性与系统负荷，是推荐的生产环境默认值。

```yaml
ems.alarm:
  default-silent-timeout-seconds: 600        # 10 分钟无数据
  default-consecutive-fail-count: 3          # 3 次失败
  poll-interval-seconds: 60                  # 1 分钟扫一次
  suppression-window-seconds: 300            # 5 分钟抑制窗
  webhook-retry-max: 3                       # 最多重试 3 次
  webhook-retry-backoff-seconds: [10, 60, 300]  # 标准指数退避 (10s, 60s, 5min)
  webhook-timeout-default-ms: 5000           # 超时 5 秒
```

### 场景 C：低频采集（仪表 5min 一次）

适用于采集周期长（5-60 分钟）的设备。参数宽松，避免在网络抖动下虚假告警。

```yaml
ems.alarm:
  default-silent-timeout-seconds: 1800       # 30 分钟无数据
  default-consecutive-fail-count: 3
  poll-interval-seconds: 300                 # 5 分钟扫一次，减小 DB 压力
  suppression-window-seconds: 1800           # 30 分钟抑制窗
  webhook-retry-max: 3
  webhook-retry-backoff-seconds: [60, 300, 1800]  # 长退避 (1min, 5min, 30min)
  webhook-timeout-default-ms: 8000           # 网络不稳定环境调宽
```

---

## 4. 修改后的生效方式

| 配置位置 | 配置方式 | 生效方式 | 等待时间 |
|---------|---------|---------|---------|
| `application.yml` 全局默认参数 | 编辑文件 + 重启 ems-app | 重启完成后立即生效 | 应用重启时间（通常 10-30s） |
| 设备级覆盖（数据库 `alarm_rules_override` 表）| REST API PUT / UI 表单 | 自动生效，无需重启 | ≤ 60s（下一轮检测周期） |
| 维护模式开关（`maintenance_mode`） | REST API / UI 表单 | 自动生效，无需重启 | ≤ 60s |

### 举例说明

**场景**：线上生产环境中，某个关键电表（设备 ID=88）连接网络不稳定，想临时放宽其连续失败阈值。

**操作流程**：
1. 访问 UI：系统健康 → 阈值规则 → 搜索设备 88
2. 编辑该设备，设置 `consecutive_fail_count` 为 5（原全局值为 3）
3. 提交保存
4. 等待最多 60 秒后，下一轮检测周期会自动应用新值
5. 若需恢复为全局默认，删除该覆盖即可

不需要修改 `application.yml` 和重启应用。

---

## 5. 校验失败处理

应用启动时会自动校验 `application.yml` 中的 alarm 参数。若参数不合法，应用会 fail-fast，日志中输出详细错误信息。下表列出常见校验错误及修复方法。

| 错误模式 | 含义 | 修复方法 |
|---------|------|---------|
| `default-silent-timeout-seconds must be greater than or equal to 1` | 该参数值小于 1 | 编辑 `application.yml`，改为 ≥ 1 的整数。典型值：60-1800 |
| `default-consecutive-fail-count must be greater than or equal to 1` | 连续失败阈值小于 1 | 编辑 `application.yml`，改为 ≥ 1 的整数。典型值：2-5 |
| `poll-interval-seconds must be greater than or equal to 10` | 扫描周期太短 | 改为 ≥ 10 的整数。建议 30-180 |
| `webhook-retry-backoff-seconds must not be empty` | 重试退避数组为空 | 至少填 1 项，如 `[10]` 或 `[10, 60, 300]` |
| `webhook-timeout-default-ms must be greater than or equal to 1000` | Webhook 超时过短 | 改为 ≥ 1000 的整数，不超过 30000 |
| `webhook-timeout-default-ms must be less than or equal to 30000` | Webhook 超时过长 | 改为 ≤ 30000 的整数 |
| Flyway `Migration V2.2.0__init_alarm.sql failed` | 数据库迁移失败 | 检查 Postgres 连接是否正常；若有半提交记录，执行 `flyway repair` 后重启 |
| `Failed to bind properties under 'ems.alarm'` | YAML 语法或类型错误 | 检查 YAML 缩进与语法；若是数组，确保用 `[ ]` 或换行列表格式 |

### 调试技巧

启动应用后，若需查看当前生效的参数值，可调用 API：

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/alarm-rules/defaults
```

返回示例：
```json
{
  "success": true,
  "data": {
    "defaultSilentTimeoutSeconds": 600,
    "defaultConsecutiveFailCount": 3,
    "pollIntervalSeconds": 60,
    "suppressionWindowSeconds": 300,
    "webhookRetryMax": 3,
    "webhookRetryBackoffSeconds": [10, 60, 300],
    "webhookTimeoutDefaultMs": 5000
  }
}
```

---

## 更多资源

- **完整设计规格**：[2026-04-29-acquisition-alarm-design.md](../superpowers/specs/2026-04-29-acquisition-alarm-design.md)
- **Webhook 对接指南**：参考设计规格 §13
- **状态机与生命周期**：参考设计规格 §14
- **部署前置检查**：参考设计规格 §16
