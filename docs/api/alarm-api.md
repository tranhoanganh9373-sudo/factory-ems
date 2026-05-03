# 采集中断报警 · API 规约

> **更新于**：2026-04-29（Phase F 完成时）
> **撰写依据**：spec §5 / §11 / §15 + 实际 Controller 实现

---

## §0 通用约定

### Base URL

所有 API 端点均以 `/api/v1` 为前缀，例如：

```
GET /api/v1/alarms
POST /api/v1/alarms/{id}/ack
```

### 鉴权

所有端点均需要 JWT Bearer Token，在请求头中携带：

```http
Authorization: Bearer <TOKEN>
```

Token 获取方式请参考 `/api/v1/auth/login` 端点（详见 auth-api.md）。

未携带 token 或 token 失效时，所有端点均返回 **401 Unauthorized**。

### 响应包装（Result\<T\>）

所有响应统一使用 `Result<T>` 结构包装：

```json
{
  "success": true,
  "data": { },
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| success | boolean | 业务是否成功（HTTP 2xx 时为 true） |
| data | object \| null | 响应数据，失败时为 null |
| message | string \| null | 成功时为 null，失败时为错误描述 |
| traceId | string | 请求追踪 ID，用于排障 |

### 时间格式

所有时间字段均使用 **ISO8601** 含时区偏移格式：

```
2026-04-29T08:30:00+08:00
```

### 分页约定

分页参数为 **1-indexed**（page 从 1 开始），分页响应结构为 `PageDTO<T>`：

```json
{
  "items": [],
  "total": 100,
  "page": 1,
  "size": 20
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| items | array | 当页数据列表 |
| total | integer | 总记录数 |
| page | integer | 当前页（1-indexed） |
| size | integer | 每页大小 |

---

## §1 报警操作类（6 端点）

### §1.1 GET /alarms — 报警列表（分页）

- **完整路径**：`GET /api/v1/alarms`
- **角色**：ADMIN, OPERATOR
- **功能**：分页查询报警列表，支持多维度筛选

#### Query 参数

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|:----:|------|------|
| status | string | 否 | - | 报警状态过滤：`ACTIVE` / `ACKED` / `RESOLVED` |
| deviceId | int | 否 | - | 设备 ID 过滤 |
| alarmType | string | 否 | - | 报警类型：`SILENT_TIMEOUT` / `CONSECUTIVE_FAIL` |
| from | datetime | 否 | - | 起始时间（ISO8601） |
| to | datetime | 否 | - | 结束时间（ISO8601） |
| page | int | 否 | 1 | 页码（1-indexed） |
| size | int | 否 | 20 | 每页大小 |

#### 响应 Schema

`Result<PageDTO<AlarmListItemDTO>>`

#### 响应示例（200 OK）

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": 42,
        "deviceId": 7,
        "deviceName": "CNC-007",
        "alarmType": "SILENT_TIMEOUT",
        "status": "ACTIVE",
        "triggeredAt": "2026-04-29T08:00:00+08:00",
        "ackedAt": null,
        "resolvedAt": null,
        "ackedBy": null
      }
    ],
    "total": 1,
    "page": 1,
    "size": 20
  },
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440000"
}
```

#### curl 示例

```bash
curl -X GET "http://localhost:8080/api/v1/alarms?status=ACTIVE&page=1&size=20" \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json"
```

#### 错误响应

| HTTP | 触发场景 | message |
|------|---------|---------|
| 401 | 未携带 token 或 token 失效 | Full authentication is required |
| 403 | VIEWER 角色访问 | Access is denied |

---

### §1.2 GET /alarms/{id} — 报警详情

- **完整路径**：`GET /api/v1/alarms/{id}`
- **角色**：ADMIN, OPERATOR
- **功能**：查询单条报警详情，含 detail Map（扩展字段）

#### 路径参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| id | long | 是 | 报警 ID |

#### 响应 Schema

`Result<AlarmDTO>`

#### 响应示例（200 OK）

```json
{
  "success": true,
  "data": {
    "id": 42,
    "deviceId": 7,
    "deviceName": "CNC-007",
    "alarmType": "SILENT_TIMEOUT",
    "status": "ACTIVE",
    "triggeredAt": "2026-04-29T08:00:00+08:00",
    "ackedAt": null,
    "resolvedAt": null,
    "ackedBy": null,
    "resolvedBy": null,
    "resolvedReason": null,
    "detail": {
      "lastSeenAt": "2026-04-29T07:50:00+08:00",
      "silentSeconds": 600
    }
  },
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440001"
}
```

#### curl 示例

```bash
curl -X GET "http://localhost:8080/api/v1/alarms/42" \
  -H "Authorization: Bearer <TOKEN>"
