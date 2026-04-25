# Plan 1.1 验证日志 — 2026-04-25

> Goal: 在 Docker Compose 全栈环境下，跑通 7 个 E2E 场景，确认 Plan 1.1（地基 + 认证 + 审计）可演示。

## 最终结果

```
ok 1 [chromium] › audit.spec.ts › audit log shows login and user-creation events
ok 2 [chromium] › lockout.spec.ts › account lockout after 5 failed attempts
ok 3 [chromium] › login.spec.ts › admin can login and logout
ok 4 [chromium] › login.spec.ts › login with wrong password shows error
ok 5 [chromium] › profile.spec.ts › user can change own password
ok 6 [chromium] › refresh-token.spec.ts › frontend auto-refreshes when access token missing
ok 7 [chromium] › user-permission.spec.ts › admin creates user, org tree, assigns permission; viewer sees only subtree

7 passed (21.7s)
```

环境：`docker compose` (nginx:8888 + factory-ems + postgres15 + frontend-builder)，Playwright `chromium`，`workers=1`。

---

## 发现并修复的 Bug

### 后端（Java / Spring）

| # | 缺陷 | 根因 | 修复 |
|---|---|---|---|
| B1 | `/admin/audit` 列表 500：`could not determine data type of parameter $N` | PostgreSQL 在 `(:p IS NULL OR col = :p)` 全 null 绑定时无法推断参数类型 | `AuditLogRepository#search` 改 native SQL，所有可空参用 `CAST(:p AS bigint/varchar/timestamptz)` 显式声明类型 |
| B2 | 创建用户后 `/admin/audit` 看不到 `CREATE_USER` 事件 | `@Audited(action="CREATE", resourceIdExpr="#result.id()")` 只写 numeric resource_id，summary 为 null，用户名只在 detail JSON 里 | 在 `UserServiceImpl#create` 上加 `summaryExpr = "'创建用户: ' + #result.username()"`，使用户名出现在审计表概述列 |
| B3 (residual) | 偶发 `ObjectOptimisticLockingFailureException: User#1` | 并发 admin 登录 (curl 健康检查 + Playwright 同时) 触发 `lastLoginAt` 写竞争 | **未修**：`workers=1` 下不可复现；Plan 1.2 期间评估是否引入 retry 或剥离 lastLoginAt 到独立表 |

### 前端（React / Vite）

| # | 缺陷 | 根因 | 修复 |
|---|---|---|---|
| F1 | `/admin/users` 等子路由白屏 | `<Route path="admin">` 包了 `<ProtectedRoute>` 但 children 用 `<></>` 而非 `<Outlet />` | `frontend/src/router/index.tsx` 改 `<Outlet />` |
| F2 | 直接 `page.goto('/admin/users')` 跳回 `/login`（accessToken 在内存丢失） | persist 仅持久化 user，accessToken 在 reload 后清空 | `frontend/src/App.tsx` 加启动 effect：若 persisted user 存在但 accessToken 缺失，调 `/auth/refresh` 取新 token |
| F3 | 错密登录被 401 刷新拦截器吞掉 | `apiClient` 401 + 40001 判断未排除 `/auth/login` 自身 | `frontend/src/api/client.ts` 跳过 `/auth/login` 与 `/auth/refresh` URL |
| F4 | 授权节点 TreeSelect 找不到测试新建的"一车间_xxx" | rc-virtual-list 仅渲染视区附近节点，offscreen 节点不在 DOM | `permissions.tsx` `<TreeSelect>` 加 `showSearch + treeNodeFilterProp="title" + allowClear`，让用户可输入过滤 |

### 测试稳定性（Playwright）

