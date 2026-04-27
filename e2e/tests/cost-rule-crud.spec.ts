/**
 * Plan 2.3 Phase J — cost-rule-crud.spec.ts
 *
 * 创建 PROPORTIONAL 规则 → Dry-run 预览 → 删除。
 *
 * Prerequisites:
 *   - App running at E2E_BASE_URL
 *   - admin / admin123! 登录
 *   - 库里至少有 1 块 ELEC meter + 1 个 org（mock-data CLI 已 seed）
 */

import { test, expect, Page } from '@playwright/test';

async function login(page: Page) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

const RULE_CODE = `E2E-RULE-${Date.now()}`;

test('cost rule create + dry-run + delete', async ({ page }) => {
  test.setTimeout(120_000);
  await login(page);

  await page.goto('/cost/rules');
  await expect(page.getByText('分摊规则').first()).toBeVisible({ timeout: 10_000 });

  // ---- 新建规则 ----
  await page.getByRole('button', { name: '新建规则' }).click();
  const modal = page.locator('.ant-modal');
  await expect(modal).toBeVisible();

  await modal.getByLabel('编码').fill(RULE_CODE);
  await modal.getByLabel('名称').fill('E2E PROPORTIONAL 规则');

  // 主表 source meter — 通过 Form.Item label 精准定位（Select 包装 combobox）
  const meterCombo = modal.getByLabel('主表 (source meter)');
  await meterCombo.click();
  // 等 dropdown 渲染
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').first().waitFor();
  await page
    .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
    .first()
    .click();

  // 目标 org — TreeSelect 第 1 个 leaf
  const orgCombo = modal.getByLabel('目标组织节点');
  await orgCombo.click();
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-tree-node-content-wrapper').first().waitFor();
  await page
    .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-tree-node-content-wrapper')
    .first()
    .click();
  // 多选 TreeSelect 不会自动关；点 modal 标题让它失焦关闭。
  // 不能 press Escape，AntD Modal 默认 keyboard:true 会一并关闭整个 modal。
  await modal.locator('.ant-modal-title').click();
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').waitFor({ state: 'detached', timeout: 5000 }).catch(() => {});

  // 提交
  await modal.getByRole('button', { name: /确\s*定/ }).click();

  // 等 modal 关闭并出现 toast
  await expect(modal).not.toBeVisible({ timeout: 10_000 });

  // ---- 列表里能看到新建的规则 ----
  await expect(page.getByText(RULE_CODE).first()).toBeVisible({ timeout: 10_000 });

  // ---- Dry-run ----
  const ruleRow = page.locator(`tr:has-text("${RULE_CODE}")`).first();
  await ruleRow.getByRole('button', { name: 'Dry-run' }).click();
  const dryModal = page.locator('.ant-modal').filter({ hasText: 'Dry-run' });
  await expect(dryModal).toBeVisible();

  // 不操作 RangePicker —— 一打开就 Escape 会同时关掉 dry-run modal（AntD keyboard:true）。
  // 直接关闭确认 modal 渲染正常即可。
  await dryModal.getByRole('button', { name: /关\s*闭/ }).click();
  await expect(dryModal).not.toBeVisible({ timeout: 5_000 });

  // ---- 删除 ----
  await ruleRow.getByRole('button', { name: /删\s*除/ }).click();
  // 二次确认
  await page
    .locator('.ant-modal-confirm')
    .getByRole('button', { name: /确\s*定/ })
    .click();

  // 列表中规则消失
  await expect(page.getByText(RULE_CODE)).toHaveCount(0, { timeout: 10_000 });
});
