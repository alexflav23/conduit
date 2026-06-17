import React, { useState } from 'react';
import { useApi } from './lib/query';
import { ApiError } from './lib/client';
import { PageHead, Card, Chip, LayerNote, EmptyRow, SkeletonRow, num } from './kit/kit';
import { I } from './kit/icons';

// Backlog — the sales-order commitment ledger (M4). Every placed order commits revenue; recognition draws it down
// at dispatch. committed = recognised + open. The open figure is the order book yet to ship — the forward-revenue
// view. Read-only, gated view:order.
//   GET /api/v1/finance/backlog (per entity)  ·  GET /api/v1/orders/{id}/commitment

interface Row { entity_id: string | null; currency: string; committed_ex_vat: number; recognised_ex_vat: number; open_ex_vat: number }
interface Commitment { order_id: string; currency: string; committed_ex_vat: number; committed_inc_vat: number; recognised_ex_vat: number; open_ex_vat: number; status: string }

const gbp = (v: number | null | undefined) => (v == null ? '—' : '£' + num(v));

function Stat({ label, value, sub, accent }: { label: string; value: React.ReactNode; sub?: string; accent?: boolean }) {
  return (
    <Card style={{ padding: '16px 18px' }}>
      <div className="dim" style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: 0.4 }}>{label}</div>
      <div style={{ fontFamily: 'var(--font-disp)', fontSize: 28, fontWeight: 600, marginTop: 4, color: accent ? 'var(--accent)' : undefined }}>{value}</div>
      {sub && <div className="dim" style={{ fontSize: 12, marginTop: 2 }}>{sub}</div>}
    </Card>
  );
}

export function Backlog(_props: any) {
  const q = useApi<Row[]>(['backlog'], '/api/v1/finance/backlog');
  const [input, setInput] = useState('');
  const [order, setOrder] = useState('');
  const oc = useApi<Commitment>(['order-commitment', order], `/api/v1/orders/${encodeURIComponent(order)}/commitment`, { enabled: !!order });

  const err = q.error as ApiError | null;
  const forbidden = !!err?.forbidden;
  const rows = Array.isArray(q.data) ? q.data : [];
  const committed = rows.reduce((a, r) => a + (r.committed_ex_vat || 0), 0);
  const recognised = rows.reduce((a, r) => a + (r.recognised_ex_vat || 0), 0);
  const open = rows.reduce((a, r) => a + (r.open_ex_vat || 0), 0);
  const pct = committed > 0 ? Math.round((recognised / committed) * 100) : 0;
  const c = oc.data && !(oc.data as any).error ? oc.data : null;

  return (
    <>
      <PageHead
        crumb="Finance · order backlog (M4)"
        title="Backlog"
        sub="The sales-order commitment ledger: every order commits revenue, recognition draws it down at dispatch. Committed = recognised + open — the open figure is the order book still to ship."
      />

      {forbidden && <LayerNote>hidden — requires view:order</LayerNote>}

      {!forbidden && (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(180px,1fr))', gap: 12, marginBottom: 16 }}>
            {q.isLoading && <Card style={{ padding: 16 }}><SkeletonRow cols={1} /></Card>}
            {!q.isLoading && <>
              <Stat label="Committed (ex-VAT)" value={gbp(committed)} sub="all orders placed" />
              <Stat label="Recognised" value={gbp(recognised)} sub={`${pct}% delivered`} />
              <Stat label="Open backlog" value={gbp(open)} sub="committed − recognised" accent />
            </>}
          </div>

          <Card title="By entity" icon={I.grid}>
            <table className="tbl">
              <thead><tr><th>Entity</th><th>Ccy</th><th style={{ textAlign: 'right' }}>Committed</th><th style={{ textAlign: 'right' }}>Recognised</th><th style={{ textAlign: 'right' }}>Open</th></tr></thead>
              <tbody>
                {q.isLoading && <SkeletonRow cols={5} />}
                {!q.isLoading && rows.length === 0 && <EmptyRow cols={5}>No backlog yet.</EmptyRow>}
                {rows.map((r, i) => (
                  <tr key={i}>
                    <td className="dim" style={{ fontFamily: 'var(--font-mono)', fontSize: 11.5 }}>{r.entity_id ? r.entity_id.slice(0, 8) : '—'}</td>
                    <td>{r.currency}</td>
                    <td style={{ textAlign: 'right' }}>{gbp(r.committed_ex_vat)}</td>
                    <td style={{ textAlign: 'right' }}>{gbp(r.recognised_ex_vat)}</td>
                    <td style={{ textAlign: 'right', color: 'var(--accent)' }}>{gbp(r.open_ex_vat)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>

          <Card title="Order commitment" icon={I.charger} aux={<span className="dim" style={{ fontSize: 12 }}>committed vs recognised vs open, per order</span>}>
            <form onSubmit={(e) => { e.preventDefault(); setOrder(input.trim()); }} style={{ display: 'flex', gap: 8, marginBottom: 12 }}>
              <input value={input} onChange={(e) => setInput(e.target.value)} placeholder="order id (UUID)" data-testid="backlog-order"
                style={{ flex: 1, padding: '9px 12px', borderRadius: 8, border: '1px solid var(--border)', background: 'var(--tint)', color: 'inherit', fontFamily: 'var(--font-mono)', fontSize: 13 }} />
              <button type="submit" className="btn" disabled={!input.trim()}>Look up</button>
            </form>
            {order && oc.isLoading && <SkeletonRow cols={1} />}
            {order && !oc.isLoading && !c && <EmptyRow cols={1}>No commitment for that order.</EmptyRow>}
            {c && (
              <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap' }}>
                <div><span className="dim" style={{ fontSize: 12 }}>Committed </span><b>{gbp(c.committed_ex_vat)}</b> ex-VAT</div>
                <div><span className="dim" style={{ fontSize: 12 }}>Recognised </span><b>{gbp(c.recognised_ex_vat)}</b></div>
                <div><span className="dim" style={{ fontSize: 12 }}>Open </span><b style={{ color: 'var(--accent)' }}>{gbp(c.open_ex_vat)}</b></div>
                <Chip s={c.open_ex_vat <= 0 ? 'ok' : 'warn'}>{c.open_ex_vat <= 0 ? 'fully recognised' : 'open'}</Chip>
              </div>
            )}
          </Card>
        </>
      )}
    </>
  );
}
