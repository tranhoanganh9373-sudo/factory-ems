/**
 * Plan 2.3 Phase L — bill-period-lifecycle.spec.ts
 *
 * /bills/periods：close → lock（含二次确认）→ unlock → 再 close。
 *
 * Prerequisites:
 *   - 库里有可关账期的 SUCCESS cost run（与 BillLifecycleIT 同一前置条件）
 *   - admin 可解锁
 */

import { test, expect, Page } from '@playwright/test';

async function login(page: Page) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

const YM = '2026-03';

test('bill period close → lock (with confirm) → unlock → reclose', async ({ page }) => {
  test.setTimeout(180_000);
  await login(page);

  await page.goto('/bills/periods');
  await expect(page.getByText('账期管理').first()).toBeVisible({ timeout: 10_000 });

  // ---- 创建账期（如不存在）----
  const periodRow = page.locator(`tr:has-text("${YM}")`).first();
  if ((await periodRow.count()) === 0) {
    await page.getByRole('button', { name: '创建账期' }).click();
    const modal = page.locator('.ant-modal');
    // 月份选择器输入
    const monthInput = modal.locator('.ant-picker input').first();
    await monthInput.click();
    await monthInput.fill(YM);
    await page.keyboard.press('Enter');
    await page.keyboard.press('Escape');
    await modal.getByRole('button', { name: '确 定' }).click();
  }
  await expect(periodRow).toBeVisible({ timeout: 10_000 });

  // ---- close ----
  await periodRow.getByRole('button', { name: /关账期|重新生成/ }).click();
  await expect(periodRow.locator('.ant-tag', { hasText: 'CLOSED' })).toBeVisible({
    timeout: 30_000,
  });

  // ---- lock：二次确认输入 "我确认锁定 2026-03" ----
  // AntD 给 2 字按钮自动加空格："锁定" → "锁 定"；用 regex 容忍。
  await periodRow.getByRole('button', { name: /锁\s*定/ }).click();
  const lockModal = page.locator('.ant-modal-confirm').filter({ hasText: '锁定账期' });
  await expect(lockModal).toBeVisible();
  await lockModal.locator('input').fill(`我确认锁定 ${YM}`);
  await lockModal.getByRole('button', { name: /锁\s*定/ }).click();
  await expect(periodRow.locator('.ant-tag', { hasText: 'LOCKED' })).toBeVisible({
    timeout: 10_000,
  });

  // ---- unlock：仅 ADMIN 可解 ----
  await periodRow.getByRole('button', { name: /解\s*锁/ }).click();
  const unlockModal = page.locator('.ant-modal-confirm').filter({ hasText: '解锁账期' });
  await expect(unlockModal).toBeVisible();
  await unlockModal.locator('input').fill(`我确认解锁 ${YM}`);
  await unlockModal.getByRole('button', { name: /解\s*锁/ }).click();
  await expect(periodRow.locator('.ant-tag', { hasText: 'CLOSED' })).toBeVisible({
    timeout: 10_000,
  });

  // ---- reclose（重写）----
  await periodRow.getByRole('button', { name: /重新生成/ }).click();
  await expect(page.getByText(/已关闭，账单已生成/)).toBeVisible({ timeout: 30_000 });

  // ---- 通过"查看账单"链接跳到 /bills?periodId=... ----
  await periodRow.getByRole('button', { name: /查看账单/ }).click();
  await expect(page).toHaveURL(/\/bills\?periodId=\d+/, { timeout: 10_000 });
});
