/**
 * E2E for OPC UA certificate approval workflow.
 *
 * Tests admin opens /admin/cert-approval, sees the page title and either an
 * empty state (no pending certs) or a table with pending certificates.
 *
 * Prerequisites:
 *   - App running at E2E_BASE_URL; admin user admin / admin123!
 */

import { test, expect, type Page } from '@playwright/test';

async function login(page: Page) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

test('admin can navigate to cert-approval page and sees expected UI', async ({ page }) => {
  await login(page);
  await page.goto('/admin/cert-approval');

  await expect(page.getByText('OPC UA 证书审批')).toBeVisible({ timeout: 10_000 });

  // In a cold-start environment there are no pending certs → empty state is shown.
  // If certs exist the table is shown instead. At least one must be visible.
  const emptyState = page.locator('[data-testid="cert-pending-empty"]');
  const table = page.locator('[data-testid="cert-pending-table"]');
  await expect(emptyState.or(table)).toBeVisible({ timeout: 10_000 });
});
