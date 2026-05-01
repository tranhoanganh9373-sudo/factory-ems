# 告警桥接配方（飞书 / 邮件 / 短信）

> **适用版本**：v1.0.0-ga（首版）｜ 最近更新：2026-05-01
> **受众**：客户开发对接 / 现场实施工程师
> **前置阅读**：[alarm-webhook-integration.md](./alarm-webhook-integration.md)（Payload / 验签 / 重试规约的权威来源；本文不重复，只给桥接代码模板）

---

## §1 为什么需要桥接

ems-alarm 首版**只支持一种推送形式**：HTTP POST 一段 generic-json 到一个全局 Webhook 地址。
钉钉、企业微信的桥接代码已在 [alarm-webhook-integration.md §6.1 / §6.2](./alarm-webhook-integration.md) 给齐。
本文补足另外三类常见渠道：

| 渠道 | 桥接方式 | 现成 Python 模板 |
|---|---|---|
| **飞书 / Lark 自定义机器人** | EMS → 中转服务 → 飞书机器人 webhook | §3 |
| **邮件 / SMTP** | EMS → 中转服务 → SMTP 服务器 | §4 |
| **短信** | EMS → 中转服务 → 阿里云 / 腾讯云 / Twilio SMS API | §5 |

> 个人微信 / 微信公众号不开放 webhook 推送给非认证企业服务号，因此**不在本文方案内**——客户企业号可走"自建后端 → 调腾讯模板消息 API"，与§5 短信桥接同形态。

---

## §2 桥接服务的通用结构

每个桥接服务都干同一件事：

```
EMS Webhook ──HTTP POST──> 桥接服务（Python / Node / Go）
                              │
                              ├─ 1. 验签 X-EMS-Signature  ──失败→ 401
                              ├─ 2. 幂等去重（alarm_id+event）
                              ├─ 3. 格式转换（generic-json → 目标平台 JSON / 文本）
                              ├─ 4. 调目标平台 API
                              └─ 5. 返回 200（即使下游失败也建议 200，避免 EMS 重试堆积；
                                            真正失败用本地告警，不指望 EMS 重发）
```

**部署形态**：FaaS（阿里云函数计算 / 腾讯云 SCF / AWS Lambda）或一台 1C1G 小虚机跑 Flask + nginx 即可。

**通用工具函数**（每个示例都用到）：

```python
import hmac, hashlib, os
from flask import request, abort

EMS_SECRET = os.environ["EMS_WEBHOOK_SECRET"]
_processed: set = set()  # 生产环境换 Redis SETNX


def verify_signature(body: bytes, sig_header: str) -> bool:
    expected = "sha256=" + hmac.new(
        EMS_SECRET.encode("utf-8"), body, hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, sig_header or "")


def is_duplicate(alarm_id: int, event: str) -> bool:
    key = f"{alarm_id}:{event}"
    if key in _processed:
        return True
    _processed.add(key)
    return False


def common_preflight():
    body = request.get_data()
    sig = request.headers.get("X-EMS-Signature", "")
    if not verify_signature(body, sig):
        abort(401, "bad signature")
    payload = request.get_json(force=True)
    if is_duplicate(payload["alarm_id"], payload["event"]):
        return None  # caller 应直接 return 200 duplicate
    return payload
```

---

## §3 飞书 / Lark 自定义机器人

### §3.1 准备工作

1. 飞书群 → 设置 → 群机器人 → 添加机器人 → 自定义机器人。
2. 安全设置选 **签名校验**（推荐）或仅 IP 白名单。
3. 拷贝 webhook URL（形如 `https://open.feishu.cn/open-apis/bot/v2/hook/<bot-id>`）+ 签名密钥。

### §3.2 中转服务（Python / Flask）

