import { test, expect } from '@playwright/test';

// The Flow tab: the H6Q variants over time + the immutable-ledger panel. Validates the readable, functional
// surface the design spec (doc 20) calls for — every number labelled by variant, revenue traced to TigerBeetle.

test('Flow: shows the H6Q variants over time and the ledger transfers that prove revenue', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:finance-e2e'); // finance sees the money layer + ledger
  await page.getByTestId('tab-flow').click();
  await page.getByTestId('flow-load').click();

  // pick HV-310 (the seeded SKU) — select by the option's value, found via its visible text
  const hv310 = await page.getByTestId('flow-variant').locator('option', { hasText: 'HV-310' }).first().getAttribute('value');
  await page.getByTestId('flow-variant').selectOption(hv310!);

  // the variants grid is labelled per stage, scrubbed across months; the seeded Sep forecast is 100
  await expect(page.getByTestId('flow-row-sales_forecast')).toContainText('Forecast');
  await expect(page.getByTestId('flow-cell-sales_forecast-2026-09')).toHaveText('100');
  await expect(page.getByTestId('flow-cell-shipped-2026-09')).toHaveText('50');
  await expect(page.getByTestId('flow-row-revenue')).toContainText('Revenue');

  // the immutable-ledger panel shows the recognised revenue + the TigerBeetle transfer id
  await expect(page.getByTestId('ledger-totals')).toContainText('Revenue £25000');
  const row = page.getByTestId('ledger-row').first();
  await expect(row).toContainText('INV-FLOW');
  await expect(row).toContainText('123456789012'); // the AR transfer id (truncated) — the proof
});
