import React, { useState } from 'react';
import * as stylex from '@stylexjs/stylex';
import { colors } from './styles/tokens.stylex';
import { getVariants, getWaterfall, getLedger, H6Q_MARKET } from './api';

// The "Flow" desk (design spec doc 20 §2.3/§2.6): the H6Q VARIANTS — forecast → committed → produced →
// delivered → ordered → shipped → revenue — and how they evolve over time, with revenue drilling to the
// immutable TigerBeetle log. Function-first: tabular, labelled, traceable. Beauty comes later.

const styles = stylex.create({
  card: { backgroundColor: colors.surface, border: `1px solid ${colors.border}`, borderRadius: '14px', padding: '1.25rem', marginBottom: '1.25rem', maxWidth: '980px' },
  section: { fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.06em', color: colors.muted, marginBottom: '0.6rem' },
  row: { display: 'flex', gap: '0.75rem', alignItems: 'center', marginBottom: '0.6rem', flexWrap: 'wrap' },
  label: { color: colors.muted, fontSize: '0.8rem' },
  input: { backgroundColor: colors.bg, color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.45rem 0.6rem', fontSize: '0.9rem' },
  button: { backgroundColor: colors.accent, color: '#fff', border: 'none', borderRadius: '10px', padding: '0.5rem 1.05rem', fontSize: '0.92rem', fontWeight: 600, cursor: 'pointer' },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '0.9rem', fontVariantNumeric: 'tabular-nums' },
  th: { textAlign: 'left', color: colors.muted, fontSize: '0.72rem', textTransform: 'uppercase', letterSpacing: '0.05em', padding: '0.5rem 0.7rem', borderBottom: `1px solid ${colors.border}` },
  thNum: { textAlign: 'right' },
  td: { padding: '0.45rem 0.7rem', borderBottom: `1px solid ${colors.border}` },
  tdNum: { textAlign: 'right', fontVariantNumeric: 'tabular-nums' },
  variant: { fontWeight: 600 },
  rev: { color: colors.accent, fontWeight: 700 },
  mono: { fontFamily: 'monospace', fontSize: '0.78rem', color: colors.muted },
  chip: { padding: '0.15rem 0.5rem', borderRadius: '999px', fontSize: '0.72rem', backgroundColor: colors.border, color: colors.text },
});

// The seven variants, in order. Each cell is a distinct quantity — never conflated.
const VARIANTS: Array<{ key: string; label: string }> = [
  { key: 'sales_forecast', label: 'Forecast' },
  { key: 'cm_committed', label: 'Committed (firm PO)' },
  { key: 'cm_produced', label: 'Produced' },
  { key: 'delivered', label: 'Delivered' },
  { key: 'ordered', label: 'Ordered (sold)' },
  { key: 'shipped', label: 'Shipped (dispatched)' },
];
const MONTHS = ['2026-08', '2026-09', '2026-10'];

export function Flow({ token }: { token: string }) {
  const [variants, setVariants] = useState<any[]>([]);
  const [variant, setVariant] = useState<string>('');
  const [grid, setGrid] = useState<Record<string, any>>({}); // period -> waterfall json
  const [ledger, setLedger] = useState<any | null>(null);
  const [error, setError] = useState<string | null>(null);

  const init = async () => {
    setError(null);
    const v = await getVariants(token);
    setVariants(v.json ?? []);
    if ((v.json ?? []).length) {
      const first = v.json[0].id;
      setVariant(first);
      await load(first);
    }
  };

  const load = async (vid: string) => {
    setError(null);
    const cells: Record<string, any> = {};
    for (const m of MONTHS) {
      const wf = await getWaterfall(token, vid, m);
      if (wf.status === 200) cells[m] = wf.json;
    }
    setGrid(cells);
    const led = await getLedger(token, H6Q_MARKET, MONTHS[1]); // the focus month
    if (led.status === 200) setLedger(led.json);
    else setError(`Ledger ${led.status}`);
  };

  const cell = (period: string, key: string): string => {
    const wf = grid[period];
    if (!wf) return '—';
    if (key === 'revenue_ex_vat') return wf.revenue_ex_vat ?? '0';
    return String(wf?.stages?.[key] ?? 0);
  };

  return (
    <div>
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.row)}>
          <button {...stylex.props(styles.button)} data-testid="flow-load" onClick={init}>Load flow</button>
          {variant && (
            <select {...stylex.props(styles.input)} data-testid="flow-variant" value={variant} onChange={(e) => { setVariant(e.target.value); load(e.target.value); }}>
              {variants.map((v) => <option key={v.id} value={v.id}>{v.sku} — {v.family}</option>)}
            </select>
          )}
          {error && <span data-testid="flow-error" style={{ color: colors.warn }}>{error}</span>}
        </div>

        <div {...stylex.props(styles.section)}>H6Q variants over time — the same demand, each stage distinct</div>
        <table {...stylex.props(styles.table)} data-testid="flow-grid">
          <thead>
            <tr>
              <th {...stylex.props(styles.th)}>Variant</th>
              {MONTHS.map((m) => <th key={m} {...stylex.props(styles.th, styles.thNum)}>{m}</th>)}
            </tr>
          </thead>
          <tbody>
            {VARIANTS.map((vr) => (
              <tr key={vr.key} data-testid={`flow-row-${vr.key}`}>
                <td {...stylex.props(styles.td, styles.variant)}>{vr.label}</td>
                {MONTHS.map((m) => <td key={m} {...stylex.props(styles.td, styles.tdNum)} data-testid={`flow-cell-${vr.key}-${m}`}>{cell(m, vr.key)}</td>)}
              </tr>
            ))}
            <tr data-testid="flow-row-revenue">
              <td {...stylex.props(styles.td, styles.variant, styles.rev)}>Revenue ex-VAT (£)</td>
              {MONTHS.map((m) => <td key={m} {...stylex.props(styles.td, styles.tdNum, styles.rev)}>{cell(m, 'revenue_ex_vat')}</td>)}
            </tr>
          </tbody>
        </table>
      </div>

      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.section)}>Immutable ledger — recognised revenue traced to TigerBeetle transfers ({MONTHS[1]})</div>
        {ledger && (
          <>
            <div {...stylex.props(styles.row)} data-testid="ledger-totals">
              <span {...stylex.props(styles.chip)}>Revenue £{ledger.totals?.revenue_ex_vat ?? '0'}</span>
              <span {...stylex.props(styles.chip)}>VAT £{ledger.totals?.vat ?? '0'}</span>
              <span {...stylex.props(styles.chip)}>COGS £{ledger.totals?.cogs ?? '0'}</span>
              <span {...stylex.props(styles.chip)}>Gross margin £{ledger.totals?.gross_margin ?? '0'}</span>
            </div>
            <table {...stylex.props(styles.table)} data-testid="ledger-table">
              <thead>
                <tr>
                  <th {...stylex.props(styles.th)}>Invoice</th>
                  <th {...stylex.props(styles.th, styles.thNum)}>Revenue</th>
                  <th {...stylex.props(styles.th, styles.thNum)}>VAT</th>
                  <th {...stylex.props(styles.th, styles.thNum)}>COGS</th>
                  <th {...stylex.props(styles.th, styles.thNum)}>Margin</th>
                  <th {...stylex.props(styles.th)}>AR transfer (TigerBeetle id)</th>
                </tr>
              </thead>
              <tbody>
                {(ledger.recognitions ?? []).map((r: any, i: number) => (
                  <tr key={i} data-testid="ledger-row">
                    <td {...stylex.props(styles.td)}>{r.invoice_no ?? '—'}</td>
                    <td {...stylex.props(styles.td, styles.tdNum)}>{r.revenue_ex_vat}</td>
                    <td {...stylex.props(styles.td, styles.tdNum)}>{r.vat}</td>
                    <td {...stylex.props(styles.td, styles.tdNum)}>{r.cogs}</td>
                    <td {...stylex.props(styles.td, styles.tdNum)}>{r.gross_margin}</td>
                    <td {...stylex.props(styles.td, styles.mono)}>{(r.ar_transfer_id ?? '').slice(0, 24)}…</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p {...stylex.props(styles.label)} style={{ marginTop: '0.6rem' }}>Each revenue figure is posted as DR AR / CR Revenue + VAT and DR COGS / CR INV in the immutable log — the transfer ids above are the proof.</p>
          </>
        )}
      </div>
    </div>
  );
}