```

#### 错误响应

| HTTP | 触发场景 | message |
|------|---------|---------|
| 401 | 未携带 token 或 token 失效 | Full authentication is required |
| 403 | VIEWER 角色访问 | Access is denied |
| 404 | 报警 ID 不存在 | Alarm not found: 42 |

---

### §1.3 POST /alarms/{id}/ack — 确认报警

- **完整路径**：`POST /api/v1/alarms/{id}/ack`
- **角色**：仅 **ADMIN**
- **功能**：将 ACTIVE 状态的报警标记为已确认（ACKED）
- **请求 Body**：空（不需要 body）

#### 路径参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| id | long | 是 | 报警 ID |

#### 响应 Schema

`Result<Void>`

#### 响应示例（200 OK）

```json
{
  "success": true,
  "data": null,
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440002"
}
```

#### curl 示例

```bash
curl -X POST "http://localhost:8080/api/v1/alarms/42/ack" \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Length: 0"
```

#### 错误响应

| HTTP | 触发场景 | message | 前端文案 |
|------|---------|---------|---------|
| 401 | 未携带 token 或 token 失效 | Full authentication is required | 请先登录 |
| 403 | OPERATOR 或 VIEWER 角色调用 | Access is denied | 您没有此操作的权限 |
| 404 | 报警 ID 不存在 | Alarm not found: {id} | 报警不存在或已被删除 |
| 409 | 报警状态不是 ACTIVE（已 ACKED 或 RESOLVED） | Cannot ack alarm in status RESOLVED | 该报警已确认或已恢复 |

---

### §1.4 POST /alarms/{id}/resolve — 手动恢复报警

- **完整路径**：`POST /api/v1/alarms/{id}/resolve`
- **角色**：仅 **ADMIN**
- **功能**：将 ACTIVE 或 ACKED 状态的报警手动标记为已恢复（RESOLVED）
- **请求 Body**：空（不需要 body）
- **副作用**：`resolved_reason` 设置为 `MANUAL`，并触发站内通知

#### 路径参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| id | long | 是 | 报警 ID |

#### 响应 Schema

`Result<Void>`

#### 响应示例（200 OK）

```json
{
  "success": true,
  "data": null,
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440003"
}
```

#### curl 示例

```bash
curl -X POST "http://localhost:8080/api/v1/alarms/42/resolve" \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Length: 0"
```

#### 错误响应

| HTTP | 触发场景 | message | 前端文案 |
|------|---------|---------|---------|
| 401 | 未携带 token 或 token 失效 | Full authentication is required | 请先登录 |
| 403 | OPERATOR 或 VIEWER 角色调用 | Access is denied | 您没有此操作的权限 |
| 404 | 报警 ID 不存在 | Alarm not found: {id} | 报警不存在或已被删除 |
| 409 | 报警已是 RESOLVED 状态 | Already resolved | 该报警已恢复 |

---

### §1.5 GET /alarms/active/count — 活动报警计数

- **完整路径**：`GET /api/v1/alarms/active/count`
- **角色**：ADMIN, OPERATOR
- **功能**：返回当前 ACTIVE 状态报警总数，供前端铃铛红点使用

#### 响应 Schema

`Result<{count: number}>`

#### 响应示例（200 OK）

```json
{
  "success": true,
  "data": {
    "count": 3
  },
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440004"
}
```

#### curl 示例

```bash
curl -X GET "http://localhost:8080/api/v1/alarms/active/count" \
  -H "Authorization: Bearer <TOKEN>"
```

#### 错误响应

| HTTP | 触发场景 | message |
|------|---------|---------|
| 401 | 未携带 token 或 token 失效 | Full authentication is required |
| 403 | VIEWER 角色访问 | Access is denied |

---

### §1.6 GET /alarms/health-summary — 设备健康总览

- **完整路径**：`GET /api/v1/alarms/health-summary`
- **角色**：ADMIN, OPERATOR
- **功能**：返回全厂设备健康状态概览，含在线/离线/报警/维保数量及报警最多的 Top 5 设备

#### 响应 Schema

`Result<HealthSummaryDTO>`

#### 响应示例（200 OK）

```json
{
  "success": true,
  "data": {
    "onlineCount": 28,
    "offlineCount": 4,
    "alarmCount": 3,
    "maintenanceCount": 2,
    "topOffenders": [
      {
        "deviceId": 7,
        "deviceName": "CNC-007",
        "activeAlarmCount": 2
      },
      {
        "deviceId": 12,
        "deviceName": "ROBOT-012",
        "activeAlarmCount": 1
      }
    ]
  },
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440005"
}
```

> `topOffenders` 按 `ACTIVE + ACKED` 报警数降序排列，最多返回 5 条。

#### curl 示例

```bash
curl -X GET "http://localhost:8080/api/v1/alarms/health-summary" \
  -H "Authorization: Bearer <TOKEN>"
