# 数据采集 · API 规约

> **适用版本**：v1.1（CP-Phase 1-9 完成时）
> **撰写依据**：spec §3 / §5 / §6 / §8 + ChannelController / ChannelDiagnosticsController / SecretController 实际实现
> **配套**：用户操作指南见 [docs/product/collector-protocols-user-guide.md](../product/collector-protocols-user-guide.md)

---

## §0 通用约定

### Base URL

所有端点以 `/api/v1` 为前缀，例如：

```
GET  /api/v1/channel
POST /api/v1/channel/{id}/test
GET  /api/v1/collector/state
```

### 鉴权

所有端点均需 JWT Bearer Token：

```http
Authorization: Bearer <TOKEN>
```

Token 通过 `/api/v1/auth/login` 取得（见 auth-api.md）。

| 端点组 | 角色要求 |
|---|---|
| `/api/v1/channel/**` | `ROLE_ADMIN` |
| `/api/v1/collector/**`（诊断） | `ROLE_ADMIN` 或 `ROLE_OPERATOR` |
| `/api/v1/secrets/**` | `ROLE_ADMIN` |

未携带 token 或 token 失效 → **401**；权限不足 → **403**。

### 时间格式

ISO-8601 含时区偏移，例：

```
2026-04-30T08:30:00+08:00
```

Channel 配置中的 `pollInterval` / `keepAlive` / `timeout` 用 ISO-8601 **Duration**：`PT5S` / `PT1M` / `PT0.5S`。

### 错误响应

错误统一以 HTTP 4xx/5xx + JSON body 返回：

```json
{
  "timestamp": "2026-04-30T08:30:00+08:00",
  "status": 400,
  "error": "Bad Request",
  "message": "host: must not be blank",
  "path": "/api/v1/channel"
}
```

400/422 校验失败时 `message` 含 Bean Validation 报错。

---

## §1 Channel CRUD（`/api/v1/channel`）

ADMIN-only。所有响应使用 `ChannelDTO`：

```json
{
  "id": 1,
  "name": "Line-A 主电表",
  "protocol": "MODBUS_TCP",
  "enabled": true,
  "isVirtual": false,
  "protocolConfig": { /* §3 各协议 schema */ },
  "description": "冲压车间主进线",
  "createdAt": "2026-04-30T08:30:00+08:00",
  "updatedAt": "2026-04-30T08:30:00+08:00"
}
```

### §1.1 列出全部通道

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8888/api/v1/channel
```

→ `200 OK`，body 是 `ChannelDTO[]`。

### §1.2 获取单条

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8888/api/v1/channel/1
```

→ `200 OK` / `404 Not Found`。

### §1.3 创建

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Line-A 主电表",
    "protocol": "MODBUS_TCP",
    "enabled": true,
    "isVirtual": false,
    "protocolConfig": {
      "protocol": "MODBUS_TCP",
      "host": "192.168.1.100",
      "port": 502,
      "unitId": 1,
      "pollInterval": "PT5S",
      "points": [
        {"key":"voltage_a","registerKind":"HOLDING","address":40001,
         "quantity":2,"dataType":"F32","scale":0.1,"unit":"V"}
      ]
    }
  }' \
  http://localhost:8888/api/v1/channel
```

→ `200 OK` 返回 `ChannelDTO`；`enabled=true` 时后台立即启动 transport。

> `protocolConfig` 是 sealed `ChannelConfig`；Jackson 通过顶层 `protocol` 字段做多态分发（`@JsonTypeInfo(property="protocol")`）。

### §1.4 更新

```bash
curl -X PUT -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ ... 同 §1.3 body ... }' \
  http://localhost:8888/api/v1/channel/1
```

→ 后台先 `stop()` 旧 transport，再以新配置 `start()`。`protocol` 字段不可改。

### §1.5 删除

```bash
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  http://localhost:8888/api/v1/channel/1
```

→ `204 No Content`，关联 transport 被停止。

### §1.6 测试连接（同步）

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8888/api/v1/channel/1/test
```

→ `200 OK` 返回 `TestResult`：

```json
{ "success": true, "latencyMs": 42, "message": null }
```

实现：优先复用已运行的 transport；否则临时构造一个仅调 `testConnection()`（不污染 active map）。**不会修改运行时状态**。

---

## §2 Collector 诊断（`/api/v1/collector`）

ADMIN 或 OPERATOR 可访问。

