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
  await expect(page).not.toHaveURL(/\/login/);
}

// Helper: pick an AntD Select option (handles virtual list + Portal).
async function pickSelectOption(page: any, text: string | RegExp) {
  const opt = page.locator('.ant-select-item-option').filter({ hasText: text }).first();
  await opt.waitFor({ state: 'visible' });
  await opt.dispatchEvent('click');
}

test('async CSV export creates task, reaches READY, and downloads', async ({ page }) => {
  test.setTimeout(180_000);
  await login(page);
  // 登录后已经在 /dashboard，本 origin 的 localStorage 可见。先清掉持久化的 stale tasks,
  // 再 navigate 到 /report —— 避开 reload 让 in-memory access token 丢失再触发 silent refresh 的 race。
  await page.evaluate(() => localStorage.removeItem('ems-report-tasks'));
  await page.goto('/report');
  // 用 card 标题精准匹配，避免 strict-mode 撞上 4 个含"导出"的元素
  await expect(page.locator('.ant-card-head-title').filter({ hasText: '报表' }).first()).toBeVisible({
    timeout: 10_000,
  });

  // ── Switch to "异步任务" mode —— 点 label 而非 hidden input，让 AntD form state 实际切换 ──
  await page.locator('label.ant-radio-wrapper').filter({ hasText: '异步任务' }).click();

  // ── Pick a date range mock-data covers ──
  const rangePicker = page.locator('.ant-picker-range').first();
  await rangePicker.click();
  const startInput = rangePicker.locator('input').nth(0);
  const endInput = rangePicker.locator('input').nth(1);
  await startInput.fill('2026-03-15 00:00:00');
  await page.keyboard.press('Tab');
  await endInput.fill('2026-03-15 23:59:59');
  await page.locator('.ant-picker-dropdown:not(.ant-picker-dropdown-hidden) .ant-picker-ok button').click();

  // ── Granularity: HOUR ──
  // AntD Select 的 selection-item span 会拦截普通 click —— 用 force 跳过 actionability 检查。
  const granInput = page.locator('#granularity');
  if (await granInput.isVisible()) {
    await granInput.click({ force: true });
    await pickSelectOption(page, /小时|HOUR|hour/i);
  }

  // ── Click 导出 → 行先 PENDING/RUNNING → 轮询到 READY (就绪) → 用户点 "下载" 触发 download ──
  const exportBtn = page.locator('button').filter({ hasText: /^导出$/ }).first();
  await exportBtn.click();

  // PENDING/RUNNING 行可见
  await expect(
    page.locator('.ant-table-row').filter({ hasText: /等待中|生成中/ }).first()
  ).toBeVisible({ timeout: 15_000 });

  // 轮询到 READY (前端把 status=READY 渲染成 "就绪")
  await expect
    .poll(
      async () => page.locator('.ant-table-row').filter({ hasText: /就绪|READY/ }).count(),
      { timeout: 60_000, intervals: [2_000] }
    )
    .toBeGreaterThan(0);

  // 点 "下载" 按钮 → 真下载事件
  // 用 page-level locator + role 过滤 + DownloadOutlined icon 区别于其他可能的 "下载" 文本
  const downloadBtn = page.getByRole('button', { name: /^download\s*下载$/ }).first();
  await expect(downloadBtn).toBeVisible({ timeout: 5_000 });
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 30_000 }),
    downloadBtn.click(),
  ]);

  const filename = download.suggestedFilename();
  expect(filename).toMatch(/^ad-hoc-/);
  expect(filename).toMatch(/\.csv$/);
});