```

#### 错误响应

| HTTP | 触发场景 | message |
|------|---------|---------|
| 401 | 未携带 token 或 token 失效 | Full authentication is required |
| 403 | VIEWER 角色访问 | Access is denied |

---

## §2 阈值规则类（5 端点）

### §2.1 GET /alarm-rules/defaults — 查询全局默认规则

- **完整路径**：`GET /api/v1/alarm-rules/defaults`
- **角色**：ADMIN, OPERATOR
- **功能**：获取系统级别的报警规则默认值（对所有未单独配置 override 的设备生效）

#### 响应 Schema

`Result<DefaultsDTO>`

#### 响应示例（200 OK）

```json
{
  "success": true,
  "data": {
    "silentTimeoutSeconds": 300,
    "consecutiveFailCount": 5,
    "suppressionWindowSeconds": 60
  },
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440010"
}
```

#### curl 示例

```bash
curl -X GET "http://localhost:8080/api/v1/alarm-rules/defaults" \
  -H "Authorization: Bearer <TOKEN>"
```

#### 错误响应

| HTTP | 触发场景 | message |
|------|---------|---------|
| 401 | 未携带 token 或 token 失效 | Full authentication is required |
| 403 | VIEWER 角色访问 | Access is denied |

---

### §2.2 GET /alarm-rules/overrides — 查询所有设备覆盖规则

- **完整路径**：`GET /api/v1/alarm-rules/overrides`
- **角色**：ADMIN, OPERATOR
- **功能**：获取所有已配置个性化阈值的设备覆盖规则列表

#### 响应 Schema

`Result<List<AlarmRuleOverride>>`

#### 响应示例（200 OK）

```json
{
  "success": true,
  "data": [
    {
      "deviceId": 7,
      "silentTimeoutSeconds": 600,
      "consecutiveFailCount": 3,
      "maintenanceMode": false,
      "maintenanceNote": null,
      "updatedAt": "2026-04-29T08:00:00+08:00",
      "updatedBy": "admin"
    }
  ],
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440011"
}
```

#### curl 示例

```bash
curl -X GET "http://localhost:8080/api/v1/alarm-rules/overrides" \
  -H "Authorization: Bearer <TOKEN>"
```

#### 错误响应

| HTTP | 触发场景 | message |
|------|---------|---------|
| 401 | 未携带 token 或 token 失效 | Full authentication is required |
| 403 | VIEWER 角色访问 | Access is denied |

---

### §2.3 GET /alarm-rules/overrides/{deviceId} — 查询指定设备覆盖规则

- **完整路径**：`GET /api/v1/alarm-rules/overrides/{deviceId}`
- **角色**：ADMIN, OPERATOR
- **功能**：查询某台设备的个性化阈值覆盖配置；若该设备未配置 override 则返回 404

#### 路径参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| deviceId | int | 是 | 设备 ID |

#### AlarmRuleOverride 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| deviceId | int | 设备 ID |
| silentTimeoutSeconds | int \| null | 静默超时秒数；null 表示沿用全局默认 |
| consecutiveFailCount | int \| null | 连续失败次数阈值；null 表示沿用全局默认 |
| maintenanceMode | boolean | 是否处于维保模式（维保期间抑制报警） |
| maintenanceNote | string \| null | 维保说明 |
| updatedAt | datetime | 最后更新时间（ISO8601） |
| updatedBy | string | 最后更新操作员用户名 |

#### 响应示例（200 OK）

```json
{
  "success": true,
  "data": {
    "deviceId": 7,
    "silentTimeoutSeconds": 600,
    "consecutiveFailCount": null,
    "maintenanceMode": false,
    "maintenanceNote": null,
    "updatedAt": "2026-04-29T08:00:00+08:00",
    "updatedBy": "admin"
  },
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440012"
}
```

#### curl 示例

```bash
curl -X GET "http://localhost:8080/api/v1/alarm-rules/overrides/7" \
  -H "Authorization: Bearer <TOKEN>"
