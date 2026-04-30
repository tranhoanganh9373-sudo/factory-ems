# 可观测性栈 · 用户指南

> 适用版本：v1.7.0+ ｜ 受众：客户运维 / IT 接口人 / 站点工程师 ｜ 最近更新：2026-04-29

本指南面向**客户侧运维人员（NOC / IT / 现场工程师）**，覆盖在 Grafana 中查看系统健康、解读 SLO、收到告警邮件后如何定位、计划维护期如何暂停告警等日常操作。本文档不涉及 Prometheus / Loki / Tempo 内部管理，那部分由 factory-ems 工程团队负责。

> 您拥有的是 Grafana **viewer（只读）** 角色，可查看所有面向客户的仪表盘，但不能编辑、不能创建静默、不能管理用户。如需扩权，请按 [第 5 节](#5-如何申请-viewer-账号) 流程申请。

---

## 1. 如何登录 Grafana / 如何切换 dashboard

### 1.1 访问入口

| 入口 | 地址 | 说明 |
|------|------|------|
| 直连（站点内网） | `http://<服务器 IP>:3000` | 默认入口，需在内网或 VPN 内 |
| nginx 反向代理（如已配置） | `https://<客户域名>/grafana/` | 客户化域名访问，由实施工程师提供 |

> 截图占位：浏览器访问 `http://<server>:3000`，进入 Grafana 登录页

### 1.2 首次登录流程

1. 访问上述地址，进入登录页。
2. 用户名输入 `admin`（或您的 viewer 账号名）。
3. 密码：
   - **首次部署**：实施工程师交付时已从 `.env.obs` 中导出 admin 初始密码并通过密码管理器或交付文档转交给您。该密码仅打印一次，遗失后须重置。
   - **viewer 账号**：实施工程师在 Grafana → Users 创建并通过安全渠道发送给您。
4. 首次登录会强制要求修改密码，请用密码管理器记录新密码。
5. 登录成功后默认进入 **Home** 页。

> 截图占位：Grafana 登录页，用户名/密码输入框

### 1.3 切换 dashboard 的两种方式

**方式 A：左侧菜单浏览**

1. 点击左上角"汉堡菜单 ☰" → **Dashboards**（仪表盘） → **Browse**（浏览）。
2. 进入 **factory-ems** 文件夹，看到 7 个 dashboard：
   - `factory-ems · SLO Overview`（D1，最常用）
   - `factory-ems · 基础设施`（D2，工程团队）
   - `factory-ems · JVM`（D3，工程团队）
   - `factory-ems · HTTP`（D4，工程团队）
   - `factory-ems · Collector`（D5，业务面）
   - `factory-ems · Alarm`（D6，业务面）
   - `factory-ems · Meter`（D7，业务面）
3. 客户 viewer 角色可访问 **D1 + D5 + D6 + D7**；D2/D3/D4 默认对客户隐藏（参见 §5 权限矩阵）。

**方式 B：URL 直达**

直接在浏览器输入：

| 目标 dashboard | URL 后缀 |
|----------------|---------|
| SLO Overview | `/d/slo-overview/` |
| Collector | `/d/ems-collector/` |
| Alarm | `/d/ems-alarm/` |
| Meter | `/d/ems-meter/` |

> 截图占位：左上角 Browse 菜单展开，列出 factory-ems 文件夹下的 7 个 dashboard

### 1.4 时间范围与自动刷新

每个 dashboard 右上角都有两个控件：

- **时间范围选择器**：默认 `Last 6 hours`。可选 `Last 5 minutes`、`Last 1 hour`、`Last 24 hours`、`Last 7 days`、自定义起止时间等。
- **刷新按钮 / 自动刷新间隔**：默认 30 秒。可手动刷新（圆圈箭头图标），或选择 `5s` / `10s` / `30s` / `1m` / `5m` 自动刷新。

> 建议：日常巡检使用 `Last 6 hours` + `30s 刷新`；事故排查时切换至 `Last 1 hour` 看趋势细节，回顾历史趋势用 `Last 7 days`。

---

## 2. 如何看 SLO 四个数字

### 2.1 SLO Overview 总览

打开 **D1 · SLO Overview**（`/d/slo-overview/`）。最上方有 4 个 stat 面板，对应合同里写的 **4 个 SLO 目标**：

| 面板 | SLO 目标 | 含义 |
|------|----------|------|
| 可用性 (30d) | ≥ 99.5% | 过去 30 天 factory-ems 平均在线时间占比 |
| API p99 (5m) | ≤ 1 秒 | 最近 5 分钟 HTTP 成功请求的 p99 响应时间 |
| 数据新鲜度 (max lag) | ≤ 5 分钟（300 秒） | 所有电表中最大读数滞后秒数 |
| 调度漂移 (max abs) | ≤ 60 秒 | 采集任务实际触发与计划时间偏差（v1 占位，恒为 0） |

> SLO 数学定义、PromQL 表达式与合同写法，参见 [observability-slo-rules.md](./observability-slo-rules.md)。

### 2.2 红 / 黄 / 绿判定

每个 stat 面板根据当前数值显示三色：

| 面板 | 绿色（正常） | 黄色（警告） | 红色（违约风险） |
|------|------------|------------|----------------|
| 可用性 (30d) | ≥ 99.5% | 99.0% ~ 99.5% | < 99.0% |
| API p99 (5m) | < 0.8s | 0.8s ~ 1s | > 1s |
| 数据新鲜度 | < 240s | 240s ~ 300s | > 300s |
| 调度漂移 | < 30s | 30s ~ 60s | > 60s |

> 截图占位：SLO Overview 顶部 4 个 stat 面板，可用性绿色 99.7%、p99 黄色 0.85s、新鲜度绿色 120s、漂移绿色 0s

### 2.3 错误预算剩余 gauge

第 5 个面板 **可用性错误预算剩余** 是一个仪表盘式 gauge，数值在 `0` 到 `1` 之间：

- `1.0` — 本月预算完整未用
- `0.5` — 已用一半（约 1.8 小时不可用）
- `0.0` — 本月 SLO 已违约

**为什么重要**：99.5% 可用性允许的 30 天总不可用时间 = `30 × 24 × 60 × 0.005 ≈ 216 分钟（约 3.6 小时）`。错误预算告诉您还有多少"余额"可用于计划维护或意外故障。

**使用建议**：

| 预算余量 | 行动建议 |
|---------|---------|
| > 70% | 充裕，可正常安排升级、变更 |
| 30% ~ 70% | 中等，谨慎评估变更影响 |
| < 30% | 告急，冻结非紧急变更 |
| 0% | 当月已违约，需故障复盘 + 厂方沟通 |

### 2.4 燃烧率与 firing 告警表

- **可用性燃烧率 (1h vs 6h) timeseries**：1 小时燃烧率（红线）和 6 小时燃烧率（橙线），与快速/慢速告警阈值参考线对比。短窗口飙升说明突发故障；长窗口持续高位说明慢速恶化。
- **当前 firing 告警 table**：列出 Prometheus 当前处于触发状态的告警，显示 `alertname` / `severity` / `team` / `instance`。空表 = 当前无活跃告警。

---

## 3. 如何在收到告警邮件后定位

### 3.1 告警邮件长什么样

每封告警邮件来自 Alertmanager，主要字段：

| 字段 | 含义 | 示例 |
|------|------|------|
| Subject（主题） | `[FIRING:1] <alertname> <severity>` | `[FIRING:1] EmsDataFreshnessCritical critical` |
| Severity | `critical`（红） / `warning`（黄） | critical |
| Summary | 告警一句话描述 | "电表读数滞后超过 10 分钟" |
| Description | 触发条件详情，含触发时刻 PromQL 当前值 | "max lag = 723s（阈值 600s）" |
| `runbook_url` | 跳转到工程团队 runbook 的链接 | `https://internal/docs/ops/observability-runbook.md#emsdatafreshnesscritical` |

> Critical 级别同时发送邮件 + 钉钉 + 企微 + 通用 webhook（如已配置）；warning 级别仅发邮件。详见 [observability-slo-rules.md §6.2](./observability-slo-rules.md)。

### 3.2 5 步定位流程

**第一步：点开 runbook 链接**

邮件正文末尾有 `runbook_url`。点击后跳转到 [observability-runbook.md](../ops/observability-runbook.md) 的对应章节，里面有该条告警的「症状 → 排查 → 修复」全流程。这是工程团队维护的标准操作手册。

**第二步：打开 SLO Overview 看趋势**

进入 D1 dashboard，将时间范围切到 `Last 1 hour`，观察对应 SLO 是否同步劣化：

- `EmsAppDown` / 燃烧率告警 → 看 **可用性 30 天趋势**
- `EmsAppLatencyHigh` → 看 **API p99 (5m)**
- `EmsDataFreshnessCritical` → 看 **数据新鲜度**

**第三步：下钻到对应 dashboard**

| 告警类型 | 下钻 dashboard |
|---------|---------------|
| `EmsAppDown` / `EmsDiskSpaceCritical` / 燃烧率 | D2 基础设施（工程团队） |
| `EmsAppLatencyHigh` / `EmsDbConnectionPoolExhausted` / `EmsJvmMemoryHigh` | D3 JVM / D4 HTTP（工程团队） |
| `EmsCollectorPollSlow` / `EmsCollectorOfflineDevices` | D5 Collector |
| `EmsAlarmDetectorSlow` / `EmsWebhookFailureRate` / `EmsAlarmBacklog` | D6 Alarm |
| `EmsDataFreshnessCritical` | D7 Meter |

> 客户 viewer 角色无 D2/D3/D4 访问权限。若告警指向基础设施，跳到第五步联系工程团队。

**第四步：用 Loki Explore 查日志上下文**

1. 左侧菜单 → **Explore**（探索）。
2. 数据源切换到 **Loki**。
3. 输入查询语句：

```
{container_name="factory-ems"} |= "error"
```

该查询返回最近 factory-ems 容器中包含 `error` 关键字的日志。可进一步加 `traceId="..."` 关联到具体追踪。

> 业务模块还可改成 `{container_name="factory-ems"} |= "ERROR" |~ "Alarm|Collector"` 缩小范围。

**第五步：升级到工程团队**

如告警 5 分钟内未自动恢复，或 dashboard 读数无法解释，按以下渠道升级：

| 严重度 | 升级路径 |
|-------|---------|
| critical | 立即电话 + 钉钉 @ on-call 工程师，提供告警截图 + dashboard 截图 |
| warning | 在 24h 内通过工单系统提交，附 alertname + 起始时刻 |

---

## 4. 如何在维护期暂停告警

计划停机、版本升级、网络改造等期间，应**提前**为对应实例创建告警静默（Silence），避免误报洪泛。

### 4.1 方法 A：amtool 命令行（推荐）

amtool 是 Alertmanager 官方 CLI，部署在观测栈服务器上。

```bash
# 创建一个 2 小时的静默，覆盖所有 factory-ems 告警
amtool silence add \
  --alertmanager.url=http://localhost:9093 \
  --duration=2h \
  --comment="计划维护：升级 v1.7.0 by 张三" \
  alertname=~"Ems.*" job="factory-ems"

# 仅静默某个具体告警 + 特定实例
amtool silence add \
  --alertmanager.url=http://localhost:9093 \
  --duration=1h \
  --comment="prod-02 硬件更换" \
  alertname="EmsAppDown" instance="prod-02"

# 查看当前静默列表
amtool silence query --alertmanager.url=http://localhost:9093

# 提前结束某个静默（替换 <silence-id>）
amtool silence expire --alertmanager.url=http://localhost:9093 <silence-id>
```

### 4.2 方法 B：Grafana UI（可视化）

1. 左侧菜单 → **Alerting**（告警） → **Silences**（静默）。
2. 点击右上角 **New silence**（新建静默）。
3. 在 **Matchers**（匹配器）区域填入标签条件，例如 `alertname=EmsAppDown`、`job=factory-ems`。
4. 设置 **Start**（开始时间）和 **Duration**（时长，如 `2h`）。
5. 在 **Comment**（备注）填写：`YYYY-MM-DD HH:mm-HH:mm + 操作人 + 原因`，例如 `2026-04-29 22:00-24:00 张三 升级 v1.7.0`。
6. 点击 **Submit** 保存。

> 截图占位：Grafana Alerting → Silences → New silence 表单

### 4.3 时机与注意事项

- **建议**：维护开始前 **5 分钟** 创建，避免脚本启动瞬间触发短促告警。
- **建议**：维护结束后**立即 expire**，不要等到自然过期，以免真实故障被掩盖。
- **建议**：静默时长不超过维护窗口 + 30 分钟缓冲。
- **重要**：观测栈的 silence **不抑制 ems-alarm 业务告警**（采集中断告警）。如果维护涉及电表/采集器停机，请同时在 EMS 应用内开启**设备维护模式**（参见 [alarm-user-guide.md §3.3](./alarm-user-guide.md)）。
- **重要**：基于 `EmsAppDown` 的抑制规则会在 EmsAppDown 触发时自动屏蔽同实例的下游告警，但维护期手动 silence 仍是更可靠的做法。

---

## 5. 如何申请 viewer 账号

### 5.1 权限矩阵（来自 spec §15）

| 操作 | ADMIN | OPERATOR | VIEWER（客户） | 工程团队 |
|------|:---:|:---:|:---:|:---:|
| 看 SLO Overview（D1） | ✅ | ✅ | ✅ | ✅ |
| 看业务 dashboard（D5 Collector / D6 Alarm / D7 Meter） | ✅ | ✅ | ✅ | ✅ |
| 看 Infra/JVM/HTTP dashboard（D2/D3/D4） | ❌ | ❌ | ❌ | ✅ |
| 编辑 dashboard | ❌ | ❌ | ❌ | ✅（Editor） |
| Alertmanager 创建 silence | ❌ | ❌ | ❌ | ✅ |
| Grafana 用户管理 | ❌ | ❌ | ❌ | ✅（Admin） |

> v1 阶段 Grafana 用户体系**独立于** ems-auth，不打通 SSO。客户 IT/运维只能拿 viewer 账号。商业化阶段（Plan #6）后将接入 SSO。

### 5.2 申请流程

1. 客户 IT 接口人通过工单系统或邮件联系 factory-ems 部署/实施工程师。
2. 提供：申请人姓名 / 邮箱 / 用途（巡检 / 月度回顾 / 应急排查）。
3. 部署工程师在 Grafana → Users 创建 viewer 账号，初始密码通过密码管理器或加密邮件发送。
4. 申请人首次登录强制修改密码，妥善保管。

### 5.3 viewer 角色可与不可

**可以**：

- 浏览所有客户面 dashboard（D1 SLO Overview + D5 Collector + D6 Alarm + D7 Meter）
- 调整时间范围、自动刷新间隔
- 在 Explore 中查询 Loki 日志
- 截图、导出 PDF / CSV 给客户管理层

**不可以**：

- 编辑、创建、删除 dashboard
- 创建、修改、解除 Alertmanager silence
- 创建、删除其他用户
- 查看工程团队专属的 D2/D3/D4 基础设施仪表盘
- 直接修改告警阈值（需联系工程团队修改 `prometheus/rules/*.yml`）

> 工程团队 Editor / Admin 角色账号**不暴露给客户**，所有需要管理动作的请求统一通过工程团队执行。

---

## 6. FAQ

### Q1：没收到告警邮件，第一步看哪？

按以下顺序排查：

1. **邮箱垃圾箱 / 拦截规则**：Alertmanager 邮件常因来源 IP 不熟被识别为垃圾邮件。
2. **SMTP 配置**：联系工程团队检查 `OBS_SMTP_HOST` / `OBS_SMTP_USER` / `OBS_SMTP_PASSWORD` 是否正确，必要时让工程团队在 `obs-smoke` 中跑一次测试发送。
3. **接收人地址**：确认 `OBS_ALERT_RECEIVER_EMAIL` 列表中是否包含您的邮箱。
4. **告警是否真触发**：在 Grafana → Alerting → Alert rules 查看 `Firing` 状态。如未触发，则没邮件是正常的。

### Q2：告警 runbook 链接打不开怎么办？

`runbook_url` 默认指向内网地址（如 `https://internal/...`）。客户网络可能无法直连。请：

1. 联系工程团队获取 runbook PDF 或公开镜像地址。
2. 或者直接在 git 仓库中查看 `docs/ops/observability-runbook.md`。
3. v1.8 计划提供 runbook 的客户化访问入口。

### Q3：dashboard 数据为空，是系统坏了吗？

不一定。可能原因：

- **时间范围不对**：右上角时间窗口可能被切到了过去无数据的区间。
- **数据源未连通**：左下角数据源图标若标红，联系工程团队检查 Prometheus / Loki 容器状态。
- **抓取目标 down**：D2 基础设施面板的"运行容器数" stat 异常说明栈本身故障。
- **客户角色限制**：D2/D3/D4 对 viewer 角色不可见，看到 "Access denied" 是预期行为，不是故障。

### Q4：告警出现误报怎么处理？

误报 = 告警触发但实际无业务影响。处置：

1. **不要立刻关静默**——先记录告警时刻、当时业务现象。
2. 24 小时内提交工单，附 alertname + 起止时刻 + 您的判断（为什么是误报）。
3. 工程团队收到反馈后会调整 PromQL 阈值或 `for:` 时间窗，再走 promtool 测试 + CI 验证流程。
4. 阈值改动需重启 Prometheus 才能生效，工程团队会安排在低峰期变更。

### Q5：维护期间的告警还会进 email / 钉钉吗？

**已 silence 的告警不会**。Alertmanager 在评估时会检查每条告警是否被 silence 命中——命中则不发送任何通知，但 Prometheus 内部仍记录告警状态。Silence 过期后，若告警仍处于 firing，会重新发送通知。

**注意**：观测栈 silence 不影响 ems-alarm 业务告警。后者由 EMS 应用自身的"维护模式"开关控制，参见 [alarm-user-guide.md §3.3](./alarm-user-guide.md)。

### Q6：我能改告警阈值吗？

**viewer 角色不能。** 阈值定义在 Prometheus 规则文件 `ops/observability/prometheus/rules/*.yml` 中。如需调整：

1. 提工单说明：哪条 alertname、当前阈值、希望调整为多少、业务理由。
2. 工程团队评估后修改文件、跑 promtool 测试、CI 验证、低峰期重启 Prometheus。
3. 整个流程通常 1-3 个工作日。

### Q7：SLO 数字跌破目标怎么办？

跌破 ≠ 立即报警，但意味着错误预算正在快速消耗：

1. **可用性 < 99.5%**：触发 `EmsBudgetBurnSlowAvailability`（warning）后会有邮件通知；持续恶化触发 `EmsBudgetBurnFastAvailability`（critical）需立即响应。
2. **p99 > 1s**：触发 `EmsAppLatencyHigh`（warning）。
3. **数据新鲜度 > 300s**：尚未触发告警，需到 600s（10 分钟）才触发 `EmsDataFreshnessCritical`。
4. **应对动作**：跟随对应告警邮件 runbook 流程；本月若多次跌破，月底召开 SLO 复盘会，与工程团队共同确认根因与改进项。

### Q8：观测栈告警与采集中断告警（ems-alarm）有什么不同？

| 维度 | 观测栈告警（本文档） | ems-alarm 业务告警 |
|------|--------------------|---------------------|
| 关注对象 | 应用 / JVM / 数据流 / 资源（系统侧） | 单设备数据是否中断（业务侧） |
| 触发条件 | Prometheus PromQL 评估 | 数据库扫描 SILENT_TIMEOUT / CONSECUTIVE_FAIL |
| 通知通道 | Alertmanager → 邮件 / 钉钉 / 企微 / 通用 webhook | EMS 应用 → 站内铃铛 + 客户配置的 webhook |
| 受众 | 客户运维 / SRE / 工程值班 | 客户操作员 / 现场维护 |
| 维护模式 | Alertmanager Silence | EMS 应用设备级维护开关 |
| 文档 | 本文档 + [observability-slo-rules.md](./observability-slo-rules.md) | [alarm-user-guide.md](./alarm-user-guide.md) |

> 同一类问题可能同时触发两边告警（如设备掉线 → ems-alarm 触发 SILENT_TIMEOUT，同时 collector poll 失败率上升触发 EmsCollectorOfflineDevices）。请按各自的处置流程分别响应。

---

## 7. 术语表

| 术语 | 解释 |
|------|------|
| **Dashboard（仪表盘）** | Grafana 中的一个完整页面，由多个面板组成，例如 "SLO Overview"、"Collector"。 |
| **Panel（面板）** | Dashboard 内单个图表或数字卡片，如"可用性 (30d)"是一个 stat 面板。 |
| **Datasource（数据源）** | Grafana 后端连接的数据系统，本栈包含 Prometheus（指标）、Loki（日志）、Tempo（追踪）三个数据源。 |
| **SLO（Service Level Objective）** | 服务级别目标，对外承诺的质量指标，例如可用性 ≥ 99.5%。 |
| **SLI（Service Level Indicator）** | 服务级别指标，对应 SLO 的具体度量值，由 PromQL 实时计算（如 `ems:slo:availability:sli_30d`）。 |
| **Error Budget（错误预算）** | SLO 允许出错的剩余余量，0 = 已违约，1 = 完整未用。 |
| **Critical / Warning** | 告警严重级别。Critical 需 5 分钟内响应（多通道通知），Warning 需 24 小时内处理（仅邮件）。 |
| **Silence（静默）** | 在 Alertmanager 中临时屏蔽特定告警的通知，常用于计划维护期。 |
| **Loki Query** | Loki 的日志查询语言（LogQL），用于从聚合日志中过滤关键字、时间窗等。本指南只涉及简单 `{label="value"} \|= "keyword"` 查询。 |
| **PromQL** | Prometheus 的查询语言，用于计算指标。普通 viewer 不需要直接写，但需理解告警阈值条件由它表达。 |
| **Adapter（适配器）** | 采集器（Collector）针对不同设备协议的实现，如 modbus、opc-ua。`EmsCollectorPollSlow` 告警按 adapter 区分。 |
| **Collector cycle（采集周期）** | 采集器对一台设备的一次完整 poll 操作，从发起请求到收到响应或超时。 |
| **p95 / p99 latency** | 把所有请求按响应时间排序，p95 是最快的 95% 请求的最长耗时，p99 同理。p99 描述"最慢的 1% 请求"上限。 |
| **Burn rate（燃烧率）** | 错误预算消耗速度。1× = 正常速度；14.4× 表示当前 1 小时不可用率达正常预算的 14.4 倍，约 2 天耗尽月预算。 |

---

## 相关文档

- [可观测性栈功能概览](./observability-feature-overview.md) — 销售/客户视角的价值主张
- [可观测性栈配置参考](./observability-config-reference.md) — 系统管理员配置详解
- [可观测性栈 SLO 与告警](./observability-slo-rules.md) — 4 SLO + 16 告警客户视角
- [可观测性栈 Metrics 字典](./observability-metrics-dictionary.md) — 指标定义与 PromQL
- [可观测性栈 Dashboard 使用指南](./observability-dashboards-guide.md) — 7 dashboard 用法
- [部署文档](../ops/observability-deployment.md) — 工程团队部署指引
- [运维 Runbook](../ops/observability-runbook.md) — 工程团队 16 告警一键处置
- [采集中断告警 · 用户指南](./alarm-user-guide.md) — 业务告警操作手册
