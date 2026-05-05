# Factory EMS Frontend — UI/UX 优化执行报告

- **执行日期**：2026-05-02
- **基准审计**：`docs/ui-audit.md`（29 项 P0–P3）
- **执行模型**：UI/UX Pro Max（Skill `ui-ux-pro-max`）+ 浏览器证据驱动
- **截图存档**：`.gstack/ui-audit/2026-05-02/`
- **bundle 序列**：`1b17876bb3f3` → `419ccd2f08dd` → `5daa97f3a82e` → `13dc4c94ded6` → `ac7be4f7219c` → `82c583ecdd1f`

---

## 1. 执行路径

| 阶段 | 范围 | 产物 |
|------|------|------|
| A 审计 | 10 页 1440 + 375 视口实拍，输出 29 个 P0–P3 项 | `docs/ui-audit.md` |
| B 共享层强化 | 主题 / AppLayout / login / EmptyState / echarts reduced-motion | 7 文件 |
| C 页面深度 | Meters / Floorplan / Reports 收敛 | 3 文件 |
| P1 高优修复 | Tariff 时段标签 / Dashboard 视觉层级 / Admin 用户表 | 4 文件 |
| P2 中优修复 | DataTable ARIA / FilterBar sticky / 时间格式化 / Skeleton | 11 文件 |
| P3 抛光 | Header role tag / OrgTree code 截断 / 分页 showTotal | 6 文件 |
| Dark 修补 | FloorplanLive 容器背景 + 主题 token 增补 | 3 文件 |

---

## 2. 改动清单（按主题）

### 2.1 设计系统 / 主题
- `src/styles/theme-light.css` — 三级文字 `#8A95A0 → #6B7785`（4.7:1 AA）；全局 `:focus-visible` 2px brand outline；`tabular-nums` for table/statistic/input-number；新增 `--ems-color-muted: #F4F5F7`
- `src/styles/theme-dark.css` — 三级文字 `#6B7682 → #9AA4AF`（6.9:1 AAA）；新增 `--ems-color-muted: #1A2332`
- `src/styles/echarts/reduced-motion.ts` — 新建 `applyReducedMotion(option, reduced)`
- `src/hooks/useReducedMotion.ts` — 新建 MediaQuery 反应式 hook
- `src/hooks/useEcharts.ts` — 接入 reduced-motion

### 2.2 布局 / 全站
- `src/layouts/AppLayout.tsx` — Drawer/Sider 自适应（`useBreakpoint` md 切换）；Header 高度 80→56；Avatar 旁加 role Tag（ADMIN/FINANCE/OPERATOR/VIEWER 颜色编码）；右侧 Space 间距 middle→large
- `src/components/BrandLockup.tsx` — 头部变体 64→36 高，logo 56→28，shadow 替代白椭圆
- `src/pages/login/index.tsx` — `autoComplete="username"`/`"current-password"` + aria-label

### 2.3 共享组件
- `src/components/EmptyState.tsx` — 新建语义化空态（DatabaseOutlined / LineChart / PieChart / Inbox / Warning），新增 `compact` 模式（52px 单行）
- `src/components/DashboardSection.tsx` — 新建主指标视觉层级容器（`tone="primary"` 加 3px brand-color 左竖条 + 浅阴影）
- `src/components/DataTable.tsx` — `withAriaSort` 给 sortable 列注入 `aria-sort='none'` fallback
- `src/utils/format.ts` — 新增 `showTotal: (total, range) => "第 N-M 条 / 共 K 条"`

### 2.4 看板（Dashboard）
- `src/pages/dashboard/index.tsx` — 6 处 `<Card>` 包装替换为 `<DashboardSection>`，KPI / RealtimeSeries 标 `tone="primary"`
- `src/pages/dashboard/FilterBar.tsx` — `position:sticky; top:56; z-index:10; backdropFilter`
- `src/pages/dashboard/{KpiPanel,RealtimeSeriesPanel,EnergyCompositionPanel,TopNPanel}.tsx` — 空态切 `compact`，AntD `<Empty>` 全替换为 `<EmptyState>`
- `src/pages/dashboard/FloorplanLivePanel.tsx` — 容器背景 hardcoded `#fafafa` → `var(--ems-color-muted)`

