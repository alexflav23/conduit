import { test, expect } from '@playwright/test';
import { mkdirSync } from 'fs';
import { resolve } from 'path';
import { CHAPTERS } from '../src/help/content';

// Docs-as-code screenshot capture (spec 38 §5b). For every documented chapter with a `screenshot` key, sign in,
// navigate to its screen, let it settle, and write public/help-shots/<key>.png — the image the manual renders.
// The committed PNGs are the manual's living illustrations; re-running this (yarn shots) regenerates them from the
// live UI, so they cannot silently rot. CI re-captures + visual-diffs to flag drift. Captured at a fixed viewport
// for determinism; signed in as the CEO dev door so every data layer renders (no layer-stripped blanks).
const OUT = resolve(process.cwd(), 'public/help-shots');
const shots = CHAPTERS.filter((c) => c.route && c.screenshot);

test.use({ viewport: { width: 1440, height: 900 } });

test.beforeAll(() => mkdirSync(OUT, { recursive: true }));

for (const ch of shots) {
  test(`shot · ${ch.id}`, async ({ page }) => {
    // Auth is Keycloak-only now (no pre-login dev door in the UI), so inject the operator dev bearer the API
    // accepts in non-prod (AuthService devMode) directly into the session store before the app boots.
    await page.addInitScript(() => {
      try { sessionStorage.setItem('conduit_dev_token', 'dev:google:flavian@hypervolt.co.uk'); } catch { /* */ }
    });
    await page.goto('/' + ch.route);
    // settle: the shell rail is up (authenticated), then let data + charts resolve
    await expect(page.getByTestId('tab-order')).toBeVisible({ timeout: 15_000 });
    await page.waitForLoadState('networkidle').catch(() => {});
    await page.waitForTimeout(900);
    await page.screenshot({ path: resolve(OUT, `${ch.screenshot}.png`), fullPage: false });
  });
}
