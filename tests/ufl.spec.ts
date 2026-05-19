import { test, expect, Page } from '@playwright/test';

const APP_PATH = '/ufl/ufl-mobile.html';

const parseTimer = (s: string): number => {
  const [m, sec] = s.split(':').map(Number);
  return m * 60 + sec;
};

const startMatch = async (page: Page) => {
  await page.locator('#name-l').fill('LEFT');
  await page.locator('#name-r').fill('RIGHT');
  await page.locator('#btn-start').tap();
  await expect(page.locator('#s-match.active')).toBeVisible({ timeout: 5000 });
};

test.describe('UFL fencing app', () => {
  test('loads without console errors and setup screen is interactive', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });

    await page.goto(APP_PATH);
    await expect(page.locator('#s-setup.active')).toBeVisible();

    await page.locator('[data-group="wpn"][data-val="epee"]').tap();
    await expect(page.locator('[data-group="wpn"][data-val="epee"]')).toHaveClass(/active/);

    await page.locator('[data-group="per"][data-val="3"]').tap();
    await expect(page.locator('[data-group="per"][data-val="3"]')).toHaveClass(/active/);

    expect(errors, errors.join('\n')).toEqual([]);
  });

  test('timer resumes after returning to match from events list (P0 #2)', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);

    const before = parseTimer(await page.locator('#timer-disp').innerText());

    await page.locator('#pause-btn').tap();
    await expect(page.locator('#s-result.active')).toBeVisible();

    await page.locator('#res-events').tap();
    await expect(page.locator('#s-events.active')).toBeVisible();

    await page.locator('#ev-back').tap();
    await expect(page.locator('#s-match.active')).toBeVisible();

    await page.waitForTimeout(1500);
    const after = parseTimer(await page.locator('#timer-disp').innerText());

    expect(after, `timer should have decreased; before=${before}, after=${after}`).toBeLessThan(before);
  });

  test('END MATCH reveals NEW MATCH which resets back to setup (P0 #3)', async ({ page }) => {
    await page.goto(APP_PATH);
    await startMatch(page);

    await page.locator('#pause-btn').tap();
    await expect(page.locator('#s-result.active')).toBeVisible();

    await expect(page.locator('#res-new')).toBeHidden();
    await expect(page.locator('#res-end')).toBeVisible();

    await page.locator('#res-end').tap();

    await expect(page.locator('#res-new')).toBeVisible();
    await expect(page.locator('#res-end')).toBeHidden();
    await expect(page.locator('#res-resume')).toBeHidden();

    await page.locator('#res-new').tap();
    await expect(page.locator('#s-setup.active')).toBeVisible();
  });
});
