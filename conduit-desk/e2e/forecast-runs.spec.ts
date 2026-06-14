import { test, expect } from '@playwright/test';

// Forecast-run tracking (doc 26 §7): the tournament stores an immutable, idempotent record per origin. The
// desk lists the run timeline, opens a comprehensive per-run report (the bake-off basis + provenance), and
// diffs two runs into a human-readable account of how the forecast evolved. Seeded: two origins (2026-03,
// 2026-06) where the H6Q Leeds account's champion moves runrate3 → depletion and total-level error improves.
test('Forecast Runs: timeline loads, a run report opens, and a diff narrates the evolution', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:finance-e2e');
  await page.getByTestId('tab-runs').click();

  // the timeline lists the seeded origins
  await page.getByTestId('fr-load').click();
  await expect(page.getByTestId('fr-run-row').first()).toBeVisible();
  await expect(page.getByTestId('fr-runs')).toContainText('2026-06');
  await expect(page.getByTestId('fr-runs')).toContainText('2026-03');

  // open the most recent run's report — the bake-off shows depletion beating runrate3
  await page.getByTestId('fr-open').first().click();
  await expect(page.getByTestId('fr-report')).toBeVisible();
  await expect(page.getByTestId('fr-accuracy')).toContainText('depletion');

  // diff the two runs: the narrative names the improvement + the champion change table lists it
  await page.getByTestId('fr-from').selectOption('2026-03');
  await page.getByTestId('fr-to').selectOption('2026-06');
  await page.getByTestId('fr-compare').click();
  await expect(page.getByTestId('fr-diff')).toBeVisible();
  await expect(page.getByTestId('fr-narrative')).toContainText('improved');
  await expect(page.getByTestId('fr-champion-changes')).toContainText('depletion');

  // the browsable delta: a per-segment breakdown, then re-grouped by market (channel-by-channel-for-market lives here)
  await expect(page.getByTestId('fr-breakdown-row').first()).toBeVisible();
  await expect(page.getByTestId('fr-breakdown')).toContainText('wholesale');
  await page.getByTestId('fr-by-market').click();
  await expect(page.getByTestId('fr-breakdown')).toBeVisible();
});
