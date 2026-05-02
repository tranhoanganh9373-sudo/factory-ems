# 采集中断告警 · Webhook 接入指南

> **更新于**：2026-04-29（Phase E 完成时）
> **撰写依据**：spec §13 + WebhookChannel/WebhookSigner/GenericJsonAdapter 实际实现

---

## 1. 适用场景

### 何时需要 Webhook

以下场景适合用 Webhook：

| 场景 | 示例工具 |
|------|---------|
| **IM 推送** | 钉钉自定义机器人、企业微信群机器人、Slack Incoming Webhook |
| **工单系统** | Jira Automation、PagerDuty Events API、飞书多维表格 |
| **SIEM / 监控平台** | Splunk HTTP Event Collector、Grafana Oncall、自建 ELK |
| **自建告警平台** | 内部运营中台、自定义设备管理系统 |

### 不需要 Webhook 的场景

以下情况不用配 Webhook，站内通知（alarm_inbox）就够了：

- 值班人员全天在 EMS UI 里监控，不需要外部推送
- 只需要记录告警历史，不需要实时触达
- 接收方需要双向交互（Webhook 是单向推送，不支持从接收方回调 EMS）

### 替代方案

EMS 默认为每条告警写入站内 alarm_inbox 通知，不用任何配置就能在 EMS UI 的"系统健康 → 告警历史"和"通知中心"查看。Webhook 是站内通知的补充，二者同时生效。

> **首版说明**：恢复事件（`alarm.resolved`）目前只触发站内通知，不发 Webhook。

---

## 2. 配置 Webhook 流程（UI）

### 访问路径

**系统健康 → Webhook 配置页**

> [配置页截图：系统健康菜单，高亮"Webhook 配置"入口]

### 配置字段说明

| 字段 | 类型 | 默认值 | 取值范围 / 说明 |
|------|------|--------|----------------|
| `url` | string | — | **必填**。接收方 URL，强烈建议使用 `https://` 开头 |
| `secret` | string | — | **必填**。HMAC-SHA256 签名密钥，建议 ≥ 32 字符随机串 |
| `adapter_type` | enum | `GENERIC_JSON` | `GENERIC_JSON`（原始 JSON）；未来可扩展 `DINGTALK`、`WECHAT_WORK` 等 |
| `timeout_ms` | int | `5000` | 每次 HTTP 请求的超时时间，范围 1000–30000 ms |
| `enabled` | boolean | `true` | 关闭后完全跳过 Webhook 派发（仅站内通知），无需删除配置 |

> [配置页截图：showing webhook config form — 字段填写示例]

### "测试发送"按钮

点击后，EMS 用当前保存的配置向接收方发送一条 `alarm.test` 事件：

- 不创建真实告警，使用占位 Sample Alarm 数据
- 页面显示：HTTP 状态码 + 请求耗时（ms）
- 不写入 `webhook_delivery_log`，不占用重试次数

> [配置页截图：showing "测试发送" 按钮及响应结果面板]

### 启用 / 禁用开关

`enabled=false` 时，EMS 跳过 Webhook 派发逻辑，所有告警事件仍正常写入站内通知。

> [配置页截图：showing enabled toggle 开关状态]

> [配置页截图：showing webhook list — 多条配置的状态总览]

---

## 3. Payload 完整字段词典

### 3.1 字段表

所有时间戳都是 ISO 8601 / RFC 3339 格式，含时区偏移（例：`2026-04-29T08:15:30+08:00`），字符集 UTF-8。

