# 采集中断告警 · Webhook 接入指南

> **状态**：占位骨架。**Phase E**（派发器 + 重试 + Adapter 完成时）填充。
> 撰写依据：spec §13 Webhook payload 字段词典 + 对接示例

---

## 1. 适用场景

（待 Phase E 填充：什么时候要接 Webhook、不接的替代方案是什么 — 站内通知）

## 2. 配置 Webhook

（待 Phase E 填充：UI 配置完整流程截图占位）

```
[截图：系统健康 → Webhook 配置页 → 启用开关 / URL / Secret / Adapter / Timeout 表单]
```

字段说明（搬 spec §12 + §16）。

## 3. Payload 完整字段词典

（待 Phase E 填充：搬 spec §13.1 的字段表）

## 4. HTTP Headers

（待 Phase E 填充：搬 spec §13.2，含 HMAC 签名验证伪代码）

## 5. 接收方实现要点

（待 Phase E 填充：4 条核心要求 — 验签 / 幂等 / 路由 / 状态码）

## 6. 对接示例

### 6.1 钉钉自定义机器人
（待 Phase E 填充：转换示例 + 中间适配层架构图）

### 6.2 企业微信群机器人
（待 Phase E 填充）

### 6.3 自定义后端（直接消费）
（待 Phase E 填充：含完整 Python 伪代码示例：验签 + 幂等 + 状态码）

### 6.4 Slack（可选）
（待 Phase E 填充，如时间允许）

## 7. 重试与失败处理

（待 Phase E 填充：3 次重试 + 退避策略、失败后的 delivery log、UI 手动重放）

## 8. 测试 Webhook

（待 Phase E 填充：UI 上"发送测试"按钮的行为、用 ngrok / webhook.site 验证的步骤）

## 9. 安全建议

（待 Phase E 填充：secret 强度 / HTTPS / IP 白名单 / 接收方鉴权）

## 10. 故障排查

（待 Phase E 填充：搬 spec §15.2 的错误前缀对照表 + 接收方排查步骤）

## 11. 新增 Adapter（开发对接）

（待 Phase E 填充：spec §13.4 完整步骤）

---

**Phase E 任务清单**（实施时执行）：
- [ ] Payload 完整字段词典
- [ ] 至少 3 个对接示例（钉钉 / 企微 / 自定义后端），自定义后端含完整代码
- [ ] HMAC 验签代码（多语言：Python/Node/Java 各一段）
- [ ] 故障排查对照表
- [ ] 删除本"任务清单"段
