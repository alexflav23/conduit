import React, { useState } from 'react';
import * as stylex from '@stylexjs/stylex';
import { colors } from './styles/tokens.stylex';
import { getOrderLifecycle } from './api';

// The Order Collection Ledger desk (M13 doc 13 §void). The order is the root; this REPLAYS the immutable event
// stream for one order into a readable ledger: the per-invoice collection cycles (the back-and-forth — issued →
// collected → voided → refunded → replaced-by) and the chronological event timeline (the "perfect log").
const styles = stylex.create({
  card: { backgroundColor: colors.surface, border: `1px solid ${colors.border}`, borderRadius: '14px', padding: '1.25rem', marginBottom: '1.25rem', maxWidth: '980px' },
  section: { fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.06em', color: colors.muted, marginBottom: '0.6rem' },
  row: { display: 'flex', gap: '0.75rem', alignItems: 'center', marginBottom: '0.7rem', flexWrap: 'wrap' },
  button: { backgroundColor: colors.accent, color: '#fff', border: 'none', borderRadius: '10px', padding: '0.5rem 1.05rem', fontSize: '0.92rem', fontWeight: 600, cursor: 'pointer' },
  input: { backgroundColor: colors.bg, color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.45rem 0.6rem', fontSize: '0.9rem' },
  label: { color: colors.muted, fontSize: '0.8rem' },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '0.9rem', fontVariantNumeric: 'tabular-nums' },
  th: { textAlign: 'left', color: colors.muted, fontSize: '0.72rem', textTransform: 'uppercase', letterSpacing: '0.05em', padding: '0.45rem 0.7rem', borderBottom: `1px solid ${colors.border}` },
  td: { padding: '0.45rem 0.7rem', borderBottom: `1px solid ${colors.border}` },
  num: { textAlign: 'right' },
  badge: { fontSize: '0.68rem', fontWeight: 700, padding: '0.1rem 0.45rem', borderRadius: '999px', textTransform: 'uppercase' },
  badgeVoid: { backgroundColor: 'rgba(179,38,30,0.18)', color: '#ff6b6b' },
  badgePaid: { backgroundColor: 'rgba(45,200,120,0.16)', color: '#39c97f' },
  ev: { display: 'flex', gap: '0.7rem', alignItems: 'baseline', padding: '0.3rem 0', borderBottom: `1px solid ${colors.border}`, fontSize: '0.86rem' },
  seq: { color: colors.muted, fontVariantNumeric: 'tabular-nums', width: '2.5rem' },
  etype: { fontWeight: 600, minWidth: '11rem' },
  corr: { color: colors.accent, fontSize: '0.72rem', fontFamily: 'monospace' },
});

export function Lifecycle({ token }: { token: string }) {
  const [orderId, setOrderId] = useState('33333333-3333-3333-3333-333333333333');
  const [data, setData] = useState<any | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const load = async () => {
    const r = await getOrderLifecycle(token, orderId);
    if (r.status === 200) { setData(r.json); setErr(null); } else { setData(null); setErr(`failed (${r.status})`); }
  };

  const m = (v: any) => (v == null ? '—' : `£${Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`);
  const cycles: any[] = data?.cycles ?? [];
  const timeline: any[] = data?.timeline ?? [];

  return (
    <div>
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.section)}>Order collection ledger — replayed from the immutable event log</div>
        <div {...stylex.props(styles.row)}>
          <span {...stylex.props(styles.label)}>Order id</span>
          <input {...stylex.props(styles.input)} data-testid="life-order-id" value={orderId} onChange={(e) => setOrderId(e.target.value)} style={{ width: '340px' }} />
          <button {...stylex.props(styles.button)} data-testid="life-load" onClick={load}>Load lifecycle</button>
          {err && <span {...stylex.props(styles.label)} data-testid="life-err">{err}</span>}
        </div>
      </div>

      {/* Collection cycles — the back-and-forth */}
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.section)}>Collection cycles (one per invoice on this order)</div>
        <table {...stylex.props(styles.table)} data-testid="life-cycles">
          <thead><tr>
            <th {...stylex.props(styles.th)}>#</th>
            <th {...stylex.props(styles.th)}>Invoice</th>
            <th {...stylex.props(styles.th)}>State</th>
            <th {...stylex.props(styles.th, styles.num)}>Total</th>
            <th {...stylex.props(styles.th, styles.num)}>Paid</th>
            <th {...stylex.props(styles.th, styles.num)}>Refunded</th>
            <th {...stylex.props(styles.th, styles.num)}>Outstanding</th>
            <th {...stylex.props(styles.th)}>Void / replaced-by</th>
          </tr></thead>
          <tbody>
            {cycles.map((c, i) => (
              <tr key={i} data-testid="life-cycle-row">
                <td {...stylex.props(styles.td)}>{c.cycle}</td>
                <td {...stylex.props(styles.td)}>{c.invoice_no}{c.credit_note_no && <span {...stylex.props(styles.label)}> · CN {c.credit_note_no}</span>}</td>
                <td {...stylex.props(styles.td)}>
                  {c.status === 'void' ? <span {...stylex.props(styles.badge, styles.badgeVoid)}>void</span>
                    : c.status === 'paid' ? <span {...stylex.props(styles.badge, styles.badgePaid)}>paid</span>
                    : c.status}
                </td>
                <td {...stylex.props(styles.td, styles.num)}>{m(c.total)}</td>
                <td {...stylex.props(styles.td, styles.num)}>{m(c.paid)}</td>
                <td {...stylex.props(styles.td, styles.num)}>{m(c.refunded)}</td>
                <td {...stylex.props(styles.td, styles.num)}>{m(c.outstanding)}</td>
                <td {...stylex.props(styles.td)}>{c.void_kind ? `${c.void_kind}: ${c.void_reason ?? ''}` : ''}{c.replaced_by ? ` → ${c.replaced_by}` : ''}</td>
              </tr>
            ))}
            {data && cycles.length === 0 && <tr><td {...stylex.props(styles.td)} colSpan={8} style={{ color: colors.muted }}>No collection cycles for this order.</td></tr>}
          </tbody>
        </table>
      </div>

      {/* Event timeline — the perfect log */}
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.section)}>Event timeline (append-only, chronological)</div>
        <div data-testid="life-timeline">
          {timeline.map((e, i) => (
            <div key={i} {...stylex.props(styles.ev)} data-testid="life-event">
              <span {...stylex.props(styles.seq)}>{e.seq}</span>
              <span {...stylex.props(styles.etype)}>{e.event_type}</span>
              <span {...stylex.props(styles.label)}>{(e.occurred_at ?? '').slice(0, 10)}</span>
              {e.invoice_no && <span {...stylex.props(styles.label)}>{e.invoice_no}</span>}
              {e.correlation_id && <span {...stylex.props(styles.corr)}>⛓ {String(e.correlation_id).slice(0, 8)}</span>}
            </div>
          ))}
          {data && timeline.length === 0 && <span {...stylex.props(styles.label)}>No events recorded for this order.</span>}
        </div>
      </div>
    </div>
  );
}