| 字段 | 类型 | 必填 | 含义 | 示例 |
|------|------|:----:|------|------|
| `event` | string | ✅ | 事件类型：`alarm.triggered` / `alarm.resolved` / `alarm.test` | `alarm.triggered` |
| `alarm_id` | int | ✅ | 告警 ID，用于关联同一告警的不同事件（触发 / 恢复） | `12345` |
| `device_id` | int | ✅ | 设备数据库 ID（`meters.id`） | `88` |
| `device_type` | string | ✅ | 设备类型：`METER` / `COLLECTOR`（首版仅 `METER`） | `METER` |
| `device_code` | string | ✅ | 设备编码（人类可读标识符） | `M-A01-001` |
| `device_name` | string | ✅ | 设备显示名称 | `一号车间总表` |
| `alarm_type` | string | ✅ | 告警类型：`SILENT_TIMEOUT` / `CONSECUTIVE_FAIL` | `SILENT_TIMEOUT` |
| `severity` | string | ✅ | 严重程度（首版仅 `WARNING`） | `WARNING` |
| `triggered_at` | string | ✅ | 告警触发时间（ISO 8601 含时区） | `2026-04-29T08:15:30+08:00` |
| `last_seen_at` | string | 否 | 设备最后一次成功上报时间；`SILENT_TIMEOUT` 必带，`CONSECUTIVE_FAIL` 可为 `null` | `2026-04-29T08:00:12+08:00` |
| `detail` | object | 否 | 触发上下文（阈值配置 / 快照错误计数等），可按需扩展 | `{ "threshold_silent_seconds": 600 }` |

### 3.2 完整 Payload 示例

#### alarm.triggered（SILENT_TIMEOUT，典型）

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
  "detail": {
    "threshold_silent_seconds": 600,
    "threshold_consecutive_fails": 3,
    "snapshot_consecutive_errors": 0
  }
}
```

#### alarm.triggered（CONSECUTIVE_FAIL）

```json
{
  "event": "alarm.triggered",
  "alarm_id": 12346,
  "device_id": 91,
  "device_type": "METER",
  "device_code": "M-B02-003",
  "device_name": "二号车间 B 回路表",
  "alarm_type": "CONSECUTIVE_FAIL",
  "severity": "WARNING",
  "triggered_at": "2026-04-29T09:30:00+08:00",
  "last_seen_at": null,
  "detail": {
    "threshold_silent_seconds": 600,
    "threshold_consecutive_fails": 3,
    "snapshot_consecutive_errors": 3
  }
}
```

> **注意**：首版 Webhook 不发送 `alarm.resolved`（恢复事件只写入站内 alarm_inbox 通知）。

---

## 4. HTTP Headers

### 4.1 Headers 一览

| Header | 含义 |
|--------|------|
| `Content-Type` | `application/json`（固定，UTF-8 编码） |
| `X-EMS-Event` | 事件类型副本，与 Payload 中的 `event` 字段相同 |
| `X-EMS-Signature` | `sha256=<hex>`，HMAC-SHA256 签名 |

### 4.2 说明

`X-EMS-Event` 让接收方不解析 Body 也能知道事件类型，便于：

- 负载均衡器 / API 网关按事件类型路由到不同后端
- 消息队列按 Header 过滤，避免无关消费
- 日志系统快速归类，不用反序列化

`X-EMS-Signature` 的计算方式：`HMAC-SHA256(secret_utf8, body_utf8)` 取 hex 编码，前缀 `sha256=`。

---

## 5. 接收方实现要点

### 5.1 必须验签

用 timing-safe 比较，防止侧信道攻击：

- Python：`hmac.compare_digest(expected, received)`
- Node.js：`crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(received))`
- Java：`MessageDigest.isEqual(expected.getBytes(), received.getBytes())`
- Go：`hmac.Equal([]byte(expected), []byte(received))`

不要用字符串直接 `==` 比较签名。各语言完整验签实现见 §5.6。

### 5.2 必须幂等

EMS 重试机制可能让同一事件多次到达接收方。推荐去重策略：

- 以 `(alarm_id, event)` 元组作为去重 key
- 存储方式：Redis SET（`SETNX alarm:{id}:{event} 1 EX 86400`）或数据库唯一索引
- 收到重复请求时返回 `200 OK`，body 标记 `"status": "duplicate"`

### 5.3 按 alarm_type 路由

建议按 `alarm_type` 分流处理逻辑：

| alarm_type | 推荐处理 |
|-----------|---------|
| `SILENT_TIMEOUT` | 推送 IM（设备长时间无数据，运营关注） |
| `CONSECUTIVE_FAIL` | 推送运维工单（采集服务故障，技术关注） |

### 5.4 HTTP 状态码语义

| 状态码范围 | EMS 行为 |
|-----------|---------|
| `2xx` | 成功，EMS 不重试 |
| `4xx` | EMS 仍会重试 3 次（客户端错误通常需人工介入修复后重放） |
| `5xx` | EMS 自动重试，接收方应尽快恢复 |
| 连接超时 | 视为失败，EMS 自动重试 |

### 5.5 响应时间要求

建议接收方在 1 秒内返回响应（默认 `timeout_ms=5000`）。如果接收方要做耗时操作（如调用外部 API），先返回 `200 OK`，再异步执行后续处理（写队列、后台任务等）。

### 5.6 多语言验签代码

**Python**

```python
import hmac
import hashlib

