# 前端全站重设计 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在保持现有路由、API 与状态管理不变的前提下，将 factory-ems 前端替换为工业化扁平双主题界面（松羽科技集团 · 能源管理系统）。

**Architecture:** Zustand `themeStore` 作为唯一真实源，向 AntD `ConfigProvider`、CSS 变量 (`:root[data-theme]`)、ECharts `registerTheme`、Konva `floorplanTokens` 四路分发。新增 `styles/`、`stores/themeStore`、`hooks/use{Density,DocumentTitle,EchartsTheme}`、`components/{BrandLockup,ThemeToggle,PageHeader,KpiCard,StatusTag,DataTable}`、`utils/{i18n-dict,floorplanTokens}`。改造 `index.html` / `main.tsx` / `App.tsx` / `AppLayout.tsx` 与全部 14 个业务页面。

**Tech Stack:** React 18 + Vite + TypeScript + AntD 5.20 + ECharts 5.6 + Konva 10 + React Router 6 + React Query 5 + Zustand。**不引入新依赖。**

**Spec:** `docs/superpowers/specs/2026-04-30-frontend-redesign-design.md`
**Backup:** Tag `backup/pre-redesign-2026-04-30`
**Working dir:** `frontend/` （除非另注，所有路径均相对此目录）
**Test commands:**
- 单元/集成：`cd frontend && npm test -- --run <pattern>`
- 类型检查：`cd frontend && npm run typecheck`
- Lint：`cd frontend && npm run lint`
- 启动：`cd frontend && npm run dev`

---

## Phase A — 基建：分支 + 主题 store + CSS 变量 + Token 接入

### Task A1：创建分支并落 Zustand themeStore 骨架

**Files:**
- Create: `frontend/src/stores/themeStore.ts`
- Test: `frontend/src/stores/themeStore.test.ts`

- [x] **Step 1：从 feat/ems-observability 切出新分支**

```bash
cd /Users/mac/factory-ems
git switch feat/ems-observability
git switch -c feat/frontend-redesign
```

- [x] **Step 2：写失败测试**

文件：`frontend/src/stores/themeStore.test.ts`

```typescript
import { describe, it, expect, beforeEach } from 'vitest';
import { act } from '@testing-library/react';
import { useThemeStore } from './themeStore';

describe('themeStore', () => {
  beforeEach(() => {
    localStorage.clear();
    useThemeStore.setState({ mode: 'light' });
  });

  it('default mode is light when no preference', () => {
    expect(useThemeStore.getState().mode).toBe('light');
  });

  it('toggle flips between light and dark', () => {
    act(() => useThemeStore.getState().toggle());
    expect(useThemeStore.getState().mode).toBe('dark');
    act(() => useThemeStore.getState().toggle());
    expect(useThemeStore.getState().mode).toBe('light');
  });

  it('setMode writes through and persists to localStorage', () => {
    act(() => useThemeStore.getState().setMode('dark'));
    expect(useThemeStore.getState().mode).toBe('dark');
    expect(localStorage.getItem('ems.theme.mode')).toContain('dark');
  });
});
```

- [x] **Step 3：运行测试确认失败**

Run: `cd frontend && npm test -- --run src/stores/themeStore.test.ts`
Expected: FAIL（找不到模块）

- [x] **Step 4：实现 themeStore**

文件：`frontend/src/stores/themeStore.ts`

```typescript
import { create } from 'zustand';
import { persist } from 'zustand/middleware';

export type ThemeMode = 'light' | 'dark';

interface ThemeStoreState {
  mode: ThemeMode;
  setMode: (mode: ThemeMode) => void;
  toggle: () => void;
}

function detectInitialMode(): ThemeMode {
  if (typeof window === 'undefined' || !window.matchMedia) return 'light';
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export const useThemeStore = create<ThemeStoreState>()(
  persist(
    (set, get) => ({
      mode: detectInitialMode(),
      setMode: (mode) => set({ mode }),
      toggle: () => set({ mode: get().mode === 'light' ? 'dark' : 'light' }),
    }),
    { name: 'ems.theme.mode' },
  ),
);
```

- [x] **Step 5：运行测试确认通过**

Run: `cd frontend && npm test -- --run src/stores/themeStore.test.ts`
Expected: PASS（3/3）

- [x] **Step 6：提交**

```bash
cd /Users/mac/factory-ems
git add frontend/src/stores/themeStore.ts frontend/src/stores/themeStore.test.ts
git commit -m "feat(theme): add Zustand themeStore with localStorage persistence"
```

---

### Task A2：浅/深主题 CSS 变量

**Files:**
- Create: `frontend/src/styles/theme-light.css`
- Create: `frontend/src/styles/theme-dark.css`
- Modify: `frontend/src/main.tsx`（导入 CSS + 启动时同步 data-theme）

- [x] **Step 1：创建浅色主题变量**

文件：`frontend/src/styles/theme-light.css`

```css
:root[data-theme='light'] {
  --ems-color-brand-primary: #007D8A;
  --ems-color-brand-accent: #FFD732;
  --ems-color-bg-page: #F4F5F7;
  --ems-color-bg-container: #FFFFFF;
  --ems-color-bg-elevated: #FFFFFF;
  --ems-color-bg-header: #0E1A2B;
  --ems-color-bg-sider: #FFFFFF;
  --ems-color-text-primary: #16181D;
  --ems-color-text-secondary: #5A6470;
  --ems-color-text-tertiary: #8A95A0;
  --ems-color-text-on-header: #FFFFFF;
  --ems-color-border: #E2E5E9;
  --ems-color-border-strong: #C9CED4;
  --ems-color-success: #1B7A3E;
  --ems-color-warning: #E37A00;
  --ems-color-error: #C8201F;
  --ems-color-info: #0E6BB8;
  --ems-radius-sm: 2px;
  --ems-radius-base: 4px;
  --ems-radius-lg: 6px;
  --ems-radius-pill: 9999px;
  --ems-space-1: 4px;
  --ems-space-2: 8px;
  --ems-space-3: 12px;
  --ems-space-4: 16px;
  --ems-space-5: 24px;
  --ems-space-6: 32px;
  --ems-space-7: 48px;
  --ems-shadow-1: 0 1px 2px rgba(14,26,43,0.06);
  --ems-shadow-2: 0 4px 12px rgba(14,26,43,0.10);
  --ems-shadow-popup: 0 8px 24px rgba(14,26,43,0.16);
  --ems-font-family-sans: "PingFang SC", "Microsoft YaHei", "Helvetica Neue", "Segoe UI", system-ui, sans-serif;
  --ems-font-family-mono: "JetBrains Mono", "SF Mono", "Cascadia Mono", Consolas, monospace;
  --ems-font-size-xs: 12px;
  --ems-font-size-sm: 13px;
  --ems-font-size-base: 14px;
  --ems-font-size-lg: 16px;
  --ems-font-size-xl: 20px;
  --ems-font-size-display: 28px;
}

html, body, #root {
  background: var(--ems-color-bg-page);
  color: var(--ems-color-text-primary);
  font-family: var(--ems-font-family-sans);
}
```

- [x] **Step 2：创建深色主题变量**

文件：`frontend/src/styles/theme-dark.css`

```css
:root[data-theme='dark'] {
  --ems-color-brand-primary: #00C2CC;
  --ems-color-brand-accent: #FFD732;
  --ems-color-bg-page: #0A1018;
  --ems-color-bg-container: #141C28;
  --ems-color-bg-elevated: #1A2536;
  --ems-color-bg-header: #06090F;
  --ems-color-bg-sider: #0F1722;
  --ems-color-text-primary: #E8ECF1;
  --ems-color-text-secondary: #A8B2BD;
  --ems-color-text-tertiary: #6B7682;
  --ems-color-text-on-header: #FFFFFF;
  --ems-color-border: #252F3E;
  --ems-color-border-strong: #3A4658;
  --ems-color-success: #3DCB6E;
  --ems-color-warning: #FFA940;
  --ems-color-error: #FF6464;
  --ems-color-info: #4FA8FF;
  --ems-shadow-1: 0 1px 2px rgba(0,0,0,0.4);
  --ems-shadow-2: 0 4px 12px rgba(0,0,0,0.5);
  --ems-shadow-popup: 0 8px 24px rgba(0,0,0,0.6);
}
```

- [x] **Step 3：在 main.tsx 引入 CSS 并同步 data-theme**

完整覆盖 `frontend/src/main.tsx`：

```tsx
import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ConfigProvider, App as AntdApp } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import 'antd/dist/reset.css';
import './styles/theme-light.css';
import './styles/theme-dark.css';
import { ErrorBoundary } from './components/ErrorBoundary';
import App from './App';
import { useThemeStore } from './stores/themeStore';

document.documentElement.setAttribute('data-theme', useThemeStore.getState().mode);
useThemeStore.subscribe((state) =>
  document.documentElement.setAttribute('data-theme', state.mode),
);

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 30_000, refetchOnWindowFocus: false, retry: 1 },
  },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider locale={zhCN}>
      <AntdApp>
        <QueryClientProvider client={queryClient}>
          <ErrorBoundary>
            <BrowserRouter>
              <App />
            </BrowserRouter>
          </ErrorBoundary>
        </QueryClientProvider>
      </AntdApp>
    </ConfigProvider>
  </React.StrictMode>,
);
```

- [x] **Step 4：手测**

```bash
cd frontend && npm run dev
```
浏览器打开 `http://localhost:5173`，DevTools Elements 检查 `<html data-theme="light">` 存在。

- [x] **Step 5：提交**

```bash
git add frontend/src/styles/ frontend/src/main.tsx
git commit -m "feat(theme): add light/dark CSS variables and html[data-theme] sync"
```

---

### Task A3：AntD theme token + 响应式 ConfigProvider

**Files:**
- Create: `frontend/src/styles/tokens.ts`
- Create: `frontend/src/styles/tokens.test.ts`
- Modify: `frontend/src/main.tsx`（包裹 ThemedConfigProvider）

