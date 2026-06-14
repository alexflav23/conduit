import React, { useState } from 'react';
import { useApi } from './lib/query';
import { PageHead, Card, Chip, Money, AuditRef, EmptyRow, LayerNote, SkeletonRow, Skeleton } from './kit/kit';
import { I } from './kit/icons';

// Lifecycle — the event-sourced order reconstruction (doc 20 D21 / spec/ui/09-lifecycle.md). The order is the
// root; this REPLAYS the immutable event stream into (a) the per-invoice collection cycles and (b) a true
// chronological timeline. Each event carries its ORIGIN (user / consumer / relay) so human vs machine causation
// is plain, and expands to the payload / the figures it moved (AuditRef → ledger where money). The hero is the
// timeline as truth: the order is rebuildable from events alone.
//
// Backend: GET /api/v1/orders/{id}/lifecycle (OrderLifecycleRoutes) → { order_id, timeline, cycles }. Auto-loads
// via React Query keyed on the committed order id — no manual Load/Refresh button; the search box IS the
// navigation, and a new id refetches. View-gated on `order`; the cycle money is commercial-layer (collapses for
// a principal lacking it). An invalid UUID returns 400 → inline error.

// Render the stored UTC instant as a complete, timezone-explicit timestamp (e.g. "2026-09-10 09:30:00 UTC").
function utc(iso: string | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())} ${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())} UTC`;
}

// Origin → chip palette. user is a person, consumer/relay are machines (event-driven causation).
const ORIGIN_CHIP: Record<string, string> = { user: 'accent', consumer: 'plum', relay: 'neutral' };

// A loose scan of an event payload for "the figures it moved" — anything that looks monetary. The data-layer wall
// is enforced server-side (a withheld layer never arrives), so whatever the payload carries is renderable; we tag
// each figure with the layer the spec assigns it so the kit Money can still collapse for a viewer who lacks it.
const MONEY_KEYS: Array<{ key: RegExp; label: string; layer: string }> = [
  { key: /total|gross|net|amount|subtotal|due|outstanding/i, label: 'amount', layer: 'commercial' },
  { key: /paid|collected|settled|refund/i, label: 'settled', layer: 'commercial' },
  { key: /margin|cost|profit/i, label: 'margin', layer: 'profitability' },
  { key: /commission/i, label: 'commission', layer: 'commission' },
];

function figuresOf(payload: any): Array<{ label: string; key: string; value: any; layer: string }> {
  if (!payload || typeof payload !== 'object') return [];
  const out: Array<{ label: string; key: string; value: any; layer: string }> = [];
  Object.keys(payload).forEach((k) => {
    const v = payload[k];
    if (v == null || typeof v === 'object') return;
    const n = typeof v === 'number' ? v : typeof v === 'string' && /^-?\d+(\.\d+)?$/.test(v) ? parseFloat(v) : NaN;
    if (isNaN(n)) return;
    const hit = MONEY_KEYS.find((m) => m.key.test(k));
    if (hit) out.push({ label: k, key: k, value: n, layer: hit.layer });
  });
  return out;
}

interface Lifecycle {
  order_id?: string;
  timeline?: any[];
  cycles?: any[];
}

const SEED_ID = '33333333-3333-3333-3333-333333333333';

// An honest "endpoint not built" panel (404). Distinct from a stuck skeleton or a £0.
function NotAvailable() {
  return (
    <div
      data-testid="life-not-available"
      style={{ padding: '28px 18px', textAlign: 'center', color: 'var(--muted)', border: '1px dashed var(--border)', borderRadius: 10, background: 'var(--bg-2)' }}
    >
      <div style={{ marginBottom: 6, color: 'var(--faint)' }}>{I.list({ size: 22 })}</div>
      <div style={{ fontWeight: 600, fontSize: 13.5, color: 'var(--text)' }}>Not available in this environment yet</div>
      <div className="dim" style={{ fontSize: 12, marginTop: 4 }}>The order lifecycle endpoint isn't built in this deployment.</div>
    </div>
  );
}

export function Lifecycle({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  // The order id is the only navigable input. `query` is the live input; `orderId` is the committed value the
  // query is keyed on (so typing doesn't refetch — only ↵ does).
  const [orderId, setOrderId] = useState(SEED_ID);
  const [query, setQuery] = useState(SEED_ID);
  const [expanded, setExpanded] = useState<Record<number, boolean>>({});

  const id = orderId.trim();
  const q = useApi<Lifecycle>(['order-lifecycle', id], `/api/v1/orders/${encodeURIComponent(id)}/lifecycle`, {
    enabled: id.length > 0,
  });

  const err = q.error;
  const forbidden = err?.forbidden ?? false;
  const notImpl = err?.notImplemented ?? false;
  // A 400 (invalid UUID) or any other non-forbidden/non-404 error is a real inline error.
  const otherError = !!err && !forbidden && !notImpl;

  const cycles: any[] = q.data?.cycles ?? [];
  const timeline: any[] = q.data?.timeline ?? [];
  const ready = !q.isLoading && !err && id.length > 0;

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    const next = query.trim();
    setExpanded({});
    if (next === orderId) q.refetch();
    else setOrderId(next);
  };

  const toggle = (i: number) => setExpanded((m) => ({ ...m, [i]: !m[i] }));

  return (
    <div className="page" style={{ maxWidth: 1320 }}>
      <PageHead
        crumb="Order lifecycle · replayed from the immutable event log"
        title="Lifecycle"
        sub="The order is the root. Its event stream replays into per-invoice collection cycles and a perfect chronological log — each event tagged with its origin (user · consumer · relay) so you can see exactly what happened, and who or what did it."
        right={
          <form onSubmit={submit} style={{ margin: 0 }}>
            <div className="search" style={{ width: 360 }}>
              {I.search()}
              <input
                data-testid="life-order-id"
                placeholder="Order id…"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
              />
              <kbd style={{ fontFamily: 'var(--font-mono)', fontSize: 10, border: '1px solid var(--border)', borderRadius: 5, padding: '1px 6px', color: 'var(--faint)' }}>↵</kbd>
            </div>
          </form>
        }
      />

      {/* The route returns only { order_id, timeline, cycles }; the order id is the stable header anchor. */}
      {ready && (
        <div className="row between" style={{ padding: '13px 16px', border: '1px solid var(--border)', borderRadius: 12, background: 'var(--bg-2)', marginBottom: 14 }} data-testid="life-order-header">
          <div className="row g12">
            <span style={{ width: 38, height: 38, borderRadius: 11, display: 'grid', placeItems: 'center', background: 'var(--accent-subtle)', color: 'var(--accent-bright)', flex: '0 0 38px' }}>{I.list({ size: 18 })}</span>
            <div>
              <div className="mono" style={{ fontSize: 14, fontWeight: 600 }}>{q.data?.order_id || id}</div>
              <div className="dim" style={{ fontSize: 12 }}>reconstructed from {timeline.length} event{timeline.length === 1 ? '' : 's'}</div>
            </div>
          </div>
        </div>
      )}

      {notImpl ? (
        <NotAvailable />
      ) : (
        <>
          <Card title="Collection cycles" icon={I.clock} aux={<span className="dim" style={{ fontSize: 12 }}>one per invoice on this order</span>} style={{ marginBottom: 14, padding: 0 }} className="tablewrap">
            <table className="tbl" data-testid="life-cycles">
              <thead><tr>
                <th>#</th><th>Invoice</th><th>State</th><th className="num">Total</th><th className="num">Paid</th>
                <th className="num">Refunded</th><th className="num">Outstanding</th><th>Void / replaced-by</th>
              </tr></thead>
              <tbody>
                {q.isLoading && Array.from({ length: 2 }).map((_, i) => <SkeletonRow key={i} cols={8} />)}
                {forbidden && <tr><td colSpan={8} style={{ padding: 14 }}><LayerNote>hidden — requires <b>commercial</b></LayerNote></td></tr>}
                {otherError && <EmptyRow cols={8}>Couldn't load this order's collection cycles — {err?.message || 'check the order id and try again.'}</EmptyRow>}
                {ready && cycles.length === 0 && <EmptyRow cols={8}>No invoices issued yet — nothing to collect.</EmptyRow>}
                {ready && cycles.map((c, i) => (
                  <tr key={i} data-testid="life-cycle-row">
                    <td>{c.cycle}</td>
                    <td><b className="mono" style={{ fontSize: 11.5 }}>{c.invoice_no}</b>{c.credit_note_no && <span className="dim"> · CN {c.credit_note_no}</span>}</td>
                    <td><Chip s={c.status}>{c.status}</Chip></td>
                    <td className="num"><Money value={c.total} ccy={c.currency} layer="commercial" role={role} /></td>
                    <td className="num"><Money value={c.paid} ccy={c.currency} layer="commercial" role={role} /></td>
                    <td className="num"><Money value={c.refunded} ccy={c.currency} layer="commercial" role={role} /></td>
                    <td className="num"><Money value={c.outstanding} ccy={c.currency} layer="commercial" role={role} /></td>
                    <td className="dim">{c.void_kind ? `${c.void_kind}: ${c.void_reason ?? ''}` : ''}{c.replaced_by ? ` → ${c.replaced_by}` : ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>

          <Card title="Event timeline" icon={I.list} aux={<span className="dim" style={{ fontSize: 12 }}>append-only, chronological — the perfect log · click an event to expand its payload</span>}>
            {q.isLoading && <div style={{ padding: '4px 0' }}><Skeleton lines={6} /></div>}
            {forbidden && <LayerNote>hidden — requires <b>view:order</b></LayerNote>}
            {otherError && <div className="dim">Couldn't replay the event log for this order — {err?.message || 'check the order id and try again.'}</div>}
            {ready && timeline.length === 0 && <div className="dim" data-testid="life-empty">No events recorded for this order.</div>}
            {ready && timeline.length > 0 && (
              <div className="tl" data-testid="life-timeline">
                {timeline.map((e, i) => {
                  const figs = figuresOf(e.payload);
                  const isOpen = !!expanded[i];
                  return (
                    <div className="ev" key={i} data-testid="life-event" style={{ flexWrap: 'wrap' }}>
                      <span className="seq">{e.seq}</span>
                      <span className="when" data-testid="life-when">{utc(e.occurred_at)}</span>
                      <span
                        className="etype"
                        style={{ cursor: 'pointer', display: 'inline-flex', alignItems: 'center', gap: 5 }}
                        onClick={() => toggle(i)}
                        data-testid="life-event-toggle"
                      >
                        {I.chevR({ size: 13, style: { transform: isOpen ? 'rotate(90deg)' : 'none', transition: 'transform var(--fast)', color: 'var(--faint)' } })}
                        {e.event_type}
                      </span>
                      {e.origin && <span className={'chip ' + (ORIGIN_CHIP[e.origin] || 'neutral')} data-testid="life-origin" style={{ padding: '1px 8px', fontSize: 10.5 }}><span className="d" />{e.origin}</span>}
                      {e.invoice_no && <span className="dim" style={{ fontSize: 11.5 }}>{e.invoice_no}</span>}
                      {(e.transfer_id || e.event_id) && <AuditRef id={String(e.transfer_id || e.event_id).slice(0, 8)} />}
                      {e.correlation_id && <span className="corr">⛓ {String(e.correlation_id).slice(0, 8)}</span>}

                      {isOpen && (
                        <div style={{ flexBasis: '100%', marginTop: 8, marginLeft: 35 }} data-testid="life-event-detail">
                          {figs.length > 0 && (
                            <div className="row g10 wrap" style={{ marginBottom: e.payload ? 9 : 0 }}>
                              {figs.map((f) => (
                                <span key={f.key} className="row g6" style={{ alignItems: 'baseline' }}>
                                  <span className="dim" style={{ fontSize: 11 }}>{f.label}</span>
                                  <Money value={f.value} ccy={e.payload?.currency} layer={f.layer} role={role} />
                                </span>
                              ))}
                            </div>
                          )}
                          <pre
                            className="mono"
                            style={{ margin: 0, padding: '10px 12px', background: 'var(--bg-2)', border: '1px solid var(--border)', borderRadius: 9, fontSize: 11, color: 'var(--muted)', overflowX: 'auto', whiteSpace: 'pre-wrap', maxHeight: 240 }}
                          >
                            {e.payload ? JSON.stringify(e.payload, null, 2) : 'no payload recorded for this event'}
                          </pre>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </Card>
        </>
      )}
    </div>
  );
}
