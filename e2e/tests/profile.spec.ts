import { test, expect } from '@playwright/test';

test('user can change own password', async ({ page }) => {
  // 建临时用户供测试
  // 1) admin 登录
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: '登录' }).click();

  const uname = `pwd_${Date.now()}`;
  await page.goto('/admin/users');
  await page.getByRole('button', { name: '新建' }).click();
  await page.getByLabel('用户名').fill(uname);
  await page.getByLabel('初始密码').fill('OldPass_123');
  await page.getByRole('button', { name: '确 定' }).click();

  // 2) admin 登出
  await page.getByText(/admin|系统管理员/).click();
  await page.getByText('退出登录').click();

  // 3) 新用户登录 → 改密
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill(uname);
  await page.getByPlaceholder('密码').fill('OldPass_123');
  await page.getByRole('button', { name: '登录' }).click();

  await page.goto('/profile');
  await page.getByLabel('原密码').fill('OldPass_123');
  await page.getByLabel('新密码').fill('NewPass_456');
  await page.getByLabel('确认新密码').fill('NewPass_456');
  await page.getByRole('button', { name: '保存' }).click();
  await expect(page.getByText('密码已更新')).toBeVisible();

  // 4) 登出 + 用新密码登录
  await page.getByText(uname).click();
  await page.getByText('退出登录').click();

  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill(uname);
  await page.getByPlaceholder('密码').fill('NewPass_456');
  await page.getByRole('button', { name: '登录' }).click();
  await expect(page).toHaveURL('/');
});
