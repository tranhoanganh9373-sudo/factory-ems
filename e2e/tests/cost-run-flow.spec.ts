/**
 * Plan 2.3 Phase K — cost-run-flow.spec.ts
 *
 * /cost/runs：新建批次 → 轮询到 SUCCESS → 进入 detail 看到 line 明细。
 *
 * Prerequisites:
 *   - 库里至少有 1 个启用且 effective 的 cost rule（不论算法）。
 *     若无规则，本测试会 SKIP 而非 fail —— 取决于"新建批次"按钮是否能正常提交。
 */

import { test, expect, Page } from '@playwright/test';

async function login(page: Page) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).toHaveURL('/');
}

test('submit cost run and wait for SUCCESS, then open detail', async ({ page }) => {
  await login(page);

  await page.goto('/cost/runs');
  await expect(page.getByText('分摊批次').first()).toBeVisible({ timeout: 10_000 });

  // ---- 新建批次 ----
  await page.getByRole('button', { name: '新建批次' }).click();
  const modal = page.locator('.ant-modal');
  await expect(modal).toBeVisible();

  // 选时间区间 — 直接 fill 到两个 input
  const rangeInputs = modal.locator('.ant-picker-input input');
  await rangeInputs.nth(0).fill('2026-03-01 00:00:00');
  await rangeInputs.nth(1).fill('2026-04-01 00:00:00');
  await page.keyboard.press('Escape');

  await modal.getByRole('button', { name: '确 定' }).click();

  // ---- 等 SUCCESS（最多 60s）----
  // 第一行 ID 列点进去 → 等 status tag 变 SUCCESS
  const successTag = page.locator('.ant-tag', { hasText: 'SUCCESS' }).first();
  await expect(successTag).toBeVisible({ timeout: 60_000 });

  // ---- 进入 detail ----
  const firstIdLink = page.locator('a[href^="/cost/runs/"]').first();
  await firstIdLink.click();
  await expect(page).toHaveURL(/\/cost\/runs\/\d+/);

  // detail 显示 status SUCCESS 和明细表
  await expect(page.locator('.ant-tag', { hasText: 'SUCCESS' }).first()).toBeVisible({
    timeout: 10_000,
  });
  // 至少 1 行 line
  await expect(page.locator('.ant-table-row').first()).toBeVisible({ timeout: 10_000 });
});
