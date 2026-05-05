# Factory EMS Frontend — UI/UX 审计报告

- **审计日期**：2026-05-02
- **审计基准**：UI/UX Pro Max 10 大优先级（A11y → Touch → Perf → Style → Layout → Type/Color → Animation → Forms → Nav → Charts）
- **采样视口**：1440×900（桌面）+ 375×812（移动）
- **审计范围**：登录、看板、表计、组织树、报表、报警健康、采集、平面图、电价、用户管理（共 10 页）
- **截图存档**：`.gstack/ui-audit/2026-05-02/*.png`
- **设计系统结论（来自 UI/UX Pro Max）**：Data-Dense Dashboard 风格，与现状一致 —— 不需要换风格，需要补缺与精修。

> 与 `docs/qa/2026-04-30-redesign-issues.md`（4-30 重设计验收日志）配套：那篇覆盖代码层 QA（lint/test/build），本篇覆盖业务页面在后端栈就位后的 UX 审计。

---

## 严重等级定义

| 等级 | 含义 | 处理 |
|------|------|------|
| 🔴 P0 — Blocker | 影响核心可用性、可达性 AA、或导致页面无法使用 | 必须修，单独 PR |
| 🟠 P1 — High | 影响专业感、效率或多页一致性 | 列入下一迭代 |
| 🟡 P2 — Medium | 局部体验问题、可改进 | 与 P1 一同收口 |
| ⚪ P3 — Polish | 锦上添花 | 闲时处理 |

---

## P0 — Blocker（4 项）

### P0-01 移动端布局完全坏掉（横向溢出，主内容仅 120px 宽）
- **现象**：`AppLayout` 在 375px 视口下 Sider 仍占 240px 宽不收起，主内容被挤到 120px，文档 `scrollWidth = 593`，**整页横向滚动**。截图：`11-dashboard-375.png`。
- **违反规则**：`horizontal-scroll`（无横向滚动）、`mobile-first`、`viewport-units`、`adaptive-navigation`（≤768 应转 Drawer）。
- **根因**：`src/layouts/AppLayout.tsx` 用 `<Sider collapsed={collapsed} />`，没有 useBreakpoint 自动切换，移动端无 Drawer。
- **修法（最小）**：用 AntD `useBreakpoint()` + `Drawer` 替换 `Sider`，<768 自动收起为抽屉；hamburger 按钮已有，仅需切目标。

### P0-02 三级文字色 `#8A95A0` 在白底对比度 ~3.4:1 — 不达 WCAG AA
- **现象**：`--ems-color-text-tertiary: #8A95A0`（用于 helper text、placeholder、空态副标题）对 `#FFFFFF` 仅 3.4:1，正文需 ≥4.5:1。
- **违反规则**：`color-contrast`、`color-accessible-pairs`、`contrast-readability`。
- **修法**：把 `--ems-color-text-tertiary` 改为 `#6B7785`（约 4.7:1）。Dark mode 同步审视。

### P0-03 登录表单缺 `autocomplete` 属性 → 浏览器无法记住、密码管理器失效
- **现象**：Chrome verbose 日志直接报警 `[DOM] Input elements should have autocomplete attributes (suggested: "current-password")`。
- **违反规则**：`autofill-support`、`input-type-keyboard`。
- **修法**：`pages/login/index.tsx` 用户名加 `autoComplete="username"`，密码加 `autoComplete="current-password"`。<5 行改动。

### P0-04 看板核心区域占满 33% 横向却内容空白（采集健康卡片）
- **现象**：截图 `02-dashboard-1440.png` 第一行 `采集健康` Card 用 `xs={24} lg={8}`，剩 16/24 栅格永远是空白；KPI 区显示"暂无 KPI 数据"+ 打印机图标占满整行 100px+，构成一种"专业感塌陷"。
- **违反规则**：`primary-action`、`empty-data-state`、`visual-hierarchy`、`content-priority`。
- **修法**：① 第一行整行排 4 个状态卡片（在线/离线/报警/维护）—— `alarms/health` 页本身就是这 4 卡片，看板首屏可直接复用；② 空态用 AntD `<Empty>` 但替换图标为 `LineChartOutlined`/`DatabaseOutlined`，文案改"等待数据接入"+ 操作按钮"配置测点 →"。

