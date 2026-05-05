# Factory-EMS 前端全站重设计 设计规范

**日期：** 2026-04-30
**主题：** 参考 Siemens iX 工业化扁平风格 + 双主题（浅/深），覆盖 14 个业务页面与共享组件
**目标分支：** `feat/frontend-redesign`（基于 `feat/ems-observability`）
**备份点：** Tag `backup/pre-redesign-2026-04-30`

---

## 1. 目标

将 factory-ems 前端从当前 Ant Design 默认风格升级为**工业化扁平双主题**界面：

- **品牌**：松羽科技集团 · 能源管理系统
- **风格**：参考西门子 iX Design System（高对比、低饱和、信息密度优先）
- **主题**：浅色（默认）+ 深色（监控大屏友好），用户可手动切换、记忆偏好
- **覆盖**：14 个业务页面 + AppLayout + Login + 所有共享组件 + ECharts 图表 + Konva 平面图
- **不破坏**：保留现有路由、API 契约、状态管理、组件树。仅替换视觉令牌、布局壳、主题机制。

## 2. 架构总览

**核心机制**：单一 token 源 → 三路分发

```
ThemeStore (Zustand, localStorage)
   ├─→ AntD ConfigProvider (运行时主题切换)
   ├─→ CSS variables (--ems-*) (布局壳/自定义组件)
   ├─→ ECharts theme (registerTheme + setOption)
   └─→ Konva tokens (常量导出供 floorplan 使用)
```

**密度分层**（Q3 决议）：按路由前缀决定 AntD `componentSize`

| 路由前缀 | 密度 | 适用 |
|---|---|---|
| `/dashboard`、`/collector`、`/floorplan`、`/alarms` | `small` | 监控大屏，信息密度优先 |
| `/admin`、`/meters`、`/orgtree`、`/tariff`、`/report` | `middle` | 配置管理，可读性优先 |
| `/login`、`/profile` | `middle` | 表单为主 |

**迁移策略**（Q1 决议）：Big-bang

- 单个 PR 替换所有页面外观
- 不保留旧主题开关或兼容层
- 在新分支上滚动开发，合并前完成全部页面验证

## 3. 技术栈

保留现有：React 18 + Vite + TypeScript + AntD 5.20 + ECharts 5.6 + Konva 10 + React Router 6 + React Query 5 + Zustand。

新增依赖：**无**（所有新机制基于 AntD `theme` token + 原生 CSS 变量）。

## 4. 设计令牌

### 4.1 颜色 — 浅色主题（默认）

| Token | 值 | 用途 |
|---|---|---|
| `--ems-color-brand-primary` | `#007D8A` | Petrol，主品牌色，按钮、链接、激活态 |
| `--ems-color-brand-accent` | `#FFD732` | Yellow，强调/警示辅助 |
| `--ems-color-bg-page` | `#F4F5F7` | 页面背景 |
| `--ems-color-bg-container` | `#FFFFFF` | 卡片/表格容器 |
| `--ems-color-bg-elevated` | `#FFFFFF` | 弹窗/抽屉 |
| `--ems-color-bg-header` | `#0E1A2B` | 顶栏深底（品牌区） |
| `--ems-color-bg-sider` | `#FFFFFF` | 侧边栏 |
| `--ems-color-text-primary` | `#16181D` | 主文 |
| `--ems-color-text-secondary` | `#5A6470` | 次文 |
| `--ems-color-text-tertiary` | `#8A95A0` | 辅助/占位 |
| `--ems-color-text-on-header` | `#FFFFFF` | 顶栏白文 |
| `--ems-color-border` | `#E2E5E9` | 分割线/边框 |
| `--ems-color-border-strong` | `#C9CED4` | 强边框 |
| `--ems-color-success` | `#1B7A3E` | 正常 |
| `--ems-color-warning` | `#E37A00` | 报警 |
| `--ems-color-error` | `#C8201F` | 严重 |
| `--ems-color-info` | `#0E6BB8` | 提示 |

