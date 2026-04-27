/**
 * K3 — report-csv-sync.spec.ts
 *
 * Prerequisites:
 *   - App running at E2E_BASE_URL (default http://localhost:8888)
 *   - seed-postgres.sql + seed-influx.sh loaded (data present for today's range).
 *   - Admin user: admin / admin123!
 *
 * What it tests:
 *   - Admin navigates to /report, picks today's date range, sets granularity to HOUR,
 *     mode to "同步导出", clicks "导出".
 *   - Waits for the browser download event.
 *   - Asserts filename starts with "ad-hoc-" and ends with ".csv".
 *   - Reads the download stream to assert file size > 0 and first 3 bytes are the
 *     UTF-8 BOM (0xEF, 0xBB, 0xBF) — using Playwright's download.createReadStream(),
 *     which requires no Node @types/node import.
 */

import { test, expect } from '@playwright/test';

async function login(page: any) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

// Helper: pick an AntD Select option (handles virtual list + Portal).
async function pickSelectOption(page: any, text: string | RegExp) {
  const opt = page.locator('.ant-select-item-option').filter({ hasText: text }).first();
  await opt.waitFor({ state: 'visible' });
  await opt.dispatchEvent('click');
}

/** Read the first N bytes from a Playwright Download via its read stream. */
async function readFirstBytes(download: any, n: number): Promise<number[]> {
  const stream = await download.createReadStream();
  return new Promise((resolve, reject) => {
    const chunks: number[] = [];
    stream.on('data', (chunk: any) => {
      for (let i = 0; i < chunk.length && chunks.length < n; i++) {
        chunks.push(chunk[i]);
      }
      if (chunks.length >= n) {
        stream.destroy();
        resolve(chunks);
      }
    });
    stream.on('end', () => resolve(chunks));
    stream.on('error', reject);
  });
}

test('sync CSV export downloads a valid UTF-8 BOM csv file', async ({ page }) => {
  await login(page);
  await page.goto('/report');
  await expect(page.locator('.ant-card-head-title').filter({ hasText: '报表' }).first()).toBeVisible({
    timeout: 10_000,
  });

  // ── Pick a date range mock-data covers (showTime → 必须点 panel OK 才能 commit）──
  const rangePicker = page.locator('.ant-picker-range').first();
  await rangePicker.click();
  const startInput = rangePicker.locator('input').nth(0);
  const endInput = rangePicker.locator('input').nth(1);
  await startInput.fill('2026-03-15 00:00:00');
  await page.keyboard.press('Tab');
  await endInput.fill('2026-03-15 23:59:59');
  await page.locator('.ant-picker-dropdown:not(.ant-picker-dropdown-hidden) .ant-picker-ok button').click();

  // ── Set granularity to HOUR ──
  const granInput = page.locator('#granularity');
  if (await granInput.isVisible()) {
    await granInput.click({ force: true });
    await pickSelectOption(page, /小时|HOUR|hour/i);
  }

  // ── Set export mode to "同步导出"（默认就是 sync，但显式 click 一次确保） ──
  await page.locator('label.ant-radio-wrapper').filter({ hasText: '同步导出' }).click();

  // ── Click 导出 and capture download ──
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 60_000 }),
    page.getByRole('button', { name: /导出/ }).click(),
  ]);

  // Assert filename
  const filename = download.suggestedFilename();
  expect(filename).toMatch(/^ad-hoc-/);
  expect(filename).toMatch(/\.csv$/);

  // Read first bytes via stream (no fs/Buffer imports needed)
  const bytes = await readFirstBytes(download, 3);
  expect(bytes.length).toBeGreaterThan(0);

  // Assert UTF-8 BOM: 0xEF 0xBB 0xBF
  expect(bytes[0]).toBe(0xef);
  expect(bytes[1]).toBe(0xbb);
  expect(bytes[2]).toBe(0xbf);
});