---

## P1 — High（11 项）

### P1-01 头部 Logo 在 navy 背景上有显眼白色椭圆底
- **现象**：`BrandLockup` 在导航栏（`--ems-color-bg-header: #0E1A2B` 深蓝）显示时仍带白色椭圆背景，像贴了一张未抠图的图标。`02-dashboard-1440.png` 极清晰可见。
- **违反规则**：`brand-correctness`、`elevation-consistent`、`vector-only-assets`。
- **修法**：`components/BrandLockup.tsx` 在 `variant="header"` 路径下输出去底版本（白色 mark + 白字），或在 `theme-light.css` 给 `.ems-brand-header img { mix-blend-mode: screen }` 临时遮蔽。最佳方案：补一个 `logo-on-dark.svg`。

### P1-02 表计/用户/平面图列表的 `删除` 按钮全部红描边红字
- **现象**：每行 1 个红描边按钮，3 个行操作里 1/3 视觉权重永远是危险动作；54 行 = 屏幕上 54 块红色。
- **违反规则**：`destructive-emphasis`（应在最末 + 视觉分离，不该跟普通操作并列同样字号）、`primary-action`（每屏只一处主 CTA）。
- **修法**：① 行操作里"删除"改为图标按钮 `<Button type="text" danger icon=<DeleteOutlined/>>`，无文字；② 鼠标悬停才显红；③ 复杂场景再升级到 hover-action 套餐。统一改 `components/DataTable.tsx` 的 actions 渲染规则。

### P1-03 空态全部沿用 AntD 默认"打印机/收件箱"插画
- **现象**：看板 4 处、采集列表、报表查询前都是同一张打印机图。看板"暂无 KPI 数据"`02-dashboard-1440.png` 截图 row 1。
- **违反规则**：`no-emoji-icons`（同理：默认插画与产品语义无关）、`empty-data-state`（应包含 guidance + action）。
- **修法**：建一个共享 `<EmptyState kind="no-data" | "no-result" | "error" action=<Button/>>` 组件，按场景给图（Lucide `Inbox` / `Database` / `LineChart`）+ 一行文案 + 主动作。

### P1-04 表计列表行高 ~50px、操作列 3 个文字按钮挤一行
- **现象**：`03-meters-1440.png` 表格 18 行 ≈ 900px 纵高，编辑/绑父/删除按钮等宽分布。
- **违反规则**：`spacing-scale`、`whitespace-balance`、`touch-density`。
- **修法**：① 改 `<Table size="middle">` → `size="small"`（行高 32→44），② 操作列改图标组（48px 总宽），③ 把"批量导入/新建测点"沉到右侧 `<Toolbar>` 区，统一"主页面 = `<PageHeader title actions>`"骨架。

### P1-05 看板 ECharts 不响应 prefers-reduced-motion
- **现象**：`echarts` 默认 1000ms 动画对运维大屏长期播放显示，工厂车间显示器易触发眩晕投诉。
- **违反规则**：`reduced-motion`、`animation-optional`、`motion-meaning`。
- **修法**：在 `styles/echarts/` 下新增 `getReducedMotionOption()`，全部 chart 配置统一通过 `useMatchMedia('(prefers-reduced-motion: reduce)')` 关闭 animation。

### P1-06 头部高度 80px 偏大 + line-height 80px 锁定
- **现象**：`AppLayout.tsx:163` `height: 80, lineHeight: '80px'` 浪费纵向空间，且锁 lineHeight 让头部内任何弹层定位偏移。
- **违反规则**：`scroll-behavior`、`fixed-element-offset`、`whitespace-balance`。
- **修法**：高度收到 56px（AntD 默认），用 flex `align-items: center` 取代 lineHeight。

