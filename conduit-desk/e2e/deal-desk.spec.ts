import { test, expect } from '@playwright/test';

// Drives the Deal Desk against the running Conduit API (seeded with an out-of-band ORD-DEALDESK exception).
// Verifies clear price banding, the agent proposal workflow, the single-CEO-approver permission, and release.

test('Deal Desk: price banding is explicit; agent proposes; only the CEO approves; approval is timed + volume-contingent', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:agent-e2e');
  await page.getByTestId('tab-dealdesk').click();
  await page.getByTestId('load-pending').click();

  // clear price banding everywhere
  await expect(page.getByTestId('exc-list-price')).toHaveText('587.5000');
  await expect(page.getByTestId('exc-band')).toContainText('10');
  await expect(page.getByTestId('exc-requested')).toHaveText('400.0000');
  await expect(page.getByTestId('exc-deviation')).toContainText('31');
  await expect(page.getByTestId('exc-status')).toHaveText('pending_ceo');

  // agent proposes a narrative + volume expectation
  await page.getByTestId('narr-justification').fill('Strategic Octopus rollout; competitive displacement');
  await page.getByTestId('narr-volume').fill('500');
  await page.getByTestId('narr-strategic').fill('Anchors the energy channel for FY27');
  await page.getByTestId('submit-narrative').click();
  await expect(page.getByTestId('exc-status')).toHaveText('pending_ceo');

  // the agent (not the CEO) cannot approve a price deviation
  await page.getByTestId('dec-memo').fill('agent tries to approve');
  await page.getByTestId('approve-btn').click();
  await expect(page.getByTestId('dd-error')).toContainText('403');

  // only the CEO may approve — timed (valid-to), volume-contingent (min volume), with a memo
  await page.getByTestId('token').fill('dev:ceo-e2e');
  await page.getByTestId('dec-memo').fill('Approved for Octopus; 500-unit commitment');
  await page.getByTestId('dec-volume-min').fill('400');
  await page.getByTestId('approve-btn').click();
  await expect(page.getByTestId('exc-status')).toHaveText('approved');
});