### 4.2 颜色 — 深色主题

| Token | 值 | 用途 |
|---|---|---|
| `--ems-color-brand-primary` | `#00C2CC` | Cyan，深底高亮 |
| `--ems-color-brand-accent` | `#FFD732` | 同浅色 |
| `--ems-color-bg-page` | `#0A1018` | 页面背景 |
| `--ems-color-bg-container` | `#141C28` | 卡片 |
| `--ems-color-bg-elevated` | `#1A2536` | 弹窗 |
| `--ems-color-bg-header` | `#06090F` | 顶栏 |
| `--ems-color-bg-sider` | `#0F1722` | 侧边栏 |
| `--ems-color-text-primary` | `#E8ECF1` | 主文 |
| `--ems-color-text-secondary` | `#A8B2BD` | 次文 |
| `--ems-color-text-tertiary` | `#6B7682` | 辅助 |
| `--ems-color-text-on-header` | `#FFFFFF` | 顶栏白文 |
| `--ems-color-border` | `#252F3E` | 边框 |
| `--ems-color-border-strong` | `#3A4658` | 强边框 |
| `--ems-color-success` | `#3DCB6E` | 正常 |
| `--ems-color-warning` | `#FFA940` | 报警 |
| `--ems-color-error` | `#FF6464` | 严重 |
| `--ems-color-info` | `#4FA8FF` | 提示 |

### 4.3 字体

| Token | 值 |
|---|---|
| `--ems-font-family-sans` | `"PingFang SC", "Microsoft YaHei", "Helvetica Neue", "Segoe UI", system-ui, sans-serif` |
| `--ems-font-family-mono` | `"JetBrains Mono", "SF Mono", "Cascadia Mono", Consolas, monospace`（用于数值/编号） |
| `--ems-font-size-xs` | `12px` |
| `--ems-font-size-sm` | `13px` |
| `--ems-font-size-base` | `14px` |
| `--ems-font-size-lg` | `16px` |
| `--ems-font-size-xl` | `20px` |
| `--ems-font-size-display` | `28px` |
| `--ems-font-weight-regular` | `400` |
| `--ems-font-weight-medium` | `500` |
| `--ems-font-weight-bold` | `600` |

### 4.4 圆角

| Token | 值 | 用途 |
|---|---|---|
| `--ems-radius-sm` | `2px` | 小元素（标签、徽章） |
| `--ems-radius-base` | `4px` | 按钮、输入框、卡片（**工业扁平**） |
| `--ems-radius-lg` | `6px` | 弹窗 |
| `--ems-radius-pill` | `9999px` | 胶囊状态、Logo 白底 |

### 4.5 间距

| Token | 值 |
|---|---|
| `--ems-space-1` | `4px` |
| `--ems-space-2` | `8px` |
| `--ems-space-3` | `12px` |
| `--ems-space-4` | `16px` |
| `--ems-space-5` | `24px` |
| `--ems-space-6` | `32px` |
| `--ems-space-7` | `48px` |

### 4.6 阴影

工业风克制使用阴影，仅在浮层。

| Token | 浅色 | 深色 |
|---|---|---|
| `--ems-shadow-1` | `0 1px 2px rgba(14,26,43,0.06)` | `0 1px 2px rgba(0,0,0,0.4)` |
| `--ems-shadow-2` | `0 4px 12px rgba(14,26,43,0.10)` | `0 4px 12px rgba(0,0,0,0.5)` |
| `--ems-shadow-popup` | `0 8px 24px rgba(14,26,43,0.16)` | `0 8px 24px rgba(0,0,0,0.6)` |

## 5. 主题切换机制

### 5.1 ThemeStore（Zustand）

```typescript
// src/stores/themeStore.ts
type ThemeMode = 'light' | 'dark';

interface ThemeStore {
  mode: ThemeMode;
  setMode: (mode: ThemeMode) => void;
  toggle: () => void;
}
```