### §2.1 列出全部运行时状态

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8888/api/v1/collector/state
```

→ `200 OK`，body 是 `ChannelRuntimeState[]`：

```json
[
  {
    "channelId": 1,
    "protocol": "MODBUS_TCP",
    "connState": "CONNECTED",
    "lastConnectAt": "2026-04-30T08:00:00+08:00",
    "lastSuccessAt": "2026-04-30T08:30:00+08:00",
    "lastFailureAt": null,
    "lastErrorMessage": null,
    "successCount24h": 17280,
    "failureCount24h": 0,
    "avgLatencyMs": 35,
    "protocolMeta": { "unitId": 1 }
  }
]
```

`connState` 取值：`CONNECTING` / `CONNECTED` / `DISCONNECTED` / `ERROR`。

`protocolMeta` 内容随协议变化：OPC UA 含 `subscriptionId`、MQTT 含 `brokerVersion`、Modbus 含 `unitId`、VIRTUAL 含 `pointCount`。

### §2.2 单条详情

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8888/api/v1/collector/1/state
```

→ `200 OK` 返回单个 `ChannelRuntimeState`；通道未启动时返回 404。

### §2.3 测试连接

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8888/api/v1/collector/1/test
```

与 §1.6 等价；保留此别名以便诊断 UI 与 CRUD UI 共用 service 层。

### §2.4 强制重连

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  http://localhost:8888/api/v1/collector/1/reconnect
```

→ `200 OK`（无 body）。后台先 `stop()` 当前 transport，立即用现有 channel 配置重启。

> **v1.1 限制**：Modbus 连接失败后**不会**自动退避重连；MQTT / OPC UA 由底层 SDK 处理。需要手工触发请调本端点。

### §2.5 列出待审批 OPC UA 证书

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8888/api/v1/collector/cert-pending
```

→ `200 OK`，body 是 `PendingCertificate[]`：

```json
[
  {
    "thumbprint": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
    "channelId": 7,
    "endpointUrl": "opc.tcp://192.168.10.30:4840",
    "firstSeenAt": "2026-04-30T08:15:33Z",
    "subjectDn": "CN=PLC-Line1,O=Acme"
  }
]
```

ADMIN-only。当 OPC UA channel 在 SIGN/SIGN_AND_ENCRYPT 模式下连接到信任库未收录的服务端时，证书会落入 `pending/`，同时触发 `OPC_UA_CERT_PENDING` 告警。

### §2.6 批准服务端证书

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"thumbprint":"9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"}' \
  http://localhost:8888/api/v1/collector/7/trust-cert
```

→ `204 No Content`。后端将 `.der` 从 `pending/` 移到 `trusted/`，写审计 `CERT_TRUST`，自动解除关联告警。下次重连周期到达即恢复。

### §2.7 拒绝服务端证书

```bash
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  http://localhost:8888/api/v1/collector/cert-pending/9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
```

→ `204 No Content`。`.der` 移到 `rejected/` 留证；不再触发告警，但允许后续审计。

---

## §3 Secret 管理（`/api/v1/secrets`）

ADMIN-only。Secret 以 `secret://path` 引用方式嵌入到 channel 配置（OPC UA / MQTT 凭据字段）。后端 `FilesystemSecretResolver` 把明文落到 `${ems.secrets.dir}` 下，文件权限 600。

### §3.1 列出已存 ref

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8888/api/v1/secrets
```

→ `200 OK`，body 是 `string[]`，例：

```json
["opcua/cert-line1", "mqtt/broker-prod-passwd"]
```

仅返回 ref 路径；**绝不返回明文**。

### §3.2 写入（新建或覆盖）

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"ref":"mqtt/broker-prod-passwd","value":"<plain>"}' \
  http://localhost:8888/api/v1/secrets
```

→ `204 No Content`，落盘后写审计事件 `SECRET_WRITE`。

### §3.3 删除

```bash
curl -X DELETE -H "Authorization: Bearer $TOKEN" \
  "http://localhost:8888/api/v1/secrets?ref=mqtt/broker-prod-passwd"
```

→ `204 No Content`，写审计事件 `SECRET_DELETE`。引用该 secret 的 channel 下次 transport 重启时会失败。

> **v1.1 限制**：spec §8.3 描述的 `POST /api/v1/secrets/opcua/cert`（multipart .pfx 上传）尚未实装；需先手工把 .pfx 放入 `~/.ems/secrets/opcua/certs/` 再用 §3.2 写入引用。

---

## §4 protocolConfig schema 速查

5 种 sealed `ChannelConfig` 子类型由顶层 `protocol` 字段分发。

### §4.1 `MODBUS_TCP`

```json
{
  "protocol": "MODBUS_TCP",
  "host": "192.168.1.100",
  "port": 502,
  "unitId": 1,
  "pollInterval": "PT5S",
  "timeout": "PT1S",
  "points": [
    {"key":"voltage_a","registerKind":"HOLDING","address":40001,
     "quantity":2,"dataType":"F32","byteOrder":"AB_CD","scale":0.1,"unit":"V"}
  ]
}
```