```python
import time, hashlib, base64, hmac, json, os, requests
from flask import Flask, jsonify
# 复用 §2 的 common_preflight / verify_signature

app = Flask(__name__)
FEISHU_URL = os.environ["FEISHU_WEBHOOK_URL"]
FEISHU_SECRET = os.environ.get("FEISHU_SIGN_SECRET")  # 可选


def feishu_sign(secret: str, ts: int) -> str:
    string_to_sign = f"{ts}\n{secret}"
    h = hmac.new(string_to_sign.encode("utf-8"), digestmod=hashlib.sha256).digest()
    return base64.b64encode(h).decode()


def to_feishu_card(p: dict) -> dict:
    title = "🔴 设备失联告警" if p["alarm_type"] == "SILENT_TIMEOUT" else "🔴 采集连续失败"
    last_seen = p.get("last_seen_at") or "—"
    detail = p.get("detail") or {}
    return {
        "msg_type": "interactive",
        "card": {
            "header": {"title": {"tag": "plain_text", "content": title},
                       "template": "red"},
            "elements": [{
                "tag": "div",
                "fields": [
                    {"is_short": True, "text": {"tag": "lark_md",
                        "content": f"**设备**\n{p['device_name']} (`{p['device_code']}`)"}},
                    {"is_short": True, "text": {"tag": "lark_md",
                        "content": f"**触发时间**\n{p['triggered_at']}"}},
                    {"is_short": True, "text": {"tag": "lark_md",
                        "content": f"**最后上报**\n{last_seen}"}},
                    {"is_short": True, "text": {"tag": "lark_md",
                        "content": f"**告警 ID**\n{p['alarm_id']}"}},
                    {"is_short": False, "text": {"tag": "lark_md",
                        "content": f"**阈值快照**\n```\n{json.dumps(detail, ensure_ascii=False, indent=2)}\n```"}},
                ]
            }]
        }
    }


@app.post("/ems/feishu")
def hook():
    payload = common_preflight()
    if payload is None:
        return jsonify(status="duplicate"), 200
    if payload["event"] != "alarm.triggered":
        return jsonify(status="ignored"), 200  # 首版不发 resolved

    body = to_feishu_card(payload)
    if FEISHU_SECRET:
        ts = int(time.time())
        body = {"timestamp": str(ts), "sign": feishu_sign(FEISHU_SECRET, ts), **body}

    r = requests.post(FEISHU_URL, json=body, timeout=5)
    r.raise_for_status()
    return jsonify(status="forwarded", feishu_status=r.status_code), 200


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8080)
```

### §3.3 部署 + 在 EMS 配置

```bash
export EMS_WEBHOOK_SECRET="<在 EMS /alarm/webhook 页面拷贝>"
export FEISHU_WEBHOOK_URL="https://open.feishu.cn/open-apis/bot/v2/hook/<bot-id>"
export FEISHU_SIGN_SECRET="<飞书机器人签名密钥>"
gunicorn -w 2 -b 0.0.0.0:8080 app:app
```

EMS UI（`/alarm/webhook`）填写：URL = `https://your-bridge.example.com/ems/feishu`，Secret = `EMS_WEBHOOK_SECRET` 同值，点"测试发送"应能立即收到飞书卡片。

### §3.4 curl 自检

```bash
PAYLOAD='{"event":"alarm.triggered","alarm_id":12345,"device_id":88,"device_type":"METER","device_code":"M-A01-001","device_name":"一号车间总表","alarm_type":"SILENT_TIMEOUT","severity":"WARNING","triggered_at":"2026-04-29T08:15:30+08:00","last_seen_at":"2026-04-29T08:00:12+08:00","detail":{"threshold_silent_seconds":600}}'
SIG=$(printf '%s' "$PAYLOAD" | openssl dgst -sha256 -hmac "$EMS_WEBHOOK_SECRET" -binary | xxd -p -c 256)
curl -X POST http://localhost:8080/ems/feishu \
  -H "Content-Type: application/json" \
  -H "X-EMS-Event: alarm.triggered" \
  -H "X-EMS-Signature: sha256=$SIG" \
  -d "$PAYLOAD"
```

---

## §4 邮件（SMTP）

### §4.1 适用场景

- 现场没装 IM / 远程客户没有钉钉群，但有邮箱。
- 24/7 oncall 列表轮换收件，邮箱比 IM 更稳定。
- 取证 / 留档（合规审计），邮件天然不可篡改。

### §4.2 中转服务（Python / Flask + smtplib）