- 持久化键：`ems.theme.mode`，存于 `localStorage`
- 首次访问：检测 `prefers-color-scheme`，写入持久化
- 切换：触发 `setMode` → 同步更新 AntD ConfigProvider + `<html data-theme="...">` 属性

### 5.2 AntD ConfigProvider

`App.tsx` 顶层包裹：

```tsx
<ConfigProvider
  theme={{
    algorithm: mode === 'dark' ? theme.darkAlgorithm : theme.defaultAlgorithm,
    token: emsAntdTokens[mode],
    components: emsAntdComponentTokens[mode],
  }}
  componentSize={densityForRoute(currentPath)}
  locale={zhCN}
>
```

### 5.3 CSS 变量

`<html data-theme="light|dark">` 触发 `:root[data-theme="..."]` 块切换，所有自定义组件读取 `var(--ems-*)`。

### 5.4 ECharts

注册 `ems-light` 与 `ems-dark` 两个主题，组件根据 mode 选择。

### 5.5 Konva

导出 `floorplanTokens.ts`，从 ThemeStore 订阅，平面图重绘时使用最新颜色。

### 5.6 切换 UI

顶栏右侧添加 `<ThemeToggle />` 按钮，太阳/月亮图标，点击即切换。

## 6. 视觉密度（Q3）

`useDensity()` Hook：根据 `useLocation().pathname` 返回 `'small' | 'middle'`，由 AppLayout 传给 `ConfigProvider componentSize`。

```typescript
const COMPACT_PREFIXES = ['/dashboard', '/collector', '/floorplan', '/alarms'];
function densityForRoute(pathname: string): 'small' | 'middle' {
  return COMPACT_PREFIXES.some(p => pathname.startsWith(p)) ? 'small' : 'middle';
}
```

## 7. 品牌识别

### 7.1 Logo 处理

- 资源：`frontend/src/assets/logo.png`（松羽集团橙黑标）
- 顶栏（深底）：**白色胶囊容器**包裹 Logo
  - 容器：`background: #FFFFFF; padding: 4px 12px; border-radius: 9999px;`
  - 行高：32px，垂直居中
  - 紧邻"能源管理系统"白色文字
- 登录页（浅底）：**大尺寸**直接展示
  - 宽度：240px，高度按比例
  - 上方居中，下方间距 24px 接系统名

### 7.2 文案规范

- **公司名**：松羽科技集团（默认完整使用，不缩写）
- **系统名**：能源管理系统（替换原"能管"）
- **品牌锁定**：
  - 顶栏品牌区：`[Logo] 能源管理系统`
  - 登录页 Hero：`[大 Logo]` + `能源管理系统` 标题 + `松羽科技集团` 副标
  - 页脚：`© 2026 松羽科技集团 · 能源管理系统 v1.7.0`

### 7.3 浏览器标题

格式：`{页面名} - 能源管理系统` （系统优先，不带公司名以保持简短）

`document.title` 由 `useDocumentTitle(pageName)` Hook 在每个路由组件挂载时设置。

## 8. 中文本地化映射

完整映射表保存在 `frontend/src/utils/i18n-dict.ts`。关键示例：

| 后端枚举 | UI 显示 |
|---|---|
| `ACTIVE` / `INACTIVE` | 在线 / 离线 |
| `OPEN` / `ACK` / `CLEARED` | 未处理 / 已确认 / 已恢复 |
| `CRITICAL` / `MAJOR` / `MINOR` / `WARNING` | 严重 / 重要 / 次要 / 提醒 |
| `MODBUS_TCP` / `MODBUS_RTU` | Modbus TCP / Modbus RTU（保留协议名） |
| Dashboard / Collector / Floorplan | 综合看板 / 数据采集 / 设备分布图 |
| Alarms / Meters / Cost / Bills | 报警 / 表计 / 成本核算 / 账单 |
| Tariff / Report / Admin | 电价 / 报表 / 系统管理 |

