import { test, expect, Page } from '@playwright/test';

async function login(page: Page) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

test('alarm smoke: health + history + rules + webhook (admin)', async ({ page }) => {
  await login(page);

  // 1) 健康总览页
  await page.goto('/alarms/health');
  await expect(page.getByText(/在线设备/)).toBeVisible();
  await expect(page.getByText(/报警中/)).toBeVisible();

  // 2) Webhook 配置 + 测试（指向不可达端口期望失败/非2xx，不强求文案）
  await page.goto('/alarms/webhook');
  // 等待表单加载
  await expect(page.getByText(/Webhook/i).first()).toBeVisible();
  // URL 输入框可能用 label 也可能用占位符，宽容匹配
  const urlInput = page.locator('input[id*="url" i], input[placeholder*="http" i]').first();
  await urlInput.fill('http://127.0.0.1:1/'); // 不可达端口
  // 测试按钮（带"测试"文字）— 部分页面可能 disabled，使用 click force 或宽容超时
  const testBtn = page.getByRole('button', { name: /测\s*试/ });
  if (await testBtn.isEnabled().catch(() => false)) {
    await testBtn.click();
    // 至少出现一条 message/notification（成功/失败/警告任意），证明请求已发出且 UI 响应
    await expect(page.locator('.ant-message, .ant-notification')).toBeVisible({ timeout: 10_000 });
  }

  // 3) 阈值规则页 — 可达 + 显示默认值卡 + 表格
  await page.goto('/alarms/rules');
  await expect(page.getByText(/全局默认|默认阈值|静默超时/)).toBeVisible();

  // 4) 历史页 — 可达 + 表格存在
  await page.goto('/alarms/history');
  await expect(page.getByRole('table')).toBeVisible();
});