```python
import os, smtplib
from email.mime.text import MIMEText
from email.utils import formataddr
from flask import Flask, jsonify
# 复用 §2 的 common_preflight

app = Flask(__name__)
SMTP_HOST = os.environ["SMTP_HOST"]               # smtp.example.com
SMTP_PORT = int(os.environ.get("SMTP_PORT", "465"))
SMTP_USER = os.environ["SMTP_USER"]               # alarm@example.com
SMTP_PASS = os.environ["SMTP_PASS"]
MAIL_FROM_NAME = os.environ.get("MAIL_FROM_NAME", "EMS 告警机器人")
MAIL_TO = [x.strip() for x in os.environ["MAIL_TO"].split(",")]  # 多人逗号分隔


def to_email(p: dict) -> tuple[str, str]:
    subject = f"[EMS 告警] {p['device_name']} 失联（{p['device_code']}）"
    body = f"""
告警类型：{p['alarm_type']}
告警 ID：{p['alarm_id']}
设备：{p['device_name']}（编码 {p['device_code']}，ID {p['device_id']}）
触发时间：{p['triggered_at']}
最后上报：{p.get('last_seen_at') or '—'}
严重程度：{p['severity']}

阈值快照：
{p.get('detail') or {}}

— 由 EMS 平台自动发送，请勿直接回复 —
""".strip()
    return subject, body


@app.post("/ems/email")
def hook():
    payload = common_preflight()
    if payload is None:
        return jsonify(status="duplicate"), 200
    if payload["event"] != "alarm.triggered":
        return jsonify(status="ignored"), 200

    subject, body = to_email(payload)
    msg = MIMEText(body, "plain", "utf-8")
    msg["From"] = formataddr((MAIL_FROM_NAME, SMTP_USER))
    msg["To"] = ", ".join(MAIL_TO)
    msg["Subject"] = subject

    with smtplib.SMTP_SSL(SMTP_HOST, SMTP_PORT, timeout=10) as s:
        s.login(SMTP_USER, SMTP_PASS)
        s.sendmail(SMTP_USER, MAIL_TO, msg.as_string())
    return jsonify(status="sent", to=MAIL_TO), 200


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8081)
```

### §4.3 部署 + 在 EMS 配置

```bash
export EMS_WEBHOOK_SECRET="<...>"
export SMTP_HOST="smtp.qiye.aliyun.com"
export SMTP_PORT="465"
export SMTP_USER="alarm@your-company.com"
export SMTP_PASS="<企业邮箱授权码>"   # 阿里云 / 腾讯邮箱要用授权码而非登录密码
export MAIL_TO="ops-oncall@your-company.com,manager@your-company.com"
gunicorn -w 1 -b 0.0.0.0:8081 app:app
```

> SMTP 端口对应：`465` SSL（推荐）/ `587` STARTTLS / `25` 明文（公网常被运营商封禁）。
> 国内邮箱（QQ / 网易 / 阿里云企业邮箱）通常需先在邮箱后台开启 IMAP/SMTP 服务并申请"授权码"。

### §4.4 防垃圾邮件实践

- 用**专用发件邮箱**（如 `alarm@your-company.com`），不要拿员工个人邮箱。
- 发件域名配 **SPF + DKIM + DMARC**，否则 Gmail / Outlook 大概率进垃圾箱。
- 高频告警合并：同一 device 30 分钟内多次触发只发一封（在桥接服务里加 30min TTL 去重）。

---

## §5 短信（阿里云 / 腾讯云 / Twilio）

### §5.1 适用场景

- 关键设备失联（如冷库、医院供电），oncall 没盯 IM 也要立刻知道。
- 海外现场用 Twilio；国内用阿里云 / 腾讯云 SMS。
- **单价 0.04–0.12 元 / 条**，建议**严重程度阈值过滤**+**夜间专用通道**。

### §5.2 中转服务（Python / Flask + 阿里云 SDK 示例）