**保留原文**：API 路径、配置 key、SI 单位（kWh/kW/V/A/Hz）、ISO 时间戳、设备标识符。

## 9. 共享组件规范

### 9.1 AppLayout

- **顶栏**（高 56px，bg-header 深色）
  - 左：白色胶囊 Logo + "能源管理系统" 白文（18px Medium）
  - 中：面包屑（白文 70% 不透明度）
  - 右：主题切换 + 通知铃铛 + 用户头像下拉
- **侧栏**（默认展开 240px，可折叠至 64px）
  - bg-sider，菜单图标 + 文字
  - 选中项：左 3px Petrol/Cyan 实条 + bg-container-hover
- **内容区**
  - 默认 padding 24px
  - 监控页 padding 16px

### 9.2 Login

- 全屏分屏：左 60% 视觉区（深底 + 工厂剪影背景图，可后续替换），右 40% 表单区
- 视觉区：大 Logo（240px）+ "能源管理系统" 28px 白文 + "松羽科技集团" 14px 70% 白文
- 表单区：白底 + 容器卡片（最大宽 400px）+ 用户名/密码/记住我 + 登录按钮（Petrol 实色、4px 圆角）
- 失败提示：表单上方 inline alert

### 9.3 Dashboard

- 顶部 4 个 KPI Card（电、水、气、综合能耗）
  - 大数字（28px Mono）+ 趋势箭头 + 与昨日对比
- 主图区 2x2：用电曲线（折线）、能源结构（环形）、车间对比（条形）、报警实时流（List）
- ECharts 主题统一：背景透明、网格淡色、字色用 var(--ems-color-text-secondary)

### 9.4 表格模板

- 工具条：左侧筛选 chips + 右侧搜索/导出/刷新
- 表头：bg-container 深色描边、文字 secondary
- 行高：紧凑 36px / 标准 44px
- 状态列：胶囊徽章（背景 = state color 12% alpha，文字 = state color）
- 操作列：text button + 图标，避免过多颜色
- 分页：右下角 default 大小

### 9.5 表单模板

- 卡片容器（4px 圆角）+ Section 分组标题（border-bottom）
- 标签左对齐、宽 120px
- 必填红星
- 底部固定 sticky footer：取消（次按钮）+ 保存（主按钮）
- 校验失败：inline 红字 + 输入框红边

### 9.6 ECharts 主题

#### Light (`ems-light`)

```json
{
  "color": ["#007D8A", "#FFD732", "#0E6BB8", "#1B7A3E", "#E37A00", "#9B59B6", "#34495E", "#E84C5E"],
  "backgroundColor": "transparent",
  "textStyle": { "color": "#5A6470", "fontFamily": "PingFang SC, sans-serif" },
  "title": { "textStyle": { "color": "#16181D", "fontWeight": 600 } },
  "legend": { "textStyle": { "color": "#5A6470" } },
  "axisPointer": { "lineStyle": { "color": "#C9CED4" } },
  "categoryAxis": {
    "axisLine": { "lineStyle": { "color": "#E2E5E9" } },
    "axisTick": { "lineStyle": { "color": "#E2E5E9" } },
    "axisLabel": { "color": "#5A6470" },
    "splitLine": { "show": false }
  },
  "valueAxis": {
    "axisLine": { "show": false },
    "axisTick": { "show": false },
    "axisLabel": { "color": "#8A95A0" },
    "splitLine": { "lineStyle": { "color": "#E2E5E9", "type": "dashed" } }
  }
}
```

#### Dark (`ems-dark`)

