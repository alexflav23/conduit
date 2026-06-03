import { test, expect } from '@playwright/test';

// Drives the real Order Desk against a running Conduit API (seeded with HV-310 @ £587.50, GB 20% VAT,
// 10% ADLP band, and a `dev:agent-e2e` retail agent). Exercises /pricing/quote and /orders end to end.

test('a compliant quote shows Standard ADLP and correct totals, and an order places', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:agent-e2e');
  await page.getByTestId('sku').fill('HV-310');
  await page.getByTestId('qty').fill('2');
  await page.getByTestId('unit-price').fill('');
  await page.getByTestId('quote-btn').click();

  await expect(page.getByTestId('total-inc-vat')).toHaveText('1410.00');
  await expect(page.getByTestId('vat-total')).toHaveText('235.00');
  await expect(page.getByTestId('adlp')).toHaveText('Standard');

  await page.getByTestId('place-btn').click();
  await expect(page.getByTestId('order-status')).toHaveText('placed');
  await expect(page.getByTestId('order-no')).toContainText('ORD-');
});

test('an out-of-band discount is flagged as an ADLP Exception', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:agent-e2e');
  await page.getByTestId('sku').fill('HV-310');
  await page.getByTestId('qty').fill('1');
  await page.getByTestId('unit-price').fill('400.00');
  await page.getByTestId('quote-btn').click();

  await expect(page.getByTestId('adlp')).toHaveText('Exception');
});
