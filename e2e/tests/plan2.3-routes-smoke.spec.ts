/**
 * Plan 2.3 — routes smoke test.
 *
 * 不依赖 mock-data 业务种子；仅验证：
 *   1) admin login 成功（不停留在 /login）
 *   2) 6 个 Plan 2.3 新路由 + dashboard 都能渲染（不进 forbidden）
 *   3) 各页 H 标题或 Empty 占位都能找到 — 即"页面挂上"了
 *
 * 真正的业务断言交给 cost-rule-crud / cost-run-flow / bill-period-lifecycle / cost-export
 * 4 个 spec（需要更深的 fixture / 更稳的 modal 交互调优）。
 */

import { test, expect, Page } from '@playwright/test';

async function login(page: Page) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

test('Plan 2.3 routes — admin can reach cost+bills pages', async ({ page }) => {
  await login(page);

  // 不应被 forbidden 拦截
  for (const path of [
    '/cost/rules',
    '/cost/runs',
    '/bills',
    '/bills/periods',
    '/dashboard',
  ]) {
    await page.goto(path);
    await expect(page).not.toHaveURL(/\/forbidden/, { timeout: 5000 });
    // 等页面 settle，避免不同路由的渲染时机
    await page.waitForLoadState('networkidle', { timeout: 10_000 }).catch(() => {});
  }

  // 关键文案存在（每个路由各一条）
  await page.goto('/cost/rules');
  await expect(page.getByText('分摊规则').first()).toBeVisible({ timeout: 10_000 });

  await page.goto('/cost/runs');
  await expect(page.getByText('分摊批次').first()).toBeVisible({ timeout: 10_000 });

  await page.goto('/bills');
  await expect(page.getByText('账单').first()).toBeVisible({ timeout: 10_000 });

  await page.goto('/bills/periods');
  await expect(page.getByText('账期管理').first()).toBeVisible({ timeout: 10_000 });

  // dashboard 第 10 块（成本分布）
  await page.goto('/dashboard');
  await expect(page.getByText('成本分布').first()).toBeVisible({ timeout: 10_000 });
});
