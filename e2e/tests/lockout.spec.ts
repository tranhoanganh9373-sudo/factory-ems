import { test, expect } from '@playwright/test';

test('account lockout after 5 failed attempts', async ({ page, request }) => {
  // 通过 API 建一个独立用户（需要 admin token）
  const loginRes = await request.post('/api/v1/auth/login', {
    data: { username: 'admin', password: 'admin123!' },
  });
  const loginBody = await loginRes.json();
  const adminToken = loginBody.data.accessToken;

  const uname = `lock_${Date.now()}`;
  await request.post('/api/v1/users', {
    headers: { Authorization: `Bearer ${adminToken}` },
    data: { username: uname, password: 'TempPass_1', displayName: 'lock', roleCodes: ['VIEWER'] },
  });

  // 5 次错误
  for (let i = 0; i < 5; i++) {
    await page.goto('/login');
    await page.getByPlaceholder('用户名').fill(uname);
    await page.getByPlaceholder('密码').fill('wrongwrong');
    await page.getByRole('button', { name: /登\s*录/ }).click();
    await expect(page.getByText(/用户名或密码错误|锁定/)).toBeVisible();
  }
  // 第 6 次用正确密码 — 应被告知锁定
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill(uname);
  await page.getByPlaceholder('密码').fill('TempPass_1');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page.getByText(/锁定/)).toBeVisible();
});