- [x] **Step 1：写 token 模块**

文件：`frontend/src/styles/tokens.ts`

```typescript
import { theme, type ThemeConfig } from 'antd';
import type { ThemeMode } from '@/stores/themeStore';

const SHARED = {
  fontFamily:
    '"PingFang SC", "Microsoft YaHei", "Helvetica Neue", "Segoe UI", system-ui, sans-serif',
  borderRadius: 4,
  borderRadiusLG: 6,
  borderRadiusSM: 2,
  fontSize: 14,
};

const LIGHT_TOKEN = {
  ...SHARED,
  colorPrimary: '#007D8A',
  colorInfo: '#0E6BB8',
  colorSuccess: '#1B7A3E',
  colorWarning: '#E37A00',
  colorError: '#C8201F',
  colorBgBase: '#FFFFFF',
  colorBgLayout: '#F4F5F7',
  colorBgContainer: '#FFFFFF',
  colorTextBase: '#16181D',
  colorBorder: '#E2E5E9',
};

const DARK_TOKEN = {
  ...SHARED,
  colorPrimary: '#00C2CC',
  colorInfo: '#4FA8FF',
  colorSuccess: '#3DCB6E',
  colorWarning: '#FFA940',
  colorError: '#FF6464',
  colorBgBase: '#141C28',
  colorBgLayout: '#0A1018',
  colorBgContainer: '#141C28',
  colorTextBase: '#E8ECF1',
  colorBorder: '#252F3E',
};

export function buildAntdTheme(mode: ThemeMode): ThemeConfig {
  return {
    algorithm: mode === 'dark' ? theme.darkAlgorithm : theme.defaultAlgorithm,
    token: mode === 'dark' ? DARK_TOKEN : LIGHT_TOKEN,
    components: {
      Layout: {
        headerBg: mode === 'dark' ? '#06090F' : '#0E1A2B',
        headerColor: '#FFFFFF',
        siderBg: mode === 'dark' ? '#0F1722' : '#FFFFFF',
        bodyBg: mode === 'dark' ? '#0A1018' : '#F4F5F7',
      },
      Menu: {
        itemSelectedBg: mode === 'dark' ? '#1A2536' : '#E6F4F5',
        itemSelectedColor: mode === 'dark' ? '#00C2CC' : '#007D8A',
      },
      Card: { borderRadiusLG: 4 },
      Button: { borderRadius: 4 },
      Table: { headerBg: mode === 'dark' ? '#1A2536' : '#FAFBFC' },
    },
  };
}
```

- [x] **Step 2：写测试**

文件：`frontend/src/styles/tokens.test.ts`

```typescript
import { describe, it, expect } from 'vitest';
import { theme } from 'antd';
import { buildAntdTheme } from './tokens';

describe('buildAntdTheme', () => {
  it('returns light algorithm with petrol primary', () => {
    const cfg = buildAntdTheme('light');
    expect(cfg.algorithm).toBe(theme.defaultAlgorithm);
    expect(cfg.token?.colorPrimary).toBe('#007D8A');
  });

  it('returns dark algorithm with cyan primary', () => {
    const cfg = buildAntdTheme('dark');
    expect(cfg.algorithm).toBe(theme.darkAlgorithm);
    expect(cfg.token?.colorPrimary).toBe('#00C2CC');
  });

  it('keeps brand-locked layout header colors', () => {
    expect(buildAntdTheme('light').components?.Layout?.headerBg).toBe('#0E1A2B');
    expect(buildAntdTheme('dark').components?.Layout?.headerBg).toBe('#06090F');
  });
});
```

- [x] **Step 3：运行 token 测试**

```bash
cd frontend && npm test -- --run src/styles/tokens.test.ts
```
Expected: PASS（3/3）

- [x] **Step 4：在 main.tsx 接入响应式 ConfigProvider**

替换 `frontend/src/main.tsx` 中的 `ConfigProvider` 引用：

```tsx
import { buildAntdTheme } from './styles/tokens';

function ThemedConfigProvider({ children }: { children: React.ReactNode }) {
  const mode = useThemeStore((s) => s.mode);
  return (
    <ConfigProvider locale={zhCN} theme={buildAntdTheme(mode)}>
      <AntdApp>{children}</AntdApp>
    </ConfigProvider>
  );
}

// ...在 ReactDOM.createRoot 内：
<React.StrictMode>
  <ThemedConfigProvider>
    <QueryClientProvider client={queryClient}>
      <ErrorBoundary>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </ErrorBoundary>
    </QueryClientProvider>
  </ThemedConfigProvider>
</React.StrictMode>
```

去掉外层旧的 `<ConfigProvider locale={zhCN}><AntdApp>...` 嵌套。

- [x] **Step 5：typecheck + 启动**

```bash
cd frontend && npm run typecheck
cd frontend && npm run dev
```
浏览器：按钮主色为 Petrol `#007D8A`；DevTools Console 执行 `useThemeStore` 测试切换（在生产代码中暂未暴露按钮，下一阶段加 ThemeToggle）。

- [x] **Step 6：提交**

```bash
git add frontend/src/styles/tokens.ts frontend/src/styles/tokens.test.ts frontend/src/main.tsx
git commit -m "feat(theme): wire AntD ConfigProvider to themeStore via buildAntdTheme"
```

---

## Phase B — 品牌：useDocumentTitle + useDensity + BrandLockup + ThemeToggle

### Task B1：useDocumentTitle hook + index.html 默认 title

**Files:**
- Create: `frontend/src/hooks/useDocumentTitle.ts`
- Create: `frontend/src/hooks/useDocumentTitle.test.ts`
- Modify: `frontend/index.html:5`

- [x] **Step 1：写失败测试**

```typescript
import { describe, it, expect, afterEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useDocumentTitle } from './useDocumentTitle';

describe('useDocumentTitle', () => {
  afterEach(() => {
    document.title = '能源管理系统';
  });

  it('sets title with system suffix', () => {
    renderHook(() => useDocumentTitle('实时看板'));
    expect(document.title).toBe('实时看板 - 能源管理系统');
  });

  it('uses bare system name when pageName empty', () => {
    renderHook(() => useDocumentTitle(''));
    expect(document.title).toBe('能源管理系统');
  });
});
```

- [x] **Step 2：实现 hook**

```typescript
import { useEffect } from 'react';

const SYSTEM = '能源管理系统';

export function useDocumentTitle(pageName: string): void {
  useEffect(() => {
    document.title = pageName ? `${pageName} - ${SYSTEM}` : SYSTEM;
  }, [pageName]);
}
```

- [x] **Step 3：测试通过**

```bash
cd frontend && npm test -- --run src/hooks/useDocumentTitle.test.ts
```
Expected: PASS（2/2）

- [x] **Step 4：改 index.html 默认标题**

修改 `frontend/index.html:5`：

```html
    <title>能源管理系统</title>
```

- [x] **Step 5：提交**

```bash
git add frontend/src/hooks/useDocumentTitle.ts frontend/src/hooks/useDocumentTitle.test.ts frontend/index.html
git commit -m "feat(brand): useDocumentTitle hook and default index.html title"
```

---

### Task B2：useDensity hook（路由分层）

**Files:**
- Create: `frontend/src/hooks/useDensity.ts`
- Create: `frontend/src/hooks/useDensity.test.ts`

- [x] **Step 1：写失败测试**

```typescript
import { describe, it, expect } from 'vitest';
import { densityForRoute } from './useDensity';

describe('densityForRoute', () => {
  it.each([
    '/dashboard',
    '/dashboard/overview',
    '/collector',
    '/floorplan/editor',
    '/alarms/history',
  ])('returns small for monitoring prefix %s', (p) => {
    expect(densityForRoute(p)).toBe('small');
  });

  it.each([
    '/admin/users',
    '/meters',
    '/orgtree',
    '/tariff',
    '/report/daily',
    '/login',
    '/profile',
  ])('returns middle for non-monitoring %s', (p) => {
    expect(densityForRoute(p)).toBe('middle');
  });
});
```

- [x] **Step 2：实现**

```typescript
import { useLocation } from 'react-router-dom';
import type { SizeType } from 'antd/es/config-provider/SizeContext';

const COMPACT_PREFIXES = ['/dashboard', '/collector', '/floorplan', '/alarms'];

export function densityForRoute(pathname: string): SizeType {
  return COMPACT_PREFIXES.some((p) => pathname.startsWith(p)) ? 'small' : 'middle';
}

export function useDensity(): SizeType {
  return densityForRoute(useLocation().pathname);
}
```

- [x] **Step 3：测试通过**

```bash
cd frontend && npm test -- --run src/hooks/useDensity.test.ts
```
Expected: PASS

- [x] **Step 4：提交**

```bash
git add frontend/src/hooks/useDensity.ts frontend/src/hooks/useDensity.test.ts
git commit -m "feat(brand): useDensity hook with route-prefix density tiering"
```

---

### Task B3：BrandLockup 组件

**Files:**
- Create: `frontend/src/components/BrandLockup.tsx`
- Create: `frontend/src/components/BrandLockup.test.tsx`

- [x] **Step 1：写失败测试**

```tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { BrandLockup } from './BrandLockup';

describe('BrandLockup', () => {
  it('header variant shows pill logo + 能源管理系统', () => {
    render(<BrandLockup variant="header" />);
    expect(screen.getByAltText('松羽科技集团')).toBeInTheDocument();
    expect(screen.getByText('能源管理系统')).toBeInTheDocument();
  });

  it('login variant shows large logo + system + company subtitle', () => {
    render(<BrandLockup variant="login" />);
    expect(screen.getByAltText('松羽科技集团')).toBeInTheDocument();
    expect(screen.getByText('能源管理系统')).toBeInTheDocument();
    expect(screen.getByText('松羽科技集团')).toBeInTheDocument();
  });
});
```

- [x] **Step 2：实现组件**