### 2.5 业务页面
- `src/pages/meters/index.tsx` — 表格 size small + `scroll x:max-content`，操作列 180→120 (3 个 icon-only Tooltip 按钮)，pagination 加 `showTotal`
- `src/pages/floorplan/list.tsx` — 加载占位 4 张 Skeleton 卡格栅；删除按钮 hover 态 + aria-label；EmptyState 替代 AntD Empty
- `src/pages/floorplan/editor.tsx` — `<Spin>` → `<Skeleton>`
- `src/pages/report/index.tsx` — Space 间距 8→16；提交按钮 loading 文案
- `src/pages/report/{daily,monthly,yearly,shift}.tsx` — `<Spin>` → `<Skeleton active paragraph>`
- `src/pages/tariff/TariffTimeline.tsx` — 标签居中（absolute + transform）；whiteSpace:nowrap；textShadow 提对比；宽度 < 6% 不渲染；跨午夜段去重 `isPrimary`；tickbar 用 `--ems-color-text-tertiary` + tabular-nums；aria-label
- `src/pages/admin/users/list.tsx` — username 列 ellipsis + Tooltip + 自动 `E2E` Tag；最近登录用 dayjs `YYYY-MM-DD HH:mm`；状态/最近登录列宽收紧；pagination `showTotal`
- `src/pages/admin/users/permissions.tsx` — createdAt 列 `formatDateTime` + tabular-nums
- `src/pages/admin/users/edit.tsx` — `<Spin>` → `<Skeleton>`
- `src/pages/admin/audit/list.tsx` — pagination `showTotal`
- `src/pages/admin/audit/DetailModal.tsx` — 时间字段 `formatDateTime`
- `src/pages/alarms/rules.tsx` — 更新时间列 `formatDateTime` + tabular-nums
- `src/pages/bills/detail.tsx` — `<Spin>` → `<Skeleton>`
- `src/pages/bills/periods.tsx` — pagination `showTotal`
- `src/pages/orgtree/index.tsx` — Tree title React 节点（name + 截断 code + Tooltip + nodeType color tag）

---

## 3. 审计项收口对照

### P0 — Blocker (4/4 ✓)
| ID | 描述 | 状态 | 证据 |
|----|------|------|------|
| P0-01 | 移动端布局横向溢出 | ✓ | `AFTER-dashboard-375.png` / `AFTER-drawer-open-375.png` |
| P0-02 | 三级文字色 AA 不达标 | ✓ | theme-light/dark 双修 |
| P0-03 | 登录表单缺 autocomplete | ✓ | path B |
| P0-04 | 看板首屏空区 | ✓ | DashboardSection + EmptyState compact |

### P1 — High (11/11 ✓)
| ID | 描述 | 证据 |
|----|------|------|
| P1-01 | Header logo 白椭圆 | BrandLockup 修 |
| P1-02 | 行操作红描边删除 | `AFTER-c-meters-1440.png` |
| P1-03 | 空态打印机插画 | EmptyState 全替换 |
| P1-04 | 表计行高 50px | path C |
| P1-05 | ECharts 不响应 reduced-motion | useReducedMotion + applyReducedMotion |
| P1-06 | Header 80px lineHeight 锁 | path B |
| P1-07 | Tariff 标签压边 | `AFTER-P1-07-tariff-1440.png` |
| P1-08 | Floorplan 红删除 | path C |
| P1-09 | Dashboard 卡片层级 | `AFTER-P1-09-dashboard-1440.png` |
| P1-10 | Admin 用户表截断 | `AFTER-P1-10-admin-users-1440.png` |
| P1-11 | 报表按钮无 loading | path C |

### P2 — Medium (8/9 ✓, 1 backlog)
| ID | 描述 | 状态 |
|----|------|------|
| P2-01 | 看板空区折叠 | ✓ EmptyState compact `AFTER-P2-01-dashboard-compact.png` |
| P2-02 | 列表批量选择 | ⏸ backlog（需后端批量 API） |
| P2-03 | Dark mode 全页核验 | ✓ `AFTER-P2-03-dark-{dashboard,meters,tariff,users}.png` |
| P2-04 | 表头排序 ARIA | ✓ DataTable wrap (前瞻; 业务暂无 sorter) |
| P2-05 | 全局 focus ring | ✓ path B |
| P2-06 | FilterBar sticky | ✓ `AFTER-P2-06-sticky-scrolled.png` |
| P2-07 | tabular-nums | ✓ path B 全局 + 业务列 |
| P2-08 | ISO 时间戳格式化 | ✓ `AFTER-P2-08-audit-detail-modal.png` |
| P2-09 | Skeleton 替换 Spin | ✓ 7 处替换 |

