import { test, expect } from '@playwright/test';

test('user can change own password', async ({ page }) => {
  // 建临时用户供测试
  // 1) admin 登录
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).toHaveURL('/');

  const uname = `pwd_${Date.now()}`;
  await page.goto('/admin/users');
  await page.getByRole('button', { name: /新\s*建/ }).click();
  await page.getByLabel('用户名').fill(uname);
  await page.getByLabel('初始密码').fill('OldPass_123');
  await page.getByRole('button', { name: '确 定' }).click();
  await expect(page.getByText('已创建')).toBeVisible();

  // 2) admin 登出
  await page.locator('.ant-layout-header').getByText(/admin|系统管理员/).click();
  await page.getByText('退出登录').click();

  // 3) 新用户登录 → 改密
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill(uname);
  await page.getByPlaceholder('密码').fill('OldPass_123');
  const loginResp = page.waitForResponse((r) => /\/api\/v1\/auth\/login$/.test(r.url()));
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await loginResp;

  await page.goto('/profile');
  await page.getByLabel('原密码').fill('OldPass_123');
  await page.locator('input#newPassword').fill('NewPass_456');
  await page.locator('input#confirm').fill('NewPass_456');
  await page.getByRole('button', { name: /保\s*存/ }).click();
  await expect(page.getByText('密码已更新')).toBeVisible();

  // 4) 登出 + 用新密码登录
  await page.locator('.ant-layout-header').getByText(uname).click();
  await page.getByText('退出登录').click();

  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill(uname);
  await page.getByPlaceholder('密码').fill('NewPass_456');
  const loginResp2 = page.waitForResponse(
    (r) => /\/api\/v1\/auth\/login$/.test(r.url()) && r.status() === 200
  );
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await loginResp2;
});
