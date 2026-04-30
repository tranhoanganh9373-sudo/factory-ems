# API 文档目录

> **受众**：第三方系统集成开发、客户开发对接、撰写 API 参考手册的 AI。
> **不要写**：UI 操作、运维流程（这些在 `docs/product/` 与 `docs/ops/`）。

## 写作约定

- **OpenAPI 风格**：每个端点列出 method / path / 鉴权 / 请求参数 / 请求体 / 响应 / 错误码
- **可复制 curl 示例**：每个端点附 1-2 个能直接 copy-paste 的 curl 命令
- **示例 JSON**：用合成数据，不能含真实生产值
- **响应字段词典**：每字段含义 + 类型 + 是否可空 + 示例值

## 文档清单

| 文档 | 范围 | Phase 负责人 |
|------|------|-------------|
| [alarm-api.md](./alarm-api.md) | 采集中断告警 16 个端点完整规约 | Phase F ✅ |
| [observability-metrics-api.md](./observability-metrics-api.md) | factory-ems metrics 抓取协议 + 17 业务指标速查 + 自定义集成示例 | Phase G ✅ |

## 通用约定

所有 API 沿用既有 EMS 平台规范：
- **鉴权**：JWT Bearer Token，header `Authorization: Bearer <token>`
- **响应封装**：`{"success": boolean, "data": T \| null, "errorMsg": string \| null}`
- **分页**：1-indexed，`page` 从 1 开始；响应含 `{ items, total, page, size }`
- **时间格式**：ISO8601 含时区，如 `2026-04-29T08:15:30+08:00`
- **错误码**：HTTP status + `errorMsg` 中文消息（前端 Toast 直显）
