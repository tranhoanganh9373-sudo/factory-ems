# 看板演示 5 分钟快速上手

> **场景**：采购前 / 项目启动会 / 客户参观，需要让人在 5 分钟内看到完整 EMS 看板效果——不需要任何真实硬件、不需要等楼层底图、不需要布线。
>
> **依赖**：本机有 `docker` + `git` + JDK 21 + Maven wrapper（`./mvnw`）即可。

---

## 1. 一条命令拉起来

```bash
./scripts/demo-up.sh
```

这条脚本做 3 件事（约 3-5 分钟）：

1. 起 PostgreSQL + InfluxDB 容器（`docker-compose.dev.yml`）
2. 跑 `tools/mock-data-generator`，灌 1 个月模拟数据
3. 起 ems-app + 前端 + nginx

完成后控制台会打印登录信息和访问地址。

> 想要更大规模演示：`EMS_SCALE=medium ./scripts/demo-up.sh`（120 块表 / 3 个月数据）
> 关闭：`docker compose -f docker-compose.yml -f docker-compose.dev.yml down`

---

## 2. 演示数据是什么

mock-data-generator 默认 `small` 规模灌：

| 维度 | 数量 |
|---|---|
| Org-tree 节点 | 1 工厂 → 4 车间（冲压/焊接/涂装/总装）→ 20 工序 + 1 公用动力区 |
| Meters | 20 块（电/水/蒸汽混合）|
| 时序数据 | 1 个月分钟级 + 小时/天/月汇总 |
| 电价方案 | 2 套（峰平谷 + 单一价）|
| 班次 | 早/中/夜 3 班（夜班跨零点）|
| 用户 | 12 人，4 角色 |

> **跟你将来的真实部署的对应关系**：
>
> | 演示里 | 你的实部署里（4 楼层 50 块表）|
> |---|---|
> | 4 个 WORKSHOP 节点（车间）| 4 个 FLOOR 节点（1F/2F/3F/4F）|
> | 20 块表 | 50 块表 |
> | demo 占位"平面图"（如启用）| 4 张你的楼层平面图 |
> | mock-data-generator 灌的虚拟时序 | collector 从串口服务器实采的真实时序 |
>
> 切到真实部署的步骤见 `dashboard-commissioning-sop.md`。

---

## 3. 演示账号（从 `UserSeeder.java` 验证）

| 账号 | 密码 | 角色 | 演示场景 |
|---|---|---|---|
| `MOCK-admin` | `Mock123!` | ADMIN | 全功能、能配置 |
| `MOCK-finance-1` | `Mock123!` | FINANCE | 看账单 / 成本分摊 |
| `MOCK-mgr-a` | `Mock123!` | MANAGER | 只看冲压车间 |
| `MOCK-viewer-1` | `Mock123!` | VIEWER | 只读，不可改 |

> 演示给老板/客户用 `MOCK-admin`；演示权限隔离效果用 `MOCK-mgr-a`（只能看到冲压车间的数据）。

---

## 4. 4 个看点（按顺序展示，每点 1 分钟）

### 看点 ① — KPI 摘要（开场印象）

`/dashboard` 顶部下拉切到 **MOCK 工厂**。

|  | 你说的话 |
|---|---|
| KPI 卡片 | "今日已用 X 度、当前功率 Y kW、采集在线 99.5%、活跃告警 2 条——管理层 30 秒看完" |
| 实时功率曲线 | "5 分钟粒度，能看到上下班负荷台阶" |

**看点**：上班前后负荷曲线明显分层（mock-data-generator 内置班次曲线）。

### 看点 ② — Top N + 能耗构成（找问题）

往下滚动，主屏放 **Top N 设备** + **能耗构成饼图**。

|  | 你说的话 |
|---|---|
| Top N | "用电最多的 10 台设备，谁是耗能大户一目了然" |
| 能耗构成 | "按车间 / 按能源类型拆分，知道电费花在哪" |

