# 采集协议使用指南

> **适用版本**：v1.1（CP-Phase 1-9 完成时）
> **受众**：现场运维 / 工程实施 / 客户管理员
> **配套**：API 集成请改读 [docs/api/collector-api.md](../api/collector-api.md)；OPC UA 证书运维参考 [docs/ops/opcua-cert-management.md](../ops/opcua-cert-management.md)。

---

## 1. 概述

factory-ems 采集器支持 5 种协议接入工业仪表与边缘消息系统：

| 协议 | 类型 | 典型场景 |
|---|---|---|
| `MODBUS_TCP` | 拉取（轮询） | 以太网网关下挂的电表 / 多功能仪表 |
| `MODBUS_RTU` | 拉取（轮询） | 通过串口转换器的旧式现场仪表 |
| `OPC_UA` | 拉取 / 订阅 | PLC、DCS、新型测控网关 |
| `MQTT` | 推送（事件） | 物联网网关、车间 MES 总线 |
| `VIRTUAL` | 模拟 | 测点未到货 / 演示 / 占位 |

所有协议在前端 `/collector` 页通过统一的 **ChannelEditor Drawer** 配置；保存后由后台 `ChannelService` 自动启动 Transport，并在 5 秒内出现在实时状态表中。

---

## 2. 协议选择决策表

| 现场情况 | 推荐协议 | 备注 |
|---|---|---|
| 仪表自带以太网口 + 暴露 Modbus TCP 502 端口 | `MODBUS_TCP` | 最常见；轮询 5 s 起步 |
| 仪表只有 RS-485；通过串口服务器转换 | `MODBUS_RTU` | 选好波特率 / 校验位 / 站号 |
| PLC 已开放 OPC UA endpoint | `OPC_UA`（Read 模式） | v1.1 仅支持 SecurityMode=NONE 端到端可用 |
| 边缘网关把数据推到 MQTT broker | `MQTT` | broker 单向上报，无需 EMS 反向连仪表 |
| 新车间硬件未到货 / 内部演示 | `VIRTUAL` | 4 种模拟模式，下游链路一并联调 |

> **`OPC UA` 订阅模式（SUBSCRIBE）已在数据模型中保留**，但 v1.1 transport 启动链路尚未完全打通；若现场需要订阅模式请评估升级到 v2 或临时回退到 `READ` 模式。

---

## 3. 操作 SOP：在 /collector 页配置一条新通道

### 3.1 入口

1. 以 **ADMIN** 角色登录 EMS（`/login`）
2. 进入左侧菜单 **「数据采集」**（路由 `/collector`）
3. 实时表上方点击 **「新增通道」** → 弹出右侧 Drawer

### 3.2 通用字段（所有协议共有）

| 字段 | 必填 | 说明 |
|---|---|---|
| 通道名称 | 是 | 业务名，最多 128 字符；保存后**协议字段不可修改** |
| 协议 | 是 | 5 选 1，决定下方动态表单 |
| 描述 | 否 | 自由文本，便于交接 |

### 3.3 协议特定字段 → 测试连接

填完表单 → 点击 Drawer 顶部 **「保存」** → 表格刷新后该行出现「测试」「重连」「编辑」「详情」按钮。

- **「测试」**：同步测试连接，立即给出 toast（成功 / 失败 + 延迟毫秒）。**不会**修改运行时状态。
- **「重连」**：触发该 channel 的 transport 重启；适用于网络瞬断后无法自愈的场景。
- **「详情」**：打开 ChannelDetailDrawer，查看 24 h 成功率、最近一次错误、协议元信息（OPC UA 显示 subscriptionId、MQTT 显示 brokerVersion 等）。

---

## 4. 各协议参数详解

### 4.1 MODBUS_TCP

| 字段 | 默认 | 说明 |
|---|---|---|
| 主机 | — | 仪表 IP 或主机名 |
| 端口 | 502 | TCP 端口；标准 Modbus 用 502 |
| 从站 ID | 1 | unitId，0–247 |
| 轮询间隔 | PT5S | ISO-8601 Duration；最小建议 1 s |
| 测点列表 | — | 至少 1 行：Key + 地址（40001 形式）+ 数据类型（U16/I16/U32/I32/F32）+ 倍率/偏移/单位 |

### 4.2 MODBUS_RTU

| 字段 | 默认 | 说明 |
|---|---|---|
| 串口 | — | `/dev/ttyUSB0`（Linux）或 `COM3`（Win） |
| 波特率 | — | 1200 起步；常见 9600 / 19200 / 38400 |
| 数据位 / 停止位 / 校验位 | — | 默认 8 / 1 / N |
| 从站 ID | 1 | 串口共享时区分多个仪表 |
| 轮询间隔 | — | 同 TCP；RTU 通常更慢，建议 ≥ 2 s |
| 测点列表 | — | 与 TCP 同结构 |

### 4.3 OPC UA

| 字段 | 默认 | 说明 |
|---|---|---|
| Endpoint URL | — | `opc.tcp://host:4840/...` |
| 安全模式 | NONE | NONE / SIGN / SIGN_AND_ENCRYPT；**v1.1 仅 NONE 端到端可用** |
| 证书引用 / 证书密码引用 | — | secret://opcua/<channel>.pfx 形式（v2 启用） |
| 用户名 / 密码引用 | — | 仅 SecurityMode≠NONE 时必填 |
| 轮询间隔 | — | 仅 READ 模式必填；全 SUBSCRIBE 模式可空 |
| 测点列表 | — | Key + nodeId（`ns=2;s=Channel1.Tag1`） + 模式（READ/SUBSCRIBE） + 采样间隔（ms） |

