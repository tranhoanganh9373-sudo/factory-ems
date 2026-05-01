/**
 * CP-Phase9 — channel-opcua-cert.spec.ts
 *
 * Prerequisites:
 *   - App running at E2E_BASE_URL; admin user admin / admin123!
 *   - At least one channel exists in the realtime table (any protocol). The test
 *     skips itself when no rows are present (cold-start environments).
 *
 * What it tests:
 *   - Admin opens /collector, clicks "详情" on the first channel row, and verifies
 *     the ChannelDetailDrawer renders Descriptions including 协议, 状态, 24h 成功 / 失败,
 *     平均延迟 and 协议元信息 fields.
 *
 * Notes:
 *   - In v1.1 OPC UA only SecurityMode.NONE is wired end-to-end. The certificate
 *     approval REST flow described in the spec (POST /api/v1/collector/{id}/trust-cert)
 *     is NOT yet exposed; this E2E intentionally covers only the surfaces that exist.
 *   - The drawer is the spec'd surface for future cert-approval UI; this test
 *     exercises that drawer's foundation today.
 */

import { test, expect } from '@playwright/test';

async function login(page: any) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

test('admin can open the channel detail drawer with realtime fields visible', async ({ page }) => {
  await login(page);
  await page.goto('/collector');
  await expect(page.getByRole('main').getByText('数据采集')).toBeVisible();

  // Wait briefly for the first refetch (refetchInterval = 5s) so the table fills.
  await page.waitForTimeout(2_000);

  const firstRow = page.locator('tr.ant-table-row').first();
  if ((await firstRow.count()) === 0) {
    test.skip(true, 'no channels in this environment; skipping detail drawer assertion');
  }

  await firstRow.getByRole('button', { name: '详情' }).click();

  // Drawer title format: `通道 #<id> 详情`
  await expect(page.getByText(/通道 #\d+ 详情/)).toBeVisible({ timeout: 10_000 });

  // ChannelDetailDrawer Descriptions labels — match exactly what the component renders
  await expect(page.getByText('协议', { exact: true })).toBeVisible();
  await expect(page.getByText('状态', { exact: true })).toBeVisible();
  await expect(page.getByText('24h 成功', { exact: true })).toBeVisible();
  await expect(page.getByText('24h 失败', { exact: true })).toBeVisible();
  await expect(page.getByText('平均延迟', { exact: true })).toBeVisible();
  await expect(page.getByText('协议元信息', { exact: true })).toBeVisible();
});