```

#### 错误响应

| HTTP | 触发场景 | message | 前端文案 |
|------|---------|---------|---------|
| 401 | 未携带 token 或 token 失效 | Full authentication is required | 请先登录 |
| 403 | VIEWER 角色访问 | Access is denied | 您没有此操作的权限 |
| 404 | 该设备未配置个性化 override | AlarmRuleOverride not found: 7 | 该设备未配置个性化阈值 |

---

### §2.4 PUT /alarm-rules/overrides/{deviceId} — 创建或更新设备覆盖规则

- **完整路径**：`PUT /api/v1/alarm-rules/overrides/{deviceId}`
- **角色**：仅 **ADMIN**
- **功能**：创建或更新指定设备的个性化阈值配置（Upsert 语义）
- **说明**：`silentTimeoutSeconds` 或 `consecutiveFailCount` 传 `null` 表示该字段"沿用全局默认"，不覆盖全局值

#### 路径参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| deviceId | int | 是 | 设备 ID |

#### 请求 Body（JSON）

```json
{
  "silentTimeoutSeconds": 600,
  "consecutiveFailCount": null,
  "maintenanceMode": false,
  "maintenanceNote": "设备正常运行"
}
```

| 字段 | 类型 | 必填 | 校验规则 | 说明 |
|------|------|:----:|---------|------|
| silentTimeoutSeconds | int \| null | 否 | @Positive（若非 null） | null = 沿用全局默认 |
| consecutiveFailCount | int \| null | 否 | @Positive（若非 null） | null = 沿用全局默认 |
| maintenanceMode | boolean | 是 | - | true = 抑制该设备报警 |
| maintenanceNote | string | 否 | - | 维保说明，可为空字符串 |

#### 响应 Schema

`Result<AlarmRuleOverride>`（返回更新后的完整 override 对象）

#### 响应示例（200 OK）

```json
{
  "success": true,
  "data": {
    "deviceId": 7,
    "silentTimeoutSeconds": 600,
    "consecutiveFailCount": null,
    "maintenanceMode": false,
    "maintenanceNote": "设备正常运行",
    "updatedAt": "2026-04-29T10:00:00+08:00",
    "updatedBy": "admin"
  },
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440013"
}
```

#### curl 示例

```bash
curl -X PUT "http://localhost:8080/api/v1/alarm-rules/overrides/7" \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "silentTimeoutSeconds": 600,
    "consecutiveFailCount": null,
    "maintenanceMode": false,
    "maintenanceNote": "正常运行"
  }'
```

#### 错误响应

| HTTP | 触发场景 | message | 前端文案 |
|------|---------|---------|---------|
| 400 | silentTimeoutSeconds 或 consecutiveFailCount 不是正整数 | must be greater than 0 | 阈值必须为正整数 |
| 401 | 未携带 token 或 token 失效 | Full authentication is required | 请先登录 |
| 403 | OPERATOR 或 VIEWER 角色调用 | Access is denied | 您没有此操作的权限 |

---

### §2.5 DELETE /alarm-rules/overrides/{deviceId} — 删除设备覆盖规则

- **完整路径**：`DELETE /api/v1/alarm-rules/overrides/{deviceId}`
- **角色**：仅 **ADMIN**
- **功能**：删除指定设备的个性化覆盖配置，恢复使用全局默认值
- **幂等性**：删除不存在的 override 同样返回 200（幂等操作）

#### 路径参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| deviceId | int | 是 | 设备 ID |

#### 响应 Schema

`Result<Void>`

#### 响应示例（200 OK）

```json
{
  "success": true,
  "data": null,
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440014"
}
```

#### curl 示例

```bash
curl -X DELETE "http://localhost:8080/api/v1/alarm-rules/overrides/7" \
  -H "Authorization: Bearer <TOKEN>"
```

#### 错误响应

| HTTP | 触发场景 | message |
|------|---------|---------|
| 401 | 未携带 token 或 token 失效 | Full authentication is required |
| 403 | OPERATOR 或 VIEWER 角色调用 | Access is denied |

---

## §3 Webhook 配置类（5 端点）

### §3.1 GET /webhook-config — 查询 Webhook 配置

- **完整路径**：`GET /api/v1/webhook-config`
- **角色**：仅 **ADMIN**
- **功能**：获取当前 Webhook 推送配置

> **安全说明**：响应中 `secret` 字段永不返回明文。已设置时返回固定掩码 `"***"`；未设置时返回空字符串 `""`。

> **无配置时**：当数据库尚无配置记录时，返回默认空对象（见下方第二个示例）。

#### 响应 Schema

`Result<WebhookConfigDTO>`

#### 响应示例（200 OK — 已配置）

```json
{
  "success": true,
  "data": {
    "enabled": true,
    "url": "https://hooks.example.com/alarm",
    "secret": "***",
    "adapterType": "GENERIC_JSON",
    "timeoutMs": 5000,
    "updatedAt": "2026-04-29T09:00:00+08:00"
  },
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440020"
}
```

#### 响应示例（200 OK — 无配置记录时的默认值）

```json
{
  "success": true,
  "data": {
    "enabled": false,
    "url": "",
    "secret": "",
    "adapterType": null,
    "timeoutMs": 0,
    "updatedAt": null
  },
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440021"
}
```

#### curl 示例

```bash
curl -X GET "http://localhost:8080/api/v1/webhook-config" \
  -H "Authorization: Bearer <TOKEN>"
