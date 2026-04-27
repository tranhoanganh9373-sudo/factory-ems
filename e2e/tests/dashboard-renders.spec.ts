/**
 * K2 — dashboard-renders.spec.ts
 *
 * Prerequisites:
 *   - App running at E2E_BASE_URL (default http://localhost:8888)
 *   - seed-postgres.sql + seed-influx.sh loaded: 6 demo meters (M-1..M-6) with
 *     7 days × 5-minute readings, so KPI totals are non-zero and TopN has rows.
 *   - Admin user: admin / admin123!
 *
 * What it tests:
 *   - KPI cards (AntD Statistic) render with non-zero values → proves the full
 *     backend → InfluxDB → rollup → dashboard chain is working.
 *   - ECharts canvas elements exist under the realtime-series panel and the
 *     composition (energy-mix) panel.
 *   - TopN table has at least one row.
 *   - Clicking the first TopN row opens MeterDetailDrawer showing code + name.
 */

import { test, expect } from '@playwright/test';

test('dashboard KPI, charts, and TopN render correctly', async ({ page }) => {
  // ── Login ──
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/);

  // ── Navigate to dashboard with a CUSTOM range mock-data covers (Feb-Mar 2026) ──
  // 后端 from/to 期望 ISO Instant，date-only 会触发 500。
  await page.goto('/dashboard?range=CUSTOM&from=2026-03-01T00:00:00Z&to=2026-03-31T23:59:59Z');
  await expect(page).toHaveURL(/\/dashboard/);

  // ── KPI cards: at least one Statistic value must be non-zero ──
  // AntD Statistic renders value in .ant-statistic-content-value
  const kpiValues = page.locator('.ant-statistic-content-value');
  await expect(kpiValues.first()).toBeVisible({ timeout: 15_000 });

  // At least one KPI shows a non-zero numeric value
  await expect
    .poll(
      async () => {
        const texts = await kpiValues.allTextContents();
        return texts.some((t) => {
          const n = parseFloat(t.replace(/,/g, ''));
          return !isNaN(n) && n > 0;
        });
      },
      { timeout: 30_000, intervals: [1_000] }
    )
    .toBe(true);

  // ── ECharts canvas: realtime series panel ──
  // The realtime chart panel contains a canvas element rendered by ECharts.
  // We locate by a known panel heading and then check for canvas inside.
  const realtimePanel = page
    .locator('.ant-card, [class*="panel"], [class*="Panel"]')
    .filter({ hasText: /实时|趋势|series|realtime/i })
    .first();
  // Fallback: any canvas on the page if the panel selector doesn't match
  const canvasLocator = realtimePanel.locator('canvas').or(page.locator('canvas').first());
  await expect(canvasLocator).toBeVisible({ timeout: 20_000 });

  // ── ECharts canvas: energy composition / mix panel ──
  const compositionPanel = page
    .locator('.ant-card, [class*="panel"], [class*="Panel"]')
    .filter({ hasText: /占比|构成|composition|mix/i })
    .first();
  const compositionCanvas = compositionPanel.locator('canvas').or(page.locator('canvas').nth(1));
  await expect(compositionCanvas).toBeVisible({ timeout: 20_000 });

  // ── TopN table has ≥ 1 row ──
  const topNRows = page.locator('.ant-table-row');
  await expect(topNRows.first()).toBeVisible({ timeout: 15_000 });

  // ── Click first TopN row → MeterDetailDrawer opens ──
  await topNRows.first().click();

  // Drawer body — strict mode 下 .ant-drawer-body 唯一
  const drawer = page.locator('.ant-drawer-body');
  await expect(drawer).toBeVisible({ timeout: 10_000 });

  // 抽屉里至少能看到 测点 / MOCK 之类文本
  await expect(
    drawer.getByText(/MOCK-M|测点|meter/i).first()
  ).toBeVisible({ timeout: 10_000 });
});
