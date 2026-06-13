import { test, expect } from '@playwright/test';

// M-Ingest (doc 33 §7): the shadow dual-run sync-health board. Finance/auditors watch per-source cursor + lag
// + last status to confirm Conduit is tracking each source system during the parallel run. The seed registers
// xero + mrpeasy streams; finance-e2e holds view:sync_state.
test('Sync: the sync-health board shows each ingest stream with its status, lag and cursor', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:finance-e2e');
  await page.getByTestId('tab-sync').click();

  await page.getByTestId('sync-load').click();
  await expect(page.getByTestId('sync-row').first()).toBeVisible();

  // the seeded streams are present with an ok status and a recent last-run
  const board = page.getByTestId('sync-board');
  await expect(board).toContainText('xero');
  await expect(board).toContainText('mrpeasy');
  await expect(board).toContainText('ago'); // lag rendered (e.g. "45s ago")
});
