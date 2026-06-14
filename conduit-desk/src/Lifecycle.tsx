import React, { useState, useEffect, useCallback } from 'react';
import { getOrderLifecycle } from './api';
import { PageHead, Card, Chip, Money, AuditRef, EmptyRow, LayerNote, SkeletonRow, Skeleton, useToast } from './kit/kit';
import { I } from './kit/icons';

// Lifecycle — the event-sourced order reconstruction (doc 20 D21 / spec/ui/09-lifecycle.md). The order is the
// root; this REPLAYS the immutable event stream into (a) the per-invoice collection cycles and (b) a true
// chronological timeline. Each event carries its ORIGIN (user / consumer / relay) so human vs machine causation
// is plain, and expands to the payload / the figures it moved (AuditRef → ledger where money). The hero is the
// timeline as truth: the order is rebuildable from events alone.
//
// Backend: GET /orders/{id}/lifecycle  (api.ts: getOrderLifecycle). Auto-loads on mount + whenever the entered
// order id changes — there is no manual Load/Refresh button; the search box IS the navigation.

type State = 'loading' | 'ready' | 'empty' | 'forbidden' | 'error';

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

export function Lifecycle({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  // Seed from a flow-friendly id; the order id is the only navigable input.
  const [orderId, setOrderId] = useState('33333333-3333-3333-3333-333333333333');
  const [query, setQuery] = useState('33333333-3333-3333-3333-333333333333');
  const [state, setState] = useState<State>('loading');
  const [data, setData] = useState<any | null>(null);
  const [expanded, setExpanded] = useState<Record<number, boolean>>({});
  const [toastNode] = useToast();

  const load = useCallback(async (id: string) => {
    if (!id.trim()) { setState('empty'); setData(null); return; }
    setState('loading');
    setExpanded({});
    const r = await getOrderLifecycle(authForLoad(role), id.trim());
    if (r.status === 403) { setState('forbidden'); setData(null); return; }
    if (r.status === 404 || (r.status === 200 && r.json == null)) { setState('empty'); setData(null); return; }
    if (r.status !== 200) { setState('error'); setData(null); return; }
    setData(r.json);
    setState('ready');
  }, [role]);

  // Auto-load on mount and whenever the committed order id changes (no manual Load button).
  useEffect(() => { load(orderId); }, [orderId, load]);

  const cycles: any[] = data?.cycles ?? [];
  const timeline: any[] = data?.timeline ?? [];
  const ord = data?.order;

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (query.trim() === orderId) load(orderId);
    else setOrderId(query.trim());
  };

  const toggle = (i: number) => setExpanded((m) => ({ ...m, [i]: !m[i] }));

  return (
    <div className="page" style={{ maxWidth: 1320 }}>
      {toastNode}
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

      {/* loaded-order header */}
      {state === 'ready' && (
        <div className="row between" style={{ padding: '13px 16px', border: '1px solid var(--border)', borderRadius: 12, background: 'var(--bg-2)', marginBottom: 14 }} data-testid="life-order-header">
          <div className="row g12">
            <span style={{ width: 38, height: 38, borderRadius: 11, display: 'grid', placeItems: 'center', background: 'var(--accent-subtle)', color: 'var(--accent-bright)', flex: '0 0 38px' }}>{I.list({ size: 18 })}</span>
            <div>
              <div className="mono" style={{ fontSize: 14, fontWeight: 600 }}>{ord?.order_no || orderId}</div>
              <div className="dim" style={{ fontSize: 12 }}>{[ord?.customer, ord?.branch, ord?.po].filter(Boolean).join(' · ') || 'reconstructed from events'}</div>
            </div>
          </div>
          <div className="row g10">
            {ord?.status && <Chip s={ord.status}>{ord.status}</Chip>}
            {ord?.total != null && <Money value={ord.total} ccy={ord?.currency} layer="commercial" role={role} />}
          </div>
        </div>
      )}

      <Card title="Collection cycles" icon={I.clock} aux={<span className="dim" style={{ fontSize: 12 }}>one per invoice on this order</span>} style={{ marginBottom: 14, padding: 0 }} className="tablewrap">
        <table className="tbl" data-testid="life-cycles">
          <thead><tr>
            <th>#</th><th>Invoice</th><th>State</th><th className="num">Total</th><th className="num">Paid</th>
            <th className="num">Refunded</th><th className="num">Outstanding</th><th>Void / replaced-by</th>
          </tr></thead>
          <tbody>
            {state === 'loading' && Array.from({ length: 2 }).map((_, i) => <SkeletonRow key={i} cols={8} />)}
            {state === 'forbidden' && <tr><td colSpan={8} style={{ padding: 14 }}><LayerNote>hidden — requires <b>commercial</b></LayerNote></td></tr>}
            {state === 'error' && <EmptyRow cols={8}>Couldn't load this order's collection cycles. Try again.</EmptyRow>}
            {state === 'empty' && <EmptyRow cols={8}>Unknown order — no collection cycles to replay.</EmptyRow>}
            {state === 'ready' && cycles.length === 0 && <EmptyRow cols={8}>No invoices issued yet — nothing to collect.</EmptyRow>}
            {state === 'ready' && cycles.map((c, i) => (
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
        {state === 'loading' && <div style={{ padding: '4px 0' }}><Skeleton lines={6} /></div>}
        {state === 'forbidden' && <LayerNote>hidden — requires <b>volume</b></LayerNote>}
        {state === 'error' && <div className="dim">Couldn't replay the event log for this order. Try again.</div>}
        {state === 'empty' && <div className="dim" data-testid="life-empty">Unknown order — no events to reconstruct.</div>}
        {state === 'ready' && timeline.length === 0 && <div className="dim">No events recorded for this order.</div>}
        {state === 'ready' && timeline.length > 0 && (
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
    </div>
  );
}

// The shell passes role; the api client reads the live bearer from sessionStorage, so the second arg is a
// fallback only. Prefer the viewer's own token when present on role.
function authForLoad(role: any): string {
  return (role && role.token) || '';
}
