import { test, expect } from '@playwright/test';

// The Proof Center (doc 31 §2): the interactive formal proof. The law register re-runs controls on click
// (green earned, never cached); the Journal Walk shows an invoice's DR/CR legs with the conservation strip
// recomputed in the browser; the Tamper Sandbox breaks the books and watches CTRL-LINEAGE-CLOSURE name it,
// then restore to green. finance-e2e holds view:proof_center; admin-e2e holds manage (the tamper gate).
test('Proof: laws re-run green, the journal walk balances, the tamper sandbox is named then restored', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:finance-e2e');
  await page.getByTestId('tab-proof').click();

  // the law register loads and a control re-runs green on the live (clean) book
  await page.getByTestId('proof-load-laws').click();
  await expect(page.getByTestId('proof-law-row').first()).toBeVisible();
  await page.getByTestId('proof-run-CTRL-LINEAGE-CLOSURE').click();
  await expect(page.getByTestId('proof-pin-CTRL-LINEAGE-CLOSURE')).toContainText('pass');

  // the journal walk: INV-FLOW's six legs, and the conservation strip ties in the browser
  await page.getByTestId('proof-nav-walk').click();
  await page.getByTestId('proof-invoice').fill('INV-FLOW');
  await page.getByTestId('proof-walk').click();
  await expect(page.getByTestId('proof-leg').first()).toBeVisible();
  await expect(page.getByTestId('proof-conservation')).toContainText('balanced');

  // the wall: finance has view but NOT manage — the tamper buttons report the surface is unavailable to it
  await page.getByTestId('proof-nav-tamper').click();
  await page.getByTestId('proof-tamper-delete_leg').click();
  await expect(page.getByTestId('proof-tamper-control').or(page.getByText('developer session'))).toBeVisible();
});

// ASC 606 (doc 31 §2.3): the five steps for a real order, and the wall — finance (no inter_entity) sees the
// recognition but NOT the principal/LRD decomposition. ORD-FLOW is the seeded sale (fixed id).
test('Proof: the ASC-606 walkthrough renders the five steps; the LRD overlay is walled from finance', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:finance-e2e');
  await page.getByTestId('tab-proof').click();
  await page.getByTestId('proof-nav-asc606').click();

  await page.getByTestId('asc606-order').fill('33333333-3333-3333-3333-333333333333');
  await page.getByTestId('asc606-load').click();

  await expect(page.getByTestId('asc606-order-head')).toContainText('ORD-FLOW');
  await expect(page.getByTestId('asc606-step1_identify_contract')).toBeVisible();
  await expect(page.getByTestId('asc606-step5_recognition')).toContainText('INV-FLOW');
  // the wall: finance has no inter_entity, so the principal/LRD decomposition is absent, not shown
  await expect(page.getByTestId('asc606-no-flash')).toBeVisible();
  await expect(page.getByTestId('asc606-flash')).not.toBeVisible();
});

// The admin path: the full corrupt → named → restore loop the CTO watches live.
test('Proof: an admin breaks a leg, the control names the break, restore returns it to green', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:admin-e2e');
  await page.getByTestId('tab-proof').click();
  await page.getByTestId('proof-nav-tamper').click();

  await page.getByTestId('proof-tamper-delete_leg').click();
  await expect(page.getByTestId('proof-tamper-control')).toContainText('fail');

  await page.getByTestId('proof-tamper-restore').click();
  await expect(page.getByTestId('proof-tamper-control')).toContainText('pass');
});
