# 告警上线 SOP（5 步打通采集中断告警 + Webhook 推送）

> **场景**：50 块表已通过 [field-installation-sop.md](./field-installation-sop.md) 接通，按 [dashboard-commissioning-sop.md](./dashboard-commissioning-sop.md) 完成看板配置后，本阶段把"断线告警 + 推钉钉/企微"跑起来。
>
> **前置**：
> - `/collector` 4 条通道全 `CONNECTED`、24h 成功率 ≥ 99%
> - `/dashboard` 能看见 50 块表数据
> - 已有 ADMIN JWT Token

---

## 0. 一图看懂告警链路（v1.6.0）

```
ems-collector ─consecutiveErrors─┐
                                 ├─> ems-alarm
ems-meter ─阈值/维护模式─────────┘     │
                                       ├─> 站内通知（铃铛 + 通知抽屉，30s 轮询）
                                       ├─> 全局 Webhook ──┐
                                       └─> 审计日志        │
                                                          ▼
                                              桥接服务（你自建的 Python/Node 小服务）
                                                          │
                                              ┌───────────┴────────────┐
                                              ▼                        ▼
                                          钉钉机器人              企业微信群机器人
```

**v1.6.0 关键限制**（先了解，避免误解）：

| 限制 | 含义 | 替代方案 |
|---|---|---|
| 只有 1 个全局 Webhook | 全场告警共用 1 个 URL | 桥接服务里按 `org_node_id` 分流给多个 IM |
| 只有 `GENERIC_JSON` adapter | 不内置钉钉/企微的 markdown 模板 | 走桥接做格式转换 |
| 没有严重级别（severity） | 全部告警一律 NORMAL | v1.7+ 规划；目前可按 `device_code` 前缀人工区分 |
| 阈值热更新只对设备级 override 生效 | 全局参数改完要重启 ems-app | 大批量调阈值用 override 批量推 |

---

## 1. 步骤 ①：调全局阈值（application.yml）

50 块表 × 60 s 采样周期，推荐配置（写入 `ems-app/src/main/resources/application.yml`）：

```yaml
ems:
  alarm:
    default-silent-timeout-seconds: 300        # 5 分钟无数据 = 5×采集周期，留余量
    default-consecutive-fail-count: 3          # 串口服务器抖一下不会触发
    poll-interval-seconds: 60                  # 每 60 s 扫一次设备状态
    suppression-window-seconds: 300            # 恢复后 5 min 内不重复告警
    webhook-retry-max: 3                       # 失败重试 3 次
    webhook-retry-backoff-seconds: [10, 60, 300]  # 10 s → 1 min → 5 min
    webhook-timeout-default-ms: 5000           # 接收方超时 5 s
```

**改完重启**：`docker compose restart ems-app`，约 10-30 s 后生效。

**验证生效值**：

```bash
TOKEN="<jwt>"
BASE="https://ems.example.com"

curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/alarm-rules/defaults" | jq
# 应返回上面 7 个字段的当前值
```

> 详细参数语义、3 种典型场景配置（高可靠工控 / 默认 / 低频采集）见
> [`docs/product/alarm-config-reference.md §3`](../product/alarm-config-reference.md)。

---

## 2. 步骤 ②（可选）：给关键表加严

50 块表里通常有 **2 块主进线表**（`1F-MAIN` 和 `4F-MAIN`，APM810），它们一断线，整楼层数据就全废。给它们设更严的阈值（不重启）：

```bash
# 假设 1F-MAIN 的 meter id = 5
curl -s -X PUT "$BASE/api/v1/alarm-rules/overrides/5" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{
    "silentTimeoutSeconds": 120,
    "consecutiveFailCount": 2,
    "maintenanceMode": false,
    "maintenanceNote": null
  }'
```

效果：1F-MAIN 静默 2 分钟即告警（普通表是 5 分钟），失败 2 次即告警。60 秒内热生效，不用重启。

**查所有 override**：

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/alarm-rules/overrides" | jq '.data | length'
```

**移除 override**（恢复全局默认）：

```bash
curl -s -X DELETE -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/alarm-rules/overrides/5"
```

---

## 3. 步骤 ③：配 Webhook（推钉钉为例）

### 3.1 先起一个桥接服务

EMS 不直接调钉钉机器人 API（Markdown 格式不通用），需要一个最小的中转服务做格式转换。

**最快方案**：把 [`alarm-bridge-recipes.md`](../product/alarm-bridge-recipes.md) 的 §3（飞书）/ 钉钉模板直接照抄部署，约 50 行 Python + Flask，跑在一台 1C1G 小虚机或函数计算上。

部署后你会拿到一个公网（或内网可达）地址，例如：

```
https://bridge.example.com/webhook/dingtalk
```

### 3.2 配回 EMS

```bash
SECRET="$(openssl rand -hex 32)"   # 32 字节随机密钥
echo "Save this secret, set it in your bridge service env: $SECRET"

