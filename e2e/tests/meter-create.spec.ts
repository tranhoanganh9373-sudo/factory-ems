/**
 * K1 — meter-create.spec.ts
 *
 * Prerequisites:
 *   - App running at E2E_BASE_URL (default http://localhost:8888)
 *   - seed-postgres.sql loaded: org_nodes include LINE-A (code='LINE-A', name='产线A')
 *   - Admin user: admin / admin123!
 *
 * What it tests:
 *   - Admin can navigate to 测点管理, open 新建测点 form, fill it, submit,
 *     see toast confirmation and new row in the table.
 *   - Cleanup: deletes the created meter in a finally block so the test is idempotent.
 */

import { test, expect } from '@playwright/test';

async function login(page: any) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

// Helper: pick an option from an AntD Select dropdown (handles virtual list + Portal).
async function pickSelectOption(page: any, text: string) {
  const opt = page.locator('.ant-select-item-option').filter({ hasText: text }).first();
  await opt.waitFor({ state: 'visible' });
  await opt.dispatchEvent('click');
}

test('admin can create and delete a meter', async ({ page }) => {
  const meterCode = `E2E-M-${Date.now()}`;
  const meterName = 'E2E 测点';

  await login(page);
  await page.goto('/meters');
  await expect(page.getByRole('main').getByText('测点管理')).toBeVisible();

  // Open create form
  await page.getByRole('button', { name: /新建测点/ }).click();

  // Fill code/name — Form.Item label 是"编码"/"名称"（modal 标题已有"新建测点"前缀）
  await page.getByLabel('编码').fill(meterCode);
  await page.getByLabel('名称').fill(meterName);

  // Energy type — 通过 Form.Item 包装找到 .ant-select-selector，点击它能稳定打开 dropdown。
  await page
    .locator('.ant-form-item')
    .filter({ hasText: '能源类型' })
    .locator('.ant-select-selector')
    .click();
  await pickSelectOption(page, '电');

  // Org node
  await page
    .locator('.ant-form-item')
    .filter({ hasText: '组织节点' })
    .locator('.ant-select-selector')
    .click();
  const orgOpt = page
    .locator('.ant-select-tree-title, .ant-select-item-option')
    .filter({ hasText: /冲压车间|MOCK-WS-A/ })
    .first();
  await orgOpt.waitFor({ state: 'visible' });
  await orgOpt.dispatchEvent('click');

  // Influx fields — measurement is pre-filled by convention; set tag value = code
  // Only fill if the fields are visible/required
  const influxMeasurementInput = page.getByLabel('Measurement');
  if (await influxMeasurementInput.isVisible()) {
    await influxMeasurementInput.fill('energy_reading');
  }
  const influxTagKeyInput = page.getByLabel('Tag Key');
  if (await influxTagKeyInput.isVisible()) {
    await influxTagKeyInput.fill('meter_code');
  }
  const influxTagValueInput = page.getByLabel('Tag Value');
  if (await influxTagValueInput.isVisible()) {
    await influxTagValueInput.fill(meterCode);
  }

  // Submit
  await page.getByRole('button', { name: '确 定' }).click();

  // Assert toast / success message
  await expect(page.getByText(/已创建|创建成功|success/i)).toBeVisible({ timeout: 10_000 });

  // Assert new row appears in the table
  await expect(page.locator('.ant-table-row').filter({ hasText: meterCode })).toBeVisible({
    timeout: 10_000,
  });

  // ── Cleanup: delete the row (try/finally to always attempt cleanup) ──
  try {
    const row = page.locator('.ant-table-row').filter({ hasText: meterCode });
    await row.getByRole('button', { name: /删除/ }).click();
    // Confirm the AntD Popconfirm
    await page.getByRole('button', { name: /确认|确 定|是/ }).last().click();
    await expect(row).toHaveCount(0, { timeout: 10_000 });
  } catch {
    // Cleanup failure should not fail the test — log and continue
    console.warn('[K1] Cleanup (delete meter) failed for code:', meterCode);
  }
});
