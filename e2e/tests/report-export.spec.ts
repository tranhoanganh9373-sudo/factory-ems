/**
 * T2 — report-export.spec.ts
 *
 * Plan 1.3 / Phase T 保命用例：月报 Excel 导出。
 *
 * Prerequisites:
 *   - App running at E2E_BASE_URL (default http://localhost:8888)
 *   - seed data loaded (Postgres + Influx) so 2026-04 has rows
 *   - Admin user: admin / admin123!
 *
 * What it tests:
 *   - Admin opens /report/monthly, picks 2026-04, clicks "导出 Excel".
 *   - Waits for download event (200).
 *   - Reads first 4 bytes; asserts xlsx zip magic 0x50 0x4B 0x03 0x04 ("PK\x03\x04").
 */

import { test, expect, Download, Page } from '@playwright/test';

async function login(page: Page) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

async function readFirstBytes(download: Download, n: number): Promise<number[]> {
  const stream = await download.createReadStream();
  return new Promise((resolve, reject) => {
    if (!stream) {
      reject(new Error('download.createReadStream() returned null'));
      return;
    }
    const out: number[] = [];
    stream.on('data', (chunk: any) => {
      for (let i = 0; i < chunk.length && out.length < n; i++) out.push(chunk[i]);
      if (out.length >= n) {
        stream.destroy();
        resolve(out);
      }
    });
    stream.on('end', () => resolve(out));
    stream.on('error', reject);
  });
}

test('admin exports monthly Excel report and file is a valid xlsx (PK magic)', async ({ page }) => {
  // 月报数据 + 异步导出轮询（最长 ~120s），默认 30s 不够。
  test.setTimeout(180_000);
  await login(page);

  // ── Navigate to monthly report page ──
  await page.goto('/report/monthly');
  await expect(page.getByRole('main')).toBeVisible({ timeout: 10_000 });

  // ── Pick month 2026-03（mock-data 覆盖到 03-31）──
  const monthPicker = page.locator('.ant-picker').first();
  await expect(monthPicker).toBeVisible({ timeout: 10_000 });
  const monthInput = monthPicker.locator('input').first();
  await monthInput.click();
  await monthInput.fill('2026-03');
  await page.keyboard.press('Enter');
  await page.waitForTimeout(500);

  // Some pages auto-load on date change; wait briefly.
  await page.waitForTimeout(1_000);

  // ── Click "导出 Excel" ──
  const exportBtn = page
    .getByRole('button', { name: /导出\s*Excel|Export\s*Excel/i })
    .or(page.getByRole('button', { name: /^导出$/ }))
    .first();
  await expect(exportBtn).toBeVisible({ timeout: 10_000 });

  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 60_000 }),
    exportBtn.click(),
  ]);

  // ── Filename sanity ──
  const filename = download.suggestedFilename();
  expect(filename).toMatch(/\.(xlsx|xls)$/i);

  // ── First 4 bytes must be the xlsx zip magic: PK\x03\x04 ──
  const bytes = await readFirstBytes(download, 4);
  expect(bytes.length).toBe(4);
  expect(bytes[0]).toBe(0x50); // 'P'
  expect(bytes[1]).toBe(0x4b); // 'K'
  expect(bytes[2]).toBe(0x03);
  expect(bytes[3]).toBe(0x04);
});