curl -s -X PUT "$BASE/api/v1/webhook-config" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{
    \"enabled\": true,
    \"url\": \"https://bridge.example.com/webhook/dingtalk\",
    \"secret\": \"$SECRET\",
    \"adapterType\": \"GENERIC_JSON\",
    \"timeoutMs\": 5000
  }"
```

把 `$SECRET` 设到桥接服务的环境变量 `EMS_WEBHOOK_SECRET`，桥接服务用它验签 `X-EMS-Signature` 头。

### 3.3 测试连通性

```bash
curl -s -X POST "$BASE/api/v1/webhook-config/test" -H "Authorization: Bearer $TOKEN" | jq
```

期望：
- `success: true` + `latencyMs: <数字>` → 桥接服务收到测试 payload，钉钉群里出现一条测试消息
- `success: false` → 看 `message` 排查（多半是 URL 不可达 / 签名密钥不一致 / 桥接 500）

> Webhook payload schema、HMAC 签名计算、重试规约的权威文档：
> [`docs/product/alarm-webhook-integration.md`](../product/alarm-webhook-integration.md)
>
> 桥接服务的现成代码模板：
> [`docs/product/alarm-bridge-recipes.md`](../product/alarm-bridge-recipes.md)

---

## 4. 步骤 ④：端到端试一发真告警

不用等真出故障，**主动制造一次断线**：

```bash
# 1) 找一条 channel id（如 1F-MCC-485 = id 1）
# 2) 把它停掉（在 EMS UI /collector 点删除，或直接关串口服务器电源 5 分钟）

# 3) 等 5-7 分钟（5 min 静默 + 1-2 min 检测周期），观察：
#    - 站内：右上角铃铛出现红色角标，点开看到新告警
#    - 钉钉/企微：群里弹出告警消息
#    - API：
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/alarms?status=ACTIVE" | jq '.data | length'
# 应 ≥ 1（该楼层 12 块表的 12 条 ACTIVE 告警）
```

### 状态机演示

```bash
# 4) 在 UI 上点告警的「确认」按钮（或 API）
ALARM_ID=42
curl -s -X POST "$BASE/api/v1/alarms/$ALARM_ID/ack" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"note": "故意断线测试，已通知现场"}'

# 5) 把串口服务器电源插回
# 6) 等 1-2 个检测周期 → 告警自动 RESOLVED（看 alarmStatus 字段）
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/alarms/$ALARM_ID" | jq '.data.status'
# 期望: "RESOLVED"
```

### 看 Webhook 推送成功率

```bash
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/webhook-deliveries?limit=20" | jq '.data[] | {alarmId, event, status, httpStatus, latencyMs, retryCount}'
```

- `status: SUCCESS` 占多数 → 链路 OK
- `status: FAILED` + `retryCount: 3` → 桥接服务挂了，去看桥接服务日志

---

## 5. 步骤 ⑤：维护模式（停机演练）

模拟车间例行停机：1F 整层临时停电做维护，避免误告警。

```bash
# 给 1F 12 块表全部开维护模式
for METER_ID in 5 6 7 8 9 10 11 12 13 14 15 16; do
  curl -s -X PUT "$BASE/api/v1/alarm-rules/overrides/$METER_ID" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d '{
      "silentTimeoutSeconds": null,
      "consecutiveFailCount": null,
      "maintenanceMode": true,
      "maintenanceNote": "2026-05-15 计划停电维护，预计 4h"
    }' > /dev/null
done
```

效果：这 12 块表的检测全部跳过，停电期间无告警噪音。

维护结束后批量关掉：

```bash
for METER_ID in 5 6 7 8 9 10 11 12 13 14 15 16; do
  curl -s -X DELETE -H "Authorization: Bearer $TOKEN" \
    "$BASE/api/v1/alarm-rules/overrides/$METER_ID" > /dev/null
