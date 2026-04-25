# Factory EMS · 模拟数据生成方案（Mock Data Plan）

**Goal:** 在不接真实采集设备的前提下，用脚本批量灌入足够真实感的数据，让子项目 2（成本分摊）和子项目 3 之前的开发验证全部可跑。**真实数据采集推到子项目 3 之后再做。**

**Status:** 规划阶段。要在 Plan 2.1 启动前完成 P0 + P1。

**关联：**
- 子项目 1 v1.0.0 已发布（提供数据模型、API）
- 子项目 2 spec：`docs/superpowers/specs/2026-04-25-factory-ems-subproject-2-cost-allocation.md`
- 子项目 2 Plans 2.1 / 2.2 / 2.3 都依赖本方案的产物

---

## 1. 为什么不直接接真实数据

| 选项 | 优势 | 劣势 |
|---|---|---|
| **直接接真实采集（OPC UA / Modbus / IEC104）** | 数据完全真实 | 需要现场施工 / 网关 / 通讯协议联调 / 工厂停机窗口；阻塞研发 6-8 周 |
| **接真实数据库 dump（甲方导出 1 个月）** | 真实 | 数据敏感、隐私合规风险；需 NDA；脱敏成本不低 |
| **✅ 模拟数据生成（本方案）** | 立刻可用、可重放、可极端化 | 不能 100% 还原真实噪声 |

模拟数据"先跑通流程、再校真实数据"是工业软件常见路径。**真实采集放在子项目 3 之后**：那时 v2.0.0 已经稳定，需要现场试点验证算法准确性时再上。

---

## 2. 数据规模（MVP 默认）

> 一套"中型工厂"画像。可通过 `--scale=small|medium|large` 参数缩放。

| 维度 | 数量 |
|---|---|
| 组织节点（org_node） | 约 40 个：1 工厂 → 4 车间 → 每车间 4-6 工序 → 公共区域（行政/食堂/照明） |
| 测点（meter） | 约 120 个：电 80 / 水 15 / 气 10 / 蒸汽 8 / 油 7 |
| meter_topology 边 | 约 100 条：1 总进线 → 6 大表 → ~80 中表 → 部分子表 |
| 电价方案 | 2 套（工业大工业 + 一般工商业）× 4 段时段 |
| 班次 | 3 个（早 06:00-14:00 / 中 14:00-22:00 / 夜 22:00-06:00 跨零点） |
| 产品 | 6 种 SKU |
| 时序数据时长 | **3 个完整月**（默认 2026-02-01 ~ 2026-04-30） |
| 时序点频次 | 每分钟 1 点（写 InfluxDB raw）+ 每小时聚合 `ts_rollup_hourly`（写 PG）+ 日聚合 `ts_rollup_daily` + 月聚合 `ts_rollup_monthly` |
| 总记录数 | ≈ 120 测点 × 60 × 24 × 90 ≈ 1556 万行（Influx raw）、26 万行（ts_rollup_hourly）、1 万行（ts_rollup_daily）、360 行（ts_rollup_monthly） |
| 产量记录 | 4 车间 × 90 天 × 3 班 ≈ 1080 行 |
| 用户 | 12 个（admin × 1 / finance × 2 / 各车间主管 × 4 / viewer × 5） |

> **scale=small**：仅 30 测点 × 7 天，≈ 30 万行 raw，开发本地用 5 分钟生成。
> **scale=large**：500 测点 × 12 个月，≈ 2.6 亿行，做性能压测专用。

---

## 3. 数据"真实感"要素（要骗过算法不要骗过自己）

模拟数据不是随机数。要让 4 种分摊算法 + 时段拆分 + 看板都跑出"像真的"的结果，必须包含：

### 3.1 时段画像
- 工业用电典型曲线：08:00-12:00 / 14:00-18:00 高，凌晨低
- 周末 / 节假日断崖（用 `chinese-holidays` 列表识别 2026 节日）
- 班次切换时段（06:00 / 14:00 / 22:00）有一个 ~10 分钟"换班谷"

### 3.2 跨零点
- 至少 1 套电价方案带跨零点段（22:00-06:00 谷段）
- 至少 1 个班次跨零点（夜班 22:00-06:00）

### 3.3 父子守恒（容差内）
- 父表 ≈ Σ 子表 + 残差（公摊）
- 残差占比 5-15%（照明 / 损耗）
- 偶发：注入 1-2 个时段"残差为负"的异常（测点漂移），让 Plan 2.1 的 negative-residual clamp 路径被触发

