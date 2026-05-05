/**
 * CP-Phase9 — channel-modbus.spec.ts
 *
 * Prerequisites:
 *   - App running at E2E_BASE_URL; admin user admin / admin123!
 *
 * What it tests:
 *   - Admin opens /collector, creates a MODBUS_TCP channel pointing at an unreachable
 *     host (RFC5737 TEST-NET-1 192.0.2.x), submits successfully, then triggers
 *     "测试" from the realtime table and expects a failure toast (host unreachable).
 *
 * Notes:
 *   - Save is expected to succeed (validation passes); the connection test will fail
 *     because 192.0.2.100 is reserved for documentation and not routable.
 *   - This verifies the form schema + the diagnostics test path; it does not require
 *     a real PLC.
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

test('admin can configure MODBUS_TCP channel; test connection fails for unreachable host', async ({
  page,
}) => {
  test.setTimeout(60_000);
  const channelName = `e2e-modbus-${Date.now()}`;

  await login(page);
  await page.goto('/collector');

  // Open ChannelEditor
  await page.getByRole('button', { name: /新\s*增\s*通\s*道/ }).click();
  await page.getByLabel('通道名称').fill(channelName);

  // Protocol = Modbus TCP (default value, but click explicitly to make the test
  // resilient if defaults change in the future)
  await page
    .locator('.ant-form-item')
    .filter({ hasText: '协议' })
    .locator('.ant-select-selector')
    .click();
  await pickSelectOption(page, 'Modbus TCP');

  // Host = RFC5737 TEST-NET-1 reserved address (guaranteed unreachable)
  await page.getByLabel('主机').fill('192.0.2.100');
  // port (502) and unitId (1) keep defaults

  // Add one register point
  await page.getByRole('button', { name: /\+\s*新增测点/ }).click();
  // The register point row: "标签名" is the Key field label in the actual DOM
  const lastKey = page
    .locator('label:has-text("标签名")')
    .last()
    .locator('xpath=following::input[1]');
  await lastKey.fill('voltage_a');
  const lastAddress = page
    .locator('label:has-text("地址")')
    .last()
    .locator('xpath=following::input[1]');
  await lastAddress.fill('40001');

  // Save
  await page.getByRole('button', { name: /^保\s*存$/ }).click();
  await expect(page.locator('.ant-message-success').first()).toBeVisible({ timeout: 10_000 });

  // If a dropdown menu is open (e.g. user avatar menu), click away to close it.
  const openDropdown = page.locator('.ant-dropdown-menu').first();
  if (await openDropdown.isVisible()) {
    await page.mouse.click(400, 400);
  }

  // The table may be paginated; scroll to the last page to find the newly created row.
  // Use table's "last page" button if available, or search via pagination controls.
  // Simpler: navigate to collector page with a search param or just scroll to bottom.
  // The row was confirmed to be created (success toast appeared); find it via "最后一页".
  const lastPageBtn = page.locator('.ant-pagination-next').last();
  // Keep clicking next until the channel row is visible or we run out of pages.
  const modbusRow = page.locator('tr').filter({ hasText: channelName }).last();
  // Try current page first, then paginate forward.
  let rowFound = await modbusRow.isVisible();
  while (!rowFound) {
    const nextBtn = page.locator('.ant-pagination-next:not(.ant-pagination-disabled)');
    if ((await nextBtn.count()) === 0) break;
    await nextBtn.click();
    await page.waitForTimeout(500);
    rowFound = await modbusRow.isVisible();
  }
  await expect(modbusRow).toBeVisible({ timeout: 5_000 });

  // Trigger test connection — must fail (TEST-NET-1 host unreachable).
  // AntD adds a space between 2-char CJK button labels: "测 试".
  await modbusRow.getByRole('button', { name: /测\s*试/ }).click();
  // Connection failure is surfaced in the row's "最后错误" column (not a toast).
  // Wait for either an error toast OR the row to show a timeout/error message.
  await expect(
    page
      .locator('.ant-message-error, .ant-notification-notice-error, tr')
      .filter({ hasText: /超时|error|失败|unreachable/i })
      .first(),
  ).toBeVisible({ timeout: 30_000 });
});
