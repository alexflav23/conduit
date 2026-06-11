// Captures every desk tab against the LIVE local API into spec/design-assets/desk/ — the visual ground
// truth for the design handoff (spec/27). Tabs with a one-click loader are clicked first so the captures
// show real data, not empty states. Run: node scripts/capture-screens.mjs (dev server on :3002, api on :8080).
import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';

const OUT = new URL('../../spec/design-assets/desk/', import.meta.url).pathname;
mkdirSync(OUT, { recursive: true });

const TABS = [
  { tab: 'tab-order', name: 'D-order-desk', click: [] },
  { tab: 'tab-dealdesk', name: 'D5-deal-desk', click: ['load-pending'] },
  { tab: 'tab-h6q', name: 'D7-h6q-board', click: ['h6q-tab-board'] },
  { tab: 'tab-flow', name: 'D9-flow', click: ['flow-load'] },
  { tab: 'tab-supply', name: 'D11-supply', click: ['supply-load'] },
  { tab: 'tab-shelf', name: 'D11-shelf', click: ['shelf-load'], token: 'dev:finance-e2e' },
  { tab: 'tab-finance', name: 'D9-finance', click: ['fin-load-pnl', 'fin-load-wf'], token: 'dev:finance-e2e' },
  { tab: 'tab-docs', name: 'D17-documents', click: ['doc-load'], token: 'dev:finance-e2e' },
  { tab: 'tab-lifecycle', name: 'D21-lifecycle', click: [] },
  { tab: 'tab-audit', name: 'D15-18-audit', click: ['aud-load-periods', 'aud-load-controls'], token: 'dev:finance-e2e' },
  { tab: 'tab-tax', name: 'D-tax', click: ['tax-quote-btn'], token: 'dev:finance-e2e' },
  { tab: 'tab-engine', name: 'forecast-engine', click: [] },
];

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
page.on('pageerror', (e) => console.log(`  pageerror: ${String(e).slice(0, 160)}`));
await page.goto('http://localhost:3002/');

for (const t of TABS) {
  try {
    // identity per tab: sign out if signed in, then enter through the gate
    if (await page.getByTestId('signout').isVisible().catch(() => false)) await page.getByTestId('signout').click();
    await page.getByTestId('token').fill(t.token ?? 'dev:agent-e2e');
    await page.getByTestId(t.tab).click({ timeout: 8000 });
    for (const c of t.click) {
      try {
        await page.getByTestId(c).click({ timeout: 3000 });
        await page.waitForTimeout(1500); // let the live API answer
      } catch { /* loader absent or data empty — capture the state as-is */ }
    }
    await page.waitForTimeout(500);
    await page.screenshot({ path: `${OUT}${t.name}.png`, fullPage: true });
    console.log(`captured ${t.name}`);
  } catch (e) {
    console.log(`FAILED ${t.name}: ${String(e).slice(0, 140)}`);
    await page.goto('http://localhost:3002/'); // recover: a crashed tab must not sink the rest
    await page.getByTestId('token').fill('dev:agent-e2e');
  }
}

await browser.close();
console.log(`done -> ${OUT}`);
