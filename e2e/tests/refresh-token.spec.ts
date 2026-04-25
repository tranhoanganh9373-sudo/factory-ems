import { test, expect } from '@playwright/test';

test('frontend auto-refreshes when access token missing (reload) - refresh cookie still valid', async ({ page }) => {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page).toHaveURL('/');

  // 刷新页面 — Zustand token 在内存丢失，persist 只有 user
  await page.reload();

  // 直接访问需要鉴权的接口驱动的页面
  await page.goto('/admin/users');
  // 如果 cookie refresh token 能走 /auth/refresh 成功，页面应正常加载
  // 当前实现下：先 GET /users 返回 401 → interceptor 尝试 /auth/refresh → 用户列表渲染
  await expect(page.getByText('用户管理')).toBeVisible({ timeout: 10_000 });
});
