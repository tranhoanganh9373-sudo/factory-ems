/**
 * T3 — viewer-report-scope.spec.ts
 *
 * Plan 1.3 / Phase T 保命用例：VIEWER 用户在日报上只能看到自己被授权子树的行。
 *
 * Prerequisites:
 *   - App running at E2E_BASE_URL (default http://localhost:8888)
 *   - seed-postgres.sql loaded (org tree with 产线A and 产线B)
 *   - Admin user: admin / admin123!
 *   - Viewer user `e2e_report_viewer` will be created idempotently with
 *     SUBTREE permission on 产线A only.
 *
 * What it tests:
 *   1. Admin captures the row count of /report/daily for "today" (baseline).
 *   2. Admin creates viewer + grants SUBTREE on 产线A.
 *   3. Viewer logs in, opens /report/daily (same date), counts rows.
 *   4. Asserts viewer rows < admin rows AND viewer rows >= 1 (sees something).
 *   5. Asserts viewer rows do NOT contain 产线B sub-nodes.
 */

import { test, expect, Page } from '@playwright/test';

const VIEWER_NAME = 'e2e_report_viewer';
const VIEWER_PASS = 'reportViewer123!';
const PERMITTED_ORG = '产线A';
const FORBIDDEN_ORG_LABELS = ['产线B', 'LINE-B'];

async function login(page: Page, username: string, password: string) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill(username);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).toHaveURL('/');
}

async function pickToday(page: Page) {
  const today = new Date();
  const yyyy = today.getFullYear();
  const mm = String(today.getMonth() + 1).padStart(2, '0');
  const dd = String(today.getDate()).padStart(2, '0');
  const todayStr = `${yyyy}-${mm}-${dd}`;
  const dp = page.locator('.ant-picker').first();
  if (await dp.isVisible().catch(() => false)) {
    await dp.locator('input').first().fill(todayStr);
    await page.keyboard.press('Enter');
    await page.keyboard.press('Escape');
  }
  await page.waitForTimeout(1_000);
}

async function countDailyRows(page: Page): Promise<number> {
  // /report/daily renders a grouped table per org node + energy type.
  // Count visible body rows.
  const rows = page.locator('.ant-table-row');
  // Wait for table to settle
  await expect(rows.first()).toBeVisible({ timeout: 15_000 }).catch(() => {});
  return rows.count();
}

test.describe('viewer report scope', () => {
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();

    await login(page, 'admin', 'admin123!');

    // Create viewer (idempotent)
    await page.goto('/admin/users');
    await page.getByRole('button', { name: /新\s*建/ }).click();
    await page.getByLabel('用户名').fill(VIEWER_NAME);
    await page.getByLabel('初始密码').fill(VIEWER_PASS);
    try {
      await page.getByLabel('姓名').fill('E2E Report Viewer');
    } catch {
      // optional
    }
    await page.getByRole('button', { name: '确 定' }).click();
    await page.waitForTimeout(1_500);

    // Grant SUBTREE on 产线A
    await page.goto('/admin/users');
    const row = page.getByRole('row', { name: new RegExp(VIEWER_NAME) });
    await expect(row).toBeVisible({ timeout: 10_000 });
    await row.getByRole('link', { name: /权限/ }).click();
    const grantBtn = page.getByRole('button', { name: '授权节点' });
    await expect(grantBtn).toBeVisible({ timeout: 10_000 });
    await grantBtn.click();
    await page.getByLabel('组织节点').click();
    await page.getByLabel('组织节点').fill(PERMITTED_ORG);
    const treeNode = page
      .locator('span.ant-select-tree-title')
      .filter({ hasText: PERMITTED_ORG })
      .first();
    await treeNode.waitFor({ state: 'visible' });
    await treeNode.dispatchEvent('click');
    await page.getByRole('button', { name: '确 定' }).click();
    await page.waitForTimeout(1_000);

    await ctx.close();
  });

  test('viewer sees strict subset of admin daily report rows', async ({ browser }) => {
    // Admin baseline
    const adminCtx = await browser.newContext();
    const adminPage = await adminCtx.newPage();
    await login(adminPage, 'admin', 'admin123!');
    await adminPage.goto('/report/daily');
    await pickToday(adminPage);
    const adminRows = await countDailyRows(adminPage);
    await adminCtx.close();

    expect(adminRows).toBeGreaterThan(0);

    // Viewer
    const viewerCtx = await browser.newContext();
    const viewerPage = await viewerCtx.newPage();
    await login(viewerPage, VIEWER_NAME, VIEWER_PASS);
    await viewerPage.goto('/report/daily');
    await pickToday(viewerPage);
    const viewerRows = await countDailyRows(viewerPage);

    expect(viewerRows).toBeGreaterThanOrEqual(1);
    expect(viewerRows).toBeLessThan(adminRows);

    // Forbidden labels must not appear
    for (const label of FORBIDDEN_ORG_LABELS) {
      await expect(
        viewerPage.locator('.ant-table-row').filter({ hasText: label })
      ).toHaveCount(0, { timeout: 5_000 });
    }

    await viewerCtx.close();
  });
});
