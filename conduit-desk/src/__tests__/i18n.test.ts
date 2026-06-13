import { describe, it, expect } from 'vitest';
import i18n, { LOCALES } from '../i18n';

// P2.1 (doc 34): the locale key-parity guard — every one of the 15 locales must carry EXACTLY the keys of the
// en base of record. A missing key is a blank string in the UI; an extra key is dead translation. Reads the
// actually-loaded i18next resource bundles (what the app renders from), CI-enforced so drift is caught at build.
function keysOf(lng: string): string[] {
  const bundle = (i18n.getResourceBundle(lng, 'common') ?? {}) as Record<string, string>;
  return Object.keys(bundle).sort();
}

describe('i18n locale key parity', () => {
  const base = keysOf('en');

  it('ships the full 15-locale set incl. CJK + Thai', () => {
    expect(LOCALES.length).toBe(15);
    expect(LOCALES).toContain('ja');
    expect(LOCALES).toContain('zh');
    expect(LOCALES).toContain('th');
  });

  it('every locale has exactly the en key set — no missing, no extra', () => {
    expect(base.length).toBeGreaterThan(0);
    for (const lng of LOCALES) {
      expect(keysOf(lng), `locale ${lng} key set`).toEqual(base);
    }
  });

  it('translated locales actually differ from en (not silent copies) for nav.order', () => {
    const en = i18n.getResourceBundle('en', 'common') as Record<string, string>;
    for (const lng of ['de', 'fr', 'ja', 'zh', 'th']) {
      const loc = i18n.getResourceBundle(lng, 'common') as Record<string, string>;
      expect(loc['nav.order'], `locale ${lng} nav.order`).not.toBe(en['nav.order']);
    }
  });

  it('t() resolves a non-Latin translation after changeLanguage', async () => {
    await i18n.changeLanguage('ja');
    expect(i18n.t('nav.order')).toBe('注文デスク');
    await i18n.changeLanguage('en');
    expect(i18n.t('nav.order')).toBe('Order Desk');
  });
});
