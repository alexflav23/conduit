import { describe, it, expect } from 'vitest';
import { CHAPTERS, PENDING_CHAPTERS } from '../content';

// The anti-rot rule (spec 38 §3): the manual cannot drift from the product. Every desk screen must be either
// documented (a chapter with that route) or explicitly listed as PENDING (a shrinking allowlist). A new screen
// with neither — or a chapter/pending entry pointing at a screen that no longer exists — fails CI.
//
// ALL_TABS mirrors the PAGES registry in App.tsx. Keep it in sync (adding a screen = add it here + a chapter or
// a PENDING entry). 'help' is the manual itself and is intentionally excluded from the documentation requirement.
const ALL_TABS = [
  'order', 'dealdesk', 'pricing', 'returns', 'crm', 'reseller',
  'h6q', 'flow', 'supply', 'shelf', 'engine', 'runs',
  'inventory', 'purchasing', 'batch', 'activation', 'warranty',
  'finance', 'commission', 'docs', 'lifecycle', 'tax', 'backlog',
  'intercompany', 'procurement', 'treasury',
  'audit', 'period', 'sync', 'shadow', 'proof', 'access', 'notifications',
];

const documented = CHAPTERS.filter((c) => c.route).map((c) => c.route as string);

describe('user manual ↔ desk parity', () => {
  it('every chapter route is a real screen (no stale chapters)', () => {
    for (const r of documented) expect(ALL_TABS, `chapter route '${r}' is not a known screen`).toContain(r);
  });

  it('every PENDING entry is a real screen and not already documented', () => {
    for (const p of PENDING_CHAPTERS) {
      expect(ALL_TABS, `PENDING '${p}' is not a known screen`).toContain(p);
      expect(documented, `PENDING '${p}' already has a chapter — remove it from PENDING_CHAPTERS`).not.toContain(p);
    }
  });

  it('every screen is documented or explicitly pending (no silent gaps)', () => {
    const covered = new Set([...documented, ...PENDING_CHAPTERS]);
    const missing = ALL_TABS.filter((t) => !covered.has(t));
    expect(missing, `screens with no chapter and no PENDING entry: ${missing.join(', ')}`).toEqual([]);
  });

  it('chapter ids are unique', () => {
    const ids = CHAPTERS.map((c) => c.id);
    expect(new Set(ids).size, 'duplicate chapter id').toBe(ids.length);
  });
});
