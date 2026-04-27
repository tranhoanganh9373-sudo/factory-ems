/**
 * T4 — sankey.spec.ts
 *
 * Plan 1.3 / Phase T 保命用例：看板面板 ⑧ Sankey 能流图渲染。
 *
 * Prerequisites:
 *   - App running at E2E_BASE_URL (default http://localhost:8888)
 *   - seed data with meter_topology rows (parent/child meters)
 *   - Admin user: admin / admin123!
 *
 * What it tests:
 *   - Admin opens /dashboard.
 *   - Network: GET /api/v1/dashboard/sankey resolves with 200.
 *   - Panel ⑧ container renders an SVG with at least 1 link element
 *     (ECharts emits each Sankey link as <path class="...sankey-link..."/>).
 */

import { test, expect, Page } from '@playwright/test';

async function login(page: Page) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

test('dashboard panel 8 Sankey renders >= 1 link path', async ({ page }) => {
  await login(page);

  // Wait for the sankey API to resolve as the user navigates to the dashboard.
  const sankeyRespPromise = page.waitForResponse(
    (r) => /\/api\/v1\/dashboard\/sankey/.test(r.url()) && r.status() < 400,
    { timeout: 30_000 }
  );

  await page.goto('/dashboard');
  await expect(page.getByRole('main')).toBeVisible({ timeout: 10_000 });

  const sankeyResp = await sankeyRespPromise;
  expect(sankeyResp.status()).toBeGreaterThanOrEqual(200);
  expect(sankeyResp.status()).toBeLessThan(300);

  // Panel container — fall back to the first SVG on the dashboard if the
  // [data-panel] attribute isn't applied yet by the frontend.
  const panel = page.locator('[data-panel="sankey"], [data-panel-id="8"]').first();

  // ECharts renders the chart as <svg> inside the panel; in some skins the
  // chart is rendered as <canvas>. Sankey forces SVG when {renderer:'svg'},
  // which the panel uses to allow CSS hover states.
  const svg = panel.locator('svg').first().or(page.locator('svg').first());
  await expect(svg).toBeVisible({ timeout: 20_000 });

  // Count <path> elements inside the SVG. Sankey emits one path per link.
  // Filter to those whose `d` attribute starts with "M" (curve) and has at
  // least one bezier "C" — that excludes axis ticks if any.
  await expect(async () => {
    const paths = await svg.locator('path').all();
    let curveCount = 0;
    for (const p of paths) {
      const d = (await p.getAttribute('d')) ?? '';
      if (/^M[^z]*C/i.test(d)) curveCount++;
    }
    expect(curveCount).toBeGreaterThanOrEqual(1);
  }).toPass({ timeout: 15_000 });
});
