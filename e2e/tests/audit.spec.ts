import { test, expect } from '@playwright/test';

test('audit log shows login and user-creation events', async ({ page }) => {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).toHaveURL('/');

  const uname = `audit_${Date.now()}`;
  await page.goto('/admin/users');
  await page.getByRole('button', { name: /新\s*建/ }).click();
  await page.getByLabel('用户名').fill(uname);
  await page.getByLabel('初始密码').fill('AuditPass_1');
  await page.getByRole('button', { name: '确 定' }).click();
  await expect(page.getByText('已创建')).toBeVisible();

  await page.goto('/admin/audit');
  await expect(page.getByRole('main').getByText('审计日志')).toBeVisible();
  await expect(page.getByRole('main').getByText('LOGIN').first()).toBeVisible({ timeout: 10_000 });
  await expect(page.getByRole('main').getByText(uname).first()).toBeVisible({ timeout: 10_000 });
});
