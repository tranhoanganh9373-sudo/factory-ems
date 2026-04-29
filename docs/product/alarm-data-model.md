# 采集中断告警 · 数据模型说明

> **状态**：占位骨架。**Phase B**（实体 + Repository 完成时）填充。
> 撰写依据：spec §2 数据模型 + Phase A migration 落地后的实际 schema

---

## 1. 表关系总览图

（待 Phase B 填充：5 张表 + 与既有 `meters` / `users` 表的关联，ASCII 或 Mermaid 图）

## 2. `alarms` —— 告警事件主表

（待 Phase B 填充：每字段含义 + 取值范围 + 与状态机的关系）

| 字段 | 类型 | 含义 | 业务规则 |
|------|------|------|---------|
| `id` | BIGSERIAL | 告警唯一 ID | 自动生成，不可修改 |
| `device_id` | BIGINT | 关联 meters.id | … |
| `device_type` | VARCHAR(32) | … | 首版仅 METER |
| ... | | | |

## 3. `alarm_rules_override` —— 设备级阈值覆盖

（待 Phase B 填充）

## 4. `webhook_config` —— Webhook 配置

（待 Phase B 填充：含 secret 字段的敏感性说明）

## 5. `webhook_delivery_log` —— 派发流水

（待 Phase B 填充：保留期建议、何时可清理）

## 6. `alarm_inbox` —— 站内收件箱

（待 Phase B 填充：与 `users` 的关联、已读/未读）

## 7. 数据生命周期

（待 Phase B 填充：每张表的保留策略；首版"永久保留"，但建议商业化后加归档机制）

## 8. 数据查询常见场景

（待 Phase B 填充：最常用 5-10 条 SQL，含解释。例：当前所有 ACTIVE 告警按设备分组、最近 7 天告警次数 Top 10 等）

---

**Phase B 任务清单**（实施时执行）：
- [ ] 表关系图（参考其他 ops runbook 风格）
- [ ] 5 张表的完整字段词典（参考 spec §2，扩写业务含义）
- [ ] 数据生命周期建议
- [ ] 5-10 条业务 SQL 示例
- [ ] 删除本"任务清单"段
