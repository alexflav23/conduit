import { test, expect } from '@playwright/test';

// The Finance tab (M13): ASC-606 P&L proved on the ledger + the cash waterfall by contractual due date.
// Finance sees the money layer; the seeded Flow sale (market 22222…, 2026-09) recognised £25,000 revenue.
test('Finance: P&L shows recognised revenue/margin and the cash waterfall buckets the open invoice', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:finance-e2e');
  await page.getByTestId('tab-finance').click();

  // P&L for the demo market, September
  await page.getByTestId('fin-load-pnl').click();
  await expect(page.getByTestId('fin-revenue')).toContainText('25,000');
  await expect(page.getByTestId('fin-margin')).toContainText('13,000');

  // cash waterfall — the open INV-FLOW falls in its Oct due-date bucket
  await page.getByTestId('fin-load-wf').click();
  await expect(page.getByTestId('fin-wf-row').first()).toContainText('2026-10');
  await expect(page.getByTestId('fin-waterfall')).toContainText('30,000');
});
