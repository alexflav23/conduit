import React, { useState } from 'react';
import { getOrderLifecycle } from './api';
import { PageHead, Card, Chip, LoadBar } from './kit/kit';
import { I } from './kit/icons';

// The Order Collection Ledger desk (M13 doc 13 §void / spec/ui/11-lifecycle.md). The order is the root; this
// REPLAYS the immutable event stream for one order into a readable ledger: the per-invoice collection cycles
// (issued → collected → voided → refunded → replaced-by) and the chronological event timeline (the "perfect
// log"). Ported to the desk kit (.tbl + Chip), testids preserved.

// Render the stored UTC instant as a complete, timezone-explicit timestamp (e.g. "2026-09-10 09:30:00 UTC").
function utc(iso: string | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())} ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())} UTC`;
}

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
    <>
      <PageHead
        title="Order lifecycle"
        sub="The order collection ledger — replayed from the immutable event log"
        right={
          <LoadBar>
            <span className="dim">Order id</span>
            <input className="fld" style={{ width: 300 }} data-testid="life-order-id" value={orderId} onChange={(e) => setOrderId(e.target.value)} />
            <button className="btn primary" data-testid="life-load" onClick={load}>Load lifecycle</button>
            {err && <span className="dim" data-testid="life-err">{err}</span>}
          </LoadBar>
        }
      />

      <Card title="Collection cycles" icon={I.clock} aux={<span className="dim" style={{ fontSize: 12 }}>one per invoice on this order</span>}>
        <div className="tablewrap">
          <table className="tbl" data-testid="life-cycles">
            <thead><tr>
              <th>#</th><th>Invoice</th><th>State</th><th className="num">Total</th><th className="num">Paid</th>
              <th className="num">Refunded</th><th className="num">Outstanding</th><th>Void / replaced-by</th>
            </tr></thead>
            <tbody>
              {cycles.map((c, i) => (
                <tr key={i} data-testid="life-cycle-row">
                  <td>{c.cycle}</td>
                  <td className="mono">{c.invoice_no}{c.credit_note_no && <span className="dim"> · CN {c.credit_note_no}</span>}</td>
                  <td>{c.status === 'void' ? <Chip s="danger">void</Chip> : c.status === 'paid' ? <Chip s="paid">paid</Chip> : <Chip s={c.status}>{c.status}</Chip>}</td>
                  <td className="num">{m(c.total)}</td>
                  <td className="num">{m(c.paid)}</td>
                  <td className="num">{m(c.refunded)}</td>
                  <td className="num">{m(c.outstanding)}</td>
                  <td className="dim">{c.void_kind ? `${c.void_kind}: ${c.void_reason ?? ''}` : ''}{c.replaced_by ? ` → ${c.replaced_by}` : ''}</td>
                </tr>
              ))}
              {data && cycles.length === 0 && <tr><td className="dim" colSpan={8} style={{ padding: '14px 12px' }}>No collection cycles for this order.</td></tr>}
            </tbody>
          </table>
        </div>
      </Card>

      <Card title="Event timeline" icon={I.list} aux={<span className="dim" style={{ fontSize: 12 }}>append-only, chronological — the perfect log</span>}>
        <div data-testid="life-timeline">
          {timeline.map((e, i) => (
            <div key={i} className="ev" data-testid="life-event" style={{ display: 'flex', gap: 11, alignItems: 'baseline', padding: '5px 0', borderBottom: '1px solid var(--border)', fontSize: 13 }}>
              <span className="dim num" style={{ width: 36 }}>{e.seq}</span>
              <span className="dim mono" style={{ minWidth: 200, fontSize: 12 }} data-testid="life-when">{utc(e.occurred_at)}</span>
              <span style={{ fontWeight: 600, minWidth: 170 }}>{e.event_type}</span>
              {e.origin && <span className="aref" style={{ fontSize: 10.5, color: 'var(--accent)' }} data-testid="life-origin">{e.origin}</span>}
              {e.invoice_no && <span className="dim">{e.invoice_no}</span>}
              {e.correlation_id && <span className="mono" style={{ color: 'var(--accent)', fontSize: 11.5 }}>⛓ {String(e.correlation_id).slice(0, 8)}</span>}
            </div>
          ))}
          {data && timeline.length === 0 && <span className="dim">No events recorded for this order.</span>}
        </div>
      </Card>
    </>
  );
}