### 4.4 MQTT

| 字段 | 默认 | 说明 |
|---|---|---|
| Broker URL | — | `tcp://host:1883` 或 `ssl://host:8883` |
| Client ID | — | 全局唯一；建议 `ems-<env>-<channel>` |
| 用户名 / 密码引用 | — | secret:// 形式 |
| TLS CA 证书引用 | — | 仅 ssl:// 时必填 |
| QoS | 1 | 0 / 1（v1 不支持 2） |
| Clean Session | true | 默认每次重连重订阅 |
| Keep Alive | PT60S | 与 broker 协商 |
| 测点列表 | — | Key + Topic（支持 `+` 单层、`#` 多层通配） + JsonPath（`$.value`） + 时间戳 JsonPath（可空，缺省取消息到达时间） |

### 4.5 VIRTUAL

| 字段 | 默认 | 说明 |
|---|---|---|
| 轮询间隔 | PT1S | 越短数据越密；测试场景常用 PT1S–PT5S |
| 测点列表 | — | Key + 模式 + 单位 + JSON 参数（参数随模式变化，见下表） |

**4 种模式 + 参数：**

| 模式 | 参数（params JSON） | 行为 |
|---|---|---|
| `CONSTANT` | `{"value": 42}` | 每次输出固定值 |
| `SINE` | `{"amplitude": 10, "periodSec": 60, "offset": 0}` | 正弦曲线 |
| `RANDOM_WALK` | `{"start": 50, "step": 0.5}` | 随机游走，便于压测 |
| `CALENDAR_CURVE` | （内部曲线） | 工作日 / 周末分两条曲线，模拟工厂负荷 |

> 所有 VIRTUAL 采集生成的 `Sample` 自动带 tag `virtual=true`，下游告警 / 对账可按需过滤。

---

## 5. 凭据管理（secret://）

OPC UA / MQTT 配置中所有 `*Ref` 字段都写 **`secret://path`** 形式的引用，不直接写明文：

1. 在 `ChannelEditor` 中点击对应字段旁的 **「修改」** 按钮 → 弹 Modal 输入明文
2. 后端 `POST /api/v1/secrets` 保存到本地 `~/.ems/secrets/` 目录（mode 600）
3. 表单回填的是 `secret://opcua/cert-line1` 之类的引用串
4. 通道运行时由 `FilesystemSecretResolver` 解析

详细 API 见 [docs/api/collector-api.md §3](../api/collector-api.md)。

---

## 6. 故障排查 FAQ

| 现象 | 可能原因 | 处置 |
|---|---|---|
| 「测试」按钮显示 `Connection refused` | 防火墙 / 端口未开 | 在仪表侧网络上 `telnet host port` 验证 |
| Modbus TCP 状态长期 `CONNECTING` | TCP 握手成功但读寄存器超时 | 减少测点 / 增大 timeout / 检查 unitId |
| OPC UA 报 `Untrusted server certificate` | 服务器证书未在 trusted 目录 | 见 [opcua-cert-management.md](../ops/opcua-cert-management.md)（v1.1 仅 NONE 模式可用，非 NONE 暂未端到端打通） |
| MQTT 状态 CONNECTED 但 24 h 成功 = 0 | Topic 通配不匹配 / JsonPath 不命中 | 在 broker 侧 `mosquitto_sub -t '#'` 抓真实 payload；用 jsonpath.com 校验 |
| VIRTUAL 通道一直 ERROR | params JSON 解析失败 | Drawer 中 params 必须是合法 JSON；SINE 必须有 amplitude+periodSec |
| 状态卡在 ERROR 不重试 | v1.1 Modbus 暂未实现自动 reconnect 退避 | 点击「重连」按钮手动触发；持续失败请检查仪表 |

---

## 7. 已知限制（v1.1）

为避免误用，以下能力在 v1.1 **暂未实装**，将在 v2 评估：

- **OPC UA SecurityMode `SIGN` / `SIGN_AND_ENCRYPT`**：枚举与字段已就位，但 transport 启动期对客户端证书加载未完整接入（Phase 5 Task 5.5 推迟）
- **OPC UA 证书审批 REST API**：spec §6.2 描述的 `POST /api/v1/collector/{id}/trust-cert` 端点未实装；需手工把服务器证书放入 `trusted/` 目录
- **Modbus 自动重连退避**：连接失败后不会自动重试；需点「重连」按钮或重启服务
- **MQTT QoS 2**：仅支持 QoS 0 / 1
- **通道删除按钮**：v1.1 UI 暂无删除按钮，需通过 API（`DELETE /api/v1/channel/{id}`）

---

## 8. 相关链接

- API 参考：[docs/api/collector-api.md](../api/collector-api.md)
- OPC UA 证书运维：[docs/ops/opcua-cert-management.md](../ops/opcua-cert-management.md)
- 旧版 Modbus runbook（plan 1.5.1）：[docs/ops/collector-runbook.md](../ops/collector-runbook.md)
- 设计文档：`docs/superpowers/specs/2026-04-30-collector-protocols-design.md`