def verify_signature(secret: str, body: bytes, signature_header: str) -> bool:
    expected = "sha256=" + hmac.new(secret.encode("utf-8"), body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature_header)
```

**Node.js**

```js
const crypto = require('crypto');

function verifySignature(secret, body, signatureHeader) {
  const expected = 'sha256=' + crypto.createHmac('sha256', secret).update(body).digest('hex');
  if (expected.length !== signatureHeader.length) return false;
  return crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(signatureHeader));
}
```

**Java**

```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.security.MessageDigest;

public boolean verifySignature(String secret, String body, String signatureHeader) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String expected = "sha256=" + HexFormat.of().formatHex(
        mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        signatureHeader.getBytes(StandardCharsets.UTF_8));
}
```

**Go**

```go
import (
    "crypto/hmac"
    "crypto/sha256"
    "encoding/hex"
)

func verifySignature(secret string, body []byte, signatureHeader string) bool {
    h := hmac.New(sha256.New, []byte(secret))
    h.Write(body)
    expected := "sha256=" + hex.EncodeToString(h.Sum(nil))
    return hmac.Equal([]byte(expected), []byte(signatureHeader))
}
```

---

## 6. 对接示例

### 6.1 钉钉自定义机器人

**架构**：EMS → 适配层（FaaS / 中间服务）→ 钉钉机器人

```
EMS Webhook 推送
       |
       v
适配层（Flask / FaaS）
  |-- 验签（X-EMS-Signature）
  |-- 幂等去重（alarm_id + event）
  |-- 格式转换（EMS JSON -> 钉钉 Markdown）
  +-- 转发到钉钉 Webhook URL
       |
       v
钉钉群消息（Markdown 卡片）
```

**适配层完整代码（Python / Flask）**

```python
import hmac
import hashlib
import os
import requests
from flask import Flask, request, jsonify

app = Flask(__name__)

EMS_SECRET = os.environ["EMS_WEBHOOK_SECRET"]
DINGTALK_URL = os.environ["DINGTALK_WEBHOOK_URL"]

# 简单内存去重（生产环境建议换成 Redis）
_processed: set = set()


def verify_ems_signature(body: bytes, sig_header: str) -> bool:
    expected = "sha256=" + hmac.new(
        EMS_SECRET.encode("utf-8"), body, hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, sig_header)


def build_dingtalk_message(p: dict) -> dict:
    last_seen = p.get("last_seen_at") or "—"
    text = (
        f"### ⚠️ 设备数据中断\n\n"
        f"- **设备**：{p['device_code']} {p['device_name']}\n"
        f"- **类型**：{p['alarm_type']}\n"
        f"- **严重程度**：{p['severity']}\n"
        f"- **触发时间**：{p['triggered_at']}\n"
        f"- **最后数据**：{last_seen}\n\n"
        f"[查看详情](https://ems.example.com/alarms/history?id={p['alarm_id']})"
    )
    return {
        "msgtype": "markdown",
        "markdown": {
            "title": f"[采集告警] {p['device_code']}",
            "text": text,
        },
    }