`registerKind`：`COIL` / `DISCRETE` / `INPUT` / `HOLDING`。
`dataType`：`U16` / `I16` / `U32` / `I32` / `F32`。

### §4.2 `MODBUS_RTU`

```json
{
  "protocol": "MODBUS_RTU",
  "serialPort": "/dev/ttyUSB0",
  "baudRate": 9600,
  "dataBits": 8,
  "stopBits": 1,
  "parity": "N",
  "unitId": 1,
  "pollInterval": "PT2S",
  "timeout": "PT1S",
  "points": [ /* 同 TCP points */ ]
}
```

`parity`：`N` / `E` / `O`。

### §4.3 `OPC_UA`

```json
{
  "protocol": "OPC_UA",
  "endpointUrl": "opc.tcp://plc-1:4840/UA",
  "securityMode": "NONE",
  "certRef": null,
  "certPasswordRef": null,
  "usernameRef": null,
  "passwordRef": null,
  "pollInterval": "PT1S",
  "points": [
    {"key":"motor_speed","nodeId":"ns=2;s=Channel1.Tag1",
     "mode":"READ","samplingIntervalMs":1000,"unit":"rpm"}
  ]
}
```

`securityMode`：`NONE` / `SIGN` / `SIGN_AND_ENCRYPT`（**v1.1 仅 NONE 端到端可用**）。
`mode`：`READ` / `SUBSCRIBE`。
`pollInterval` 仅在含 `READ` 测点时必填；全 `SUBSCRIBE` 模式可空。

### §4.4 `MQTT`

```json
{
  "protocol": "MQTT",
  "brokerUrl": "tcp://mqtt-broker:1883",
  "clientId": "ems-prod-line1",
  "usernameRef": "secret://mqtt/broker-prod-user",
  "passwordRef": "secret://mqtt/broker-prod-passwd",
  "tlsCaCertRef": null,
  "qos": 1,
  "cleanSession": true,
  "keepAlive": "PT60S",
  "points": [
    {"key":"power_kw","topic":"factory/+/power",
     "jsonPath":"$.power","unit":"kW","timestampJsonPath":"$.ts"}
  ]
}
```

`qos`：`0` / `1`（v1 不支持 2）。
`brokerUrl` 以 `ssl://` 开头时 `tlsCaCertRef` 必填。
`pollInterval` 永远为 `null`（事件驱动）。

### §4.5 `VIRTUAL`

```json
{
  "protocol": "VIRTUAL",
  "pollInterval": "PT1S",
  "points": [
    {"key":"sim_temp","mode":"SINE",
     "params":{"amplitude":10,"periodSec":60,"offset":25},"unit":"°C"}
  ]
}
```

`mode`：`CONSTANT` / `SINE` / `RANDOM_WALK` / `CALENDAR_CURVE`。
`params` 是 `Map<String, Double>`，键随模式变化（参见用户指南 §4.5）。

---

## §5 限流与认证摘要

| 维度 | v1.1 现状 |
|---|---|
| 全局限流 | 走 nginx 反代层，详见 docs/ops/nginx-setup.md |
| 端点级限流 | 未实装（spec 后续版本） |
| Token 过期 | 默认 30 分钟（与平台统一） |
| 审计事件 | `CHANNEL_CREATE` / `CHANNEL_UPDATE` / `CHANNEL_DELETE` / `SECRET_WRITE` / `SECRET_DELETE`（写入 `audit_events` 表） |

---

## §6 已知 v1.1 限制（与 spec 偏差）

- **未实装**：`POST /api/v1/secrets/opcua/cert`（multipart .pfx 上传）— 见 §3.3 备注
- **未实装**：Modbus 自动 reconnect 退避 — 失败后需调 §2.4

> 已在 v1.1 落地（先前文档曾标记为未实装，现已交付）：
> - `GET /api/v1/collector/cert-pending` / `POST /api/v1/collector/{channelId}/trust-cert` / `DELETE /api/v1/collector/cert-pending/{thumbprint}` — OPC UA 服务器证书审批
> - OPC UA `SecurityMode = SIGN` 客户端 PEM 私钥加载（见 `OpcUaCertificateLoader`）

---

## §7 相关链接

- 用户操作指南：[docs/product/collector-protocols-user-guide.md](../product/collector-protocols-user-guide.md)
- OPC UA 证书运维：[docs/ops/opcua-cert-management.md](../ops/opcua-cert-management.md)
- 设计文档：`docs/superpowers/specs/2026-04-30-collector-protocols-design.md`
- 告警相关 API：[alarm-api.md](./alarm-api.md)
