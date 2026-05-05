/**
 * Plan 2.3 Phase M — cost-export.spec.ts
 *
 * /bills 页：选账期 → 点"导出 Excel (COST_MONTHLY)" → 异步导出 → 校验 PK 头。
 */

import { test, expect, Download, Page } from '@playwright/test';
import { ensurePeriodClosed } from './fixtures/billPeriodSetup.js';

// cost-export needs a CLOSED bill period so that bills appear in the dropdown
// and the export button is enabled. ensurePeriodClosed handles the full
// prerequisite chain (period row → SUCCESS cost run → API close) without
// going through the UI.
const EXPORT_YM = '2026-03';

test.beforeAll(async () => {
  await ensurePeriodClosed(EXPORT_YM);
});

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

test('cost-monthly Excel async export with PK header check', async ({ page }) => {
  test.setTimeout(60_000);
  await login(page);

  await page.goto('/bills');
  await expect(page.getByText('账单').first()).toBeVisible({ timeout: 10_000 });

  // 选第一个账期 — 等 dropdown 选项出现（无账期数据时快速失败，而非挂起 180s）
  const periodSelect = page.locator('.ant-select').first();
  await periodSelect.click();
  const firstOption = page.locator('.ant-select-item-option').first();
  await expect(firstOption).toBeVisible({ timeout: 10_000 });
  await firstOption.click();

  // 等账单列表渲染
  await expect(page.locator('.ant-table-row').first()).toBeVisible({ timeout: 10_000 });

  // 点导出
  const exportBtn = page.getByRole('button', { name: /导出\s*Excel/ });
  await expect(exportBtn).toBeEnabled({ timeout: 10_000 });

  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 30_000 }),
    exportBtn.click(),
  ]);

  // 文件名 sanity
  const filename = download.suggestedFilename();
  expect(filename).toMatch(/\.xlsx$/i);

  // PK\x03\x04 = xlsx zip magic
  const bytes = await readFirstBytes(download, 4);
  expect(bytes).toEqual([0x50, 0x4b, 0x03, 0x04]);
});
