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
// mock-data 用 冲压车间 (MOCK-WS-A) 作为顶层授权子树；其余车间应被过滤。
const PERMITTED_ORG = '冲压车间';
const FORBIDDEN_ORG_LABELS = ['焊接车间', '涂装车间', '总装车间'];
// mock-data 覆盖 2026-02-01..03-31；today (2026-04-26+) 没有 rollup。
const REPORT_DATE = '2026-03-15';

/** API helper（拷贝自 dashboard-permission，避免共享 file 引入循环依赖）：
 *  创建 viewer 用户 + SUBTREE 授权，绕开 UI 列表分页/时序问题。 */
async function ensureViewerWithSubtreePerm(
  req: import('@playwright/test').APIRequestContext,
  username: string,
  password: string,
  orgName: string
) {
  const loginRes = await req.post('/api/v1/auth/login', {
    data: { username: 'admin', password: 'admin123!' },
  });
  const adminToken = (await loginRes.json()).data.accessToken as string;
  const auth = { Authorization: `Bearer ${adminToken}` };

  const treeRes = await req.get('/api/v1/org-nodes/tree', { headers: auth });
  const tree = (await treeRes.json()).data as Array<{
    id: number;
    name: string;
    children?: unknown[];
  }>;
  const orgId = findOrgIdByName(tree, orgName);
  if (orgId == null) throw new Error(`org "${orgName}" not found`);

  let userId: number | null = null;
  const createRes = await req.post('/api/v1/users', {
    headers: auth,
    data: { username, password, displayName: username, roleCodes: ['VIEWER'] },
  });
  if (createRes.ok()) {
    userId = (await createRes.json()).data.id as number;
  } else {
    const listRes = await req.get('/api/v1/users?page=1&size=500', { headers: auth });
    const items = (await listRes.json()).data.items as Array<{ id: number; username: string }>;
    userId = items.find((u) => u.username === username)?.id ?? null;
  }
  if (userId == null) throw new Error(`could not create or find user ${username}`);

  await req.put(`/api/v1/users/${userId}/password/reset`, {
    headers: auth,
    data: { newPassword: password },
  });
  await req.post(`/api/v1/users/${userId}/node-permissions`, {
    headers: auth,
    data: { orgNodeId: orgId, scope: 'SUBTREE' },
  });
}

function findOrgIdByName(
  nodes: Array<{ id: number; name: string; children?: unknown[] }>,
  name: string
): number | null {
  for (const n of nodes) {
    if (n.name === name) return n.id;
    if (n.children?.length) {
      const found = findOrgIdByName(n.children as typeof nodes, name);
      if (found != null) return found;
    }
  }
  return null;
}

async function login(page: Page, username: string, password: string) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill(username);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

async function pickReportDate(page: Page) {
  const dp = page.locator('.ant-picker').first();
  if (await dp.isVisible().catch(() => false)) {
    await dp.locator('input').first().fill(REPORT_DATE);
    await page.keyboard.press('Enter');
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
  test.describe.configure({ timeout: 90_000 });
  test.beforeAll(async ({ request }) => {
    test.setTimeout(90_000);
    await ensureViewerWithSubtreePerm(request, VIEWER_NAME, VIEWER_PASS, PERMITTED_ORG);
  });

  test('viewer sees strict subset of admin daily report rows', async ({ browser }) => {
    // Admin baseline
    const adminCtx = await browser.newContext();
    const adminPage = await adminCtx.newPage();
    await login(adminPage, 'admin', 'admin123!');
    await adminPage.goto('/report/daily');
    await pickReportDate(adminPage);
    const adminRows = await countDailyRows(adminPage);
    await adminCtx.close();

    expect(adminRows).toBeGreaterThan(0);

    // Viewer
    const viewerCtx = await browser.newContext();
    const viewerPage = await viewerCtx.newPage();
    await login(viewerPage, VIEWER_NAME, VIEWER_PASS);
    await viewerPage.goto('/report/daily');
    await pickReportDate(viewerPage);
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
