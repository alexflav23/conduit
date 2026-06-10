import { test, expect } from '@playwright/test';

// The Forecast Engine explainer (doc 26, productized): a static, self-contained page — no API calls — so the
// spec verifies the interactive machinery: sections expand, the depletion playground computes the real curve,
// and the tournament stepper walks its steps.
test('Forecast Engine: explainer renders, depletion playground computes, tournament stepper steps', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('tab-engine').click();

  // the pipeline section is open by default; the honesty rules expand on click
  await expect(page.getByText('The pipeline — data to bottom line')).toBeVisible();
  await page.getByTestId('explainer-why-it-is-bulletproof-the-honesty-rules').click();
  await expect(page.getByText('No leakage, ever')).toBeVisible();

  // the falsification table lists the documented negative results
  await page.getByTestId('explainer-the-falsification-discipline-seven-ideas-the-harness-killed').click();
  await expect(page.getByText('Recency-decayed evidence')).toBeVisible();

  // depletion playground: defaults reproduce the Q2'25 Octopus shape (M1 = 0: shelf exceeds one month's installs)
  await page.getByTestId('explainer-try-it-the-depletion-model-live').click();
  await expect(page.getByTestId('shelf-slider')).toBeVisible();
  await expect(page.locator('svg').first().getByText('0', { exact: true }).first()).toBeVisible();

  // tournament stepper: the incumbent-prior step is reachable and explains the keystone
  await page.getByTestId('explainer-the-tournament-how-an-account-gets-its-champion').click();
  await page.getByTestId('tstep-5').click();
  await expect(page.getByText('The incumbent prior.', { exact: false })).toBeVisible();
});
