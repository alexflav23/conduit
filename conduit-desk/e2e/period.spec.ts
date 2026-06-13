import { test, expect } from '@playwright/test';

// The Period investigation view (doc 32 §2): a finance/auditor front door to one accounting period. For the
// seeded 2026-09 group period it shows the operating entity's close status, the netted INV-FLOW journals, the
// business events that drove them, and lineage entry-points that trace an invoice to its CM PO. The group
// roll-up lock (doc 32 §1 / ASC 810) refuses while the entity period is still open.
test('Period: investigation assembles the period, and the group lock is gated while an entity is open', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:finance-e2e');
  await page.getByTestId('tab-period').click();

  await page.getByTestId('per-key').fill('2026-09');
  await page.getByTestId('per-investigate').click();

  // the operating entity's close status + the period window
  await expect(page.getByTestId('per-entity-row').first()).toBeVisible();
  await expect(page.getByTestId('per-group-status')).toContainText('group:');

  // the INV-FLOW journals and the business events both re-projected onto September
  await expect(page.getByTestId('per-journals')).toContainText('AR:flow');
  await expect(page.getByTestId('per-events')).toContainText('order');

  // lineage entry-point: click INV-FLOW through to its ledger transfers
  await page.getByTestId('per-lineage-link').first().click();
  await expect(page.getByTestId('per-lineage')).toContainText('INV-FLOW');

  // the roll-up gate: the entity period is still open, so the group lock is refused
  await page.getByTestId('per-lock').click();
  await expect(page.getByTestId('per-status')).toContainText('lock blocked');
});