```json
{
  "color": ["#00C2CC", "#FFD732", "#4FA8FF", "#3DCB6E", "#FFA940", "#C77DFF", "#A8B2BD", "#FF6464"],
  "backgroundColor": "transparent",
  "textStyle": { "color": "#A8B2BD", "fontFamily": "PingFang SC, sans-serif" },
  "title": { "textStyle": { "color": "#E8ECF1", "fontWeight": 600 } },
  "legend": { "textStyle": { "color": "#A8B2BD" } },
  "axisPointer": { "lineStyle": { "color": "#3A4658" } },
  "categoryAxis": {
    "axisLine": { "lineStyle": { "color": "#252F3E" } },
    "axisTick": { "lineStyle": { "color": "#252F3E" } },
    "axisLabel": { "color": "#A8B2BD" },
    "splitLine": { "show": false }
  },
  "valueAxis": {
    "axisLine": { "show": false },
    "axisTick": { "show": false },
    "axisLabel": { "color": "#6B7682" },
    "splitLine": { "lineStyle": { "color": "#252F3E", "type": "dashed" } }
  }
}
```

### 9.7 Konva 平面图令牌

`src/utils/floorplanTokens.ts` 导出：

```typescript
export const floorplanTokens = (mode: 'light' | 'dark') => ({
  bgFill: mode === 'light' ? '#F4F5F7' : '#0A1018',
  gridStroke: mode === 'light' ? '#E2E5E9' : '#252F3E',
  deviceFill: mode === 'light' ? '#FFFFFF' : '#141C28',
  deviceStroke: mode === 'light' ? '#007D8A' : '#00C2CC',
  deviceStrokeAlarm: mode === 'light' ? '#C8201F' : '#FF6464',
  labelText: mode === 'light' ? '#16181D' : '#E8ECF1',
});
```

平面图组件订阅 `useThemeStore` 并在重绘时传入。

## 10. 文件结构变更

### 新增

```
frontend/src/
├── styles/
│   ├── theme-light.css       # :root[data-theme="light"] 变量
│   ├── theme-dark.css        # :root[data-theme="dark"] 变量
│   ├── tokens.ts             # 集中导出 antd token + ems token
│   └── echarts/
│       ├── ems-light.ts      # registerTheme 调用
│       └── ems-dark.ts
├── stores/
│   └── themeStore.ts         # Zustand 主题 store
├── components/
│   ├── ThemeToggle.tsx       # 顶栏切换按钮
│   ├── BrandLockup.tsx       # Logo + 系统名 + 公司名 组合
│   ├── PageHeader.tsx        # 标准页头（标题 + 面包屑 + 操作）
│   ├── KpiCard.tsx           # 看板 KPI 卡片
│   ├── StatusTag.tsx         # 状态胶囊徽章
│   └── DataTable.tsx         # 表格模板封装
├── hooks/
│   ├── useDocumentTitle.ts   # 浏览器标题
│   ├── useDensity.ts         # 路由密度
│   └── useEchartsTheme.ts    # 选取 ECharts 主题
├── utils/
│   ├── i18n-dict.ts          # 中文映射表
│   └── floorplanTokens.ts    # Konva 颜色令牌
└── assets/
    └── logo.png              # 已就位（松羽集团 logo）
```

### 修改

- `App.tsx` — ConfigProvider 包裹 + 主题/密度联动
- `layouts/AppLayout.tsx` — 顶栏品牌、菜单视觉、ThemeToggle、面包屑
- `pages/login/*` — 分屏布局 + 大 Logo + 中文文案
- `pages/dashboard/*` — KpiCard + ECharts 主题接入
- 其他 13 个页面（`alarms/`、`bills/`、`collector/`、`cost/`、`floorplan/`、`home/`、`meters/`、`orgtree/`、`production/`、`profile/`、`report/`、`tariff/`、`admin/`）— 替换页头、表格/表单使用模板组件、文案改中文
- `index.html` — 默认 title 改 `能源管理系统`
- `vite.config.ts` — 不变（无新依赖）

### 删除

无（旧主题机制将由新机制替换，不保留兼容）。

## 11. 测试策略

### 单元测试（Vitest + Testing Library）

