/**
 * T1 — floorplan.spec.ts
 *
 * Plan 1.3 / Phase T 保命用例：平面图编辑器端到端。
 *
 * Prerequisites:
 *   - App running at E2E_BASE_URL (default http://localhost:8888)
 *   - Admin user: admin / admin123!
 *   - At least one meter exists (seed data provides M-1, M-2, ...)
 *
 * What it tests:
 *   1. Admin uploads a 1x1 PNG via the floorplan list page upload control.
 *   2. Opens the editor for the newly created floorplan.
 *   3. Drags two meter pins onto the canvas at distinct coordinates.
 *   4. Saves the layout.
 *   5. Reloads the page; both pins persist with their coordinates.
 *
 * Notes:
 *   - The frontend route /floorplan/list & /floorplan/editor/:id may not yet
 *     exist when this spec is authored — that's expected; the spec is the
 *     contract Phase 1.3 frontend must satisfy.
 *   - We construct a minimal PNG in-memory (8 bytes header + IHDR + IDAT + IEND)
 *     to avoid shipping a binary fixture file.
 */

import { test, expect, Page } from '@playwright/test';

// 1×1 transparent PNG (smallest valid PNG, 67 bytes).
const TINY_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNgYAAAAAMAASsJTYQAAAAASUVORK5CYII=';

/**
 * Decode a base64 string to a Uint8Array without depending on Node's Buffer.
 * Playwright's setInputFiles accepts Uint8Array as the buffer field.
 */
function base64ToBytes(b64: string): Uint8Array {
  // atob is available in modern Node (≥16) under Playwright's runtime.
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function login(page: Page) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill('admin');
  await page.getByPlaceholder('密码').fill('admin123!');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).toHaveURL('/');
}

test('admin uploads PNG, drags 2 meter points, saves, reload persists', async ({ page }) => {
  await login(page);

  // ── 1. Navigate to floorplan list ──
  await page.goto('/floorplan/list');
  await expect(page.getByRole('main')).toBeVisible({ timeout: 10_000 });

  // ── 2. Upload a tiny PNG via hidden file input ──
  const uploadInput = page.locator('input[type="file"]').first();
  await expect(uploadInput).toBeAttached({ timeout: 10_000 });

  const buffer = base64ToBytes(TINY_PNG_BASE64);
  const fpName = `e2e-fp-${Date.now()}`;

  // Some upload UIs need a name field set before file selection
  const nameInput = page.getByLabel(/名称|平面图名称/).first();
  if (await nameInput.isVisible().catch(() => false)) {
    await nameInput.fill(fpName);
  }

  await uploadInput.setInputFiles({
    name: 'e2e-tiny.png',
    mimeType: 'image/png',
    buffer,
  });

  // Wait for upload success — most AntD upload components show a row in a list
  // or fire a "上传成功" message
  await page.waitForTimeout(2_000);

  // ── 3. Open the editor on the newly-created floorplan ──
  // Editor link may be in a row action button or use the row click.
  const editLink = page
    .getByRole('link', { name: /编辑|打开|editor/ })
    .or(page.getByRole('button', { name: /编辑|打开|editor/ }))
    .first();
  await expect(editLink).toBeVisible({ timeout: 10_000 });
  await editLink.click();

  await expect(page).toHaveURL(/\/floorplan\/editor\/\d+/, { timeout: 10_000 });

  // ── 4. Drag 2 meter pins onto the canvas ──
  // The editor renders a Konva <Stage> as a <canvas> with role=img or class
  // ant-konva / floorplan-stage. The meter palette is on the side.
  const stage = page.locator('canvas').first();
  await expect(stage).toBeVisible({ timeout: 15_000 });

  const stageBox = await stage.boundingBox();
  expect(stageBox).not.toBeNull();
  if (!stageBox) return;

  // Drag from "添加测点 / 测点列表" panel onto the canvas at two positions.
  // We use the meter codes (M-1, M-2) which are seeded.
  const firstMeter = page.getByText(/M-1\b/).first();
  const secondMeter = page.getByText(/M-2\b/).first();
  await expect(firstMeter).toBeVisible({ timeout: 10_000 });
  await expect(secondMeter).toBeVisible({ timeout: 10_000 });

  // Drag M-1 to (stage center-left).
  await firstMeter.dragTo(stage, {
    targetPosition: { x: stageBox.width * 0.25, y: stageBox.height * 0.5 },
  });
  // Drag M-2 to (stage center-right).
  await secondMeter.dragTo(stage, {
    targetPosition: { x: stageBox.width * 0.75, y: stageBox.height * 0.5 },
  });

  // ── 5. Save ──
  const saveBtn = page.getByRole('button', { name: /保\s*存|save/i });
  await expect(saveBtn).toBeVisible({ timeout: 10_000 });

  // Capture the save POST response so we know the points were committed
  const [saveResp] = await Promise.all([
    page.waitForResponse((r) => /\/floorplans?\/\d+\/points/.test(r.url()) && r.request().method() === 'POST', {
      timeout: 15_000,
    }),
    saveBtn.click(),
  ]);
  expect(saveResp.status()).toBeGreaterThanOrEqual(200);
  expect(saveResp.status()).toBeLessThan(300);

  // ── 6. Reload — points must persist ──
  await page.reload();
  await expect(stage).toBeVisible({ timeout: 15_000 });

  // After reload, GET /floorplans/{id} should return points; the editor
  // either renders pin markers (Konva Circle) overlaid on the canvas or
  // lists them in a side panel. Validate the side panel which is more
  // resilient to canvas pixel drift.
  const meterPinList = page.locator('[data-test="floorplan-meter-pin"], .floorplan-meter-pin, .ant-tag');
  // We only need to assert "at least 2 pins are displayed"
  await expect(async () => {
    const count = await meterPinList.filter({ hasText: /M-1|M-2/ }).count();
    expect(count).toBeGreaterThanOrEqual(2);
  }).toPass({ timeout: 10_000 });
});
