import { test, expect } from '@playwright/test';

// The Documents tab (M13 doc 17 §6 + §void). Finance lists the legal artefacts for an invoice and can invalidate
// it. The seeded INV-FLOW has a finalised invoice document; voiding it is an immutable reversal (request accepted,
// the consumer then reverses the ledger + mints a credit note). Finance has edit+approve on order in the seed.
test('Documents: finance lists the invoice document and can request a void', async ({ page }) => {
  await page.goto('/');
  await page.getByTestId('token').fill('dev:finance-e2e');
  await page.getByTestId('tab-docs').click();

  // list the documents for the seeded invoice
  await page.getByTestId('doc-load').click();
  await expect(page.getByTestId('doc-row').first()).toContainText('HV-UK-INV-2026-000001');
  await expect(page.getByTestId('doc-table')).toContainText('30,000');
  await expect(page.getByTestId('doc-download').first()).toBeVisible();

  // invalidate it — a cancellation (no approval needed); the request is accepted (202)
  await page.getByTestId('void-kind').selectOption('cancellation');
  await page.getByTestId('void-reason').fill('duplicate PO from the customer');
  await page.getByTestId('void-submit').click();
  await expect(page.getByTestId('void-status')).toContainText('cancellation requested');
});