```

#### 错误响应

| HTTP | 触发场景 | message |
|------|---------|---------|
| 401 | 未携带 token 或 token 失效 | Full authentication is required |
| 403 | 非 ADMIN 角色访问 | Access is denied |

---

### §3.2 PUT /webhook-config — 更新 Webhook 配置

- **完整路径**：`PUT /api/v1/webhook-config`
- **角色**：仅 **ADMIN**
- **功能**：创建或更新 Webhook 推送配置

> **secret 保留行为**：`secret` 字段传 `null` 或空字符串 `""` 时，保持数据库中原有 secret 不变。若需要更新 secret，传入新的非空字符串即可。

#### 请求 Body（JSON）

```json
{
  "enabled": true,
  "url": "https://hooks.example.com/alarm",
  "secret": "",
  "adapterType": "GENERIC_JSON",
  "timeoutMs": 5000
}
```

| 字段 | 类型 | 必填 | 校验规则 | 说明 |
|------|------|:----:|---------|------|
| enabled | boolean | 是 | - | 是否启用 Webhook 推送 |
| url | string | 是 | 必须 http:// 或 https:// | Webhook 接收地址 |
| secret | string \| null | 否 | - | 签名密钥；null 或 "" 表示保持原值不变 |
| adapterType | string | 是 | - | 适配器类型，如 `GENERIC_JSON` |
| timeoutMs | int | 是 | [1000, 30000] | 请求超时毫秒数（1-30 秒） |

#### 响应 Schema

`Result<WebhookConfigDTO>`（返回更新后的配置，secret 同样脱敏为 `"***"`）

#### 响应示例（200 OK）

```json
{
  "success": true,
  "data": {
    "enabled": true,
    "url": "https://hooks.example.com/alarm",
    "secret": "***",
    "adapterType": "GENERIC_JSON",
    "timeoutMs": 5000,
    "updatedAt": "2026-04-29T10:30:00+08:00"
  },
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440022"
}
```

#### curl 示例

```bash
curl -X PUT "http://localhost:8080/api/v1/webhook-config" \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "url": "https://hooks.example.com/alarm",
    "secret": "",
    "adapterType": "GENERIC_JSON",
    "timeoutMs": 5000
  }'
```

#### 错误响应

| HTTP | 触发场景 | message | 前端文案 |
|------|---------|---------|---------|
| 400 | URL 不是 http:// 或 https:// | url scheme must be http or https | Webhook URL 必须 http/https |
| 400 | timeoutMs 不在 [1000, 30000] 范围内 | timeoutMs must be in [1000, 30000] | 超时必须 1-30 秒 |
| 401 | 未携带 token 或 token 失效 | Full authentication is required | 请先登录 |
| 403 | 非 ADMIN 角色操作 | Access is denied | 您没有此操作的权限 |

---

### §3.3 POST /webhook-config/test — 测试 Webhook 连通性

- **完整路径**：`POST /api/v1/webhook-config/test`
- **角色**：仅 **ADMIN**
- **功能**：使用临时配置（不保存）向目标 URL 发送一次测试请求，验证连通性
- **说明**：该操作不会写入 `webhook_delivery_log`，仅用于测试验证

> 测试请求使用模拟报警：`{id: 0, deviceId: 0, code: "M-TEST"}`

#### 请求 Body（JSON）

与 `PUT /webhook-config` 请求体结构相同（临时使用，不持久化）：

```json
{
  "enabled": true,
  "url": "https://hooks.example.com/alarm",
  "secret": "my-secret",
  "adapterType": "GENERIC_JSON",
  "timeoutMs": 5000
}
```

#### 响应 Schema

`Result<WebhookTestResultDTO>`

#### 响应示例（200 OK — 测试成功）

```json
{
  "success": true,
  "data": {
    "statusCode": 200,
    "durationMs": 142,
    "error": null
  },
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440023"
}
```

#### 响应示例（200 OK — 目标服务器返回错误）

```json
{
  "success": true,
  "data": {
    "statusCode": 500,
    "durationMs": 2031,
    "error": "Internal Server Error"
  },
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440024"
}
```

#### curl 示例

```bash
curl -X POST "http://localhost:8080/api/v1/webhook-config/test" \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "enabled": true,
    "url": "https://hooks.example.com/alarm",
    "secret": "my-secret",
    "adapterType": "GENERIC_JSON",
    "timeoutMs": 5000
  }'