```python
import os, json
from flask import Flask, jsonify
from aliyunsdkcore.client import AcsClient
from aliyunsdkcore.request import CommonRequest
# 复用 §2 的 common_preflight

app = Flask(__name__)
AK = os.environ["ALIYUN_SMS_ACCESS_KEY"]
SK = os.environ["ALIYUN_SMS_ACCESS_SECRET"]
SIGN_NAME = os.environ["ALIYUN_SMS_SIGN_NAME"]      # 已审核通过的签名（如「松羽科技」）
TEMPLATE_CODE = os.environ["ALIYUN_SMS_TEMPLATE"]   # 已审核通过的模板（如 SMS_12345）
PHONE_NUMBERS = os.environ["SMS_TO"].split(",")     # 13800138000,13800138001

client = AcsClient(AK, SK, "cn-hangzhou")


def to_sms_params(p: dict) -> dict:
    """模板示例：'EMS 告警：${device}失联，触发时间${time}，请尽快处理'"""
    return {
        "device": f"{p['device_name']}({p['device_code']})"[:30],  # 阿里云模板变量限长
        "time": p["triggered_at"][11:19],  # 只截 HH:MM:SS
    }


@app.post("/ems/sms")
def hook():
    payload = common_preflight()
    if payload is None:
        return jsonify(status="duplicate"), 200
    if payload["event"] != "alarm.triggered":
        return jsonify(status="ignored"), 200

    req = CommonRequest()
    req.set_accept_format("json")
    req.set_domain("dysmsapi.aliyuncs.com")
    req.set_method("POST")
    req.set_protocol_type("https")
    req.set_version("2017-05-25")
    req.set_action_name("SendSms")
    req.add_query_param("PhoneNumbers", ",".join(PHONE_NUMBERS))
    req.add_query_param("SignName", SIGN_NAME)
    req.add_query_param("TemplateCode", TEMPLATE_CODE)
    req.add_query_param("TemplateParam", json.dumps(to_sms_params(payload),
                                                    ensure_ascii=False))
    resp = client.do_action_with_exception(req)
    return jsonify(status="sent", aliyun=json.loads(resp)), 200


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8082)
```

### §5.3 部署 + 在 EMS 配置

```bash
export EMS_WEBHOOK_SECRET="<...>"
export ALIYUN_SMS_ACCESS_KEY="LTAI..."
export ALIYUN_SMS_ACCESS_SECRET="..."
export ALIYUN_SMS_SIGN_NAME="松羽科技"
export ALIYUN_SMS_TEMPLATE="SMS_123456789"
export SMS_TO="13800138000,13800138001"
pip install aliyun-python-sdk-core
gunicorn -w 1 -b 0.0.0.0:8082 app:app
```

### §5.4 模板申请要点（阿里云 / 腾讯云）

- **签名 SignName**：必须用企业实名（个人开发者只能用 `阿里云短信测试`）。审核 1-2 小时。
- **模板 TemplateCode**：变量数 ≤ 5 个、单变量 ≤ 30 字符、整条 ≤ 70 字符。审核 1-2 小时。
- **模板示例**：`【松羽科技】EMS告警：${device}失联，时间${time}，请处理。回TD退订` —— 必须含"回 TD 退订"等运营商合规要求。
- **腾讯云 SMS** API 不同但形态相同（`SmsClient.SendSms`），代码替换即可。
- **海外用 Twilio**：`twilio.rest.Client(SID, TOKEN).messages.create(to, from_, body)`，无需模板审核但单条 ≈ 0.5 元。

### §5.5 成本与频控

- 一条告警发 2 个号码 = 2 条计费。每月 100 次告警 × 2 = 200 条 ≈ 30 元。
- 强烈建议在桥接服务里加**严重度过滤**（首版 severity 都是 WARNING，后续 v1.7 会出分级）和**夜间静默窗口**（如 23:00-7:00 仅 P0）。
- 加**全天上限**（如同一号码每日 ≤ 20 条），避免循环告警时短信费爆。

---

## §6 通用注意事项

| 主题 | 要点 |
|---|---|
| **重试** | EMS 默认 3 次重试（指数退避，详见 [alarm-webhook-integration.md §7](./alarm-webhook-integration.md)）。桥接服务**返回 200 即视为成功**，下游真失败请在桥接侧自行落本地日志 + 单独告警，**不要返回 5xx 让 EMS 反复重发**——重发只会复制下游账单（短信费 / 邮件配额）。 |
| **幂等** | EMS 重试可能让同一 `(alarm_id, event)` 多次到达。§2 的 `is_duplicate` 是内存版仅供单实例 demo；多实例 / 重启不丢请用 `Redis SETNX key 1 EX 86400`。 |
| **限流** | 飞书机器人单 webhook 默认 100 次/分；阿里云 SMS 单签名 1000 次/天默认配额。配桥接时务必看官方文档配额，避免一次故障刷爆。 |
| **观测** | 桥接服务自身要暴露 `/healthz`，并把"已转发 / 已去重 / 下游失败"分别打 metric（Prometheus counter），接到 ems-observability 同一 Grafana。 |
| **凭据** | 所有 token / Secret 走环境变量或机密管理（K8s Secret / Vault），**绝不写进代码或镜像**。 |
| **HTTPS** | EMS → 桥接强烈推荐 HTTPS。桥接 → 飞书 / SMTP / SMS API 均默认 HTTPS。 |