@app.post("/dingtalk-adapter")
def to_dingtalk():
    body = request.get_data()
    sig = request.headers.get("X-EMS-Signature", "")

    if not verify_ems_signature(body, sig):
        return jsonify(error="bad signature"), 403

    p = request.json
    key = (p["alarm_id"], p["event"])
    if key in _processed:
        return jsonify(status="duplicate"), 200
    _processed.add(key)

    msg = build_dingtalk_message(p)
    try:
        r = requests.post(DINGTALK_URL, json=msg, timeout=5)
        r.raise_for_status()
        return jsonify(status="forwarded", dingtalk_response=r.json()), 200
    except requests.RequestException as e:
        return jsonify(error=f"dingtalk request failed: {e}"), 500


if __name__ == "__main__":
    app.run(port=8080)
```

**钉钉 Markdown 消息效果预览**

```json
{
  "msgtype": "markdown",
  "markdown": {
    "title": "[采集告警] M-A01-001",
    "text": "### ⚠️ 设备数据中断\n\n- **设备**：M-A01-001 一号车间总表\n- **类型**：SILENT_TIMEOUT\n- **严重程度**：WARNING\n- **触发时间**：2026-04-29T08:15:30+08:00\n- **最后数据**：2026-04-29T08:00:12+08:00\n\n[查看详情](https://ems.example.com/alarms/history?id=12345)"
  }
}
```

---

### 6.2 企业微信群机器人

**完整代码（Python / Flask）**

```python
import hmac
import hashlib
import os
import requests
from flask import Flask, request, jsonify

app = Flask(__name__)

EMS_SECRET = os.environ["EMS_WEBHOOK_SECRET"]
WECHAT_WORK_URL = os.environ["WECHAT_WORK_WEBHOOK_URL"]

_processed: set = set()


def verify_ems_signature(body: bytes, sig_header: str) -> bool:
    expected = "sha256=" + hmac.new(
        EMS_SECRET.encode("utf-8"), body, hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, sig_header)


def build_wechat_work_message(p: dict) -> dict:
    last_seen = p.get("last_seen_at") or "暂无"
    content = (
        f"# ⚠️ 采集告警 — {p['device_code']}\n\n"
        f"> 设备：**{p['device_name']}**\n"
        f"> 类型：{p['alarm_type']}\n"
        f"> 严重程度：{p['severity']}\n"
        f"> 触发时间：{p['triggered_at']}\n"
        f"> 最后数据：{last_seen}\n\n"
        f"[查看详情](https://ems.example.com/alarms/history?id={p['alarm_id']})"
    )
    return {
        "msgtype": "markdown",
        "markdown": {"content": content},
    }


@app.post("/wechat-work-adapter")
def to_wechat_work():
    body = request.get_data()
    sig = request.headers.get("X-EMS-Signature", "")

    if not verify_ems_signature(body, sig):
        return jsonify(error="bad signature"), 403

    p = request.json
    key = (p["alarm_id"], p["event"])
    if key in _processed:
        return jsonify(status="duplicate"), 200
    _processed.add(key)

    msg = build_wechat_work_message(p)
    try:
        r = requests.post(WECHAT_WORK_URL, json=msg, timeout=5)
        r.raise_for_status()
        return jsonify(status="forwarded", wechat_response=r.json()), 200
    except requests.RequestException as e:
        return jsonify(error=f"wechat work request failed: {e}"), 500


if __name__ == "__main__":
    app.run(port=8081)
```

**企微 Markdown 渲染效果**

```
# ⚠️ 采集告警 — M-A01-001

> 设备：一号车间总表
> 类型：SILENT_TIMEOUT
> 严重程度：WARNING
> 触发时间：2026-04-29T08:15:30+08:00
> 最后数据：2026-04-29T08:00:12+08:00

查看详情
```

---

### 6.3 自定义后端（直接消费）

适合把告警事件写入自研运营平台、工单系统或消息队列的场景。

**完整接收器（Python / Flask）**

```python
import hmac
import hashlib
import os
import logging
from flask import Flask, request, jsonify

app = Flask(__name__)
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

SECRET = os.environ["EMS_WEBHOOK_SECRET"]

# 生产环境建议用 Redis SET 或数据库唯一索引替代
_processed: set = set()


