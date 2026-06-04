import { test, expect } from '@playwright/test';

// Drives the H6Q desk against the running Conduit API (seeded with one forecastable account owned by the agent,
// an open weekly cycle, and a finance viewer). Verifies the core interaction the business cares about: a sales
// agent updates *their portion* (per SKU × demand band) and it rolls up — reconciling branch vs agent.

test('H6Q: an agent submits their portion; it rolls up bottom-up and reconciles branch ≡ agent', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:agent-e2e');
  await page.getByTestId('tab-h6q').click();

  // capture: the agent loads their accounts and forecasts their portion for the horizon month
  await page.getByTestId('h6q-load-mine').click();
  await expect(page.getByTestId('h6q-account')).toBeVisible();
  await page.getByTestId('h6q-qty-HV-310-P50').fill('120');
  await page.getByTestId('h6q-submit').click();
  await expect(page.getByTestId('h6q-cap-status')).toContainText('submitted');

  // board (finance viewer): the same estimate has rolled up; branch and agent axes reconcile
  await page.getByTestId('token').fill('dev:finance-e2e');
  await page.getByTestId('h6q-tab-board').click();
  await page.getByTestId('h6q-board-load').click();
  await expect(page.getByTestId('h6q-total')).toContainText('120');
  await expect(page.getByTestId('h6q-reconcile')).toContainText('✓');

  // the SAME numbers re-pivot by sales agent (ownership axis) and still total 120
  await page.getByTestId('h6q-by-agent').click();
  await expect(page.getByTestId('h6q-total')).toContainText('120');
  await expect(page.getByTestId('h6q-reconcile')).toContainText('✓');
});