```tsx
import logo from '@/assets/logo.png';

interface Props {
  variant: 'header' | 'login';
}

const SYSTEM = '能源管理系统';
const COMPANY = '松羽科技集团';

export function BrandLockup({ variant }: Props) {
  if (variant === 'header') {
    return (
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <span
          style={{
            background: '#FFFFFF',
            padding: '4px 12px',
            borderRadius: 9999,
            display: 'inline-flex',
            alignItems: 'center',
            height: 32,
          }}
        >
          <img src={logo} alt={COMPANY} style={{ height: 24, display: 'block' }} />
        </span>
        <span style={{ color: '#FFFFFF', fontSize: 18, fontWeight: 500 }}>{SYSTEM}</span>
      </div>
    );
  }
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12 }}>
      <img src={logo} alt={COMPANY} style={{ width: 240, display: 'block' }} />
      <div style={{ fontSize: 28, fontWeight: 600, color: '#FFFFFF' }}>{SYSTEM}</div>
      <div style={{ fontSize: 14, color: 'rgba(255,255,255,0.7)' }}>{COMPANY}</div>
    </div>
  );
}
```

- [x] **Step 3：测试通过**

```bash
cd frontend && npm test -- --run src/components/BrandLockup.test.tsx
```
Expected: PASS

- [x] **Step 4：提交**

```bash
git add frontend/src/components/BrandLockup.tsx frontend/src/components/BrandLockup.test.tsx
git commit -m "feat(brand): BrandLockup component (header pill + login hero variants)"
```

---

### Task B4：ThemeToggle 组件

**Files:**
- Create: `frontend/src/components/ThemeToggle.tsx`
- Create: `frontend/src/components/ThemeToggle.test.tsx`

- [x] **Step 1：写失败测试**

```tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ThemeToggle } from './ThemeToggle';
import { useThemeStore } from '@/stores/themeStore';

describe('ThemeToggle', () => {
  beforeEach(() => useThemeStore.setState({ mode: 'light' }));

  it('renders aria label for switching to dark when light', () => {
    render(<ThemeToggle />);
    expect(screen.getByLabelText('切换到深色模式')).toBeInTheDocument();
  });

  it('clicking flips mode', () => {
    render(<ThemeToggle />);
    fireEvent.click(screen.getByLabelText('切换到深色模式'));
    expect(useThemeStore.getState().mode).toBe('dark');
  });
});
```

- [x] **Step 2：实现组件**

```tsx
import { Button, Tooltip } from 'antd';
import { BulbOutlined, BulbFilled } from '@ant-design/icons';
import { useThemeStore } from '@/stores/themeStore';

export function ThemeToggle() {
  const mode = useThemeStore((s) => s.mode);
  const toggle = useThemeStore((s) => s.toggle);
  const isLight = mode === 'light';
  const label = isLight ? '切换到深色模式' : '切换到浅色模式';
  return (
    <Tooltip title={label}>
      <Button
        type="text"
        aria-label={label}
        icon={isLight ? <BulbOutlined /> : <BulbFilled />}
        onClick={toggle}
        style={{ color: '#FFFFFF' }}
      />
    </Tooltip>
  );
}
```

- [x] **Step 3：测试通过**

```bash
cd frontend && npm test -- --run src/components/ThemeToggle.test.tsx
```
Expected: PASS

- [x] **Step 4：提交**

```bash
git add frontend/src/components/ThemeToggle.tsx frontend/src/components/ThemeToggle.test.tsx
git commit -m "feat(theme): ThemeToggle button bound to themeStore"
```

---

## Phase C — AppLayout：顶栏品牌 + 密度联动

### Task C1：AppLayout 顶栏接入 BrandLockup + ThemeToggle + 密度

**Files:**
- Modify: `frontend/src/layouts/AppLayout.tsx`（替换 Header 区与 Layout 内层 ConfigProvider）
- Create: `frontend/src/layouts/AppLayout.test.tsx`

- [x] **Step 1：替换 Header 区**

保留原 `menuItems` 与 ADMIN/FINANCE 条件分支不变，整体框架替换：

```tsx
import { ConfigProvider, Layout, Menu, Avatar, Dropdown, Space } from 'antd';
import { UserOutlined } from '@ant-design/icons';
import { useLocation, useNavigate, Outlet } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';
import { authApi } from '@/api/auth';
import { AlarmBell } from '@/components/AlarmBell';
import { BrandLockup } from '@/components/BrandLockup';
import { ThemeToggle } from '@/components/ThemeToggle';
import { useDensity } from '@/hooks/useDensity';

const { Header, Sider, Content } = Layout;

export default function AppLayout() {
  const { user, clear } = useAuthStore();
  const location = useLocation();
  const navigate = useNavigate();
  const density = useDensity();
  // ...保留原 menuItems useMemo（含 ADMIN/FINANCE 条件分支）...

  const userMenu = {
    items: [
      { key: 'profile', label: '个人中心', onClick: () => navigate('/profile') },
      { type: 'divider' as const },
      {
        key: 'logout',
        label: '退出登录',
        onClick: async () => {
          await authApi.logout().catch(() => {});
          clear();
          navigate('/login');
        },
      },
    ],
  };

  return (
    <ConfigProvider componentSize={density}>
      <Layout style={{ minHeight: '100vh' }}>
        <Header
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '0 24px',
            background: 'var(--ems-color-bg-header)',
            height: 56,
            lineHeight: '56px',
          }}
        >
          <BrandLockup variant="header" />
          <Space size="middle">
            <ThemeToggle />
            <AlarmBell />
            <Dropdown menu={userMenu} placement="bottomRight">
              <Space style={{ color: '#FFFFFF', cursor: 'pointer' }}>
                <Avatar size="small" icon={<UserOutlined />} />
                <span>{user?.username ?? '用户'}</span>
              </Space>
            </Dropdown>
          </Space>
        </Header>
        <Layout>
          <Sider
            width={240}
            collapsible
            theme="light"
            style={{ background: 'var(--ems-color-bg-sider)' }}
          >
            <Menu
              mode="inline"
              selectedKeys={[location.pathname]}
              items={menuItems}
              style={{ borderInlineEnd: 0, paddingTop: 8 }}
            />
          </Sider>
          <Content
            style={{
              padding: density === 'small' ? 16 : 24,
              background: 'var(--ems-color-bg-page)',
            }}
          >
            <Outlet />
          </Content>
        </Layout>
      </Layout>
    </ConfigProvider>
  );
}
```

- [x] **Step 2：写 smoke 测试**

文件：`frontend/src/layouts/AppLayout.test.tsx`

```tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import AppLayout from './AppLayout';
import { useAuthStore } from '@/stores/authStore';

describe('AppLayout', () => {
  it('renders header brand lockup with system name', () => {
    useAuthStore.setState({
      user: { id: 1, username: 'tester', roles: ['ADMIN'] } as any,
      accessToken: 'x',
    });
    render(
      <MemoryRouter initialEntries={['/home']}>
        <AppLayout />
      </MemoryRouter>,
    );
    expect(screen.getByText('能源管理系统')).toBeInTheDocument();
  });
});
```

- [x] **Step 3：测试 + typecheck**

```bash
cd frontend && npm test -- --run src/layouts/AppLayout.test.tsx
cd frontend && npm run typecheck
```
Expected: PASS

- [x] **Step 4：浏览器手测**

`npm run dev` → 登录 → 顶栏深底 + 白胶囊 Logo + 系统名 + ThemeToggle 切换浅深。Console 无报错。

- [x] **Step 5：提交**

```bash
git add frontend/src/layouts/AppLayout.tsx frontend/src/layouts/AppLayout.test.tsx
git commit -m "feat(layout): redesign AppLayout header with BrandLockup, ThemeToggle, density"
```

---

## Phase D — Login 重写

### Task D1：分屏 Login + 大 Logo + 中文文案

**Files:**
- Modify: `frontend/src/pages/login/index.tsx`
- Create: `frontend/src/pages/login/index.test.tsx`

- [x] **Step 1：完整覆盖 Login**

```tsx
import { useState } from 'react';
import { Form, Input, Button, message, Alert } from 'antd';
import { UserOutlined, LockOutlined } from '@ant-design/icons';
import { useNavigate, useLocation } from 'react-router-dom';
import { authApi } from '@/api/auth';
import { useAuthStore } from '@/stores/authStore';
import { BrandLockup } from '@/components/BrandLockup';
import { useDocumentTitle } from '@/hooks/useDocumentTitle';

export default function LoginPage() {
  useDocumentTitle('登录');
  const [loading, setLoading] = useState(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const navigate = useNavigate();
  const location = useLocation();
  const setAuth = useAuthStore((s) => s.setAuth);
  const from = (location.state as { from?: { pathname: string } })?.from?.pathname || '/';

  const onFinish = async (v: { username: string; password: string }) => {
    setLoading(true);
    setErrorMsg(null);
    try {
      const r = await authApi.login(v.username, v.password);
      setAuth({ accessToken: r.accessToken, user: r.user, expiresIn: r.expiresIn });
      message.success('登录成功');
      navigate(from, { replace: true });
    } catch (e: any) {
      setErrorMsg(e?.message ?? '登录失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div style={{ display: 'flex', minHeight: '100vh' }}>
      <div
        style={{
          flex: '0 0 60%',
          background: '#0E1A2B',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        <BrandLockup variant="login" />
      </div>
      <div
        style={{
          flex: 1,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          background: 'var(--ems-color-bg-container)',
          padding: 32,
        }}
      >
        <div style={{ width: 400, maxWidth: '100%' }}>
          <h2 style={{ marginBottom: 24, fontSize: 20, fontWeight: 600 }}>登录系统</h2>
          {errorMsg && <Alert type="error" message={errorMsg} style={{ marginBottom: 16 }} />}
          <Form layout="vertical" onFinish={onFinish} autoComplete="off" size="large">
            <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
              <Input prefix={<UserOutlined />} placeholder="用户名" />
            </Form.Item>
            <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password prefix={<LockOutlined />} placeholder="密码" />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} block>
              登录
            </Button>
          </Form>
          <div
            style={{
              marginTop: 32,
              textAlign: 'center',
              color: 'var(--ems-color-text-tertiary)',
              fontSize: 12,
            }}
          >
            © 2026 松羽科技集团 · 能源管理系统
          </div>
        </div>
      </div>
    </div>
  );
}
```

