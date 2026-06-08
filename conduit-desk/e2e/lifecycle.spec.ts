import { test, expect } from '@playwright/test';

// The Lifecycle tab (M13 doc 13 §void) — the Order Collection Ledger. Replays the immutable event stream for one
// order: the collection cycles (per invoice) + the chronological event timeline. The seeded ORD-FLOW order has a
// fixed id, an open INV-FLOW cycle (£30,000), and a seeded event spine.
test('Lifecycle: finance replays an order — collection cycle + event timeline', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:finance-e2e');
  await page.getByTestId('tab-lifecycle').click();

  await page.getByTestId('life-load').click();

  // the collection cycle for INV-FLOW (one cycle, open, £30,000)
  await expect(page.getByTestId('life-cycle-row').first()).toContainText('INV-FLOW');
  await expect(page.getByTestId('life-cycles')).toContainText('30,000.00');

  // the event timeline replays the spine
  await expect(page.getByTestId('life-timeline')).toContainText('order.invoiced');
  await expect(page.getByTestId('life-timeline')).toContainText('revenue.recognized');
});