### P3 — Polish (5/5 ✓)
| ID | 描述 | 状态 |
|----|------|------|
| P3-01 | 登录页垂直居中 | ✓（path B 已 `alignItems:center`） |
| P3-02 | Header 用户名加 role | ✓ `AFTER-P3-header-light.png` |
| P3-03 | 顶栏图标间距 16→24 | ✓ Space size large |
| P3-04 | OrgTree code 后缀展开 | ✓ `AFTER-P3-04-orgtree.png` |
| P3-05 | 分页上下文 | ✓ `AFTER-P3-05-pagination-full.png` |

### 执行中发现的 Dark mode 修补
| 项 | 描述 | 状态 |
|----|------|------|
| Dark FloorplanLive | 容器 hardcoded `#fafafa` 在暗色违和 | ✓ var(--ems-color-muted) 替换 |

---

## 4. 验证矩阵

| 检查 | 结果 | 工具 |
|------|------|------|
| TypeScript 类型检查 | ✓ 0 错误 | `tsc -b --noEmit` |
| ESLint | ✓ 0 错误 | `eslint src --ext ts,tsx` |
| 单元 / 集成测试 | ✓ 75/75 | `vitest --run` |
| 构建 | ✓ bundle 3.0M / gzip 962K | `vite build` |
| 部署 | ✓ 6 次 redeploy | `scripts/redeploy-frontend.sh --no-install` |
| 浏览器 1440 桌面 | ✓ 6 主页面 + dark 4 页 | playwright-headless |
| 浏览器 375 移动 | ✓ Drawer + 滚动验证 | playwright-headless |

---

## 5. 度量

- **总修改文件**：30 个（含新建 4 个：`EmptyState.tsx`、`DashboardSection.tsx`、`useReducedMotion.ts`、`echarts/reduced-motion.ts`）
- **审计 29 项**：P0 4/4、P1 11/11、P2 8/9、P3 5/5 → **28/29 (96.6%)**
- **测试覆盖**：75 tests pass，无新增回归
- **可访问性**：WCAG AA 文字对比度 ✓、aria-label 补足、reduced-motion ✓、tabular-nums 数字对齐 ✓
- **暗色模式**：4 主页面验证 + 1 修补
- **响应式**：375 / 1440 双视口验证

---

## 6. Backlog（未本轮收口）

| 项 | 原因 | 建议时机 |
|----|------|----------|
| P2-02 列表批量选择 | 需后端 `POST /meters/batch-enable` 等批量 API 配套；前端单加 `rowSelection` 无意义 | 与下一轮后端接口扩展同步 |
| 平面图实际图片 PNG/JPG 加载失败 | 后端图片资源 404，与前端样式无关 | 由 backend / 上传流程修 |
| RealtimeSeries 空 series 仍渲染 chart 容器 | `data?.length>0 但 series points 空` 走入 render path | 后端契约对齐 + 前端加 `data.every(s => !s.points?.length)` 判断 |

---

## 7. 关键架构决策

1. **EmptyState `compact` mode**：避免空数据状态下看板上半屏被 4 张大空插画占满；横排 52px vs 默认 vertical 100+px。
2. **DashboardSection tone**：用左竖条 + 浅阴影建立视觉层级，比修改各 Panel 内部布局更稳（KPI / RealtimeSeries 主指标 vs 次区）。
3. **showTotal 抽 utils**：避免 6+ 处分页表各自硬编码 `(t) => 共 ${t} 条`，且文案保留页内位置（"第 N-M 条 / 共 K 条"）。
4. **TariffTimeline absolute centering**：保持 div 结构（与 AntD `Tooltip` 兼容），通过 `position:absolute + transform translate(-50%,-50%)` 严格居中，比改用 SVG 改动小且 a11y 一致。
5. **CSS 变量 `--ems-color-muted` 作为新增 token**：替代各处硬编码 `#fafafa` / `#f0f0f0`，保证暗色模式自动切换。

---

## 8. 文件位置索引

- 审计原文：`docs/ui-audit.md`
- 截图存档：`.gstack/ui-audit/2026-05-02/`
- 本执行报告：`docs/ui-audit-execution.md`
- 共享层强化：`src/styles/`、`src/components/EmptyState.tsx`、`src/components/DashboardSection.tsx`、`src/layouts/AppLayout.tsx`
- 工具函数：`src/utils/format.ts`、`src/hooks/useReducedMotion.ts`