### P1-07 Tariff 24h 时段条标签压在彩条边缘
- **现象**：`09-tariff-1440.png` 中"尖峰/平段/高峰/低谷/平段"小标签部分被压在色条边缘，时段交界处的标签被截。
- **违反规则**：`truncation-strategy`、`letter-spacing`、`number-tabular`、`label-readability`。
- **修法**：`pages/tariff/TariffTimeline.tsx` 标签改成 svg `<text>` + `text-anchor="middle"`，最小段宽 < 6% 时改用 tooltip 提示；色条高度从 24px 提到 32px。

### P1-08 Floorplan 卡片"删除"红描边 + 缩略图占位是灰底无图
- **现象**：`08-floorplan-1440.png` 10 张卡片全是灰底空缩略图；删除按钮压在左下，跟"编辑测点"等权。
- **违反规则**：`primary-action`、`destructive-emphasis`、`progressive-loading`、`image-optimization`。
- **修法**：① 缩略图加 `loading="lazy"` + skeleton；② 真实没图片时显示 SVG 楼层图标占位；③ 删除收到 hover 时才出现的 toolbar。

### P1-09 看板 KPI/Sankey/Floorplan-Live/Cost 4 个并列 Card 零差异
- **现象**：所有 Card 都是 `<Card size="small" bodyStyle={{ padding: 16 }}>`，无视觉层级差。看板首屏认知压力大。
- **违反规则**：`visual-hierarchy`、`section-spacing-hierarchy`。
- **修法**：① 抽 `<DashboardSection title actions span><Card>...</Card></DashboardSection>` 抽屉式组件；② 主指标区配粗边框/小肩标，次区无边框背景。

### P1-10 用户管理：用户名带 `_1777255469334` 时间戳，列宽固定 → 截断
- **现象**：`10-admin-users-1440.png` 第一列 `e2e_viewer_1777255469334` 单行显示但列宽不足 → 视觉碎片。
- **违反规则**：`truncation-strategy`、`number-tabular`、`number-formatting`。
- **修法**：① 列宽自适应 + tooltip 全名；② 用 `font-variant-numeric: tabular-nums`；③ 测试用户加 `e2e:` 前缀色块标识，与真用户视觉分流。

### P1-11 报表"即席查询"按钮点击无 loading 反馈
- **现象**：`05-report-1440.png` 表单提交后没有 `loading` 状态切换的可见证据；同步导出按钮 `导 出` 与"重 置"两按钮间距相邻、误触风险。
- **违反规则**：`loading-buttons`、`submit-feedback`、`touch-spacing`。
- **修法**：`pages/report/index.tsx` Submit 按钮包 `<Button loading={mutation.isPending}>`；两按钮间距改 `gap: 12px`（AntD `Space size="middle"`）。

---

## P2 — Medium（9 项）

### P2-01 Dashboard 第一行 Card 后跟 100px+ 高度的"暂无 KPI 数据"再跟"实时能耗曲线"也是空 → 上半屏 60% 都是占位
- 修法：所有 Panel 加最小数据契约：完全无数据 → 折叠到 60px 高条幅 +"配置 →"链接，避免 4 张大空卡片堆叠。

### P2-02 表计/用户列表无批量选择
- **违反**：B2B 数据密集页缺 `select-all` + 批量操作（启用/停用/删除）。
- 修法：`<Table rowSelection>` + 顶栏批量动作。

### P2-03 暗色主题未实测
- 现状：`tokens.ts` 已有 `DARK_TOKEN`，但本次审计未覆盖。Token 设置 `colorBgBase: #141C28` 与 `siderBg: #0F1722` 已是合理深度。
- 修法：单独跑一次 dark-mode 全页快照，对照 P0-02 重测对比度。

### P2-04 表头排序 ARIA 缺失
- 现象：列表头点击排序无 `aria-sort` 属性。
- 修法：`DataTable.tsx` `columns` 加上 sorter 时透传 `aria-sort`。