- [x] **Step 2：写测试**

```tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import LoginPage from './index';

describe('LoginPage', () => {
  it('renders system name, company subtitle, and login form fields', () => {
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>,
    );
    expect(screen.getByText('能源管理系统')).toBeInTheDocument();
    expect(screen.getByText('松羽科技集团')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('用户名')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('密码')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '登录' })).toBeInTheDocument();
  });
});
```

- [x] **Step 3：测试 + typecheck**

```bash
cd frontend && npm test -- --run src/pages/login/index.test.tsx
cd frontend && npm run typecheck
```
Expected: PASS

- [x] **Step 4：浏览器手测**

登出 → `/login` 显示左 60% 深底大 Logo + 系统名 + 副标，右 40% 表单。错误密码 inline alert 红色，正确密码跳转。

- [x] **Step 5：提交**

```bash
git add frontend/src/pages/login/index.tsx frontend/src/pages/login/index.test.tsx
git commit -m "feat(login): split-screen layout with BrandLockup and Chinese copy"
```

---

## Phase E — ECharts 主题接入

### Task E1：注册 ems-light / ems-dark 主题

**Files:**
- Create: `frontend/src/styles/echarts/ems-light.ts`
- Create: `frontend/src/styles/echarts/ems-dark.ts`
- Create: `frontend/src/styles/echarts/index.ts`
- Create: `frontend/src/styles/echarts/index.test.ts`
- Modify: `frontend/src/main.tsx`（顶部调用一次 ensureEchartsThemes）

- [x] **Step 1：写浅色主题对象**

文件：`frontend/src/styles/echarts/ems-light.ts`

```typescript
export const emsLightTheme = {
  color: ['#007D8A', '#FFD732', '#0E6BB8', '#1B7A3E', '#E37A00', '#9B59B6', '#34495E', '#E84C5E'],
  backgroundColor: 'transparent',
  textStyle: { color: '#5A6470', fontFamily: 'PingFang SC, sans-serif' },
  title: { textStyle: { color: '#16181D', fontWeight: 600 } },
  legend: { textStyle: { color: '#5A6470' } },
  axisPointer: { lineStyle: { color: '#C9CED4' } },
  categoryAxis: {
    axisLine: { lineStyle: { color: '#E2E5E9' } },
    axisTick: { lineStyle: { color: '#E2E5E9' } },
    axisLabel: { color: '#5A6470' },
    splitLine: { show: false },
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#8A95A0' },
    splitLine: { lineStyle: { color: '#E2E5E9', type: 'dashed' } },
  },
};
```

- [x] **Step 2：写深色主题对象**

文件：`frontend/src/styles/echarts/ems-dark.ts`

```typescript
export const emsDarkTheme = {
  color: ['#00C2CC', '#FFD732', '#4FA8FF', '#3DCB6E', '#FFA940', '#C77DFF', '#A8B2BD', '#FF6464'],
  backgroundColor: 'transparent',
  textStyle: { color: '#A8B2BD', fontFamily: 'PingFang SC, sans-serif' },
  title: { textStyle: { color: '#E8ECF1', fontWeight: 600 } },
  legend: { textStyle: { color: '#A8B2BD' } },
  axisPointer: { lineStyle: { color: '#3A4658' } },
  categoryAxis: {
    axisLine: { lineStyle: { color: '#252F3E' } },
    axisTick: { lineStyle: { color: '#252F3E' } },
    axisLabel: { color: '#A8B2BD' },
    splitLine: { show: false },
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#6B7682' },
    splitLine: { lineStyle: { color: '#252F3E', type: 'dashed' } },
  },
};
```

- [x] **Step 3：注册器**

文件：`frontend/src/styles/echarts/index.ts`

```typescript
import * as echarts from 'echarts/core';
import { emsLightTheme } from './ems-light';
import { emsDarkTheme } from './ems-dark';

export const EMS_LIGHT = 'ems-light';
export const EMS_DARK = 'ems-dark';

let registered = false;
export function ensureEchartsThemes(): void {
  if (registered) return;
  echarts.registerTheme(EMS_LIGHT, emsLightTheme as Record<string, unknown>);
  echarts.registerTheme(EMS_DARK, emsDarkTheme as Record<string, unknown>);
  registered = true;
}
```

- [x] **Step 4：在 main.tsx 顶部调用一次**

修改 `frontend/src/main.tsx`，在 `import App from './App';` 之后插入：

```typescript
import { ensureEchartsThemes } from './styles/echarts';
ensureEchartsThemes();
```

- [x] **Step 5：写注册测试**

文件：`frontend/src/styles/echarts/index.test.ts`

```typescript
import { describe, it, expect } from 'vitest';
import * as echarts from 'echarts/core';
import { ensureEchartsThemes, EMS_LIGHT, EMS_DARK } from './index';

describe('ensureEchartsThemes', () => {
  it('registers ems-light and ems-dark', () => {
    ensureEchartsThemes();
    const div = document.createElement('div');
    Object.assign(div.style, { width: '200px', height: '200px' });
    document.body.appendChild(div);
    expect(() => echarts.init(div, EMS_LIGHT)).not.toThrow();
    expect(() => echarts.init(div, EMS_DARK)).not.toThrow();
  });
});
```

- [x] **Step 6：测试通过**

```bash
cd frontend && npm test -- --run src/styles/echarts/index.test.ts
```
Expected: PASS

- [x] **Step 7：提交**

```bash
git add frontend/src/styles/echarts/ frontend/src/main.tsx
git commit -m "feat(echarts): register ems-light and ems-dark themes at startup"
```

---

### Task E2：useEchartsTheme hook

**Files:**
- Create: `frontend/src/hooks/useEchartsTheme.ts`
- Create: `frontend/src/hooks/useEchartsTheme.test.ts`

- [x] **Step 1：写测试**

```typescript
import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useEchartsTheme } from './useEchartsTheme';
import { useThemeStore } from '@/stores/themeStore';

describe('useEchartsTheme', () => {
  it('returns ems-light when light', () => {
    useThemeStore.setState({ mode: 'light' });
    const { result } = renderHook(() => useEchartsTheme());
    expect(result.current).toBe('ems-light');
  });

  it('returns ems-dark when dark', () => {
    useThemeStore.setState({ mode: 'dark' });
    const { result } = renderHook(() => useEchartsTheme());
    expect(result.current).toBe('ems-dark');
  });

  it('updates when store changes', () => {
    useThemeStore.setState({ mode: 'light' });
    const { result } = renderHook(() => useEchartsTheme());
    act(() => useThemeStore.setState({ mode: 'dark' }));
    expect(result.current).toBe('ems-dark');
  });
});
```

- [x] **Step 2：实现**

```typescript
import { useThemeStore } from '@/stores/themeStore';
import { EMS_LIGHT, EMS_DARK } from '@/styles/echarts';

export function useEchartsTheme(): typeof EMS_LIGHT | typeof EMS_DARK {
  const mode = useThemeStore((s) => s.mode);
  return mode === 'dark' ? EMS_DARK : EMS_LIGHT;
}
```

- [x] **Step 3：测试通过**

```bash
cd frontend && npm test -- --run src/hooks/useEchartsTheme.test.ts
```
Expected: PASS

- [x] **Step 4：提交**

```bash
git add frontend/src/hooks/useEchartsTheme.ts frontend/src/hooks/useEchartsTheme.test.ts
git commit -m "feat(echarts): useEchartsTheme hook bound to themeStore"
```

---

### Task E3：升级 useEcharts 应用主题（实例随主题重建）

**Files:**
- Modify: `frontend/src/hooks/useEcharts.ts`
- Create: `frontend/src/hooks/useEcharts.test.tsx`

- [x] **Step 1：替换 useEcharts**

```typescript
import { useRef, useEffect } from 'react';
import * as echarts from 'echarts/core';
import type { EChartsOption } from 'echarts';
import { useEchartsTheme } from './useEchartsTheme';

export function useEcharts(option: EChartsOption) {
  const elRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);
  const themeName = useEchartsTheme();

  useEffect(() => {
    if (!elRef.current) return;
    const chart = echarts.init(elRef.current, themeName);
    chartRef.current = chart;
    const onResize = () => chart.resize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chart.dispose();
      chartRef.current = null;
    };
  }, [themeName]);

  useEffect(() => {
    if (chartRef.current) {
      chartRef.current.setOption(option, { notMerge: false, lazyUpdate: true });
    }
  }, [option]);

  return elRef;
}
```

> 主题切换会触发 effect 依赖变化 → 重新 init 实例。setOption effect 在新实例挂载后由 React 重跑。

- [x] **Step 2：写 smoke 测试**

文件：`frontend/src/hooks/useEcharts.test.tsx`

```tsx
import { describe, it, expect } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useEcharts } from './useEcharts';
import { useThemeStore } from '@/stores/themeStore';

describe('useEcharts', () => {
  it('returns a div ref under light theme', () => {
    useThemeStore.setState({ mode: 'light' });
    const { result } = renderHook(() => useEcharts({}));
    expect(result.current).toBeDefined();
  });
});
```

- [x] **Step 3：测试 + typecheck**

```bash
cd frontend && npm test -- --run src/hooks/useEcharts.test.tsx
cd frontend && npm run typecheck
```
Expected: PASS

- [x] **Step 4：浏览器手测**

`/dashboard` → 切换主题 → 所有 ECharts 图表整体配色变化。

- [x] **Step 5：提交**

```bash
git add frontend/src/hooks/useEcharts.ts frontend/src/hooks/useEcharts.test.tsx
git commit -m "feat(echarts): apply theme on init and rebuild instance on theme change"
```

---

## Phase F — 共享组件：i18n-dict + StatusTag + PageHeader + KpiCard + DataTable