def verify_signature(body: bytes, sig_header: str) -> bool:
    expected = "sha256=" + hmac.new(
        SECRET.encode("utf-8"), body, hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, sig_header)


@app.post("/ems-webhook")
def receive():
    body = request.get_data()
    sig_header = request.headers.get("X-EMS-Signature", "")

    # 1. 验签
    if not verify_signature(body, sig_header):
        logger.warning("Signature verification failed")
        return jsonify(error="bad signature"), 403

    payload = request.json
    alarm_id = payload["alarm_id"]
    event = payload["event"]

    # 2. 幂等去重
    key = (alarm_id, event)
    if key in _processed:
        logger.info("Duplicate event ignored: alarm_id=%s event=%s", alarm_id, event)
        return jsonify(status="duplicate"), 200
    _processed.add(key)

    # 3. 按事件类型路由处理
    if event == "alarm.triggered":
        handle_triggered(payload)
    elif event == "alarm.test":
        logger.info("Test event received: alarm_id=%s", alarm_id)
    else:
        logger.info("Unhandled event type: %s", event)

    return jsonify(status="ok"), 200


def handle_triggered(payload: dict) -> None:
    """处理告警触发事件，写入自研平台或工单系统"""
    logger.info(
        "Alarm triggered: alarm_id=%s device=%s type=%s severity=%s",
        payload["alarm_id"],
        payload["device_code"],
        payload["alarm_type"],
        payload["severity"],
    )
    # 在此处调用内部系统 API，例如：
    # create_ticket(payload)
    # push_to_queue(payload)


if __name__ == "__main__":
    app.run(port=8082)
```

---

## 7. 重试与失败处理

### 7.1 默认重试策略

| 参数 | 默认值 | 可配置 |
|------|--------|--------|
| 最大重试次数 | 3 次 | 是（`application.yml`） |
| 退避间隔 | [10s, 60s, 300s] | 是（`application.yml`） |
| 超时时间 | 5000 ms | 是（每条 `webhook_config.timeout_ms`） |

### 7.2 重试时间线

```
T+0s      第 1 次发送 -> 失败（5xx / 超时 / 连接拒绝）
T+10s     第 2 次重试 -> 失败
T+70s     第 3 次重试 -> 失败
T+370s    第 4 次重试 -> 失败
              |
              v
    写 webhook_delivery_log.status = FAILED
    写 webhook_delivery_log.last_error = <错误详情>
              |
              v
    ERROR 级日志输出（运维需关注）
```

### 7.3 失败定义

以下任意一种视为单次发送失败，触发重试：

- 连接超时（超过 `timeout_ms`）
- HTTP 5xx 响应（服务端临时错误）
- HTTP 4xx 响应（客户端错误，EMS 仍重试，但建议人工修复后重放）
- 网络层错误（DNS 解析失败、连接拒绝等）

### 7.4 delivery_log 字段说明

`webhook_delivery_log` 表记录每次发送结果：

| 字段 | 含义 |
|------|------|
| `alarm_id` | 关联的告警 ID |
| `attempts` | 已尝试次数（含初次发送） |
| `response_status` | 最后一次 HTTP 响应状态码 |
| `response_ms` | 最后一次请求耗时（ms） |
| `last_error` | 最后一次失败原因（字符串） |
| `payload` | 发送的完整 JSON Payload |
| `status` | `SUCCESS` / `FAILED` / `PENDING` |

### 7.5 UI 手动重放

在"系统健康 → Webhook 配置 → 发送记录"里找到 `FAILED` 行，点"重发"按钮（仅 ADMIN 角色可见）。

等价 API 调用：

```
POST /api/v1/webhooks/deliveries/{id}/retry
Authorization: Bearer <admin-token>
```

### 7.6 SQL 查询最近失败记录

```sql
SELECT alarm_id, attempts, response_status, last_error, created_at
FROM webhook_delivery_log
WHERE status = 'FAILED'
ORDER BY created_at DESC
LIMIT 50;
```

---

## 8. 测试 Webhook

### 8.1 UI "发送测试"按钮

1. 进入 系统健康 → Webhook 配置页，找到目标配置行
2. 点"测试发送"按钮
3. EMS 用当前保存配置发送 `alarm.test` 事件（用最近一条 ACTIVE 告警数据；没有就用占位 Sample Alarm）
4. 页面显示 HTTP 状态码 + 请求耗时
5. 不写 `webhook_delivery_log`，不计入重试次数

### 8.2 配合 webhook.site 调试

1. 访问 [https://webhook.site](https://webhook.site)，获取专属临时 URL
2. 在 EMS Webhook 配置页把 `url` 填为上述临时 URL，`secret` 随意（如 `test-secret-123`）
3. 点"测试发送"
4. 在 webhook.site 页面查看完整的 Headers 和 Payload，验证字段是否符合预期
5. 调试完成后把 `url` 改回真实接收端

### 8.3 curl 模拟验签

用 `curl` 手动模拟 EMS 发送，验证自己的接收端签名实现：

```bash
SECRET="your-secret-here"
BODY='{"event":"alarm.test","alarm_id":0,"device_id":0,"device_type":"METER","device_code":"SAMPLE","device_name":"测试设备","alarm_type":"SILENT_TIMEOUT","severity":"WARNING","triggered_at":"2026-04-29T08:00:00+08:00"}'

