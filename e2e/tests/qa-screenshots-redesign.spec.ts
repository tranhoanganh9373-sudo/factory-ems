/**
 * 前端重设计 K2 — 14 路由 × 浅深主题 = 28 张截图。
 *
 * 用法（后端栈起来后）：
 *   cd e2e
 *   QA_BASE_URL=http://localhost:5173 \
 *   QA_ADMIN_USER=admin QA_ADMIN_PASS=admin123! \
 *     npx playwright test tests/qa-screenshots-redesign.spec.ts
 *
 * 输出：docs/qa/screenshots/2026-04-30-redesign/{slug}-{light|dark}.png
 *
 * 实现笔记：
 * - 主题通过 zustand persist 写 localStorage 后 reload 触发 React 重渲染
 * - 串行（worker=1，依赖 playwright.config.ts），共享一个 admin 会话
 * - 历史保留：logo.png 曾为 12864×7720（3.5 MB）触发截图编码超时，已下采样
 *   至 1024×614（commit 156aea7）；本脚本仍 remove `<img>` 作为防御层，避免
 *   未来其它路由意外引入大图重现旧 bug
 */
import { test, expect, type Page } from '@playwright/test';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SCREENSHOT_DIR = path.resolve(
  __dirname,
  '../../docs/qa/screenshots/2026-04-30-redesign'
);

const BASE_URL = process.env.QA_BASE_URL || 'http://localhost:5173';
const ADMIN_USER = process.env.QA_ADMIN_USER || 'admin';
const ADMIN_PASS = process.env.QA_ADMIN_PASS || 'admin123!';

interface Route {
  path: string;
  slug: string;
}

const ROUTES: Route[] = [
  { path: '/login', slug: 'login' },
  { path: '/dashboard', slug: 'dashboard' },
  { path: '/orgtree', slug: 'orgtree' },
  { path: '/meters', slug: 'meters' },
  { path: '/collector', slug: 'collector' },
  { path: '/floorplan', slug: 'floorplan' },
  { path: '/alarms/history', slug: 'alarms-history' },
  { path: '/alarms/rules', slug: 'alarms-rules' },
  { path: '/alarms/health', slug: 'alarms-health' },
  { path: '/alarms/webhook', slug: 'alarms-webhook' },
  { path: '/tariff', slug: 'tariff' },
  { path: '/report', slug: 'report' },
  { path: '/production/shifts', slug: 'production-shifts' },
  { path: '/production/entry', slug: 'production-entry' },
  { path: '/cost/rules', slug: 'cost-rules' },
  { path: '/cost/runs', slug: 'cost-runs' },
  { path: '/bills', slug: 'bills' },
  { path: '/bills/periods', slug: 'bills-periods' },
  { path: '/admin/users', slug: 'admin-users' },
  { path: '/profile', slug: 'profile' },
];

const THEMES = ['light', 'dark'] as const;
type Theme = (typeof THEMES)[number];

async function setTheme(page: Page, theme: Theme): Promise<void> {
  await page.evaluate((t) => {
    localStorage.setItem(
      'ems.theme.mode',
      JSON.stringify({ state: { mode: t }, version: 0 })
    );
  }, theme);
}

async function login(page: Page): Promise<void> {
  await page.goto(`${BASE_URL}/login`, { waitUntil: 'networkidle' });
  if (!/\/login/.test(page.url())) return;
  await page.getByPlaceholder('用户名').fill(ADMIN_USER);
  await page.getByPlaceholder('密码').fill(ADMIN_PASS);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/, { timeout: 10_000 });
}

async function captureRoute(
  page: Page,
  route: Route,
  theme: Theme
): Promise<void> {
  await page.goto(`${BASE_URL}${route.path}`, { waitUntil: 'networkidle' });
  await page.evaluate(() => {
    document.querySelectorAll('img').forEach((i) => i.remove());
  });
  await page.waitForTimeout(500);
  const file = path.join(SCREENSHOT_DIR, `${route.slug}-${theme}.png`);
  await page.screenshot({ path: file, fullPage: true });
}

test.describe.serial('K2 visual regression: 14 routes × 2 themes', () => {
  test.setTimeout(600_000);

  test('capture all routes', async ({ page }) => {
    for (const theme of THEMES) {
      await page.context().clearCookies();
      await page.goto(BASE_URL);
      await setTheme(page, theme);
      await login(page);

      for (const route of ROUTES) {
        try {
          await captureRoute(page, route, theme);
          console.log(`✓ ${route.slug}-${theme}`);
        } catch (err) {
          const msg = err instanceof Error ? err.message : String(err);
          console.warn(`✗ ${route.slug}-${theme}: ${msg.slice(0, 80)}`);
        }
      }
    }
  });
});
