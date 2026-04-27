import { test, expect } from '@playwright/test';

test('admin can login and logout', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByText('工厂能源管理系统')).toBeVisible();

  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();

  await expect(page).not.toHaveURL(/\/login/);
  // 登录后直跳 /dashboard?range=TODAY；"欢迎，..." 仅在 /home 显示。
  await expect(page.locator('.ant-layout-header')).toBeVisible();

  // 登出 — 头部 dropdown 触发器
  await page.locator('.ant-layout-header').getByText(/admin|系统管理员/).click();
  await page.getByText('退出登录').click();
  await expect(page).toHaveURL(/\/login/);
});

test('login with wrong password shows error', async ({ page }) => {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('wrong');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page.getByText(/密码错误|用户名或密码/)).toBeVisible();
});