```

#### 错误响应

| HTTP | 触发场景 | message |
|------|---------|---------|
| 400 | URL scheme 不合法或 timeout 越界 | url scheme must be http or https |
| 401 | 未携带 token 或 token 失效 | Full authentication is required |
| 403 | 非 ADMIN 角色操作 | Access is denied |

---

### §3.4 GET /webhook-deliveries — Webhook 投递日志

- **完整路径**：`GET /api/v1/webhook-deliveries`
- **角色**：仅 **ADMIN**
- **功能**：分页查询 Webhook 投递历史日志，按 `createdAt` 降序排列，用于运维排障

#### Query 参数

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|:----:|------|------|
| page | int | 否 | 1 | 页码（1-indexed） |
| size | int | 否 | 20 | 每页大小 |

#### 响应 Schema

`Result<PageDTO<DeliveryLogDTO>>`

#### 响应示例（200 OK）

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "id": 101,
        "alarmId": 42,
        "attempts": 2,
        "status": "FAILED",
        "lastError": "Connection refused",
        "responseStatus": null,
        "responseMs": null,
        "createdAt": "2026-04-29T08:05:00+08:00"
      },
      {
        "id": 100,
        "alarmId": 41,
        "attempts": 1,
        "status": "SUCCESS",
        "lastError": null,
        "responseStatus": 200,
        "responseMs": 156,
        "createdAt": "2026-04-29T07:50:00+08:00"
      }
    ],
    "total": 2,
    "page": 1,
    "size": 20
  },
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440025"
}
```

#### curl 示例

```bash
curl -X GET "http://localhost:8080/api/v1/webhook-deliveries?page=1&size=20" \
  -H "Authorization: Bearer <TOKEN>"
```

#### 错误响应

| HTTP | 触发场景 | message |
|------|---------|---------|
| 401 | 未携带 token 或 token 失效 | Full authentication is required |
| 403 | 非 ADMIN 角色访问 | Access is denied |

---

### §3.5 POST /webhook-deliveries/{id}/retry — 重试 Webhook 投递

- **完整路径**：`POST /api/v1/webhook-deliveries/{id}/retry`
- **角色**：仅 **ADMIN**
- **功能**：重试指定的失败 Webhook 投递记录
- **副作用**：异步执行（立即返回 200，不阻塞），将 `attempts` 重置为 1 后重新发送

#### 路径参数

| 参数 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| id | long | 是 | 投递日志 ID |

#### 响应 Schema

`Result<Void>`

#### 响应示例（200 OK）

```json
{
  "success": true,
  "data": null,
  "message": null,
  "traceId": "550e8400-e29b-41d4-a716-446655440026"
}
```

#### curl 示例

```bash
curl -X POST "http://localhost:8080/api/v1/webhook-deliveries/101/retry" \
  -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Length: 0"
```

#### 错误响应

| HTTP | 触发场景 | message |
|------|---------|---------|
| 401 | 未携带 token 或 token 失效 | Full authentication is required |
| 403 | 非 ADMIN 角色操作 | Access is denied |

---

## §4 错误码完整表

所有错误响应统一格式（`success: false`，`data: null`）：

```json
{
  "success": false,
  "data": null,
  "message": "Alarm not found: 42",
  "traceId": "550e8400-e29b-41d4-a716-446655440099"
}
```

| 异常类 | HTTP | 触发场景 | message | 前端推荐文案 |
|-------|------|---------|---------|------------|
| AlarmNotFoundException | 404 | 报警 ID 不存在 | `Alarm not found: {id}` | 报警不存在或已被删除 |
| AlarmStateException（ack 状态错误） | 409 | 对 ACKED/RESOLVED 报警调 ack | `Cannot ack alarm in status RESOLVED` | 该报警已确认或已恢复 |
| AlarmStateException（resolve 状态错误） | 409 | 对已 RESOLVED 报警调 resolve | `Already resolved` | 该报警已恢复 |
| WebhookConfigInvalidException（URL scheme） | 400 | URL 不是 http/https | `url scheme must be http or https` | Webhook URL 必须 http/https |
| WebhookConfigInvalidException（timeout） | 400 | timeoutMs < 1000 或 > 30000 | `timeoutMs must be in [1000, 30000]` | 超时必须 1-30 秒 |
| NotFoundException（override deviceId） | 404 | 查询不存在的设备 override | `AlarmRuleOverride not found: {id}` | 该设备未配置个性化阈值 |
| AccessDeniedException | 403 | 非 ADMIN 调用仅 ADMIN 端点 | `Access is denied` | 您没有此操作的权限 |
| AuthenticationException | 401 | 无 token / token 失效 | `Full authentication is required` | 请先登录 |

