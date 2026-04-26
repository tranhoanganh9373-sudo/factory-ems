/**
 * Plan 2.3 Phase M — cost-export.spec.ts
 *
 * /bills 页：选账期 → 点"导出 Excel (COST_MONTHLY)" → 异步导出 → 校验 PK 头。
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

test('cost-monthly Excel async export with PK header check', async ({ page }) => {
  await login(page);

  await page.goto('/bills');
  await expect(page.getByText('账单').first()).toBeVisible({ timeout: 10_000 });

  // 选第一个账期
  const periodSelect = page.locator('.ant-select').first();
  await periodSelect.click();
  await page.waitForTimeout(500);
  await page.locator('.ant-select-item-option').first().click();

  // 等账单加载
  await page.waitForTimeout(2_000);

  // 点导出
  const exportBtn = page.getByRole('button', { name: /导出\s*Excel/ });
  await expect(exportBtn).toBeEnabled({ timeout: 10_000 });

  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 120_000 }),
    exportBtn.click(),
  ]);

  // 文件名 sanity
  const filename = download.suggestedFilename();
  expect(filename).toMatch(/\.xlsx$/i);

  // PK\x03\x04 = xlsx zip magic
  const bytes = await readFirstBytes(download, 4);
  expect(bytes).toEqual([0x50, 0x4b, 0x03, 0x04]);
});
