import { test, expect } from '@playwright/test';

test('frontend auto-refreshes when access token missing (reload) - refresh cookie still valid', async ({ page }) => {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/);

  // 直接进入需要鉴权的页面：完整 reload，内存 accessToken 丢失，persist 还有 user
  // App bootstrap 应通过 refresh cookie 静默换新 token 后正常渲染
  await page.goto('/admin/users');
  await expect(page.getByRole('main').getByText('用户管理')).toBeVisible({ timeout: 10_000 });
});
