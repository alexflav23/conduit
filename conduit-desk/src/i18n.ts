import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';

import en from './locales/en/common.json';
import de from './locales/de/common.json';
import fr from './locales/fr/common.json';
import es from './locales/es/common.json';
import it from './locales/it/common.json';
import nl from './locales/nl/common.json';
import pl from './locales/pl/common.json';
import sv from './locales/sv/common.json';
import da from './locales/da/common.json';
import nb from './locales/nb/common.json';
import pt from './locales/pt/common.json';
import ja from './locales/ja/common.json';
import zh from './locales/zh/common.json';
import ko from './locales/ko/common.json';
import th from './locales/th/common.json';

// The 15-locale set (doc 33/CLAUDE.md §5): the year-1 + roadmap markets incl. CJK + Thai. en is the base of
// record; every other locale must carry the SAME keys (the parity guard test enforces it — no missing-key drift).
export const LOCALES = ['en', 'de', 'fr', 'es', 'it', 'nl', 'pl', 'sv', 'da', 'nb', 'pt', 'ja', 'zh', 'ko', 'th'] as const;
export type Locale = (typeof LOCALES)[number];

export const LOCALE_LABEL: Record<Locale, string> = {
  en: 'English', de: 'Deutsch', fr: 'Français', es: 'Español', it: 'Italiano', nl: 'Nederlands',
  pl: 'Polski', sv: 'Svenska', da: 'Dansk', nb: 'Norsk', pt: 'Português',
  ja: '日本語', zh: '中文', ko: '한국어', th: 'ไทย',
};

const resources = {
  en: { common: en }, de: { common: de }, fr: { common: fr }, es: { common: es }, it: { common: it },
  nl: { common: nl }, pl: { common: pl }, sv: { common: sv }, da: { common: da }, nb: { common: nb },
  pt: { common: pt }, ja: { common: ja }, zh: { common: zh }, ko: { common: ko }, th: { common: th },
};

const stored = (typeof sessionStorage !== 'undefined' && sessionStorage.getItem('conduit_locale')) || 'en';

i18n.use(initReactI18next).init({
  resources,
  lng: stored,
  fallbackLng: 'en',
  ns: ['common'],
  defaultNS: 'common',
  supportedLngs: LOCALES as unknown as string[],
  interpolation: { escapeValue: false }, // React already escapes
});

export function setLocale(lng: string): void {
  i18n.changeLanguage(lng);
  if (typeof sessionStorage !== 'undefined') sessionStorage.setItem('conduit_locale', lng);
}

export default i18n;
