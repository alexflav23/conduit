import * as stylex from '@stylexjs/stylex';

// Hypervolt design tokens — adopted from the Claude Design bundle (tokens.css, derived from the ⚡ Hypervolt
// Figma). Dark-first. The canonical near-black ink rgb(22,23,28) on big surfaces; the signature 3-colour
// gradient (magenta → purple → blue) for hero/accent only — never on dense data. Existing names are preserved so
// every component re-skins by value; magenta/blue/gradient/fonts are added for hero treatments.
export const colors = stylex.defineVars({
  accent: '#962DFF', // hv-purple — the brand accent
  magenta: '#EB01FF', // gradient start
  blue: '#0356FF', // gradient end
  gradient: 'linear-gradient(135deg, #EB01FF 0%, #962DFF 50%, #0356FF 100%)',
  bg: 'rgb(22, 23, 28)', // hv-ink — canonical near-black
  surface: 'rgb(34, 36, 43)', // hv-ink-4 — cards on dark
  border: 'rgb(47, 47, 47)', // hv-ink-5
  text: 'rgb(245, 245, 245)', // hv-grey-50
  muted: 'rgb(150, 150, 150)', // hv-grey-200
  ok: '#30d158',
  warn: '#ff9f0a',
  fontUi: '"Roboto", system-ui, -apple-system, Segoe UI, sans-serif',
  fontDisplay: '"Rubik", system-ui, sans-serif',
  fontMono: '"Monaco", ui-monospace, SFMono-Regular, monospace',
});