---

## §7 一键部署模板（Docker Compose 节选）

```yaml
services:
  ems-bridge-feishu:
    image: python:3.12-slim
    working_dir: /app
    volumes: ["./feishu:/app"]
    command: bash -c "pip install -q flask gunicorn requests && gunicorn -w 2 -b 0.0.0.0:8080 app:app"
    environment:
      EMS_WEBHOOK_SECRET: ${EMS_WEBHOOK_SECRET}
      FEISHU_WEBHOOK_URL: ${FEISHU_WEBHOOK_URL}
      FEISHU_SIGN_SECRET: ${FEISHU_SIGN_SECRET}
    ports: ["8080:8080"]
    restart: unless-stopped

  ems-bridge-email:
    image: python:3.12-slim
    working_dir: /app
    volumes: ["./email:/app"]
    command: bash -c "pip install -q flask gunicorn && gunicorn -w 1 -b 0.0.0.0:8081 app:app"
    environment:
      EMS_WEBHOOK_SECRET: ${EMS_WEBHOOK_SECRET}
      SMTP_HOST: ${SMTP_HOST}
      SMTP_PORT: ${SMTP_PORT}
      SMTP_USER: ${SMTP_USER}
      SMTP_PASS: ${SMTP_PASS}
      MAIL_TO: ${MAIL_TO}
    ports: ["8081:8081"]
    restart: unless-stopped

  ems-bridge-sms:
    image: python:3.12-slim
    working_dir: /app
    volumes: ["./sms:/app"]
    command: bash -c "pip install -q flask gunicorn aliyun-python-sdk-core && gunicorn -w 1 -b 0.0.0.0:8082 app:app"
    environment:
      EMS_WEBHOOK_SECRET: ${EMS_WEBHOOK_SECRET}
      ALIYUN_SMS_ACCESS_KEY: ${ALIYUN_SMS_ACCESS_KEY}
      ALIYUN_SMS_ACCESS_SECRET: ${ALIYUN_SMS_ACCESS_SECRET}
      ALIYUN_SMS_SIGN_NAME: ${ALIYUN_SMS_SIGN_NAME}
      ALIYUN_SMS_TEMPLATE: ${ALIYUN_SMS_TEMPLATE}
      SMS_TO: ${SMS_TO}
    ports: ["8082:8082"]
    restart: unless-stopped
```

EMS 端在 `/alarm/webhook` **只能配一个 URL**（首版限制），所以三种渠道二选一或外面套一层"扇出"——例：在 nginx / API 网关上把 `/ems/all` 复制到 `/ems/feishu` + `/ems/email` + `/ems/sms`。或客户开发自建一个总桥接，一次收到 EMS 推送后并发往三处。

---

## §8 已知限制与路线图

- **首版只能配 1 个全局 Webhook**：上面"扇出"是临时方案；v2.x 计划支持多端点 + 端点级别级分流（见 [alarm-feature-overview.md §5](./alarm-feature-overview.md)）。
- **首版无严重程度分级**：所有告警 severity = WARNING，桥接侧无法按级别分发到不同收件群 / 不同号码。v1.7+ 路线图含 severity 分级。
- **首版仅 `alarm.triggered`**：不发 `alarm.resolved`，桥接服务收不到"恢复"事件，IM 卡片 / 邮件 / 短信无法标"已恢复"。如需，订阅站内 `/api/v1/alarm/inbox` 轮询。
- **首版无用户级订阅**：所有具备 ADMIN / OPERATOR 角色的用户均收到全量站内通知；外部桥接由桥接代码自行决定收件名单。

---

## §9 相关文档

- Webhook 协议规约（必读）：[alarm-webhook-integration.md](./alarm-webhook-integration.md)
- 告警功能概览：[alarm-feature-overview.md](./alarm-feature-overview.md)
- 告警用户手册：[alarm-user-guide.md](./alarm-user-guide.md)
- 告警 API：[../api/alarm-api.md](../api/alarm-api.md)
- 平台总览：[product-overview.md](./product-overview.md)
