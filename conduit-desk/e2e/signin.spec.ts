import { test, expect } from '@playwright/test';

// D1 — sign-in/session (doc 27 §0): the desk opens on the sign-in page, never the app. The dev door mints a
// session (non-prod backends only); the session chip names the identity; sign-out returns to sign-in and
// clears the persisted session. The Google button renders only when VITE_GOOGLE_CLIENT_ID is configured —
// its server-side verification is covered by GoogleTokenVerifierSuite (audience/issuer/hd/expiry fail closed).
test('Sign-in: gate first, dev door in, session chip, sign-out clears the session', async ({ page }) => {
  await page.goto('/');

  // signed out: the gate is the only thing on screen — no tabs, no data
  await expect(page.getByTestId('signin-page')).toBeVisible();
  await expect(page.getByTestId('tab-order')).not.toBeVisible();

  // the dev door (the same field every suite uses)
  await page.getByTestId('token').fill('dev:agent-e2e');
  await expect(page.getByTestId('tab-order')).toBeVisible();
  await expect(page.getByTestId('session-chip')).toContainText('developer session');

  // the session survives a reload (sessionStorage)
  await page.reload();
  await expect(page.getByTestId('tab-order')).toBeVisible();

  // sign-out: back to the gate, session gone even after reload
  await page.getByTestId('signout').click();
  await expect(page.getByTestId('signin-page')).toBeVisible();
  await page.reload();
  await expect(page.getByTestId('signin-page')).toBeVisible();
  await expect(page.getByTestId('tab-order')).not.toBeVisible();
});