### 3.4 噪声 / 缺失
- 每个测点每 24h 注入 1-3 个 1 分钟级缺失（看板要能容忍）
- 1% 测点抽风：连续 1h 输出 0（让告警 / 阈值面板有素材）

### 3.5 产量耦合
- 产量与电耗弱相关：电耗高的小时产量也高（不是死直线，加 ±15% 噪声）
- 周末 50% 车间停产
- 单位产量能耗（kWh/件）随月份小幅漂移（让"单位产量能耗"面板出趋势）

### 3.6 季节性
- 4 月与 2 月不同：空调/暖通季节差异，至少 ±10% 月环比

---

## 4. 实现方案

### 4.1 工具选型

放在 `tools/mock-data-generator/`，独立 Maven 子模块（避免污染主 build classpath）：

```
tools/mock-data-generator/
├── pom.xml
├── src/main/java/com/ems/tools/mock/
│   ├── MockDataApplication.java        # Spring Boot CLI
│   ├── seed/
│   │   ├── OrgTreeSeeder.java          # 灌 org_node + meter + meter_topology
│   │   ├── TariffSeeder.java           # 灌 tariff_plan + tariff_period
│   │   ├── ShiftSeeder.java
│   │   ├── ProductSeeder.java
│   │   └── UserSeeder.java
│   ├── timeseries/
│   │   ├── ProfileGenerator.java       # 生成 24h × 7d × 12m 画像
│   │   ├── NoiseInjector.java          # 缺失 / 异常 / 飙升
│   │   ├── ConservationEnforcer.java   # 父=Σ子+残差 守恒
│   │   ├── InfluxBatchWriter.java      # 写 raw（每分钟 1 点）
│   │   └── RollupBatchWriter.java      # 写 ts_rollup_hourly / ts_rollup_daily / ts_rollup_monthly（PG）
│   ├── production/
│   │   └── ProductionEntryGenerator.java
│   └── config/
│       └── ScaleProfile.java           # small/medium/large 参数
└── src/main/resources/
    ├── application.yml
    └── profiles/                       # 24h × 7d 标准画像 yaml
        ├── industrial-electric.yaml
        ├── lighting.yaml
        ├── water.yaml
        └── compressor.yaml
```

### 4.2 复用 v1 的 Repository

模拟数据生成器 **不许直接 INSERT SQL**——必须通过子项目 1 的 JPA Repository 入库。理由：

- 任何 schema 变更自动跟随
- 走 entity 校验（防止灌入非法数据）
- 测试覆盖路径

唯一例外：`InfluxBatchWriter` 走 InfluxDB Java client 批量 Line Protocol（量太大走 JPA 太慢）。

### 4.3 命令行入口

```bash
# 全量灌入（默认 medium scale，3 个月）
./mvnw -pl tools/mock-data-generator spring-boot:run \
  -Dspring-boot.run.arguments="--scale=medium --months=3 --start=2026-02-01"

# 仅灌主数据（org / meter / tariff / shift / users）
./mvnw -pl tools/mock-data-generator spring-boot:run \
  -Dspring-boot.run.arguments="--seed-only=master"

# 仅追加新月份时序（增量）
./mvnw -pl tools/mock-data-generator spring-boot:run \
  -Dspring-boot.run.arguments="--seed-only=timeseries --start=2026-05-01 --months=1"
```

### 4.4 幂等 + 重置

- **幂等**：相同 seed (`--seed=42`) + 相同参数 → 完全相同的数据。算法回归测试可重放。
- **重置**：`--reset=true` 走一组 SQL：`TRUNCATE telemetry_*`、`DELETE FROM production_entry / cost_allocation_* / bill*`，主数据保留。
- **only-master**：只灌主数据，时序留空，给"先调试 schema 再灌量"的场景。

---

## 5. 验证脚本

灌完数据后跑一组 sanity check（`tools/mock-data-generator/src/main/java/com/ems/tools/mock/verify/`）：

| 检查 | 期望 |
|---|---|
| `SELECT COUNT(*) FROM ts_rollup_hourly WHERE hour_ts BETWEEN ...` | ≥ 25 万（120 测点 × 24h × 90d） |
| 父表 vs Σ 子表 + 残差，每小时偏差 | ≤ 1%（除注入的负残差时段） |
| 24h 曲线最高/最低比 | 2.0 < ratio < 4.0（工业典型） |
| 周末 vs 工作日均值比 | 0.4 - 0.6 |
| 产量 NULL 率（仅周末停产） | < 10% |
| 电价方案命中：夜班时段 ≈ 谷段 | 重合度 > 80% |

