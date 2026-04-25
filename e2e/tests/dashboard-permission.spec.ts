/**
 * K5 — dashboard-permission.spec.ts
 *
 * Prerequisites:
 *   - App running at E2E_BASE_URL (default http://localhost:8888)
 *   - seed-postgres.sql loaded: org hierarchy with 产线A (LINE-A) and sub-nodes.
 *   - Admin user: admin / admin123!
 *   - A viewer user is created by this spec if it does not yet exist (idempotent via
 *     try/catch on the create step). The viewer is granted SUBTREE permission on 产线A
 *     only — meters M-1, M-2, M-3, M-5 are under 产线A; M-4 and M-6 are under 产线B.
 *
 * What it tests:
 *   1. Viewer can reach /dashboard and sees KPI/Series/TopN panels.
 *   2. TopN only contains meters that belong to 产线A subtree (M-1, M-2, M-3, M-5).
 *      It must NOT contain meters from 产线B subtree (M-4, M-6).
 *   3. On /meters the admin action buttons (新建/编辑/删除) are NOT visible to viewer.
 *
 * Pattern: mirrors user-permission.spec.ts — admin creates viewer + assigns org permission,
 * then re-logs in as viewer to assert restricted access.
 */

import { test, expect } from '@playwright/test';

const VIEWER_NAME = 'e2e_dashboard_viewer';
const VIEWER_PASS = 'viewerDash123!';
// 产线A is the permitted subtree — meters M-4 (产线B) and M-6 (产线B) must not appear.
const PERMITTED_ORG = '产线A';
const FORBIDDEN_METERS = ['M-4', 'M-6'];

async function login(page: any, username: string, password: string) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill(username);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).toHaveURL('/');
}

// Helper: pick an AntD Select / tree-select option.
async function pickOption(page: any, text: string) {
  const opt = page
    .locator('.ant-select-item-option, span.ant-select-tree-title')
    .filter({ hasText: text })
    .first();
  await opt.waitFor({ state: 'visible' });
  await opt.dispatchEvent('click');
}

test.describe('dashboard permission (viewer)', () => {
  test.beforeAll(async ({ browser }) => {
    // Use a dedicated page to set up the viewer user as admin.
    const context = await browser.newContext();
    const page = await context.newPage();

    await login(page, 'admin', 'admin123!');

    // ── Create viewer user (idempotent: ignore if already exists) ──
    await page.goto('/admin/users');
    await page.getByRole('button', { name: /新\s*建/ }).click();
    await page.getByLabel('用户名').fill(VIEWER_NAME);
    await page.getByLabel('初始密码').fill(VIEWER_PASS);
    try {
      await page.getByLabel('姓名').fill('E2E Dashboard Viewer');
    } catch {
      // optional field
    }
    await page.getByRole('button', { name: '确 定' }).click();
    // Either "已创建" or the row already exists — both are acceptable
    await page.waitForTimeout(1_000);

    // ── Grant viewer SUBTREE permission on 产线A ──
    await page.goto('/admin/users');
    const viewerRow = page.getByRole('row', { name: new RegExp(VIEWER_NAME) });
    await expect(viewerRow).toBeVisible({ timeout: 10_000 });
    await viewerRow.getByRole('link', { name: /权限/ }).click();

    // Click "授权节点"
    const grantBtn = page.getByRole('button', { name: '授权节点' });
    await expect(grantBtn).toBeVisible({ timeout: 10_000 });
    await grantBtn.click();

    // Pick 产线A in the org tree-select
    await page.getByLabel('组织节点').click();
    await page.getByLabel('组织节点').fill(PERMITTED_ORG);
    const treeTitle = page
      .locator('span.ant-select-tree-title')
      .filter({ hasText: PERMITTED_ORG })
      .first();
    await treeTitle.waitFor({ state: 'visible' });
    await treeTitle.dispatchEvent('click');

    await page.getByRole('button', { name: '确 定' }).click();
    // Wait for success — "已授权" or similar
    await page.waitForTimeout(1_000);

    await context.close();
  });

  test('viewer sees permitted dashboard and restricted meters page', async ({ page }) => {
    await login(page, VIEWER_NAME, VIEWER_PASS);

    // ── 1. Navigate to /dashboard ──
    await page.goto('/dashboard');
    await expect(page).toHaveURL(/\/dashboard/);

    // KPI cards visible
    const kpiValues = page.locator('.ant-statistic-content-value');
    await expect(kpiValues.first()).toBeVisible({ timeout: 15_000 });

    // At least one canvas (charts rendered)
    await expect(page.locator('canvas').first()).toBeVisible({ timeout: 20_000 });

    // TopN table has at least 1 row
    const topNRows = page.locator('.ant-table-row');
    await expect(topNRows.first()).toBeVisible({ timeout: 15_000 });

    // ── 2. TopN must NOT show meters from outside 产线A subtree ──
    // M-4 and M-6 belong to 产线B; they should not appear for this viewer.
    for (const forbiddenCode of FORBIDDEN_METERS) {
      await expect(
        page.locator('.ant-table-row').filter({ hasText: forbiddenCode })
      ).toHaveCount(0, { timeout: 5_000 });
    }

    // ── 3. Navigate to /meters — admin action buttons must NOT be visible ──
    await page.goto('/meters');
    // The page itself should load (viewer can see read-only meter list)
    await expect(page.getByRole('main')).toBeVisible({ timeout: 10_000 });

    // Admin-only buttons must be absent
    await expect(page.getByRole('button', { name: /新建测点/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /编辑/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /删除/ })).toHaveCount(0);
  });
});