### P2-05 焦点可见环（Focus ring）依赖 AntD 默认
- 现状：AntD 默认聚焦环宽度 1-2px，工厂车间高分辨率显示器看不清。
- 修法：全局 CSS `:focus-visible { outline: 2px solid var(--ems-color-brand-primary); outline-offset: 2px; }`。

### P2-06 看板顶部 FilterBar 无 sticky
- 现象：滚动到 Sankey/Cost 区时筛选栏不可见，重新切换组织/能源类型要回顶。
- 修法：FilterBar 容器 `position: sticky; top: 0; z-index: 10`。

### P2-07 数字栏未启用 tabular-nums
- 现象：用户管理"最近登录"`2026-04-27T02:04:35.34805Z`、表计 `0.00 KWh` 列在不同行宽度对不齐。
- 修法：`tokens.ts` `bodyStyle` 全局加 `font-variant-numeric: tabular-nums`，或在 `theme-light.css` 类 `.ems-num` 上启用。

### P2-08 时间戳显示 ISO `2026-04-27T02:04:35.34805Z` 给业务用户看
- 修法：用 `dayjs().format('YYYY-MM-DD HH:mm:ss')`，显示 `2026-04-27 02:04:35`。

### P2-09 没有 Skeleton，加载态全是 `<Spin>`
- 现象：`App.tsx` 启动也是 fullscreen Spin；Dashboard 各 Panel 用 AntD `loading` 占位（旋转 dot）。
- 违反规则：`progressive-loading`、`content-jumping`。
- 修法：Card 内 fall-back 用 `<Skeleton active>`；启动屏维持现状即可。

---

## P3 — Polish（5 项）

- **P3-01** 登录页右半侧大段留白，垂直未居中 → 表单 `top: 35%` 改 `top: 50%, transform: translateY(-50%)`。
- **P3-02** Header 右上 `admin` 用户名前可加 role tag（`ADMIN`/`OPERATOR`），多角色对身份明确。
- **P3-03** 顶栏图标行（主题/报警/用户）三者间距 16，建议 24，呼吸感更好。
- **P3-04** Org Tree 节点显示 `(E2E_ROOT_1777095324887) [PLANT]` 的 ID 串过长 → 改成可悬停展开模式。
- **P3-05** Pagination 默认 `1` 单页，建议显示 `共 N 条 · 第 1 页` 上下文。

---

## 共享层修复优先级（推荐 B 路径执行清单）

| 序号 | 文件 | 受益面 | 时间 |
|------|------|---------|------|
| 1 | `src/layouts/AppLayout.tsx` | 全站移动端可用（修 P0-01） | 30 分钟 |
| 2 | `src/styles/theme-light.css` + `theme-dark.css` | 修 P0-02 + 加 focus ring（P2-05） | 10 分钟 |
| 3 | `src/pages/login/index.tsx` | 修 P0-03 autocomplete | 5 分钟 |
| 4 | 新建 `src/components/EmptyState.tsx` | 修 P1-03 全站空态 | 30 分钟 |
| 5 | 新建 `src/components/PageToolbar.tsx`（统一行操作 + 批量） | 修 P1-02 / P1-04 / P2-02 | 45 分钟 |
| 6 | `src/components/DataTable.tsx` | aria-sort + tabular-nums + actions 收敛（P1-02 / P2-04 / P2-07） | 30 分钟 |
| 7 | `src/components/BrandLockup.tsx` + 暗版 SVG | 修 P1-01 | 20 分钟 |
| 8 | `src/styles/echarts/`（新建 helper） | 修 P1-05 reduced-motion | 20 分钟 |

**估算共享层升级总时长：~3 小时**，影响所有页面。

---

## 不在本次审计范围

- Dark mode 全页面快照（待 P2-03 单独执行）
- 键盘流（Tab Order）端到端
- 真实数据下的图表性能（当前为空数据）
- 浏览器兼容（仅在 Chromium 165 测试）

## 下一步建议

按 **B 路径（共享层强化）** 执行上表 1-8 项即可一次性消化 P0/P1 大半，且无业务逻辑回归风险。完成后再回到 C 路径挑高优页面深度优化。