### Task F1：i18n-dict 中文映射表

**Files:**
- Create: `frontend/src/utils/i18n-dict.ts`
- Create: `frontend/src/utils/i18n-dict.test.ts`

- [x] **Step 1：写测试**

```typescript
import { describe, it, expect } from 'vitest';
import {
  translate,
  ALARM_STATE_LABEL,
  ALARM_SEVERITY_LABEL,
  METER_STATE_LABEL,
  COLLECTOR_PROTOCOL_LABEL,
} from './i18n-dict';

describe('i18n-dict', () => {
  it('maps alarm states', () => {
    expect(ALARM_STATE_LABEL.OPEN).toBe('未处理');
    expect(ALARM_STATE_LABEL.ACK).toBe('已确认');
    expect(ALARM_STATE_LABEL.CLEARED).toBe('已恢复');
  });

  it('maps severities', () => {
    expect(ALARM_SEVERITY_LABEL.CRITICAL).toBe('严重');
    expect(ALARM_SEVERITY_LABEL.MAJOR).toBe('重要');
    expect(ALARM_SEVERITY_LABEL.MINOR).toBe('次要');
    expect(ALARM_SEVERITY_LABEL.WARNING).toBe('提醒');
  });

  it('maps meter states', () => {
    expect(METER_STATE_LABEL.ACTIVE).toBe('在线');
    expect(METER_STATE_LABEL.INACTIVE).toBe('离线');
  });

  it('keeps protocol names verbatim', () => {
    expect(COLLECTOR_PROTOCOL_LABEL.MODBUS_TCP).toBe('Modbus TCP');
    expect(COLLECTOR_PROTOCOL_LABEL.MODBUS_RTU).toBe('Modbus RTU');
  });

  it('translate falls back to original when key missing', () => {
    expect(translate(ALARM_STATE_LABEL, 'UNKNOWN')).toBe('UNKNOWN');
  });
});
```

- [x] **Step 2：实现**

```typescript
export const ALARM_STATE_LABEL = {
  OPEN: '未处理',
  ACK: '已确认',
  CLEARED: '已恢复',
} as const;

export const ALARM_SEVERITY_LABEL = {
  CRITICAL: '严重',
  MAJOR: '重要',
  MINOR: '次要',
  WARNING: '提醒',
} as const;

export const METER_STATE_LABEL = {
  ACTIVE: '在线',
  INACTIVE: '离线',
} as const;

export const COLLECTOR_PROTOCOL_LABEL = {
  MODBUS_TCP: 'Modbus TCP',
  MODBUS_RTU: 'Modbus RTU',
} as const;

export const NAV_LABEL = {
  dashboard: '综合看板',
  collector: '数据采集',
  floorplan: '设备分布图',
  alarms: '报警',
  meters: '表计',
  cost: '成本核算',
  bills: '账单',
  tariff: '电价',
  report: '报表',
  admin: '系统管理',
  profile: '个人中心',
  home: '首页',
  orgtree: '组织树',
  production: '生产',
} as const;

export function translate<T extends Record<string, string>>(dict: T, key: string): string {
  return dict[key as keyof T] ?? key;
}
```

- [x] **Step 3：测试 + 提交**

```bash
cd frontend && npm test -- --run src/utils/i18n-dict.test.ts
git add frontend/src/utils/i18n-dict.ts frontend/src/utils/i18n-dict.test.ts
git commit -m "feat(i18n): central zh-CN dictionary for enums and nav labels"
```

---

### Task F2：StatusTag 胶囊状态徽章

**Files:**
- Create: `frontend/src/components/StatusTag.tsx`
- Create: `frontend/src/components/StatusTag.test.tsx`

- [x] **Step 1：写测试**

```tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { StatusTag } from './StatusTag';

describe('StatusTag', () => {
  it.each([
    ['success', '在线'],
    ['warning', '报警'],
    ['error', '严重'],
    ['info', '已确认'],
    ['default', '离线'],
  ] as const)('renders %s tone with text "%s"', (tone, label) => {
    render(<StatusTag tone={tone}>{label}</StatusTag>);
    expect(screen.getByText(label)).toBeInTheDocument();
  });
});
```

- [x] **Step 2：实现**

```tsx
import type { ReactNode } from 'react';

export type StatusTone = 'success' | 'warning' | 'error' | 'info' | 'default';

const TONE_VAR: Record<StatusTone, string> = {
  success: 'var(--ems-color-success)',
  warning: 'var(--ems-color-warning)',
  error: 'var(--ems-color-error)',
  info: 'var(--ems-color-info)',
  default: 'var(--ems-color-text-tertiary)',
};

interface Props {
  tone: StatusTone;
  children: ReactNode;
}

export function StatusTag({ tone, children }: Props) {
  const color = TONE_VAR[tone];
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        height: 22,
        padding: '0 10px',
        borderRadius: 9999,
        fontSize: 12,
        fontWeight: 500,
        color,
        background: `color-mix(in srgb, ${color} 12%, transparent)`,
        border: `1px solid color-mix(in srgb, ${color} 30%, transparent)`,
        whiteSpace: 'nowrap',
      }}
    >
      {children}
    </span>
  );
}
```

- [x] **Step 3：测试 + 提交**

```bash
cd frontend && npm test -- --run src/components/StatusTag.test.tsx
git add frontend/src/components/StatusTag.tsx frontend/src/components/StatusTag.test.tsx
git commit -m "feat(ui): StatusTag pill badge with semantic tones"
```

---

### Task F3：PageHeader 标准页头

**Files:**
- Create: `frontend/src/components/PageHeader.tsx`
- Create: `frontend/src/components/PageHeader.test.tsx`

- [x] **Step 1：写测试**

```tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PageHeader } from './PageHeader';

describe('PageHeader', () => {
  it('shows title and optional extra', () => {
    render(<PageHeader title="报警历史" extra={<button>导出</button>} />);
    expect(screen.getByText('报警历史')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '导出' })).toBeInTheDocument();
  });

  it('renders subtitle when provided', () => {
    render(<PageHeader title="账单列表" subtitle="本月共 12 条" />);
    expect(screen.getByText('本月共 12 条')).toBeInTheDocument();
  });
});
```

- [x] **Step 2：实现**

```tsx
import type { ReactNode } from 'react';

interface Props {
  title: string;
  subtitle?: ReactNode;
  extra?: ReactNode;
}

export function PageHeader({ title, subtitle, extra }: Props) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        marginBottom: 16,
        paddingBottom: 12,
        borderBottom: '1px solid var(--ems-color-border)',
      }}
    >
      <div>
        <div style={{ fontSize: 20, fontWeight: 600, color: 'var(--ems-color-text-primary)' }}>{title}</div>
        {subtitle && (
          <div style={{ fontSize: 13, color: 'var(--ems-color-text-secondary)', marginTop: 4 }}>{subtitle}</div>
        )}
      </div>
      {extra && <div>{extra}</div>}
    </div>
  );
}
```

- [x] **Step 3：测试 + 提交**

```bash
cd frontend && npm test -- --run src/components/PageHeader.test.tsx
git add frontend/src/components/PageHeader.tsx frontend/src/components/PageHeader.test.tsx
git commit -m "feat(ui): PageHeader with title, subtitle, extra slot"
```

---

### Task F4：KpiCard 看板 KPI 卡片

**Files:**
- Create: `frontend/src/components/KpiCard.tsx`
- Create: `frontend/src/components/KpiCard.test.tsx`

- [x] **Step 1：写测试**

```tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { KpiCard } from './KpiCard';

describe('KpiCard', () => {
  it('renders label, value, unit, delta', () => {
    render(<KpiCard label="本月用电" value="12,345" unit="kWh" delta={-3.2} />);
    expect(screen.getByText('本月用电')).toBeInTheDocument();
    expect(screen.getByText('12,345')).toBeInTheDocument();
    expect(screen.getByText('kWh')).toBeInTheDocument();
    expect(screen.getByText(/3\.2%/)).toBeInTheDocument();
  });

  it('omits delta when not provided', () => {
    render(<KpiCard label="水耗" value="1,200" unit="m³" />);
    expect(screen.queryByText(/%/)).toBeNull();
  });
});
```

- [x] **Step 2：实现**

```tsx
import { ArrowDownOutlined, ArrowUpOutlined } from '@ant-design/icons';

interface Props {
  label: string;
  value: string | number;
  unit?: string;
  delta?: number;
}

export function KpiCard({ label, value, unit, delta }: Props) {
  const positive = typeof delta === 'number' && delta > 0;
  const negative = typeof delta === 'number' && delta < 0;
  const deltaColor = positive ? 'var(--ems-color-error)' : 'var(--ems-color-success)';
  return (
    <div
      style={{
        background: 'var(--ems-color-bg-container)',
        border: '1px solid var(--ems-color-border)',
        borderRadius: 4,
        padding: 16,
        minWidth: 180,
      }}
    >
      <div style={{ fontSize: 13, color: 'var(--ems-color-text-secondary)' }}>{label}</div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, marginTop: 6 }}>
        <span
          style={{
            fontSize: 28,
            fontWeight: 600,
            fontFamily: 'var(--ems-font-family-mono)',
            color: 'var(--ems-color-text-primary)',
          }}
        >
          {value}
        </span>
        {unit && <span style={{ color: 'var(--ems-color-text-tertiary)' }}>{unit}</span>}
      </div>
      {typeof delta === 'number' && (
        <div style={{ marginTop: 6, fontSize: 12, color: deltaColor }}>
          {positive ? <ArrowUpOutlined /> : negative ? <ArrowDownOutlined /> : null}
          <span style={{ marginLeft: 4 }}>{Math.abs(delta).toFixed(1)}% 较昨日</span>
        </div>
      )}
    </div>
  );
}
```

- [x] **Step 3：测试 + 提交**

```bash
cd frontend && npm test -- --run src/components/KpiCard.test.tsx
git add frontend/src/components/KpiCard.tsx frontend/src/components/KpiCard.test.tsx
git commit -m "feat(ui): KpiCard with mono digits and trend delta"
```