**看点**：点 Top N 任一行 → 跳转仪表详情页，看那台表的近 7 天功率曲线 + 累计电量。

### 看点 ③ — Sankey 桑基图（震撼图）

`/dashboard` 主屏切 **Sankey 桑基图**。

|  | 你说的话 |
|---|---|
| Sankey | "能源从总进线 → 4 个车间 → 20 个工序的流向，一图看到全场能源走向" |

**看点**：演示前先准备一句话——"你看，焊接车间的总入是 30%，但下面工序加起来只有 25%，剩下 5% 是漏检或公用区域损耗，这就是节能改造的切入点。"

### 看点 ④ — 切节点演示权限（真功能）

下拉切到 **冲压车间**（任一子节点），所有面板**自动按节点过滤**。

切到 `MOCK-mgr-a` 账号登录，节点下拉只能看到冲压车间——下钻不到全厂。

|  | 你说的话 |
|---|---|
| 节点过滤 | "权限按 org-tree 节点隔离，车间主任只能看自己车间，财务能看全厂账单但改不了配置" |

---

## 5. 加分项（如有时间再展示）

- **告警页**（`/alarms`）—— 翻历史告警，讲规则引擎的 5-strike 通信故障检测
- **平面图**（`/floorplan`）—— 如果有时间手动上传一张占位图 + 拖几个挂点（5 min），现场展示效果最强
- **报表**（`/report`）—— 月报 PDF 自动生成
- **账单**（`/bills`）—— 内部分摊账单按车间出账
- **审计日志**（`/admin/audit`）—— 谁改了什么、什么时候，全程留痕

---

## 6. 演示后续做什么

| 听众 | 提示 |
|---|---|
| 老板 / 投资人 | 说一句"这是装上 5 个工厂之后能看到的样子"，把话题引到成本和 ROI |
| 客户工厂厂长 | 重点演示 Top N + Sankey + 平面图——找耗能问题、内部分摊，两个价值点说清楚 |
| 客户 IT | 说清私有部署 + Docker + 标准协议（OPC UA / Modbus / MQTT），不绑定云 |
| 内部销售培训 | 让销售自己跑一遍 demo-up.sh，5 分钟跑通就有底了 |

---

## 7. 故障速查

| 现象 | 处置 |
|---|---|
| `demo-up.sh` 在 mock-data-generator 这步卡住 | 看 mvn 日志；通常是 PG / Influx 还没真就绪。重跑一次。 |
| 页面打开空白 | 看 `docker compose ps` 确认 frontend / nginx 起了；前端编译需要 30 秒首次启动 |
| 登录提示密码错 | 仔细看密码大小写：`Mock123!`（M 大写、!）|
| dashboard 全为 0 | mock-data-generator 没跑完。`docker exec ems-postgres-dev psql -U ems -d factory_ems -c "SELECT COUNT(*) FROM org_nodes WHERE code LIKE 'MOCK-%';"` 应 ≥ 25 |
| Sankey 还是空 | 检查是否选了根节点（MOCK 工厂），子节点 Sankey 内容少 |

---

## 8. 收尾

```bash
# 完整清理（删容器 + 数据）
docker compose -f docker-compose.yml -f docker-compose.dev.yml down -v

# 只停不删数据（下次直接 demo-up.sh 又能起来）
docker compose -f docker-compose.yml -f docker-compose.dev.yml stop
```

---

**相关文档**

- 真实部署 SOP：[dashboard-commissioning-sop.md](./dashboard-commissioning-sop.md)
- mock-data-generator 详情：[../ops/mock-data-runbook.md](../ops/mock-data-runbook.md)
- 看板产品介绍：[../product/dashboard-feature-overview.md](../product/dashboard-feature-overview.md)
- 平面图产品介绍：[../product/floorplan-feature-overview.md](../product/floorplan-feature-overview.md)
