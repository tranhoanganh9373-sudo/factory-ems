/**
 * CP-Phase9 — channel-virtual.spec.ts
 *
 * Prerequisites:
 *   - App running at E2E_BASE_URL (default http://localhost:8888)
 *   - Backend (ems-app + ems-collector) reachable; admin user admin / admin123!
 *
 * What it tests:
 *   - Admin opens /collector, creates a VIRTUAL channel via the ChannelEditor drawer,
 *     submits, sees "已保存" toast and a new VIRTUAL row in the realtime table.
 *
 * Notes:
 *   - VIRTUAL channels do not need network reachability — once saved the transport
 *     should reach CONNECTED within a few seconds. We assert "row exists" and avoid
 *     strict color-of-tag assertions to keep the test stable.
 *   - Channel is left in DB on purpose (UI has no delete button in v1.1). The test
 *     uses a unique timestamped name to avoid collisions on re-runs.
 */

import { test, expect } from '@playwright/test';

async function login(page: any) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

async function pickSelectOption(page: any, text: string) {
  const opt = page.locator('.ant-select-item-option').filter({ hasText: text }).first();
  await opt.waitFor({ state: 'visible' });
  await opt.dispatchEvent('click');
}

test('admin can create VIRTUAL channel and see it appear in realtime table', async ({ page }) => {
  const channelName = `e2e-virtual-${Date.now()}`;

  await login(page);
  await page.goto('/collector');
  await expect(page.getByRole('main').getByText('数据采集')).toBeVisible();

  // Open ChannelEditor drawer
  await page.getByRole('button', { name: /新\s*增\s*通\s*道/ }).click();
  await expect(page.getByText('新增通道')).toBeVisible();

  // Channel name
  await page.getByLabel('通道名称').fill(channelName);

  // Protocol = 虚拟（模拟）
  await page
    .locator('.ant-form-item')
    .filter({ hasText: '协议' })
    .locator('.ant-select-selector')
    .click();
  await pickSelectOption(page, '虚拟（模拟）');

  // Add one virtual point. Default mode=CONSTANT, params placeholder='{"value": 0}'
  await page.getByRole('button', { name: /\+\s*新增测点/ }).click();
  // The newest "Key" input is the last Key field added by the FormList
  const lastKey = page
    .locator('label:has-text("Key")')
    .last()
    .locator('xpath=following::input[1]');
  await lastKey.fill('test-point');

  // Save
  await page.getByRole('button', { name: /^保\s*存$/ }).click();

  // Success toast
  await expect(page.locator('.ant-message-success').first()).toBeVisible({ timeout: 10_000 });

  // Realtime table refetch interval = 5s. Wait then look for any VIRTUAL row.
  // We do not assert CONNECTED state strictly because startup may briefly be CONNECTING.
  await page.waitForTimeout(7_000);
  const virtualRow = page.locator('tr').filter({ hasText: 'VIRTUAL' }).first();
  await expect(virtualRow).toBeVisible({ timeout: 15_000 });
});