- `themeStore.test.ts` — 切换、持久化、`prefers-color-scheme` 初始化
- `useDensity.test.ts` — 路由前缀映射
- `useDocumentTitle.test.ts` — 标题格式
- `ThemeToggle.test.tsx` — 点击触发切换
- `BrandLockup.test.tsx` — Logo 容器、文案变体
- `StatusTag.test.tsx` — 状态颜色映射
- `KpiCard.test.tsx` — 数值/趋势渲染
- 每个改造页面：smoke render + 关键交互

**目标覆盖率**：单元 80%+。

### 集成测试

- `App.test.tsx` — ConfigProvider 切换、densityHook 联动
- 路由级 smoke：所有 14 个页面 mount 不崩溃，`screen.getByText('能源管理系统')` 命中

### 视觉回归（人工）

- 浅色模式截图 14 页 + 深色模式截图 14 页 + Login + Dashboard 大屏 → 存档 `docs/qa/screenshots/2026-04-30-redesign/`
- 与 `.superpowers/brainstorm/.../mockup-*.html` 对照，差异在 PR 描述说明

### 浏览器验证（gstack）

- `npm run dev` 启动后用 gstack 在浏览器打开：
  - 切换浅/深主题，验证全局响应
  - 登录、Dashboard、报警表格、表单提交、平面图加载
  - 控制台无报错

## 12. 迁移计划（10 阶段）

| 阶段 | 内容 | 验证 |
|---|---|---|
| **A 基建** | 创建分支 `feat/frontend-redesign`；新建 `styles/`、`stores/themeStore.ts`、CSS 变量；ConfigProvider 接入 | typecheck 通过；首页加载无视觉变化（基线） |
| **B 品牌** | 添加 `BrandLockup`、`ThemeToggle`、`useDocumentTitle`；改造 `index.html` 默认 title；logo 资源就位 | 首页顶栏出现新品牌；切换按钮可点 |
| **C 布局壳** | 重做 `AppLayout`：顶栏深底 + 白胶囊 Logo + 侧栏菜单 + 密度 hook | 所有页面壳一致；菜单可折叠 |
| **D Login** | 分屏 + 大 Logo + 中文文案 | 登录全流程正常 |
| **E Dashboard** | KpiCard + ECharts 主题接入；监控密度 small | 4 KPI + 4 图表浅深双主题正确 |
| **F 表格页面** | `alarms`、`bills`、`meters`、`collector`、`tariff`、`report`、`orgtree`、`production`：DataTable 模板 + StatusTag + 中文 | 列表/筛选/分页/操作正常 |
| **G 表单页面** | `cost`、`floorplan`（编辑器）、`profile`、`admin`：Form 模板 + sticky footer + 中文 | 校验、保存、错误提示正常 |
| **H Floorplan** | Konva token 接入；平面图浅深主题切换不闪烁 | 设备节点颜色随主题变化 |
| **I i18n 全扫** | 全局检查文案，修补遗漏；`document.title` 全覆盖 | 全站无英文 UI 文案（除 SI 单位、协议名、API key） |
| **J QA + 截图** | 14 页 × 2 主题截图归档；视觉回归 + 浏览器手测；修 bug | gstack 走查通过；PR 准备就绪 |

每阶段独立提交。阶段 A-J 完成后合并到 `feat/ems-observability`。

## 13. 风险与回滚

### 风险

1. **主题切换闪烁**：CSS 变量切换瞬间，若 ECharts/Konva 重绘异步，可能出现配色不一致一帧 → 缓解：在切换时短暂 throttle 100ms 重绘。
2. **AntD 5.20 token 命名差异**：部分组件 token 名与设计令牌不完全一一对应 → 缓解：在 `tokens.ts` 维护映射表，遇到差异以 AntD 为准。
3. **Big-bang PR 体积大**：单 PR 改 30+ 文件 → 缓解：分阶段提交（10 个 commit），review 时按阶段 review。
4. **品牌资源版权**：logo.png 来自用户提供 → 视为合法授权，不再校验。
5. **深色模式下 ECharts 旧图表残留**：未走 useEchartsTheme 的图会显示默认主题 → 缓解：阶段 J 全扫所有 ECharts 用法。

