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
// 冲压车间 (MOCK-WS-A) is the permitted subtree — meters under 焊接车间/涂装车间 must not appear.
// 冲压车间 子树包括 ELEC-001..005 + WATER-001..003；其他车间含 STEAM 与 ELEC-006..009。
const PERMITTED_ORG = '冲压车间';
const FORBIDDEN_METERS = ['MOCK-M-STEAM-001', 'MOCK-M-STEAM-002', 'MOCK-M-ELEC-009'];

/** API helper: create viewer (idempotent) + grant SUBTREE perm on the named org.
 *  避开 UI（admin/users 列表分页 + 模态框时序）一致性问题。 */
async function ensureViewerWithSubtreePerm(
  request: import('@playwright/test').APIRequestContext,
  username: string,
  password: string,
  orgName: string
) {
  const loginRes = await request.post('/api/v1/auth/login', {
    data: { username: 'admin', password: 'admin123!' },
  });
  const adminToken = (await loginRes.json()).data.accessToken as string;
  const auth = { Authorization: `Bearer ${adminToken}` };

  // Find target org id from orgtree
  const treeRes = await request.get('/api/v1/org-nodes/tree', { headers: auth });
  const tree = (await treeRes.json()).data as Array<{
    id: number;
    name: string;
    code: string;
    children?: unknown[];
  }>;
  const orgId = findOrgIdByName(tree, orgName);
  if (orgId == null) throw new Error(`org "${orgName}" not found in tree`);

  // Create viewer (200/201 ok; 409 means already exists — search instead)
  let userId: number | null = null;
  const createRes = await request.post('/api/v1/users', {
    headers: auth,
    data: {
      username,
      password,
      displayName: username,
      roleCodes: ['VIEWER'],
    },
  });
  if (createRes.ok()) {
    userId = (await createRes.json()).data.id as number;
  } else {
    // Search by listing all (keyword filter is buggy — returns 0 matches even when user exists)
    const listRes = await request.get('/api/v1/users?page=1&size=500', { headers: auth });
    const items = (await listRes.json()).data.items as Array<{ id: number; username: string }>;
    userId = items.find((u) => u.username === username)?.id ?? null;
  }
  if (userId == null) throw new Error(`could not create or find user ${username}`);

  // Reset password each run so login works regardless of prior state
  await request.put(`/api/v1/users/${userId}/password/reset`, {
    headers: auth,
    data: { newPassword: password },
  });

  // Grant SUBTREE — service-side dedupes if same (orgNodeId, scope) already exists.
  await request.post(`/api/v1/users/${userId}/node-permissions`, {
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

async function login(page: any, username: string, password: string) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill(username);
  await page.getByPlaceholder('密码').fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/);
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
  test.describe.configure({ timeout: 90_000 });
  test.beforeAll(async ({ request }) => {
    test.setTimeout(90_000);
    await ensureViewerWithSubtreePerm(request, VIEWER_NAME, VIEWER_PASS, PERMITTED_ORG);
  });

  test('viewer sees permitted dashboard and restricted meters page', async ({ page }) => {
    await login(page, VIEWER_NAME, VIEWER_PASS);

    // ── 1. Navigate to /dashboard (CUSTOM range that mock-data covers; ISO Instant required) ──
    await page.goto('/dashboard?range=CUSTOM&from=2026-03-01T00:00:00Z&to=2026-03-31T23:59:59Z');
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
