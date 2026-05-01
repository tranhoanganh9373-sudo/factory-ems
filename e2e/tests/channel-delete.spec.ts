/**
 * E2E for channel delete UI button.
 *
 * Tests admin can create a VIRTUAL channel, delete it via the new 删除 button,
 * confirm in modal, and see the success toast.
 *
 * Prerequisites:
 *   - App running at E2E_BASE_URL; admin user admin / admin123!
 */

import { test, expect, type Page } from '@playwright/test';

async function login(page: Page) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

async function pickSelectOption(page: Page, text: string) {
  const opt = page.locator('.ant-select-item-option').filter({ hasText: text }).first();
  await opt.waitFor({ state: 'visible' });
  await opt.dispatchEvent('click');
}

test('admin can delete a channel via the 删除 button + confirm modal', async ({ page }) => {
  const channelName = `e2e-delete-${Date.now()}`;

  await login(page);
  await page.goto('/collector');

  // Create a VIRTUAL channel (cheapest, no transport required)
  await page.getByRole('button', { name: /新\s*增\s*通\s*道/ }).click();
  await page.getByLabel('通道名称').fill(channelName);
  await page.locator('.ant-form-item').filter({ hasText: '协议' }).locator('.ant-select').click();
  await pickSelectOption(page, '虚拟（模拟）');
  await page.getByRole('button', { name: /保\s*存/ }).click();
  await expect(page.getByRole('button', { name: /保\s*存/ })).not.toBeVisible({ timeout: 5000 });

  // Find the most recently-added row's delete button.
  const deleteBtn = page.locator('[data-testid^="channel-delete-"]').last();
  await expect(deleteBtn).toBeVisible({ timeout: 10_000 });

  // Click delete; confirm in modal
  await deleteBtn.click();
  const confirmBtn = page.locator('.ant-modal-confirm-btns button.ant-btn-dangerous');
  await expect(confirmBtn).toBeVisible();
  await confirmBtn.click();

  // Toast appears confirming deletion
  await expect(page.getByText('通道已删除')).toBeVisible({ timeout: 5000 });
});
