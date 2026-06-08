import { test, expect } from '@playwright/test';

// The Auditability Center (M13b): the close board, the SOX control register (re-performable), and the lineage
// explorer. Auditor/finance read; finance closes/locks. Seeded: an entity with an open Sep period (matched
// reconciliations) and the INV-FLOW sale whose figure traces to 3 ledger transfers.
test('Audit: controls run, the close board shows the period, and lineage traces the ledger transfers', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:finance-e2e');
  await page.getByTestId('tab-audit').click();

  // control register — run the gapless-numbering control and see it pass on clean data
  await page.getByTestId('aud-load-controls').click();
  await expect(page.getByTestId('aud-controls')).toContainText('CTRL-DOC-GAPLESS');
  await page.getByTestId('aud-run-CTRL-DOC-GAPLESS').click();
  await expect(page.getByTestId('aud-result-CTRL-DOC-GAPLESS')).toHaveText('pass');

  // close board — the seeded September period is present
  await page.getByTestId('aud-load-periods').click();
  await expect(page.getByTestId('aud-periods')).toContainText('2026-09');

  // lineage — INV-FLOW traces to its three TigerBeetle transfers
  await page.getByTestId('aud-load-lineage').click();
  await expect(page.getByTestId('aud-lineage')).toContainText('ledger transfers: 3');
});
