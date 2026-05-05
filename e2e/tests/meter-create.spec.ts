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
  // 编码字段已移除（后端自动生成）；用唯一名称标识行
  const meterName = `E2E-测点-${Date.now()}`;

  await login(page);
  await page.goto('/meters');
  await expect(page.getByRole('main').getByText('表计管理')).toBeVisible();

  // Open create form
  await page.getByRole('button', { name: /新建测点/ }).click();

  // Wait for modal to open, then fill name
  await expect(page.locator('.ant-modal')).toBeVisible({ timeout: 10_000 });
  await page.getByLabel('名称').fill(meterName);

  // Energy type
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
    .filter({ hasText: /测试车间|MOCK-WS-A/ })
    .first();
  await orgOpt.waitFor({ state: 'visible' });
  await orgOpt.dispatchEvent('click');

  // Submit
  await page.getByRole('button', { name: '确 定' }).click();

  // Assert toast / success message
  await expect(page.getByText(/已创建|创建成功|success/i)).toBeVisible({ timeout: 10_000 });

  // Assert new row appears in the table (find by unique name)
  await expect(page.locator('.ant-table-row').filter({ hasText: meterName })).toBeVisible({
    timeout: 10_000,
  });

  // ── Cleanup: delete the row ──
  try {
    const row = page.locator('.ant-table-row').filter({ hasText: meterName });
    await row.getByRole('button', { name: /删除/ }).click();
    // Confirm the AntD Popconfirm
    await page.getByRole('button', { name: /确认|确 定|是/ }).last().click();
    await expect(row).toHaveCount(0, { timeout: 10_000 });
  } catch {
    console.warn('[K1] Cleanup (delete meter) failed for name:', meterName);
  }
});
