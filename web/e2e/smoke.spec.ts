import { expect, test } from '@playwright/test';
import { mockApi } from './mockApi';

const live = !!process.env.E2E_LIVE;

test.describe('live map', () => {
  test.beforeEach(async ({ page }) => {
    if (!live) await mockApi(page);
  });

  test('loads the map, streams trains and shows feed health', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await page.goto('/');

    await expect(page.getByTestId('map').locator('canvas').first()).toBeVisible({ timeout: 20_000 });

    // Trains arrive over SSE (mocked: 3; live replay: a handful at least).
    await expect(page.getByTestId('train-count')).not.toHaveText(/^0 trains/, { timeout: 45_000 });
    const count = parseInt((await page.getByTestId('train-count').innerText()).split(' ')[0], 10);
    expect(count).toBeGreaterThanOrEqual(live ? 5 : 3);

    await expect(page.getByTestId('feed-subway')).toContainText(/updated|down|off/);
    expect(errors, errors.join('\n')).toEqual([]);
  });

  test('line filters hide trains', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('train-count')).not.toHaveText(/^0 trains/, { timeout: 45_000 });
    const before = parseInt((await page.getByTestId('train-count').innerText()).split(' ')[0], 10);

    if (await page.getByTestId('filters').getByRole('button', { name: 'Filters' }).isVisible()) {
      await page.getByTestId('filters').getByRole('button', { name: 'Filters' }).click();
    }
    await page.getByTestId('filter-system-subway').uncheck();
    await expect(page.getByTestId('train-count')).toHaveText('0 trains');
    await page.getByTestId('filter-system-subway').check();
    await expect(page.getByTestId('train-count')).toHaveText(`${before} trains`);
  });

  test('a station opens its departure board', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('train-count')).not.toHaveText(/^0 trains/, { timeout: 45_000 });
    // Clicking the canvas is flaky across projections; drive the same code path the map uses.
    await page.evaluate(() => window.dispatchEvent(new CustomEvent('tracknyc:select-station',
      { detail: 'subway:L08' })));
    await expect(page.getByTestId('station-panel')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByTestId('departures').locator('tbody tr')).not.toHaveCount(0);
    await page.getByTestId('departures').locator('tbody tr').first().click();
    await expect(page.getByTestId('train-panel')).toBeVisible();
    await expect(page.getByTestId('train-stops').locator('tbody tr')).not.toHaveCount(0);
  });
});
