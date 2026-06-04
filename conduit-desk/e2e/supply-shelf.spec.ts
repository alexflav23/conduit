import { test, expect } from '@playwright/test';

// The Supply window + Shelf tabs (design spec doc 20 §2.4/§2.5): the firm-commitment horizon, auto-PO proposals
// (with approve), divergence warnings, and the real-time per-account shelf.

test('Supply window: firm-commitment horizon, auto-PO proposal (approve), divergence warning', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:finance-e2e');
  await page.getByTestId('tab-supply').click();
  await page.getByTestId('supply-load').click();

  await expect(page.getByTestId('supply-cm')).toContainText('Volex');
  // the firm-commitment horizon shows the three zones
  await expect(page.getByTestId('supply-commitments')).toContainText('frozen');
  await expect(page.getByTestId('supply-commitments')).toContainText('flex');
  await expect(page.getByTestId('supply-commitments')).toContainText('free');

  // the proposal: net need 50, proposed 24 within headroom, 26 blocked (needs escalation)
  const prop = page.getByTestId('supply-proposal-row').first();
  await expect(prop).toContainText('24');
  await expect(prop).toContainText('⚠ 26');

  // a divergence warning against the frozen firm PO
  await expect(page.getByTestId('supply-warnings')).toContainText('block');

  // approve the proposal → it commits 120 + 24 = 144 to the firm PO
  await page.getByTestId('supply-approve').first().click();
  await expect(page.getByTestId('supply-commitments')).toContainText('144');
});

test('Shelf: real-time per-account stock — shipped, activated, on-shelf', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:finance-e2e');
  await page.getByTestId('tab-shelf').click();
  await page.getByTestId('shelf-load').click();

  // Flow Cust: 3 serials attributed at dispatch, 1 activated → 2 on-shelf
  const row = page.getByTestId('shelf-row').filter({ hasText: 'Flow Cust' }).first();
  await expect(row).toContainText('Flow Cust');
  await expect(row).toContainText('3'); // shipped
  await expect(row).toContainText('2'); // on-shelf (3 shipped − 1 activated)
});
