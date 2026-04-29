# 采集中断告警 · 配置参数参考

> **状态**：占位骨架。**Phase A**（模块骨架与 application.yml 完成时）填充。
> 撰写依据：spec §12 配置参数详解、§16 部署前置

---

## 1. 全局默认配置（application.yml）

（待 Phase A 填充：直接搬 spec §12 的参数表，每行加 1 句调优建议；含 3 个场景的完整 YAML 示例）

参数列表占位：

| 参数 | 默认 | 含义 | 调优建议 |
|------|------|------|---------|
| `ems.alarm.default-silent-timeout-seconds` | 600 | … | … |
| `ems.alarm.default-consecutive-fail-count` | 3 | … | … |
| `ems.alarm.poll-interval-seconds` | 60 | … | … |
| `ems.alarm.suppression-window-seconds` | 300 | … | … |
| `ems.alarm.webhook-retry-max` | 3 | … | … |
| `ems.alarm.webhook-retry-backoff-seconds` | [10,60,300] | … | … |
| `ems.alarm.webhook-timeout-default-ms` | 5000 | … | … |

## 2. 设备级覆盖（运行时配置）

（待 Phase A 填充：通过 UI / API 覆盖单设备阈值的方法、字段含义、留空行为）

## 3. 配置场景示例

（待 Phase A 填充：高可靠工控 / 一般工厂 / 低频采集 三种场景的完整 YAML）

## 4. 修改后的生效方式

（待 Phase A 填充：哪些参数需重启、哪些可热更新；目前首版全部需重启）

## 5. 校验失败处理

（待 Phase A 填充：常见配置错误 → 启动日志 → 修复方法）

---

**Phase A 任务清单**（实施时执行）：
- [ ] 将 spec §12 的参数表完整复制并扩写
- [ ] 加 3 个完整 YAML 场景配置（可直接复制使用）
- [ ] 加生效说明（哪些需重启）
- [ ] 加常见报错对照表
- [ ] 删除本"任务清单"段
