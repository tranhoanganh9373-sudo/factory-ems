# 认证与审计（ems-auth + ems-audit）· 功能概览

> 适用版本：v1.3.0+ ｜ 最近更新：2026-05-01
> 受众：销售 / 客户 / 系统管理员 / 实施工程师

---

## §1 一句话价值

ems-auth 管"谁能登录、能做什么、能看哪些节点"，包括账号、密码、JWT、4 个角色、节点级权限。ems-audit 管"谁做过什么"，所有写操作（建账期、关账、改电价、改告警等）都记一行日志，含操作人、时间、变更内容。两个模块合起来构成权限和留痕的安全底座。

---

## §2 解决什么问题

- **多人多角色，权限要分清**：财务能看费用、运营能改告警、查看者只读、管理员通杀。一套 RBAC + 节点级权限范围。
- **总部 / 厂区数据要隔离**：各厂区的运营只看自己厂区，不跨厂查别人数据。靠"组织节点权限"做范围切。
- **关键操作要留痕**：电价调整 / 账单锁定 / 用户改密 / 告警确认这些都是审计关注事项，过后查"谁、何时、改了什么"必须有据。
- **登录态要安全**：明文密码不行（bcrypt 哈希）、JWT 要过期、登录失败要锁、重置密码要记日志。

---

## §3 核心功能（ems-auth）

### §3.1 账号体系

- **`users`**：用户名、bcrypt 密码哈希、姓名、邮箱、启用开关、登录失败计数、锁定截止时间。
- **登录失败锁定**：连续失败 N 次自动锁 M 分钟（防暴力破解）。
- **强制改密**：首次登录或管理员重置后强制改密。

### §3.2 4 内置角色（roles）

- **ADMIN**：全部权限，可改用户 / 角色 / 节点权限。
- **OPERATOR**：日常运营，包括告警处理、平面图编辑、采集配置、产量录入。
- **FINANCE**：财务，负责电价、账单、成本分摊、报表。
- **VIEWER**：只读，能看不能改。

> 角色由后端字典维护，不支持自由新增（v2.x 起规划）。每个用户可有多个角色，权限取并集。

### §3.3 节点级权限（node_permissions）

- 一个用户的"可见节点范围"是一颗子树。例：财务总监权限到"全厂"根节点；某厂区运营仅"主厂区"子树。
- 所有列表 / 报表 / 仪表盘按此范围过滤。
- 默认管理员有全节点权限。

### §3.4 JWT + 刷新 token

- **`POST /auth/login`**：用户名 + 密码 → access token + refresh token（access 1 小时、refresh 7 天，可配）。
- **`POST /auth/refresh`**：refresh 换新 access。
- **`POST /auth/logout`**：使 refresh 失效。
- **`GET /auth/me`**：拿当前用户档案 + 角色 + 节点权限范围（前端登录后第一时间调用）。

### §3.5 用户 / 角色管理接口

- **`GET / POST /users`**：用户 CRUD。
- **`PUT /users/{id}/roles`**：分配角色（数组，多选）。
- **`PUT /users/{id}/password/reset`**：管理员重置密码。
- **`PUT /users/me/password`**：用户自助改密（要老密码）。
- **`GET / POST / DELETE /users/{userId}/node-permissions`**：分配节点权限（一对多）。
- **`GET /roles`**：角色列表。

---

## §4 核心功能（ems-audit）

### §4.1 审计切面（@Audited）

- 业务方法上加 `@Audited` 注解 → AOP 自动捕获方法名、参数、返回值 → 异步写到 `audit_logs` 表。
- 异步：不阻塞业务调用，写日志失败不影响业务。

### §4.2 关键操作记录

主要审计点（不全部列举）：

- **电价**：方案 / 时段的新建 / 修改 / 删除。
- **账单**：账期的建立 / 关账 / 锁定 / 解锁。
- **告警规则**：阈值变更、维护期切换。
- **用户管理**：建用户 / 改角色 / 重置密码 / 启停用。
- **节点权限**：分配 / 撤销。
- **成本分摊**：发起 run、修改规则。
- **关键登录事件**：登录 / 登出 / 登录失败锁定。

### §4.3 审计日志查询接口

- **`GET /api/v1/audit-logs`**：实现在 ems-auth 模块，但底层数据在 ems-audit 写入的 `audit_logs` 表。
- 支持筛选：操作人、动作类型、目标类型、时间窗。
- 仅 ADMIN 可查（其他角色 403）。

### §4.4 内容字段

每条日志包含：操作人 user_id、动作（如 `BILL_LOCK`、`TARIFF_PLAN_UPDATE`）、目标类型 + 目标 ID（如 `bill_period:2026-05`）、操作前后内容（jsonb，便于看改了哪些字段）、IP、User-Agent。

---

## §5 适用场景

### 场景 A：实施工程师初始建账号

实施进场后第一件事：

1. 用默认 `admin` 登录（首次密码 `admin123!` 强制改）。
2. `POST /users` 建客户的运营账号（OPERATOR 角色）+ 财务账号（FINANCE）+ 高管查看账号（VIEWER）。
3. 给各账号分配节点权限（如各厂区运营只给该厂区子树）。
4. 把 admin 密码交给客户 IT 主管保管。
5. 客户用各账号验证能登录、看到正确范围。

### 场景 B：财务终审锁账期 — 留痕

财务总监 5 月 15 日锁定 4 月账期：

1. 进 `/billing/periods/2026-04` → 点"锁定"。
2. 后端 `PUT /bills/periods/{id}/lock`，状态变 LOCKED。
3. ems-billing 调 `@Audited` 切面 → 写一条 `audit_logs`：动作 `BILL_PERIOD_LOCK`、目标 `bill_period:2026-04`、操作人 = 财务总监 user_id、IP / UA、改前 status=CLOSED、改后 status=LOCKED。
4. 后续若有人要查"4 月账期是谁锁的"，进 `/audit-logs` 按目标筛 → 一行结果。

