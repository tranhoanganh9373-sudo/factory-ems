# 产品说明书目录

> **受众**：最终用户、客户实施人员、销售技术支持、撰写产品说明书的 AI。
> **不要写**：实现细节、代码、内部架构（这些在 `docs/superpowers/` 与 `docs/ops/`）。

## 写作约定

- **从用户视角**：从"管理员要做什么"、"操作员看到什么"出发，不从"系统怎么实现"出发
- **场景化**：每个功能配 1-2 个真实使用场景（如"想给某台设备单独设置 30 秒超时"）
- **截图占位**：截图后续补，先用 `[截图：...]` 占位描述要拍什么
- **避免技术黑话**：不写 "JPA Entity"、"@Async"、"HMAC"，要写 "数据表"、"后台异步处理"、"消息签名"
- **每个文档独立可读**：不假设读者读过其他文档，必要的术语在该文档内首次出现处简注

## 文档清单

### 子项目：采集中断告警（ems-alarm，2026-04-29 起）

| 文档 | 受众 | 范围 | Phase 负责人 |
|------|------|------|-------------|
| [alarm-feature-overview.md](./alarm-feature-overview.md) | 销售/客户 | 功能概览 + 价值主张 + 适用场景 | Phase H |
| [alarm-config-reference.md](./alarm-config-reference.md) | 实施工程师 | 全部配置参数 + 调优建议 | Phase A |
| [alarm-data-model.md](./alarm-data-model.md) | 实施/数据分析 | 5 张表的字段含义 + 业务含义 | Phase B |
| [alarm-business-rules.md](./alarm-business-rules.md) | 客户/实施 | 业务规则（状态机 + 抑制窗口 + 维护模式） | Phase C |
| [alarm-detection-rules.md](./alarm-detection-rules.md) | 客户/实施 | 检测口径（什么时候触发、什么时候不触发） | Phase D |
| [alarm-webhook-integration.md](./alarm-webhook-integration.md) | 客户开发对接 | Webhook 接入指南（含钉钉/企微/自定义示例） | Phase E |
| [alarm-user-guide.md](./alarm-user-guide.md) | 最终用户 | 操作手册（管理员视角 + 操作员视角） | Phase G |

> Phase F 的 API 规约写到 [`docs/api/alarm-api.md`](../api/alarm-api.md)（开发对接文档）。

## 与 docs/ops 的边界

- `docs/ops/alarm-runbook.md` —— 运维场景（排查、备份、监控接入），由 Phase H 完成
- `docs/product/` —— 产品功能说明，从用户能做什么出发

二者互不复制内容，互相 link。
