# 采集中断告警 · API 规约

> **状态**：占位骨架。**Phase F**（REST API 完成时）填充。
> 撰写依据：spec §5 API 端点、§11 权限矩阵、§15 错误码

---

## 总览

- Base path: `/api/v1`
- 鉴权：JWT Bearer Token
- 共 16 个端点（详见各分组）

## 1. 告警操作类（6 端点）

### 1.1 GET `/alarms` — 告警列表（分页 + 筛选）
（待 Phase F 填充：完整签名、Query 参数、响应 Schema、curl 示例）

### 1.2 GET `/alarms/{id}` — 告警详情
（待 Phase F 填充）

### 1.3 POST `/alarms/{id}/ack` — 确认告警
（待 Phase F 填充：含 409 状态机错误响应示例）

### 1.4 POST `/alarms/{id}/resolve` — 手动恢复
（待 Phase F 填充）

### 1.5 GET `/alarms/active/count` — 活动告警计数
（待 Phase F 填充）

### 1.6 GET `/alarms/health-summary` — 健康总览
（待 Phase F 填充）

## 2. 阈值规则类（5 端点）

### 2.1 GET `/alarm-rules/defaults`
### 2.2 GET `/alarm-rules/overrides`
### 2.3 GET `/alarm-rules/overrides/{deviceId}`
### 2.4 PUT `/alarm-rules/overrides/{deviceId}`
### 2.5 DELETE `/alarm-rules/overrides/{deviceId}`
（全部待 Phase F 填充）

## 3. Webhook 配置类（5 端点）

### 3.1 GET `/webhook-config`
（待 Phase F 填充：注意 secret 字段在响应中 mask 为 `***`）

### 3.2 PUT `/webhook-config`
（待 Phase F 填充：含 400 校验失败示例 — invalid scheme / timeout out of range）

### 3.3 POST `/webhook-config/test`
（待 Phase F 填充）

### 3.4 GET `/webhook-deliveries`
（待 Phase F 填充）

### 3.5 POST `/webhook-deliveries/{id}/retry`
（待 Phase F 填充）

## 4. 错误码

（待 Phase F 填充：搬 spec §15.1）

| HTTP | 异常类 | 触发场景 | 响应 errorMsg |
|------|-------|---------|--------------|
| 400 | `WebhookConfigInvalidException` | … | … |
| 404 | `AlarmNotFoundException` | … | … |
| 409 | `AlarmStateException` | … | … |
| 401/403 | Spring Security | … | … |

## 5. 完整请求/响应 Schema

（待 Phase F 填充：以 OpenAPI 3.0 风格列出所有 DTO 类型）

### 5.1 `AlarmListItemDTO`
### 5.2 `AlarmDTO`
### 5.3 `HealthSummaryDTO`
### 5.4 `OverrideRequestDTO`
### 5.5 `WebhookConfigDTO`
### 5.6 `WebhookConfigRequestDTO`
### 5.7 `DeliveryLogDTO`
### 5.8 `WebhookTestResultDTO`

## 6. 客户端 SDK 提示

（待 Phase F 填充：如何用 axios / fetch / requests 包一层；建议错误码处理模式）

---

**Phase F 任务清单**（实施时执行）：
- [ ] 16 个端点完整签名 + curl 示例
- [ ] 错误码完整对照
- [ ] DTO Schema（参考 OpenAPI 风格）
- [ ] 客户端集成提示
- [ ] 删除本"任务清单"段