SIG="sha256=$(echo -n "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $2}')"

curl -X POST http://localhost:8082/ems-webhook \
  -H "Content-Type: application/json" \
  -H "X-EMS-Event: alarm.test" \
  -H "X-EMS-Signature: $SIG" \
  -d "$BODY"
```

---

## 9. 安全建议

### 9.1 Secret 强度

Secret 至少 32 字符随机串。生成命令：

```bash
openssl rand -hex 32
```

不要用固定词汇、UUID 或容易猜的字符串。

### 9.2 HTTPS 强烈推荐

HTTP 明文传输会导致：

- Payload 里的设备信息、告警时间泄露
- 中间人可伪造 Webhook 请求（虽然有签名保护，但明文会暴露更多上下文）

接收方必须部署 TLS 证书，`url` 字段以 `https://` 开头。

### 9.3 IP 白名单

接收方服务器可配置防火墙规则，只允许 EMS 出口 IP 访问 Webhook 接收端点。联系 EMS 运维获取出口 IP 列表。

### 9.4 接收方双重鉴权

除了 `X-EMS-Signature` 验签，还可以在 URL 加 Token 参数做额外校验：

```
https://your-server.com/ems-webhook?token=your-private-token
```

接收方先验证 `token` 参数，再执行 HMAC 签名验证。

### 9.5 Secret 轮换流程

建议每季度轮换一次 Secret。轮换步骤：

1. 接收方先准备好新 Secret，但继续用旧 Secret 验签（不切换）
2. 在 EMS UI 的 Webhook 配置页把 Secret 更新为新值（即时生效）
3. EMS 从此刻起用新 Secret 签名
4. 接收方把验签 Secret 切换为新值
5. 弃用旧 Secret

> **说明**：步骤 2→4 之间有 1–2 秒的签名不一致窗口，EMS 重试机制会自动补发失败请求，不丢数据。

---

## 10. 故障排查

