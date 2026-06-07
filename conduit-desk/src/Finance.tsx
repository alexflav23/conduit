import React, { useState } from 'react';
import * as stylex from '@stylexjs/stylex';
import { colors } from './styles/tokens.stylex';
import { getPnl, getCashWaterfall, getCreditTerms, setCreditTerms, FINANCE_MARKET } from './api';

// The Finance desk (M13): the P&L recognised on dispatch (proved on the immutable ledger), the cash waterfall
// (open invoices bucketed by each contact's contractual due date), and per-invoice-contact credit-terms admin.
const styles = stylex.create({
  card: { backgroundColor: colors.surface, border: `1px solid ${colors.border}`, borderRadius: '14px', padding: '1.25rem', marginBottom: '1.25rem', maxWidth: '860px' },
  section: { fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.06em', color: colors.muted, marginBottom: '0.6rem' },
  row: { display: 'flex', gap: '0.75rem', alignItems: 'center', marginBottom: '0.7rem', flexWrap: 'wrap' },
  button: { backgroundColor: colors.accent, color: '#fff', border: 'none', borderRadius: '10px', padding: '0.5rem 1.05rem', fontSize: '0.92rem', fontWeight: 600, cursor: 'pointer' },
  input: { backgroundColor: colors.bg, color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.45rem 0.6rem', fontSize: '0.9rem' },
  label: { color: colors.muted, fontSize: '0.8rem' },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '0.9rem', fontVariantNumeric: 'tabular-nums' },
  th: { textAlign: 'left', color: colors.muted, fontSize: '0.72rem', textTransform: 'uppercase', letterSpacing: '0.05em', padding: '0.45rem 0.7rem', borderBottom: `1px solid ${colors.border}` },
  td: { padding: '0.45rem 0.7rem', borderBottom: `1px solid ${colors.border}` },
  num: { textAlign: 'right' },
  pnlGrid: { display: 'flex', gap: '1.5rem', flexWrap: 'wrap' },
  metric: { minWidth: '120px' },
  metricLabel: { color: colors.muted, fontSize: '0.72rem', textTransform: 'uppercase', letterSpacing: '0.05em' },
  metricValue: { fontSize: '1.4rem', fontWeight: 800, fontVariantNumeric: 'tabular-nums' },
  margin: { color: colors.accent },
});

export function Finance({ token }: { token: string }) {
  const [period, setPeriod] = useState('2026-09');
  const [pnl, setPnl] = useState<any | null>(null);
  const [wf, setWf] = useState<any[]>([]);
  const [party, setParty] = useState('');
  const [terms, setTerms] = useState<any | null>(null);
  const [days, setDays] = useState('');
  const [status, setStatus] = useState<string | null>(null);

  const loadPnl = async () => { const r = await getPnl(token, FINANCE_MARKET, period); setPnl(r.status === 200 ? r.json : null); };
  const loadWf = async () => { const r = await getCashWaterfall(token, 'GBP'); setWf(r.json ?? []); };
  const loadTerms = async () => {
    if (!party) return;
    const r = await getCreditTerms(token, party);
    if (r.status === 200) { setTerms(r.json); setDays(String(r.json.payment_terms_days ?? '')); }
  };
  const saveTerms = async () => {
    if (!party || !days) return;
    const r = await setCreditTerms(token, party, parseInt(days, 10));
    setStatus(r.status === 200 ? `saved (${days}-day terms)` : `failed (${r.status})`);
    await loadTerms();
  };

  const m = (v: any) => (v == null ? '—' : `£${Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`);

  return (
    <div>
      {/* P&L */}
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.row)}>
          <span {...stylex.props(styles.label)}>Period</span>
          <input {...stylex.props(styles.input)} data-testid="fin-period" value={period} onChange={(e) => setPeriod(e.target.value)} style={{ width: '90px' }} />
          <button {...stylex.props(styles.button)} data-testid="fin-load-pnl" onClick={loadPnl}>Load P&amp;L</button>
          <span {...stylex.props(styles.label)}>ASC-606 — recognised on dispatch, proved on the ledger</span>
        </div>
        <div {...stylex.props(styles.section)}>P&amp;L ({period})</div>
        <div {...stylex.props(styles.pnlGrid)} data-testid="fin-pnl">
          <div {...stylex.props(styles.metric)}><div {...stylex.props(styles.metricLabel)}>Revenue (ex VAT)</div><div {...stylex.props(styles.metricValue)} data-testid="fin-revenue">{m(pnl?.revenue_ex_vat)}</div></div>
          <div {...stylex.props(styles.metric)}><div {...stylex.props(styles.metricLabel)}>VAT</div><div {...stylex.props(styles.metricValue)}>{m(pnl?.vat)}</div></div>
          <div {...stylex.props(styles.metric)}><div {...stylex.props(styles.metricLabel)}>COGS</div><div {...stylex.props(styles.metricValue)}>{m(pnl?.cogs)}</div></div>
          <div {...stylex.props(styles.metric)}><div {...stylex.props(styles.metricLabel)}>Gross margin</div><div {...stylex.props(styles.metricValue, styles.margin)} data-testid="fin-margin">{m(pnl?.gross_margin)}</div></div>
        </div>
      </div>

      {/* Cash waterfall */}
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.row)}>
          <button {...stylex.props(styles.button)} data-testid="fin-load-wf" onClick={loadWf}>Load cash waterfall</button>
          <span {...stylex.props(styles.label)}>open invoices bucketed by each contact&apos;s contractual due date</span>
        </div>
        <div {...stylex.props(styles.section)}>Cash waterfall (expected collections, GBP)</div>
        <table {...stylex.props(styles.table)} data-testid="fin-waterfall">
          <thead><tr>
            <th {...stylex.props(styles.th)}>Due month</th>
            <th {...stylex.props(styles.th)}>Currency</th>
            <th {...stylex.props(styles.th, styles.num)}>Expected cash</th>
            <th {...stylex.props(styles.th, styles.num)}>Invoices</th>
          </tr></thead>
          <tbody>
            {wf.map((r, i) => (
              <tr key={i} data-testid="fin-wf-row">
                <td {...stylex.props(styles.td)}>{r.due_month}</td>
                <td {...stylex.props(styles.td)}>{r.currency}</td>
                <td {...stylex.props(styles.td, styles.num)}>{m(r.expected_cash)}</td>
                <td {...stylex.props(styles.td, styles.num)}>{r.invoices}</td>
              </tr>
            ))}
            {wf.length === 0 && <tr><td {...stylex.props(styles.td)} colSpan={4} style={{ color: colors.muted }}>No open invoices — load to refresh.</td></tr>}
          </tbody>
        </table>
      </div>

      {/* Credit terms admin */}
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.section)}>Credit terms — per invoice contact (drives the due date + waterfall)</div>
        <div {...stylex.props(styles.row)}>
          <span {...stylex.props(styles.label)}>Party id</span>
          <input {...stylex.props(styles.input)} data-testid="fin-party" value={party} onChange={(e) => setParty(e.target.value)} style={{ width: '320px' }} placeholder="party uuid" />
          <button {...stylex.props(styles.button)} data-testid="fin-load-terms" onClick={loadTerms}>Load</button>
        </div>
        {terms && (
          <div {...stylex.props(styles.row)}>
            <span {...stylex.props(styles.label)}>Payment terms (days)</span>
            <input {...stylex.props(styles.input)} data-testid="fin-terms-days" value={days} onChange={(e) => setDays(e.target.value)} style={{ width: '80px' }} />
            <span {...stylex.props(styles.label)}>credit limit: {m(terms.credit_limit)}</span>
            <button {...stylex.props(styles.button)} data-testid="fin-save-terms" onClick={saveTerms}>Save</button>
            {status && <span {...stylex.props(styles.label)} data-testid="fin-terms-status">{status}</span>}
          </div>
        )}
      </div>
    </div>
  );
}