done
```

> **审计**：每次维护模式开/关都会写审计日志（`/admin/audit`），运营经理可查谁在什么时候改了哪台表，避免"忘记关回来"。

---

## 6. 验收清单（所有项 ✅ 才算上线）

- [ ] `application.yml` 7 个参数已按 60 s 采集周期调整、ems-app 重启后生效
- [ ] `GET /api/v1/alarm-rules/defaults` 返回值与配置一致
- [ ] 关键表（主进线 2 块）已设设备级 override（更严的阈值）
- [ ] 桥接服务部署完成、HMAC 签名验证 OK
- [ ] `POST /api/v1/webhook-config/test` 返回 success，钉钉/企微群收到测试消息
- [ ] **端到端测试**：主动断电 5 min → 站内铃铛响 → 钉钉收到 → 在 UI 点确认 → 来电后 5 min 内自动恢复
- [ ] **维护模式测试**：开启后断线不告警；关闭后立即恢复检测
- [ ] `/api/v1/webhook-deliveries` 最近 20 条全部 SUCCESS
- [ ] `/api/v1/alarms/health-summary` 显示采集健康率 ≥ 99%

---

## 7. 故障速查

| 现象 | 排查路径 |
|---|---|
| 断线 5 min 后没有告警 | ① 看 `defaults` 是否真生效（重启了吗？）② 该表是不是开了 maintenance_mode ③ collector 里这条 channel 的 connState 是不是真的变 ERROR 了（去 `/collector` 看） |
| 站内铃铛响但钉钉没收到 | ① `/api/v1/webhook-deliveries` 看最近一次是不是 FAILED ② 桥接服务是不是真起了：从 EMS 服务器 `curl -X POST <bridge_url>` 试 ③ 桥接服务日志看签名是否验证通过 |
| 钉钉收到了但是乱码 | 桥接服务的格式转换逻辑写错；对照 `alarm-bridge-recipes.md` 的模板 |
| 告警一直触发不恢复 | ① 来电后 collector 是不是真接通了（`/collector` 看 connState）② `suppression-window-seconds` 是不是设太大（恢复需 ≥ 这个时长无失败） |
| 维护期间还是被告警轰炸 | ① override 是否真写成功（`GET /alarm-rules/overrides/{id}` 看 `maintenanceMode: true`）② 60 s 内才生效；刚改的话再等等 |
| 同一断线触发了多条告警 | 正常情况——每块掉线表一条告警。嫌多的话，可以让运维 ack 完一条再等下一条；产品没做"按 channel 聚合" |
| 告警从未自动 RESOLVED | ① 检查 `alarm-detection-rules.md` 的恢复条件 ② 大概率是采集恢复后 `consecutiveErrors` 没归零 → 重启 ems-collector 可清状态 |

---

## 8. 与下一阶段的衔接

告警上线后，路线图上下一站可选：

- **电价 + 内部分摊**：装入 `ems-tariff` 工业分时电价 → `ems-cost` 按面积/产量分摊 → `ems-billing` 出内部账单。把电量变成钱，是 EMS 的商业核心。
- **报表自动化**：`ems-report` 配日/周/月报模板 → 每月 1 号自动 PDF 邮件给厂长。
- **生产能效**：`ems-production` 录入产量 → 计算"单位产品能耗"趋势，节能改造前后有数据可对比。

---

**相关文档**

- 告警产品介绍：[../product/alarm-feature-overview.md](../product/alarm-feature-overview.md)
- 告警用户指南：[../product/alarm-user-guide.md](../product/alarm-user-guide.md)
- 告警配置参考：[../product/alarm-config-reference.md](../product/alarm-config-reference.md)
- 检测算法：[../product/alarm-detection-rules.md](../product/alarm-detection-rules.md)
- Webhook 集成：[../product/alarm-webhook-integration.md](../product/alarm-webhook-integration.md)
- 桥接配方：[../product/alarm-bridge-recipes.md](../product/alarm-bridge-recipes.md)
- 告警 ops runbook：[../ops/alarm-runbook.md](../ops/alarm-runbook.md)
- 选型指南：[meter-selection-guide.md](./meter-selection-guide.md)
- 现场施工 SOP：[field-installation-sop.md](./field-installation-sop.md)
- 看板上线 SOP：[dashboard-commissioning-sop.md](./dashboard-commissioning-sop.md)
- 5 分钟演示：[dashboard-demo-quickstart.md](./dashboard-demo-quickstart.md)
- 账单上线 SOP：[billing-commissioning-sop.md](./billing-commissioning-sop.md)
- 月报自动化 SOP：[report-automation-sop.md](./report-automation-sop.md)
- 生产能效 SOP：[production-energy-sop.md](./production-energy-sop.md)
