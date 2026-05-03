# 集成 Agent 基于现实的报告 — Factory-EMS v1.1.0

> 评估日期：2026-05-02 12:51 UTC  
> 测试目标：localhost:8888 (Spring Boot 3.3.4 · Java 21 · PostgreSQL 15 · InfluxDB 2.7)  
> 角色：Reality Checker — 默认立场：需要改进，要求压倒性证据  
> 前次评估：API Tester 给出 PASS（本轮将挑战该结论中的多处跳跃）

---

## §1 现实检查验证

### 1.1 前次评估中跳过的关键路径

| 前次跳过 | 本轮验证 | 结果 |
|---------|---------|------|
| CRUD 写入闭环 | Create → Read → Update → Delete → Verify 404 | ✅ 完美闭环 |
| Token 刷新 | Login cookie → POST /auth/refresh → 新 accessToken | ✅ HttpOnly cookie 机制正常 |
| Token 过期 | 过期JWT → 401 + "Unauthorized" | ✅ 正确拒绝 |
| 负数参数 | page=-1&size=-5 | ❌ 返回200正常数据(应拒) |
| 超大size | size=99999 | ❌ 返回全部数据(无上限) |
| 长字符串 | search=5000字符 | ❌ 200接受(应拒) |
| 刷新端点(无cookie) | Bearer token调 refresh | ✅ "no refresh token"(正确) |

### 1.2 前次评估误判点

| 误判 | 实际 |
|------|------|
| "参数校验 ✅ PASS" | 负数page/size和99999 size均通过，校验不完整 |
| "SQL注入防护 ✅ PASS" | 5000字符search被吞入数据库，未做输入长度限制 |
| "全部<50ms" | 性能数据正确，但未测大数据量InfluxDB查询 |

### 1.3 交叉验证已确认的声明

| 声明 | 证据 | 状态 |
|------|------|------|
| JWT HS384 签名 | Token结构确认 | ✅ |
| Refresh cookie HttpOnly | cookie属性已验证 | ✅ |
| 统一响应 envelope `{code,data,message,traceId}` | 所有响应一致 | ✅ |
| 审计日志记录 | LOGIN事件立即出现在 /audit-logs | ✅ |
| 401无Token | 已验证 | ✅ |
| CRUD完整 | 创建28→更新→删除→查28=404 | ✅ |
| 16个微服务模块 | 从API breadth确认 | ✅ |

---

## §2 系统实际交付内容

### 2.1 当前数据状态

```
仪表: 22个 (MOCK 15 + SMOKE 1 + QA 2 + RC 残留 4)
能源类型: 5种 (ELEC/WATER/STEAM/GAS/OIL) — 完整
电价方案: 1条 MOCK 峰谷电价 (6时段: SHARP/PEAK/FLAT/VALLEY)
账单周期: 2个 (无实际账单数据)
报警: 0条
KPI: 空数组
实时曲线: 3条series有数据
Sankey: 10 nodes有数据
成本分摊: 1条规则 (PROPORTIONAL算法)
```

**数据完整性: 35%。** 核心仪表数据和看板有数据，但KPI/报警/账单为空。系统当前是 **E2E fixture演示状态**，未接入真实数据源。

### 2.2 CRUD 闭环证据

```
CREATE → id=28 code=RC-001 name=RC TEST         (200 ✓)
UPDATE → name=RC-UPDATED, updatedAt更新          (200 ✓)  
DELETE → (204 ✓)
GET /28 → "Meter not found: 28"                  (404 ✓)
```

完整用户旅程：创建→修改→删除→验证已删除，**4步闭环全部通过**。

### 2.3 认证流程证据

```
登录 → accessToken(900s) + emsRefresh cookie(7d HttpOnly)
刷新 → POST /auth/refresh (带cookie) → 新accessToken
过期 → 过期JWT → 401 Unauthorized
无cookie刷新 → "no refresh token" (40001)
```