| 现象 | 可能原因 | 排查方法 |
|------|---------|---------|
| 配置后完全没收到任何 Webhook | `enabled=false` / URL 填写错误 / 防火墙阻断 | 查 `webhook_delivery_log` 是否有记录；用 webhook.site 替换 URL 确认可达性；检查接收方服务器防火墙规则 |
| 收到 Webhook 但签名验证失败（403） | Secret 两端不一致 / Body 被中间网关修改（如 gzip 压缩、去除空白） | 用 §8.3 的 `curl` 命令 + 已知 Secret 重算签名，确认算法实现；检查中间代理是否修改了 Body |
| 多次收到同一告警事件 | 重试机制触发（接收方返回了 5xx）/ 接收方未做幂等去重 | 用 `(alarm_id, event)` 元组去重，参考 §5.2；检查 `webhook_delivery_log.attempts` 字段 |
| 接收方收到告警时间比触发晚 5 分钟 | 接收方前几次返回 5xx，触发退避重试 [10s, 60s, 300s]，最长约 6 分钟 | 查 `webhook_delivery_log.attempts` 和 `last_error`；优化接收方可用性 |
| 部分接收方请求超时 | `timeout_ms` 设置太小 / 接收方处理太慢 | 调大 `timeout_ms`（最大 30000ms）；接收方改为先返回 `200 OK` 再异步处理 |
| `webhook_delivery_log` 没有任何记录 | EMS 实例异常 / 告警派发任务未启动 / Webhook 功能未启用 | 检查 EMS 服务日志（搜索 `WebhookChannel`）；确认 `webhook_config.enabled=true` |
| 部分接收方超时而其他正常 | 各 `webhook_config` 的 `timeout_ms` 配置不同 / 接收方地域网络差异 | 分别检查各配置的 `timeout_ms`；查 `webhook_delivery_log.response_ms` 分布 |

---

## 11. 新增 Adapter（开发者自助）

要在 EMS 内直接集成新的消息格式（不用中间适配层），按以下步骤扩展：

### 步骤 1：实现 WebhookAdapter 接口

```java
@Component
public class DingTalkAdapter implements WebhookAdapter {

    @Override
    public String getType() {
        return "DINGTALK";
    }

    @Override
    public String buildPayload(Alarm alarm, String deviceCode, String deviceName) {
        String lastSeen = alarm.getLastSeenAt() != null
            ? alarm.getLastSeenAt().toString() : "—";
        String text = String.format(
            "### ⚠️ 设备数据中断\n\n"
            + "- **设备**：%s %s\n"
            + "- **类型**：%s\n"
            + "- **触发**：%s\n"
            + "- **最后数据**：%s\n\n"
            + "[查看详情](https://ems.example.com/alarms/history?id=%d)",
            deviceCode, deviceName,
            alarm.getAlarmType(),
            alarm.getTriggeredAt(),
            lastSeen,
            alarm.getId()
        );
        Map<String, Object> msg = Map.of(
            "msgtype", "markdown",
            "markdown", Map.of(
                "title", "[采集告警] " + deviceCode,
                "text", text
            )
        );
        return JsonUtil.toJson(msg);
    }
}
```

### 步骤 2：Spring 自动装配

标注 `@Component` 后，Spring 自动把它注册到 `Map<String, WebhookAdapter>` Bean（按 `getType()` 返回值为 key）。`WebhookChannel` 按 `webhook_config.adapter_type` 字段路由到对应实现，不用改主派发流程（攻击面隔离）。

### 步骤 3：前端下拉选项

在 Webhook 配置页的 `adapter_type` 下拉框里加上该选项（值与 `getType()` 返回值一致）。

### 步骤 4：编写单元测试

```java
@Test
void buildPayload_silentTimeout_returnsValidDingTalkMarkdown() {
    // Arrange
    DingTalkAdapter adapter = new DingTalkAdapter();
    Alarm alarm = buildTestAlarm(AlarmType.SILENT_TIMEOUT);

    // Act
    String payload = adapter.buildPayload(alarm, "M-A01-001", "一号车间总表");

    // Assert
    assertThat(payload).contains("msgtype");
    assertThat(payload).contains("markdown");
    assertThat(payload).contains("M-A01-001");
    assertThat(payload).contains("SILENT_TIMEOUT");
}
```

### 步骤 5：文档更新

在本文档 §6 下加一个 `6.X` 子节，描述该 Adapter 的消息格式和使用方式。

---

## 更多资源

- [alarm-config-reference.md](./alarm-config-reference.md) — 告警配置完整参考（阈值、派发渠道等）
- [alarm-data-model.md](./alarm-data-model.md) — 数据模型（alarm、webhook_delivery_log 等表结构）
- [alarm-detection-rules.md](./alarm-detection-rules.md) — 告警检测规则（SILENT_TIMEOUT / CONSECUTIVE_FAIL 触发条件）
- spec §13 — Webhook Payload 字段词典与对接示例原始规格