---

### Task F5：DataTable 表格模板封装

**Files:**
- Create: `frontend/src/components/DataTable.tsx`
- Create: `frontend/src/components/DataTable.test.tsx`

- [x] **Step 1：写测试**

```tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DataTable } from './DataTable';

describe('DataTable', () => {
  it('renders toolbar extras and column headers', () => {
    render(
      <DataTable
        rowKey="id"
        toolbarExtra={<button>刷新</button>}
        columns={[{ title: '名称', dataIndex: 'name' }]}
        dataSource={[{ id: 1, name: 'A' }]}
      />,
    );
    expect(screen.getByRole('button', { name: '刷新' })).toBeInTheDocument();
    expect(screen.getByText('名称')).toBeInTheDocument();
    expect(screen.getByText('A')).toBeInTheDocument();
  });
});
```

- [x] **Step 2：实现**

```tsx
import type { ReactNode } from 'react';
import { Table, type TableProps } from 'antd';

interface Props<T> extends TableProps<T> {
  toolbarLeft?: ReactNode;
  toolbarExtra?: ReactNode;
}

export function DataTable<T extends object>({
  toolbarLeft,
  toolbarExtra,
  ...rest
}: Props<T>) {
  return (
    <div
      style={{
        background: 'var(--ems-color-bg-container)',
        border: '1px solid var(--ems-color-border)',
        borderRadius: 4,
      }}
    >
      {(toolbarLeft || toolbarExtra) && (
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            padding: '12px 16px',
            borderBottom: '1px solid var(--ems-color-border)',
            gap: 12,
          }}
        >
          <div>{toolbarLeft}</div>
          <div>{toolbarExtra}</div>
        </div>
      )}
      <Table
        {...rest}
        pagination={rest.pagination ?? { pageSize: 20, showSizeChanger: true }}
      />
    </div>
  );
}
```

- [x] **Step 3：测试 + 提交**

```bash
cd frontend && npm test -- --run src/components/DataTable.test.tsx
git add frontend/src/components/DataTable.tsx frontend/src/components/DataTable.test.tsx
git commit -m "feat(ui): DataTable wrapper with toolbar slots"
```

---

## Phase G — Dashboard：KpiPanel + 中文化

### Task G1：KpiPanel 改用 KpiCard

**Files:**
- Modify: `frontend/src/pages/dashboard/KpiPanel.tsx`
- Create: `frontend/src/pages/dashboard/KpiPanel.test.tsx`

- [x] **Step 1：阅读现状**

```bash
sed -n '1,80p' frontend/src/pages/dashboard/KpiPanel.tsx
```

- [x] **Step 2：替换 4 KPI 渲染**

```tsx
import { KpiCard } from '@/components/KpiCard';

// 在 render 中（保留原 query/数据加载逻辑）：
<div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 16 }}>
  <KpiCard label="本月用电" value={fmtNumber(kpi.electricityKwh)} unit="kWh" delta={kpi.electricityDelta} />
  <KpiCard label="本月用水" value={fmtNumber(kpi.waterM3)} unit="m³" delta={kpi.waterDelta} />
  <KpiCard label="本月用气" value={fmtNumber(kpi.gasM3)} unit="m³" delta={kpi.gasDelta} />
  <KpiCard label="综合能耗" value={fmtNumber(kpi.totalKgce)} unit="kgce" delta={kpi.totalDelta} />
</div>
```

> 实施时若 KpiPanel 字段名不同，按现有数据映射对齐 4 张卡片；不要改动 API。

- [x] **Step 3：smoke 测试**

```tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import KpiPanel from './KpiPanel';

describe('KpiPanel', () => {
  it('renders four KPI labels', () => {
    const qc = new QueryClient();
    render(
      <QueryClientProvider client={qc}>
        <KpiPanel />
      </QueryClientProvider>,
    );
    ['本月用电', '本月用水', '本月用气', '综合能耗'].forEach((t) =>
      expect(screen.queryByText(t)).toBeTruthy(),
    );
  });
});
```

- [x] **Step 4：测试 + 提交**

```bash
cd frontend && npm test -- --run src/pages/dashboard/KpiPanel.test.tsx
git add frontend/src/pages/dashboard/KpiPanel.tsx frontend/src/pages/dashboard/KpiPanel.test.tsx
git commit -m "feat(dashboard): replace KPI tiles with KpiCard"
```

---

### Task G2：Dashboard 入口标题 + 中文标签

**Files:**
- Modify: `frontend/src/pages/dashboard/index.tsx`
- Modify: `frontend/src/pages/dashboard/{CostDistributionPanel,EnergyCompositionPanel,EnergyIntensityPanel,FilterBar,FloorplanLivePanel,RealtimeSeriesPanel,SankeyPanel,TariffDistributionPanel,TopNPanel,MeterDetailDrawer}.tsx`

- [x] **Step 1：在 dashboard/index.tsx 顶部加 useDocumentTitle**

```tsx
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
// 在 default export 函数顶部：
useDocumentTitle('综合看板');
```

- [x] **Step 2：扫描 dashboard 目录英文 UI 字符串**

```bash
grep -nE '"[A-Z][a-z]+[a-z A-Z]+"' frontend/src/pages/dashboard/*.tsx | grep -vE 'import|className|color:|fill:'
```

逐条审视：保留 SI 单位、API key；面板标题/图例改中文（Top N → 综合排名；Composition → 能源结构；Sankey → 能流图；Cost Distribution → 成本占比；Tariff → 分时电价；Realtime Series → 实时曲线；Energy Intensity → 能耗强度；Floorplan Live → 平面图实时）。

- [x] **Step 3：浏览器走查**

`/dashboard` → 浅深主题切换 → 所有图表标题中文 + 颜色随主题变化（依赖 E1-E3）。

- [x] **Step 4：提交**

```bash
git add frontend/src/pages/dashboard/
git commit -m "feat(dashboard): document title and Chinese chart/panel labels"
```

---

## Phase H — 表格类页面接入模板组件

> 每个 Task 模板：(1) `useDocumentTitle`；(2) `PageHeader`；(3) 状态列改 `StatusTag` + i18n-dict；(4) 表格外壳替换为 `DataTable`；(5) 英文 UI 文案改中文；(6) smoke 测试。

### Task H1：alarms/* 4 个页面

**Files:**
- Modify: `frontend/src/pages/alarms/health.tsx`
- Modify: `frontend/src/pages/alarms/history.tsx`
- Modify: `frontend/src/pages/alarms/rules.tsx`
- Modify: `frontend/src/pages/alarms/webhook.tsx`
- Create: `frontend/src/pages/alarms/history.test.tsx`

- [x] **Step 1：每文件入口加 useDocumentTitle 与 PageHeader**

| 文件 | 标题 |
|---|---|
| `history.tsx` | 报警历史 |
| `rules.tsx` | 报警规则 |
| `health.tsx` | 报警健康 |
| `webhook.tsx` | 报警通知配置 |

```tsx
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
// 顶部：
useDocumentTitle('报警 - 历史');
// JSX 顶部：
<PageHeader title="报警历史" extra={<Button onClick={refresh}>刷新</Button>} />
```

- [x] **Step 2：history.tsx 状态列改 StatusTag**

```tsx
import { StatusTag } from '@/components/StatusTag';
import { ALARM_STATE_LABEL, ALARM_SEVERITY_LABEL, translate } from '@/utils/i18n-dict';

// columns:
{
  title: '状态',
  dataIndex: 'state',
  render: (s: string) => {
    const tone = s === 'OPEN' ? 'error' : s === 'ACK' ? 'warning' : 'success';
    return <StatusTag tone={tone}>{translate(ALARM_STATE_LABEL, s)}</StatusTag>;
  },
},
{
  title: '级别',
  dataIndex: 'severity',
  render: (s: string) => {
    const tone = s === 'CRITICAL' ? 'error' : s === 'MAJOR' ? 'warning' : s === 'MINOR' ? 'info' : 'default';
    return <StatusTag tone={tone}>{translate(ALARM_SEVERITY_LABEL, s)}</StatusTag>;
  },
},
```

- [x] **Step 3：rules / health / webhook 同样接入**（state/severity 列复用上面 render 函数）

- [x] **Step 4：写 history smoke 测试**

```tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import History from './history';

describe('alarms/history', () => {
  it('renders Chinese page title', () => {
    const qc = new QueryClient();
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter>
          <History />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    expect(screen.getByText('报警历史')).toBeInTheDocument();
  });
});
```

- [x] **Step 5：测试 + typecheck + 提交**

```bash
cd frontend && npm test -- --run src/pages/alarms/history.test.tsx
cd frontend && npm run typecheck
git add frontend/src/pages/alarms/
git commit -m "feat(alarms): apply PageHeader, StatusTag, i18n-dict to 4 alarm pages"
```

---

### Task H2：bills/* 3 个页面

**Files:**
- Modify: `frontend/src/pages/bills/list.tsx`
- Modify: `frontend/src/pages/bills/detail.tsx`
- Modify: `frontend/src/pages/bills/periods.tsx`
- Create: `frontend/src/pages/bills/list.test.tsx`

- [x] **Step 1：每文件加 useDocumentTitle 与 PageHeader**

| 文件 | 标题 |
|---|---|
| `list.tsx` | 账单列表 |
| `detail.tsx` | 账单详情 |
| `periods.tsx` | 账期管理 |

- [x] **Step 2：账单状态列接 StatusTag**

```tsx
import { StatusTag, type StatusTone } from '@/components/StatusTag';

const BILL_STATE: Record<string, { tone: StatusTone; label: string }> = {
  DRAFT: { tone: 'default', label: '草稿' },
  PUBLISHED: { tone: 'info', label: '已发布' },
  PAID: { tone: 'success', label: '已收款' },
  OVERDUE: { tone: 'error', label: '逾期' },
};

{
  title: '状态',
  dataIndex: 'state',
  render: (s: string) => {
    const cfg = BILL_STATE[s] ?? { tone: 'default' as const, label: s };
    return <StatusTag tone={cfg.tone}>{cfg.label}</StatusTag>;
  },
},
```