---

## §5 DTO Schema（OpenAPI 3.0 风格）

```yaml
components:
  schemas:

    Result:
      type: object
      required: [success, traceId]
      properties:
        success:
          type: boolean
          description: 业务是否成功
        data:
          type: object
          nullable: true
          description: 响应数据，失败时为 null
        message:
          type: string
          nullable: true
          description: 成功时为 null，失败时为错误描述
        traceId:
          type: string
          description: 请求追踪 ID

    PageDTO:
      type: object
      required: [items, total, page, size]
      properties:
        items:
          type: array
          items:
            type: object
        total:
          type: integer
          format: int64
          description: 总记录数
        page:
          type: integer
          description: 当前页（1-indexed）
        size:
          type: integer
          description: 每页大小

    AlarmListItemDTO:
      type: object
      required: [id, deviceId, deviceName, alarmType, status, triggeredAt]
      properties:
        id:
          type: integer
          format: int64
        deviceId:
          type: integer
        deviceName:
          type: string
        alarmType:
          type: string
          enum: [SILENT_TIMEOUT, CONSECUTIVE_FAIL]
        status:
          type: string
          enum: [ACTIVE, ACKED, RESOLVED]
        triggeredAt:
          type: string
          format: date-time
        ackedAt:
          type: string
          format: date-time
          nullable: true
        resolvedAt:
          type: string
          format: date-time
          nullable: true
        ackedBy:
          type: string
          nullable: true

    AlarmDTO:
      allOf:
        - $ref: '#/components/schemas/AlarmListItemDTO'
        - type: object
          properties:
            resolvedBy:
              type: string
              nullable: true
            resolvedReason:
              type: string
              nullable: true
              enum: [MANUAL, AUTO, null]
            detail:
              type: object
              nullable: true
              additionalProperties: true
              description: 扩展字段 Map，结构依报警类型而异

    ActiveCountDTO:
      type: object
      required: [count]
      properties:
        count:
          type: integer
          format: int64
          description: 当前 ACTIVE 状态报警总数

    HealthSummaryDTO:
      type: object
      required: [onlineCount, offlineCount, alarmCount, maintenanceCount, topOffenders]
      properties:
        onlineCount:
          type: integer
          description: 在线设备数
        offlineCount:
          type: integer
          description: 离线设备数
        alarmCount:
          type: integer
          description: 当前 ACTIVE 报警设备数
        maintenanceCount:
          type: integer
          description: 处于维保模式的设备数
        topOffenders:
          type: array
          maxItems: 5
          items:
            type: object
            required: [deviceId, deviceName, activeAlarmCount]
            properties:
              deviceId:
                type: integer
              deviceName:
                type: string
              activeAlarmCount:
                type: integer
                description: ACTIVE + ACKED 报警数之和

    DefaultsDTO:
      type: object
      required: [silentTimeoutSeconds, consecutiveFailCount, suppressionWindowSeconds]
      properties:
        silentTimeoutSeconds:
          type: integer
          description: 全局静默超时秒数
        consecutiveFailCount:
          type: integer
          description: 全局连续失败次数阈值
        suppressionWindowSeconds:
          type: integer
          description: 全局抑制窗口秒数（同一设备重复报警去抖）

    AlarmRuleOverride:
      type: object
      required: [deviceId, maintenanceMode, updatedAt, updatedBy]
      properties:
        deviceId:
          type: integer
        silentTimeoutSeconds:
          type: integer
          nullable: true
          description: null = 沿用全局默认
        consecutiveFailCount:
          type: integer
          nullable: true
          description: null = 沿用全局默认
        maintenanceMode:
          type: boolean
          description: 维保模式开关（抑制报警）
        maintenanceNote:
          type: string
          nullable: true
        updatedAt:
          type: string
          format: date-time
        updatedBy:
          type: string

    AlarmRuleOverrideRequest:
      type: object
      required: [maintenanceMode]
      properties:
        silentTimeoutSeconds:
          type: integer
          nullable: true
          minimum: 1
        consecutiveFailCount:
          type: integer
          nullable: true
          minimum: 1
        maintenanceMode:
          type: boolean
        maintenanceNote:
          type: string
          nullable: true

    WebhookConfigDTO:
      type: object
      required: [enabled, url, secret, timeoutMs]
      properties:
        enabled:
          type: boolean
        url:
          type: string
          format: uri
        secret:
          type: string
          description: 已设置时返回 "***"（掩码），未设置时返回 ""，永不返回明文
        adapterType:
          type: string
          nullable: true
          example: GENERIC_JSON
        timeoutMs:
          type: integer
          description: 请求超时毫秒数
        updatedAt:
          type: string
          format: date-time
          nullable: true

    WebhookConfigRequestDTO:
      type: object
      required: [enabled, url, adapterType, timeoutMs]
      properties:
        enabled:
          type: boolean
        url:
          type: string
          format: uri
          description: 必须 http:// 或 https://
        secret:
          type: string
          nullable: true
          description: null 或 "" 表示保持原值不变
        adapterType:
          type: string
          example: GENERIC_JSON
        timeoutMs:
          type: integer
          minimum: 1000
          maximum: 30000
          description: 请求超时毫秒数（1000-30000）

    WebhookTestResultDTO:
      type: object
      required: [statusCode, durationMs]
      properties:
        statusCode:
          type: integer
          nullable: true
          description: 目标服务器返回的 HTTP 状态码；网络错误时为 null
        durationMs:
          type: integer
          nullable: true
          description: 请求耗时毫秒；超时或失败时为 null
        error:
          type: string
          nullable: true
          description: 错误描述；成功时为 null

    DeliveryLogDTO:
      type: object
      required: [id, alarmId, attempts, status, createdAt]
      properties:
        id:
          type: integer
          format: int64
        alarmId:
          type: integer
          format: int64
        attempts:
          type: integer
          description: 已尝试投递次数
        status:
          type: string
          enum: [PENDING, SUCCESS, FAILED]
        lastError:
          type: string
          nullable: true
          description: 最后一次失败的错误信息
        responseStatus:
          type: integer
          nullable: true
          description: 目标服务器最后返回的 HTTP 状态码
        responseMs:
          type: integer
          nullable: true
          description: 最后一次请求耗时毫秒
        createdAt:
          type: string
          format: date-time
```