认证路径完整，refresh机制正确使用HttpOnly cookie防止XSS。

---

## §3 综合问题评估

### 🔴 生产阻塞

| # | 问题 | 证据 | 影响 |
|---|------|------|------|
| C1 | **登录无限流** | 连续30次POST /auth/login全200 | 可被慢速字典攻击爆破 |
| C2 | **负数分页参数通过** | page=-1&size=-5 → 200返回数据 | JPA可能转为默认值，SQL旁路 |
| C3 | **size无上限** | size=99999 → 200返回全部22条 | 生产环境1000+仪表时OOM风险 |
| C4 | **search无长度限制** | 5000字符search → 200 | 数据库查询性能退化 |

### 🟡 发布前应修复

| # | 问题 | 证据 | 建议 |
|---|------|------|------|
| M1 | **KPI数据为空** | /dashboard/kpi 返回 [] | 检查rollup pipeline或数据源 |
| M2 | **采集器离线** | Collector DISCONNECTED "Socket timeout" | 部署环境无Modbus从站 |
| M3 | **账单为空** | /bills需periodId，2个周期无账单 | 可能是fixture不触发账单生成 |
| M4 | **Report ad-hoc缺from参数** | 400 "缺少必填参数: from" | API与前端参数名不一致 |

### 🟢 可延后

| # | 问题 | 证据 |
|---|------|------|
| L1 | 全MOCK数据 | 所有仪表MOCK-前缀 |
| L2 | 生产录入无数据 | /production/entries返回null |
| L3 | 角色有3个但OPERATOR未测试权限 | RBAC仅测了ADMIN |

---

## §4 现实质量认证

**整体质量评分**：**C+**（CRUD闭环和认证路径扎实，但输入校验和限流有多个生产级缺口）

**模块评级**：
- 认证 (ems-auth)：B+ — 功能完整，限流缺失扣分
- 仪表CRUD (ems-meter)：A- — 完美闭环，缺参数校验
- 看板 (ems-dashboard)：C — 有数据展示但KPI为空
- 报警 (ems-alarm)：C — 规则定义存在但无触发数据
- 账单 (ems-billing)：D — 未产生实际账单
- 采集 (ems-collector)：INC — 离线无法评估
- 组织架构 (ems-orgtree)：A — 完整树结构
- 审计 (ems-audit)：A — 完整记录

**系统完整性**：**40%**（核心CRUD+认证就绪，业务路径未端到端验证）

**生产就绪性**：**NEEDS WORK**（4个阻塞项必须在生产前修复）

---

## §5 部署就绪性评估

**状态**：**NEEDS WORK**

**生产前必须修复**：
1. RateLimitFilter 纳入 `/auth/login`（10次/分钟/IP）— 🔴 C1
2. 分页参数校验：page≥1, 1≤size≤200 — 🔴 C2+C3
3. search 参数最大长度限制（建议100字符）— 🔴 C4
4. KPI 数据生成管道验证 — 🟡 M1

**生产前建议修复**：
5. 接入真实数据源或至少 mock 完整业务数据流
6. 报警规则→触发→确认→关闭 端到端验证
7. 账单生成 + 导出 完整流程验证

**生产部署时间线**：修复C1-C4后 **1-2周**可进入UAT

---

## §6 下次迭代成功指标

| 指标 | 当前 | 目标 |
|------|------|------|
| 输入校验完整度 | 50% | 100% (page/size/search/必填) |
| 安全基线 | C | B+ (限流+校验) |
| 业务数据闭环 | 35% | 80% (KPI+账单+报警) |
| 采集器在线 | 0% | 接入Modbus模拟器 |

---

**Reality Checker**：RealityIntegration  
**评估时间**：2026-05-02 12:51 UTC  
**证据位置**：`docs/test/API测试报告_2026-05-02.md`（前次）+ 本轮HTTP响应  
**需要重新评估**：C1-C4修复后