### 场景 C：员工离职 — 撤销权限

某运营员工离职：

1. 管理员进 `/users/{id}` → 启停用切换为禁用 → audit logs 记录"USER_DISABLE"。
2. 该用户的 token 在过期后失效；如要立即生效，可在 token 黑名单（v2.x）。
3. 节点权限随用户禁用一起失效。
4. 历史审计日志保留，这个人过去做的事仍然查得到。

### 场景 D：可疑操作排查

总监觉得"上月某次电价修改不合理"，进 `/audit-logs` 按目标 `tariff_plan:江苏-2026 工商业用电` 查 → 看到某天 14:22 某账号修改了某时段单价从 0.85 改成 0.80。点开 `before` / `after` 字段看完整对比 → 找此人沟通。

---

## §6 不在范围（首版）

- **不做 SSO（单点登录）**：不集成企业 SSO（OAuth2 / SAML / LDAP / AD）。规划在 v2.x。
- **不做多因子认证（MFA）**：仅密码认证，无短信 / TOTP / 硬件 key。规划在 v2.x。
- **不做角色自定义**：4 角色固定，不能在 UI 里建"半财务半运营"的混合角色。
- **不做权限细粒度到字段**：节点权限是粗粒度（能看 / 不能看整个节点），不支持"能看节点但只读金额、不能看用量"。
- **不做审计日志清理 / 归档自动化**：日志一直留在 PostgreSQL，长期会膨胀。需要定期手工归档或外部 ETL。
- **不做审计实时告警**：审计日志只记录、不推送（如"管理员凌晨改了电价"不会发短信告警）。

---

## §7 与其他模块的关系

```
ems-orgtree              节点权限指向树节点
       │
       ▼
ems-auth ←─────  所有业务模块（每次请求过 JWT 过滤器、权限注解）
       │
       └──── ems-audit
               │
               └──< 所有业务模块（@Audited 注解切面 → 异步写 audit_logs）
```

ems-auth 是所有请求的入口闸门，ems-audit 是所有写操作的留痕仓库。两个模块都是横切关注点，被 16 个模块依赖。

---

## §8 接口入口

- **前端路径**：
  - `/login` — 登录
  - `/users` — 用户管理（仅 ADMIN）
  - `/audit-logs` — 审计日志查询（仅 ADMIN）
  - `/me/password` — 自助改密
- **API 前缀**：`/api/v1/auth`、`/api/v1/users`、`/api/v1/roles`、`/api/v1/audit-logs`
- **关键端点**：

| 端点 | 用途 |
|---|---|
| `POST /api/v1/auth/login` | 登录拿 token |
| `POST /api/v1/auth/refresh` | 刷新 token |
| `POST /api/v1/auth/logout` | 登出 |
| `GET /api/v1/auth/me` | 当前用户档案 + 权限 |
| `GET / POST /api/v1/users` | 用户管理 |
| `PUT /api/v1/users/{id}/roles` | 分配角色 |
| `PUT /api/v1/users/{id}/password/reset` | 管理员重置密码 |
| `PUT /api/v1/users/me/password` | 自助改密 |
| `GET / POST / DELETE /api/v1/users/{userId}/node-permissions` | 节点权限管理 |
| `GET /api/v1/roles` | 角色列表 |
| `GET /api/v1/audit-logs` | 审计日志查询 |

---

## §9 关键字段

### `users` 表

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | 主键 |
| `username` | varchar | 登录名，唯一 |
| `password_hash` | varchar | bcrypt 哈希 |
| `name` | varchar | 显示名 |
| `email` | varchar | 邮箱（可空）|
| `enabled` | bool | 启用开关 |
| `failed_attempts` | int | 连续失败次数 |
| `locked_until` | timestamp | 锁定截止时间 |
| `force_password_change` | bool | 是否强制改密 |
| `last_login_at` | timestamp | 上次登录时间 |

### `roles` 表（种子数据）

| code | name |
|---|---|
| `ADMIN` | 管理员 |
| `OPERATOR` | 运营 |
| `FINANCE` | 财务 |
| `VIEWER` | 查看者 |

### `user_roles` 表

| 字段 | 类型 | 说明 |
|---|---|---|
| `user_id` | bigint | 外键 → users |
| `role_id` | bigint | 外键 → roles |

### `node_permissions` 表

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | 主键 |
| `user_id` | bigint | 用户 |
| `org_node_id` | bigint | 可见节点（含子树）|

### `audit_logs` 表

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | bigint | 主键 |
| `user_id` | bigint | 操作人 |
| `action` | varchar | 动作（如 `BILL_LOCK`、`TARIFF_UPDATE`）|
| `target_type` | varchar | 目标类型（`bill_period`、`tariff_plan` 等）|
| `target_id` | varchar | 目标 ID |
| `before` | jsonb | 变更前快照 |
| `after` | jsonb | 变更后快照 |
| `ip` | varchar | 调用方 IP |
| `user_agent` | varchar | UA |
| `created_at` | timestamp | 时间 |

---

**相关文档**

- 组织树（节点权限的依据）：[orgtree-feature-overview.md](./orgtree-feature-overview.md)
- 账单（关账 / 锁定 进审计）：[billing-feature-overview.md](./billing-feature-overview.md)
- 电价（变更进审计）：[tariff-feature-overview.md](./tariff-feature-overview.md)
- 告警（确认 / 静默进审计）：[alarm-feature-overview.md](./alarm-feature-overview.md)
- 用户手册：[user-guide.md](./user-guide.md)
- 平台总览：[product-overview.md](./product-overview.md)