---

## §6 客户端集成提示

### §6.1 axios 拦截器统一处理

在前端项目中配置统一的请求/响应拦截器，避免在每个组件中重复处理错误：

```typescript
import axios from 'axios';

const http = axios.create({ baseURL: '/api/v1' });

http.interceptors.response.use(
  res => {
    if (!res.data?.success) {
      throw Object.assign(new Error(res.data?.message ?? '未知错误'), {
        traceId: res.data?.traceId,
        code: res.data?.code,
        status: res.status
      });
    }
    return res.data.data;
  },
  err => {
    const status = err.response?.status;
    const message = err.response?.data?.message ?? err.message;
    if (status === 401) {
      location.href = '/login';
    }
    return Promise.reject(
      Object.assign(err, { uiMessage: humanize(status, message) })
    );
  }
);

function humanize(status: number | undefined, message: string): string {
  const map: Record<number, string> = {
    400: message,
    401: '请先登录',
    403: '您没有此操作的权限',
    404: message,
    409: message,
    500: '服务器错误，请稍后重试',
  };
  return map[status ?? 0] ?? '未知错误';
}

export default http;
```

### §6.2 错误码 Toast 模式（前端推荐）

| HTTP | 推荐 Toast 方式 | 文案来源 |
|------|--------------|---------|
| 400 | `message.warning(error.uiMessage)` | spec §15.1 前端文案（业务校验失败） |
| 401 | 重定向到 `/login` | 固定 |
| 403 | `message.error('您没有此操作的权限')` | 固定静态文案 |
| 404 | `message.error(error.uiMessage)` | spec §15.1 前端文案（资源不存在） |
| 409 | `message.warning(error.uiMessage)` | spec §15.1 前端文案（状态冲突） |
| 500 | `message.error('服务器错误，请稍后重试')` | 固定 |

### §6.3 铃铛红点轮询示例

```typescript
// 每 30 秒轮询活动报警数，更新铃铛徽章
async function pollActiveAlarmCount(onUpdate: (count: number) => void) {
  const count = await http.get<number>('/alarms/active/count');
  onUpdate(count);
}

setInterval(() => pollActiveAlarmCount(setAlarmBadge), 30_000);
```

---

## 更多资源

- [报警配置参考](../product/alarm-config-reference.md)（阈值参数说明）
- [报警业务规则](../product/alarm-business-rules.md)（状态机、触发逻辑）
- [Webhook 集成指南](../product/alarm-webhook-integration.md)（适配器类型、签名验证）
- spec §11 — 权限矩阵完整版
- spec §15 — 错误码完整清单

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