不通过则报错退出，避免错误数据进 Plan 2.1。

---

## 6. 与子项目 1 / 2 / 3 的对接

| 阶段 | 用法 |
|---|---|
| **子项目 1 v1.0.0 验收期（已完成）** | 已用过简版 demo seed（少量数据快速演示）；本方案是升级版 |
| **Plan 2.1 启动前** | **必须** 跑 `--scale=medium --months=3` 攒够 3 月数据，cost engine 才有得分 |
| **Plan 2.2 启动前** | 同上数据集（不需要重灌，账单生成读 cost_allocation_run） |
| **Plan 2.3 / E2E** | E2E 跑前 `--scale=small --months=1` 加速；CI 用 small 跑得快 |
| **性能压测** | `--scale=large` 单独跑，与功能验证数据库分开（独立 PG/Influx 实例） |
| **子项目 3** | 能效诊断需要异常 / 漂移样本，本方案的 NoiseInjector 留 hook，子项目 3 可加更多病例 |
| **真实采集（子项目 3 之后）** | 网关上线后切到真实数据，本工具仍保留作为 dev/CI 用 |

---

## 7. 工作量

| Phase | 范围 | 估算 |
|---|---|---|
| A | 模块骨架 + Spring Boot CLI + 配置 | 0.5 天 |
| B | 主数据 Seeder（org / meter / topology / tariff / shift / users） | 1 天 |
| C | ProfileGenerator + NoiseInjector + ConservationEnforcer | 2 天 |
| D | InfluxBatchWriter（百万行/分钟级别批量） | 1 天 |
| E | RollupBatchWriter（PG COPY 或批量 upsert hourly/daily/monthly） | 0.5 天 |
| F | ProductionEntryGenerator + 节假日/班次耦合 | 0.5 天 |
| G | 验证脚本（sanity check） | 0.5 天 |
| H | 文档 + scale 三档跑通 | 0.5 天 |
| **合计** | | **6.5 天** |

---

## 8. 风险与权衡

| 风险 | 缓解 |
|---|---|
| 模拟数据被算法过度拟合，到真实数据反而失效 | 子项目 3 上真实数据后做对比验证；分摊算法本身基于物理量守恒，模拟与真实差异主要在噪声分布，不影响算法正确性 |
| 时序写入量大（亿行级别），开发机 Influx 撑不住 | scale 分三档，开发本地默认 small；large 跑独立机 |
| 工具代码维护成本 | 模块独立、不进主 build；Repository 复用让 schema 变更自动跟 |
| 节假日 / 班次耦合复杂度蔓延 | MVP 只覆盖周末 + 班次跨零点，节假日列表写死 2026 一年，不做日历服务 |
| 数据被误带到生产 | application.yml 默认 dev profile；生产用 `--profiles=prod` 强制拒绝运行（写 fail-fast 校验） |

---

## 9. 验收

- `./mvnw -pl tools/mock-data-generator package` 编译通过
- `--scale=small --months=1` 5 分钟内跑完，sanity check 全绿
- `--scale=medium --months=3` 30 分钟内跑完，sanity check 全绿
- Plan 2.1 集成测试 fixture 改用本工具产物，所有测试仍绿
- 文档：`docs/ops/mock-data-runbook.md`（如何重置 / 增量 / 验证）
- Tag `tools-v0.1.0` 标记首发

---

## 10. 后续动作

- [ ] 与 Plan 2.1 同步：实现期间复用本工具产物作为算法回归 fixture
- [ ] 子项目 3 启动前评估：是否要扩展 NoiseInjector 加更多异常样本（设备劣化、换季波动）
- [ ] 真实采集上线后：保留本工具作为 dev/CI 数据源，生产数据切到真实采集

---

## 附录 A · scale 参数参考

| scale | 测点数 | 月数 | Influx raw 行数 | ts_rollup_hourly 行数 | 用途 |
|---|---|---|---|---|---|
| small | 30 | 1 | ~130 万 | ~22000 | 本地 dev / E2E |
| medium | 120 | 3 | ~1556 万 | ~262000 | Plan 2.x 主数据集 |
| large | 500 | 12 | ~2.6 亿 | ~440 万 | 性能压测专用 |