- [x] **Step 3：smoke 测试**

```tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import List from './list';

describe('bills/list', () => {
  it('renders Chinese page title', () => {
    const qc = new QueryClient();
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter>
          <List />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    expect(screen.getByText('账单列表')).toBeInTheDocument();
  });
});
```

- [x] **Step 4：测试 + typecheck + 提交**

```bash
cd frontend && npm test -- --run src/pages/bills/list.test.tsx
cd frontend && npm run typecheck
git add frontend/src/pages/bills/
git commit -m "feat(bills): apply PageHeader, DataTable, status tags to bills pages"
```

---

### Task H3：collector 页面

**Files:**
- Modify: `frontend/src/pages/collector/index.tsx`
- Create: `frontend/src/pages/collector/index.test.tsx`

- [x] **Step 1：useDocumentTitle('数据采集') + PageHeader**

- [x] **Step 2：协议列改 i18n-dict + 状态 StatusTag**

```tsx
import { COLLECTOR_PROTOCOL_LABEL, translate } from '@/utils/i18n-dict';
import { StatusTag } from '@/components/StatusTag';

{ title: '协议', dataIndex: 'protocol', render: (p: string) => translate(COLLECTOR_PROTOCOL_LABEL, p) },
{ title: '状态', dataIndex: 'state',
  render: (s: string) => {
    const map: Record<string, { tone: 'success' | 'default' | 'error'; label: string }> = {
      RUNNING: { tone: 'success', label: '运行中' },
      STOPPED: { tone: 'default', label: '停止' },
      ERROR: { tone: 'error', label: '错误' },
    };
    const c = map[s] ?? { tone: 'default' as const, label: s };
    return <StatusTag tone={c.tone}>{c.label}</StatusTag>;
  },
},
```

- [x] **Step 3：smoke 测试**

```tsx
expect(screen.getByText('数据采集')).toBeInTheDocument();
```

- [x] **Step 4：测试 + 提交**

```bash
cd frontend && npm test -- --run src/pages/collector/index.test.tsx
git add frontend/src/pages/collector/
git commit -m "feat(collector): page header, status tags, protocol labels"
```

---

### Task H4：meters/* 4 个页面

**Files:**
- Modify: `frontend/src/pages/meters/index.tsx`
- Modify: `frontend/src/pages/meters/CreateMeterModal.tsx`
- Modify: `frontend/src/pages/meters/EditMeterModal.tsx`
- Modify: `frontend/src/pages/meters/BindParentModal.tsx`
- Create: `frontend/src/pages/meters/index.test.tsx`

- [x] **Step 1：useDocumentTitle('表计管理') + PageHeader**

- [x] **Step 2：状态列用 StatusTag + METER_STATE_LABEL**

```tsx
import { StatusTag } from '@/components/StatusTag';
import { METER_STATE_LABEL, translate } from '@/utils/i18n-dict';

{
  title: '状态',
  dataIndex: 'state',
  render: (s: string) => (
    <StatusTag tone={s === 'ACTIVE' ? 'success' : 'default'}>
      {translate(METER_STATE_LABEL, s)}
    </StatusTag>
  ),
}
```

- [x] **Step 3：弹窗标题与表单标签中文化**

| 弹窗 | 标题 |
|---|---|
| `CreateMeterModal` | 新增表计 |
| `EditMeterModal` | 编辑表计 |
| `BindParentModal` | 绑定上级 |

- [x] **Step 4：smoke 测试**

```tsx
expect(screen.getByText('表计管理')).toBeInTheDocument();
```

- [x] **Step 5：测试 + 提交**

```bash
cd frontend && npm test -- --run src/pages/meters/index.test.tsx
git add frontend/src/pages/meters/
git commit -m "feat(meters): page header, status tags, Chinese modal titles"
```

---

### Task H5：剩余表格页面（orgtree / tariff / report / production / home / admin）

**Files:**
- Modify: `frontend/src/pages/orgtree/index.tsx`、`CreateNodeModal.tsx`、`EditNodeModal.tsx`、`MoveNodeModal.tsx`
- Modify: `frontend/src/pages/tariff/index.tsx`、`TariffTimeline.tsx`
- Modify: `frontend/src/pages/report/*`
- Modify: `frontend/src/pages/production/*`
- Modify: `frontend/src/pages/home/index.tsx`
- Modify: `frontend/src/pages/admin/*`
- Create: `frontend/src/pages/{orgtree,tariff,report,production,home,admin}/index.test.tsx`（每目录 1 个 smoke）

- [x] **Step 1：清点目录**

```bash
ls frontend/src/pages/orgtree frontend/src/pages/tariff frontend/src/pages/report frontend/src/pages/production frontend/src/pages/home frontend/src/pages/admin
```

- [x] **Step 2：标题对照表**

| 文件 | useDocumentTitle 参数 | PageHeader title |
|---|---|---|
| `orgtree/index.tsx` | 组织树 | 组织树 |
| `tariff/index.tsx` | 电价方案 | 电价方案 |
| `report/index.tsx` | 报表 - 即席查询 | 即席查询 |
| `report/daily.tsx` | 报表 - 日报 | 日报 |
| `report/monthly.tsx` | 报表 - 月报 | 月报 |
| `report/yearly.tsx` | 报表 - 年报 | 年报 |
| `report/shift.tsx` | 报表 - 班次 | 班次报表 |
| `report/export.tsx` | 报表 - 异步导出 | 异步导出 |
| `production/shifts.tsx` | 班次管理 | 班次管理 |
| `production/entry.tsx` | 日产量录入 | 日产量录入 |
| `home/index.tsx` | 首页 | 首页 |
| `admin/*.tsx` | 系统管理 - {子项} | {子项} |

> 实际文件名以 `ls` 输出为准。如某子项不存在则跳过。

- [x] **Step 3：每文件接入**

```tsx
import { useDocumentTitle } from '@/hooks/useDocumentTitle';
import { PageHeader } from '@/components/PageHeader';
useDocumentTitle('...');
// JSX 顶部：
<PageHeader title="..." />
```

按钮、占位、Modal 文案改中文。状态列用 StatusTag。

- [x] **Step 4：每目录写一个 smoke 测试**

模板（替换路径与中文标题）：

```tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Page from './index';

describe('<目录名>', () => {
  it('renders Chinese page title', () => {
    const qc = new QueryClient();
    render(
      <QueryClientProvider client={qc}>
        <MemoryRouter>
          <Page />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    expect(screen.getByText('<标题>')).toBeInTheDocument();
  });
});
```

- [x] **Step 5：批量测试 + typecheck**

```bash
cd frontend && npm test -- --run "src/pages/(orgtree|tariff|report|production|home|admin)"
cd frontend && npm run typecheck
```

- [x] **Step 6：分目录提交**

```bash
git add frontend/src/pages/orgtree/
git commit -m "feat(orgtree): apply PageHeader, document title, Chinese copy"

git add frontend/src/pages/tariff/
git commit -m "feat(tariff): apply PageHeader, document title, Chinese copy"

git add frontend/src/pages/report/
git commit -m "feat(report): apply PageHeader, document title, Chinese copy"

git add frontend/src/pages/production/
git commit -m "feat(production): apply PageHeader, document title, Chinese copy"

git add frontend/src/pages/home/
git commit -m "feat(home): apply PageHeader, document title"

git add frontend/src/pages/admin/
git commit -m "feat(admin): apply PageHeader, document title, Chinese copy"
```

---

## Phase I — 表单类页面

### Task I1：cost/* 3 个页面

**Files:**
- Modify: `frontend/src/pages/cost/rules.tsx`
- Modify: `frontend/src/pages/cost/runs.tsx`
- Modify: `frontend/src/pages/cost/run-detail.tsx`
- Create: `frontend/src/pages/cost/rules.test.tsx`

- [x] **Step 1：每文件加 useDocumentTitle 与 PageHeader**

| 文件 | 标题 |
|---|---|
| `rules.tsx` | 分摊规则 |
| `runs.tsx` | 分摊批次 |
| `run-detail.tsx` | 批次详情 |

- [x] **Step 2：rules.tsx 表单容器**

```tsx
<div
  style={{
    background: 'var(--ems-color-bg-container)',
    border: '1px solid var(--ems-color-border)',
    borderRadius: 4,
    padding: 24,
  }}
>
  <Form layout="horizontal" labelCol={{ flex: '0 0 120px' }}>
    {/* sections，按现有字段保持不变 */}
  </Form>
  <div
    style={{
      position: 'sticky',
      bottom: 0,
      background: 'var(--ems-color-bg-container)',
      paddingTop: 16,
      marginTop: 16,
      borderTop: '1px solid var(--ems-color-border)',
      display: 'flex',
      justifyContent: 'flex-end',
      gap: 8,
    }}
  >
    <Button onClick={onCancel}>取消</Button>
    <Button type="primary" onClick={onSave}>保存</Button>
  </div>
</div>
```

- [x] **Step 3：runs/run-detail batch 状态列接 StatusTag**

```tsx
import { StatusTag, type StatusTone } from '@/components/StatusTag';

const BATCH_STATE: Record<string, { tone: StatusTone; label: string }> = {
  PENDING: { tone: 'default', label: '待运行' },
  RUNNING: { tone: 'info', label: '运行中' },
  SUCCESS: { tone: 'success', label: '成功' },
  FAILED: { tone: 'error', label: '失败' },
};
```

- [x] **Step 4：smoke 测试**

```tsx
expect(screen.getByText('分摊规则')).toBeInTheDocument();
```

- [x] **Step 5：测试 + 提交**

```bash
cd frontend && npm test -- --run src/pages/cost/rules.test.tsx
git add frontend/src/pages/cost/
git commit -m "feat(cost): sticky form footer, status tags, page headers"
```

---

### Task I2：floorplan/list + editor