### 回滚

- **代码回滚**：`git reset --hard backup/pre-redesign-2026-04-30`（备份 tag 已就位）
- **部分回滚**：单阶段提交可单独 revert
- **快速降级**：`themeStore` 默认 light，深色出问题时强制 light

## 14. 验收标准

### 功能

- [ ] 浅/深主题切换：点击顶栏按钮，全站组件（含 ECharts、Konva、AntD、自定义）一次性切换
- [ ] 主题偏好持久化：刷新页面保留选择
- [ ] 密度自动响应：进入监控页 → small；进入配置页 → middle
- [ ] 浏览器标题：每页 `{页面名} - 能源管理系统`
- [ ] 14 个业务页面无 UI 文案使用英文（除允许的 SI 单位、协议名、API 字段）
- [ ] 品牌锁定：顶栏 Logo 白胶囊 + "能源管理系统"；登录页大 Logo + "松羽科技集团" 副标

### 视觉

- [ ] 所有按钮、卡片、表格 4px 圆角（pill 状态除外）
- [ ] 浅色页面背景 `#F4F5F7`、深色 `#0A1018`
- [ ] 顶栏深底 `#0E1A2B`/`#06090F`，与下方区域有清晰分割
- [ ] ECharts 8 色调色板按 ems-light/ems-dark 主题渲染
- [ ] Konva 平面图节点颜色随主题切换无残留旧色
- [ ] 状态徽章使用胶囊形 + 半透明背景

### 工程

- [ ] `npm run typecheck` 通过
- [ ] `npm run lint` 通过
- [ ] `npm test` 通过且覆盖率 ≥ 80%
- [ ] 控制台启动 + 浏览器手测无报错
- [ ] 备份 tag `backup/pre-redesign-2026-04-30` 可见

### 性能

- [ ] 首屏 LCP 不退化超过 10%（与 backup tag 对比）
- [ ] 主题切换响应 < 200ms（视觉感知）

## 15. 不在范围

- 不新增业务功能
- 不修改后端 API
- 不重构状态管理（保留 Zustand + React Query）
- 不引入新依赖
- 不重做路由结构
- 不修改数据采集/报警逻辑
- 不替换 Logo 设计或公司名（沿用用户提供素材）
- 不做移动端适配（仍以桌面 1920×1080 为基准）
- 不做无障碍 WCAG 全审计（仅遵循对比度基线）
- 不做国际化框架（i18next 等）— 仅静态 zh-CN

---

**附录：Brainstorm 决议归档**

| 议题 | 决议 |
|---|---|
| Q1 迁移策略 | a：Big-bang（单 PR 全量替换） |
| Q2 主题切换 | a：手动切换按钮 + `prefers-color-scheme` 默认 |
| Q3 视觉密度 | b：按路由前缀分层（监控 small / 配置 middle） |
| Q4 颜色基色 | Petrol（#007D8A 浅 / #00C2CC 深）+ Yellow 强调 |
| Q5.1 AppLayout | 通过 |
| Q5.2 Login | 通过 |
| Q5.3 Dashboard | 通过 |
| Q5.4 Table | 通过 |
| Q5.5 Form | 通过 |
| Q6 ECharts 主题 | 通过（8 色 Petrol/Cyan 调色板） |
| 品牌 1 Logo 顶栏 | a：白色胶囊容器 |
| 品牌 2 Login Logo | 大尺寸（240px） |
| 品牌 3 公司名 | 默认完整使用"松羽科技集团" |
| 品牌 4 浏览器标题 | 强系统优先（`{页面} - 能源管理系统`） |

视觉素材：`.superpowers/brainstorm/94802-1777541054/content/*.html`（11 个 mockup）
