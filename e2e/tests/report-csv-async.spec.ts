/**
 * K4 — report-csv-async.spec.ts
 *
 * Prerequisites:
 *   - App running at E2E_BASE_URL (default http://localhost:8888)
 *   - seed-postgres.sql + seed-influx.sh loaded (data present for today's range).
 *   - Admin user: admin / admin123!
 *
 * What it tests:
 *   - Admin navigates to /report, switches to "异步任务" mode.
 *   - Picks today's range + HOUR granularity, clicks "导出".
 *   - A new row appears in the 异步任务列表 with status PENDING or RUNNING.
 *   - Polls (expect.poll) until the row status becomes READY (timeout 30s).
 *   - Clicks "下载" on that row, asserts another download event fires with the
 *     same csv signature (filename starts "ad-hoc-", ends ".csv").
 */

import { test, expect } from '@playwright/test';

async function login(page: any) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).toHaveURL('/');
}

// Helper: pick an AntD Select option (handles virtual list + Portal).
async function pickSelectOption(page: any, text: string | RegExp) {
  const opt = page.locator('.ant-select-item-option').filter({ hasText: text }).first();
  await opt.waitFor({ state: 'visible' });
  await opt.dispatchEvent('click');
}

test('async CSV export creates task, reaches READY, and downloads', async ({ page }) => {
  await login(page);
  await page.goto('/report');
  await expect(page.getByRole('main').getByText(/报表|导出|report/i)).toBeVisible({
    timeout: 10_000,
  });

  // ── Switch to "异步任务" mode ──
  const asyncRadio = page.getByLabel('异步任务').or(page.getByText('异步任务').first());
  await expect(asyncRadio).toBeVisible({ timeout: 10_000 });
  await asyncRadio.click();

  // ── Pick today's date range ──
  const today = new Date();
  const yyyy = today.getFullYear();
  const mm = String(today.getMonth() + 1).padStart(2, '0');
  const dd = String(today.getDate()).padStart(2, '0');
  const todayStr = `${yyyy}-${mm}-${dd}`;

  const rangePicker = page.locator('.ant-picker-range').first();
  if (await rangePicker.isVisible()) {
    await rangePicker.locator('input').first().fill(todayStr);
    await rangePicker.locator('input').last().fill(todayStr);
    await page.keyboard.press('Escape');
  }

  // ── Granularity: HOUR ──
  const granLabel = page.getByLabel(/粒度|时间粒度|granularity/i);
  if (await granLabel.isVisible()) {
    await granLabel.click();
    await pickSelectOption(page, /小时|HOUR|hour/i);
  }

  // ── Click 导出 ──
  await page.getByRole('button', { name: /导出/ }).click();

  // ── A new row should appear in the async task list with PENDING or RUNNING status ──
  // The task list is typically in the same page or a tab beneath the form.
  // Status is shown as an AntD Tag.
  const taskListRow = page
    .locator('.ant-table-row')
    .filter({ hasText: /PENDING|RUNNING|pending|running|待处理|处理中/ })
    .first();
  await expect(taskListRow).toBeVisible({ timeout: 15_000 });

  // ── Poll until status becomes READY ──
  // The row text (or its Tag) should change to READY / 就绪 / 完成
  await expect
    .poll(
      async () => {
        // Reload/refresh the task list if there's a refresh button; otherwise just re-query
        const refreshBtn = page.getByRole('button', { name: /刷新|refresh/i });
        if (await refreshBtn.isVisible()) {
          await refreshBtn.click();
        }
        const readyRow = page
          .locator('.ant-table-row')
          .filter({ hasText: /READY|ready|就绪|完成/ });
        return (await readyRow.count()) > 0;
      },
      { timeout: 30_000, intervals: [2_000] }
    )
    .toBe(true);

  // ── Click 下载 on the READY row and assert download event ──
  const readyRow = page
    .locator('.ant-table-row')
    .filter({ hasText: /READY|ready|就绪|完成/ })
    .first();

  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 60_000 }),
    readyRow.getByRole('button', { name: /下载/ }).click(),
  ]);

  // Assert same csv signature
  const filename = download.suggestedFilename();
  expect(filename).toMatch(/^ad-hoc-/);
  expect(filename).toMatch(/\.csv$/);
});