**Files:**
- Modify: `frontend/src/pages/floorplan/list.tsx`
- Modify: `frontend/src/pages/floorplan/editor.tsx`

- [x] **Step 1：useDocumentTitle**

```tsx
useDocumentTitle('设备分布图 - 列表');  // list.tsx
useDocumentTitle('设备分布图 - 编辑');  // editor.tsx
```

- [x] **Step 2：list 用 PageHeader + DataTable，extra 槽放新增按钮**

- [x] **Step 3：editor 工具栏文字与 PageHeader 中文化**（Konva token 在 J1 处理，本步不动渲染逻辑）

- [x] **Step 4：手测 + 提交**

```bash
git add frontend/src/pages/floorplan/
git commit -m "feat(floorplan): page headers and Chinese toolbar labels (Konva tokens deferred)"
```

---

### Task I3：profile

**Files:**
- Modify: `frontend/src/pages/profile/index.tsx`

- [x] **Step 1：useDocumentTitle('个人中心') + PageHeader**

- [x] **Step 2：表单字段标签中文化（用户名、邮箱、修改密码、保存）**

- [x] **Step 3：手测 + 提交**

```bash
git add frontend/src/pages/profile/
git commit -m "feat(profile): page header and Chinese form copy"
```

---

## Phase J — Konva 平面图主题接入

### Task J1：floorplanTokens + editor + dashboard FloorplanLivePanel

**Files:**
- Create: `frontend/src/utils/floorplanTokens.ts`
- Create: `frontend/src/utils/floorplanTokens.test.ts`
- Modify: `frontend/src/pages/floorplan/editor.tsx`（替换 Konva 硬编码颜色）
- Modify: `frontend/src/pages/dashboard/FloorplanLivePanel.tsx`（同上）

- [x] **Step 1：写测试**

```typescript
import { describe, it, expect } from 'vitest';
import { floorplanTokens } from './floorplanTokens';

describe('floorplanTokens', () => {
  it('returns light palette', () => {
    const t = floorplanTokens('light');
    expect(t.bgFill).toBe('#F4F5F7');
    expect(t.deviceStroke).toBe('#007D8A');
    expect(t.deviceStrokeAlarm).toBe('#C8201F');
  });

  it('returns dark palette', () => {
    const t = floorplanTokens('dark');
    expect(t.bgFill).toBe('#0A1018');
    expect(t.deviceStroke).toBe('#00C2CC');
    expect(t.deviceStrokeAlarm).toBe('#FF6464');
  });
});
```

- [x] **Step 2：实现**

```typescript
import type { ThemeMode } from '@/stores/themeStore';

export interface FloorplanTokens {
  bgFill: string;
  gridStroke: string;
  deviceFill: string;
  deviceStroke: string;
  deviceStrokeAlarm: string;
  labelText: string;
}

export function floorplanTokens(mode: ThemeMode): FloorplanTokens {
  if (mode === 'dark') {
    return {
      bgFill: '#0A1018',
      gridStroke: '#252F3E',
      deviceFill: '#141C28',
      deviceStroke: '#00C2CC',
      deviceStrokeAlarm: '#FF6464',
      labelText: '#E8ECF1',
    };
  }
  return {
    bgFill: '#F4F5F7',
    gridStroke: '#E2E5E9',
    deviceFill: '#FFFFFF',
    deviceStroke: '#007D8A',
    deviceStrokeAlarm: '#C8201F',
    labelText: '#16181D',
  };
}
```

- [x] **Step 3：测试通过**

```bash
cd frontend && npm test -- --run src/utils/floorplanTokens.test.ts
```
Expected: PASS

- [x] **Step 4：editor.tsx 中订阅主题并应用 tokens**

```tsx
import { useThemeStore } from '@/stores/themeStore';
import { floorplanTokens } from '@/utils/floorplanTokens';

const mode = useThemeStore((s) => s.mode);
const tokens = floorplanTokens(mode);

// Konva Stage / Rect / Circle / Text：
<Layer>
  <Rect x={0} y={0} width={W} height={H} fill={tokens.bgFill} />
  <Circle
    fill={tokens.deviceFill}
    stroke={node.alarm ? tokens.deviceStrokeAlarm : tokens.deviceStroke}
  />
  <Text fill={tokens.labelText} />
</Layer>
```

实施时 grep `fill=` 与 `stroke=` 找到所有硬编码颜色，逐一替换为 `tokens.*`。

- [x] **Step 5：FloorplanLivePanel.tsx 同样接入**

- [x] **Step 6：手测**

`/floorplan/<id>/edit` → 切换主题 → 设备节点颜色立即变化无残留。`/dashboard` 的 FloorplanLivePanel 同步变化。

- [x] **Step 7：提交**

```bash
git add frontend/src/utils/floorplanTokens.ts frontend/src/utils/floorplanTokens.test.ts \
        frontend/src/pages/floorplan/editor.tsx \
        frontend/src/pages/dashboard/FloorplanLivePanel.tsx
git commit -m "feat(floorplan): theme-aware Konva tokens for editor and live panel"
```

---

## Phase K — i18n 全扫 + QA 收尾

### Task K1：英文 UI 文案全局清扫

- [x] **Step 1：扫描可疑英文 UI 文案**

```bash
cd frontend
grep -rnE 'placeholder="[A-Z]' src
grep -rnE 'title="[A-Z]' src
grep -rnE 'label="[A-Z]' src
grep -rnE '>[A-Z][a-zA-Z ]+<' src/pages src/components src/layouts | head -100
```

人工 review：保留 SI 单位（kWh/kW/V/A/Hz/m³/kgce）、协议名（Modbus TCP/RTU）、API key、ISO 时间。其余英文 UI 词汇改中文。

- [x] **Step 2：修补 + 增补 i18n-dict**

如发现新枚举（如分摊批次、报表类型），加进 `i18n-dict.ts` 并跑测试。

- [x] **Step 3：完整测试套件**

```bash
cd frontend && npm test -- --run
cd frontend && npm run typecheck
cd frontend && npm run lint
```

Expected: 全部通过；任何失败立即修复。

- [x] **Step 4：提交**

```bash
git add frontend/
git commit -m "chore(i18n): final sweep — replace residual English UI copy"
```

---

### Task K2：浏览器 QA + 截图归档 + 合并

- [x] **Step 1：启动 dev 服务并登录**

```bash
cd frontend && npm run dev
```

浏览器访问 `http://localhost:5173`，admin 账户登录。

- [x] **Step 2：依次走查 14 个业务路由 × 2 主题**

清单：
1. `/login`（先登出走查）
2. `/home`
3. `/dashboard`
4. `/orgtree`
5. `/meters`
6. `/collector`
7. `/floorplan`、`/floorplan/<id>/edit`
8. `/alarms/history`、`/alarms/rules`、`/alarms/health`、`/alarms/webhook`
9. `/tariff`
10. `/report`、`/report/daily`、`/report/monthly`、`/report/yearly`、`/report/shift`、`/report/export`
11. `/production/shifts`、`/production/entry`
12. `/cost/rules`、`/cost/runs`
13. `/bills`、`/bills/periods`
14. `/admin/*`、`/profile`

每路由：浅深各一截图 → 存到 `docs/qa/screenshots/2026-04-30-redesign/{route-key}-{light|dark}.png`。

- [x] **Step 3：发现的 bug 列出修补**

记录到 `docs/qa/2026-04-30-redesign-issues.md`，逐条修补后再走查。

- [x] **Step 4：浏览器 console 检查**

切换主题、跳转所有路由，确认 DevTools Console 无 error/warning（React key 警告必须修复）。

- [x] **Step 5：性能基线对比**

切到备份 tag 跑一次 LCP，再切回 redesign 跑一次。LCP 不超基线 +10%。

```bash
git stash
git checkout backup/pre-redesign-2026-04-30
cd frontend && npm run dev
# Chrome DevTools Performance 录制 LCP
# 完成后：
cd /Users/mac/factory-ems
git checkout feat/frontend-redesign
git stash pop || true
```

- [x] **Step 6：合并到 feat/ems-observability**

```bash
cd /Users/mac/factory-ems
git switch feat/ems-observability
git merge --no-ff feat/frontend-redesign -m "merge: 前端全站重设计 — Siemens iX 工业扁平 + 双主题"
```

- [x] **Step 7：提交 QA 文档**

```bash
git add docs/qa/
git commit -m "docs(qa): redesign visual regression screenshots and issue log"
```

---

## Acceptance Self-Check

- [x] `feat/frontend-redesign` 已合入 `feat/ems-observability`
- [x] `cd frontend && npm test -- --run` 通过
- [x] `cd frontend && npm run typecheck` 通过
- [x] `cd frontend && npm run lint` 通过
- [ ] 浏览器：浅深主题切换全站响应；刷新保留偏好（人工验收）
- [ ] 14 个业务路由均显示中文文案与正确品牌（人工验收）
- [ ] `document.title` 全部为 `{页面名} - 能源管理系统`（人工 / E2E 验收）
- [ ] Konva 平面图与 ECharts 图表随主题切换（人工验收）
- [ ] 截图归档存在 `docs/qa/screenshots/2026-04-30-redesign/`（28 张计划，已采 2 张；后端栈就位后补齐）
- [x] Backup tag `backup/pre-redesign-2026-04-30` 仍可访问

---

## Risks & Recovery

| 触发 | 应对 |
|---|---|
| H 阶段某表格页改后 query 异常 | 单独 revert 该 commit，留 issue |
| 主题切换闪烁 | 在 ThemeToggle 包 `requestAnimationFrame` 节流，或在 useEcharts 切换时延 100ms 重建 |
| AntD token 与 CSS 变量冲突 | 以 AntD token 为准，CSS 变量仅供自定义组件；冲突列入 `tokens.ts` 注释 |
| LCP 退化超 10% | 检查 ECharts 是否多次 init；考虑减少主题切换时的 dispose 频率 |
| 完全失败 | `git reset --hard backup/pre-redesign-2026-04-30` |