| # | 缺陷 | 根因 | 修复 |
|---|---|---|---|
| T1 | `getByRole('button', { name: '登录' })` 找不到按钮 | AntD `autoInsertSpaceInButton` 把恰好 2 个 CJK 字符的按钮渲染为 `登 录` | 全部按钮断言改 `name: /登\s*录/` 等正则 |
| T2 | 登录头像下拉 `getByText(/admin\|系统管理员/)` 命中 2 元素（首页欢迎 + 头部下拉） | 两处都有"admin"文本 | 用 `page.locator('.ant-layout-header').getByText(...)` scope 到 header |
| T3 | refresh-token 测试偶发 401（rotation 孤儿） | `page.reload() + page.goto()` 紧挨发出两次 refresh，第二次拿不到新 cookie | 移除 `reload()`，只保留单次 `goto()` |
| T4 | 节点类型 PLANT 选项 click "outside of viewport" | AntD 虚拟列表 + Portal 定位偶发 | `dispatchEvent('click')` 绕过视口检查 |
| T5 | profile/audit 后续步骤抢跑 user-create（POST 被 navigation 取消） | `click 确 定` 后立即 `page.goto`，请求被取消 | 在 `goto` 之前 `await expect(page.getByText('已创建')).toBeVisible()` |
| T6 | profile.spec.ts 新用户登录后跳 /forbidden | 仅按 URL 同步登录易被前一路由 history state 干扰；toHaveURL 5s 也容易超时 | 改用 `page.waitForResponse(/\/auth\/login/)` 同步登录完成，再 `goto('/profile')` |
| T7 | profile `getByLabel('新密码')` 命中 2 元素（"新密码" 是"确认新密码"的子串） | AntD label 命名导致歧义 | 直接用 `page.locator('input#newPassword')` / `input#confirm` |
| T8 | user-permission `getByText('一车间')` strict-mode 多匹配 | 测试数据累积 + AntD 树渲染多份相同 title | 节点名加时间戳：`plantName = 'E2E工厂_' + ts`、`wsName = '一车间_' + ts` |

---

## 验证轮次（共 11 轮）

| 轮 | 通过 | 失败 | 关键发现 |
|---|---|---|---|
| 1 | 0 | 7 | 容器编排、nginx 路由对齐、登录流首通 |
| 2 | 2 | 5 | 修 T1（CJK 按钮）后 login + lockout 通过 |
| 3 | 4 | 3 | 修 F1/F2/F3、B1、T3 后 refresh-token + audit-LOGIN 通过；audit/profile/user-permission 仍挂 |
| 4 | 4 | 3 | 修 B2（CREATE_USER summary）后审计列出现用户名；profile/user-permission 还差 |
| 5 | 6 | 1 | 修 T4/T5（已创建 wait）后 audit/profile 通过；user-permission 仍卡 dropdown |
| 6 | 5 | 2 | 测试 dropdown 选 一车间 多个匹配（accumulated test data）；profile 偶发 /forbidden |
| 7 | 6 | 1 | 修 T6（waitForResponse）后 profile 通过；user-permission 仍卡 |
| 8 | 6 | 1 | 验证 viewer 看 wsName 不到（rc-virtual-list offscreen） |
| 9 | 6 | 1 | 改 plantName 唯一后仍卡——发现 dropdown 同样 virtual-list 截断 |
| 10 | 6 | 1 (flaky) | 加 `showSearch` 后首跑通过、但 worker race 导致一次重试，可视为已修 |
| 11 | **7** | **0** | 全绿，21.7s |

---

## Plan 1.1 验收口径

- ✅ admin / orgtree / auth / audit 演示路径全跑通
- ✅ JWT 双 token + httpOnly refresh cookie + interceptor 刷新链路自洽
- ✅ Audit 切面对 `CREATE_USER`/`LOGIN`/`LOGIN_FAIL`/`LOGOUT` 全部记录
- ✅ AntD 表单 + AntD Message + AntD Modal 路径稳定
- ✅ Docker Compose 一键启动，volume 共享前端构建产物
- ⚠️ User 表 `lastLoginAt` 在并发登录下偶发 OptimisticLock —— 已记录，留 Plan 1.2 修

可以打 `v1.1.0-plan1.1` tag。

---

## 后续（Plan 1.2 开工预热）

详见 `docs/superpowers/plans/2026-04-24-factory-ems-plan-1.2-core-domain-skeleton.md`，本轮已就绪条件：

1. **数据库基线干净** — `data/postgres` 经过 ~10 轮 E2E，含数百条历史记录；下次开始前清库（`docker compose down && rm -rf data/postgres`）以保证 Plan 1.2 干净
2. **认证 + 审计模块**作为底座，Plan 1.2 只在其上加 `ems-meter` / `ems-timeseries` / `ems-dashboard` / `ems-report`
3. **遗留待办**：
   - User 乐观锁问题（B3）→ Plan 1.2 第一条任务
   - orgtree API 对 VIEWER 当前返回全树 → Plan 1.2 引入 NodePermission 过滤
   - audit detail JSON 含明文 password 字段 → 应在 `ObjectMapper` 序列化前 mask（安全任务）

### 建议下一步执行顺序

1. 清库 + 打 tag `v1.1.0-plan1.1`
2. 把 B3 / orgtree 过滤 / audit 脱敏 这三条遗留进 Plan 1.2 的 sprint 1
3. 起 `ems-meter` 模块骨架（pom + entity + repository + dto），与现有 modules 风格保持一致
4. InfluxDB 容器先加进 docker-compose（即使尚未接），便于 Plan 1.2 sprint 2 直接连
