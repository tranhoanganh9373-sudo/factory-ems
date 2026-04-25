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
  await expect(page).toHaveURL('/');
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
  await expect(page.getByRole('main').getByText(/报表|导出|report/i)).toBeVisible({
    timeout: 10_000,
  });

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

  // ── Set granularity to HOUR ──
  const granLabel = page.getByLabel(/粒度|时间粒度|granularity/i);
  if (await granLabel.isVisible()) {
    await granLabel.click();
    await pickSelectOption(page, /小时|HOUR|hour/i);
  }

  // ── Set export mode to "同步导出" ──
  const syncRadio = page.getByLabel('同步导出').or(page.getByText('同步导出').first());
  if (await syncRadio.isVisible()) {
    await syncRadio.click();
  }

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
