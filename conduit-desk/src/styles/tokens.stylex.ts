import * as stylex from '@stylexjs/stylex';

// Hypervolt design tokens — dark-mode first. Canonical near-black ink rgb(22,23,28)/#16171C on big
// surfaces, layered surfaces above it, near-white text; accent = Hypervolt purple #962DFF (lifted for
// AA on dark). The signature 3-colour gradient (magenta -> purple -> blue) is hero/accent ONLY — never
// on dense data. Values mirror styles/hv-tokens.css :root (dark). Export shape/names are preserved so
// every existing import keeps compiling.
export const colors = stylex.defineVars({
  accent: '#A557FF', // hv-purple #962DFF, lifted for contrast on near-black
  magenta: '#EB01FF', // gradient start
  blue: '#0356FF', // gradient end
  gradient: 'linear-gradient(135deg, #EB01FF 0%, #962DFF 50%, #0356FF 100%)',
  bg: '#16171C', // hv-ink — canonical near-black, rgb(22,23,28)
  surface: '#1b1c23', // cards on dark
  border: 'rgba(255,255,255,0.07)',
  text: 'rgba(255,255,255,0.95)', // near-white
  muted: 'rgba(255,255,255,0.62)',
  ok: '#57E0A0',
  warn: '#F2B23E',
  fontUi: '"Roboto", system-ui, -apple-system, "Segoe UI", sans-serif',
  fontDisplay: '"Rubik", system-ui, sans-serif',
  fontMono: '"SF Mono", ui-monospace, "JetBrains Mono", Menlo, Monaco, monospace',
});
