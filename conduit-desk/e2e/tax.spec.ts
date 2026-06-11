import { test, expect } from '@playwright/test';

// The Tax tab (M13-Tax, doc 16): the determination engine + rate-table admin made tangible. A tax_specialist runs
// live quotes (the US multi-level breakdown, UK VAT) and proposes effective-dated rates; only the CFO can activate
// them (maker-checker). Tax is a quote, not a rate column — and the breakdown is the jurisdiction stack, not one number.
test('Tax: a US ZIP quote shows the state+county+district breakdown; UK shows a single VAT component', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:tax-e2e');
  await page.getByTestId('tab-tax').click();

  // Default inputs quote GB → US California ZIP 90001 → 6% + 0.25% + 2.25% = $8.50 across three components.
  await page.getByTestId('tax-quote-btn').click();
  await expect(page.getByTestId('tax-supply-kind')).toHaveText('us_destination');
  await expect(page.getByTestId('tax-total')).toContainText('8.50');
  await expect(page.getByTestId('tax-comp-row')).toHaveCount(3);

  // Re-quote a UK domestic sale → one national VAT component at 20%.
  await page.getByTestId('tax-to').fill('GB');
  await page.getByTestId('tax-region').fill('');
  await page.getByTestId('tax-postcode').fill('');
  await page.getByTestId('tax-currency').fill('GBP');
  await page.getByTestId('tax-quote-btn').click();
  await expect(page.getByTestId('tax-supply-kind')).toHaveText('domestic');
  await expect(page.getByTestId('tax-total')).toContainText('20.00');
  await expect(page.getByTestId('tax-comp-row')).toHaveCount(1);
});

test('Tax: maker-checker — tax_specialist proposes a rate (draft), cannot activate; the CFO activates it', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:tax-e2e');
  await page.getByTestId('tab-tax').click();

  // Propose a draft FR VAT rate.
  await page.getByTestId('tax-pr-juris').fill('FR');
  await page.getByTestId('tax-pr-name').fill('France VAT');
  await page.getByTestId('tax-pr-rate').fill('20.0');
  await page.getByTestId('tax-pr-from').fill('2026-01-01');
  await page.getByTestId('tax-propose-btn').click();
  await expect(page.getByTestId('tax-propose-status')).toContainText('proposed');
  // The draft appears in the rate table with an Activate button.
  await expect(page.getByTestId('tax-activate').first()).toBeVisible();

  // tax_specialist cannot activate (lacks approve:tax_rate) — fails closed.
  await page.getByTestId('tax-activate').first().click();
  await expect(page.getByTestId('tax-propose-status')).toContainText('failed');

  // Switch to the CFO; the draft is still listed — activate it (maker-checker: proposer ≠ approver).
  await page.getByTestId('signout').click();
  await page.getByTestId('token').fill('dev:ceo-e2e');
  await page.getByTestId('tab-tax').click();
  await page.getByTestId('tax-rate-juris').fill('FR');
  await page.getByTestId('tax-rates-load').click();
  await page.getByTestId('tax-activate').first().click();
  await expect(page.getByTestId('tax-propose-status')).toContainText('activated');
});

test('Tax: the seller-of-record entity map lists the active GB entity; VAT exposure board + remittance request work', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:tax-e2e');
  await page.getByTestId('tab-tax').click();

  // the entity map: GB → the active operating entity (seeded)
  await page.getByTestId('tax-se-load').click();
  await expect(page.getByTestId('tax-se-row').first()).toContainText('GB');

  // the VAT exposure board renders (per entity × jurisdiction × period)
  await page.getByTestId('tax-exposure-load').click();
  await expect(page.getByTestId('tax-exposure-table')).toBeVisible();

  // requesting a remittance is accepted (the consumer performs the ledger depletion off the API)
  await page.getByTestId('tax-rm-amount').fill('50.00');
  await page.getByTestId('tax-rm-ref').fill('HMRC-Q2');
  await page.getByTestId('tax-rm-submit').click();
  await expect(page.getByTestId('tax-rm-status')).toContainText('requested');
});
