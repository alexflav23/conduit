import React, { useState, useRef, useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ApiError, request } from './lib/client';
import { marketId } from './api';
import { PageHead, Card, Chip, Money, LayerNote, Skeleton } from './kit/kit';
import { I } from './kit/icons';

// Order Desk (spec/ui/01-order-desk.md · M4 · doc 24): the keyboard-first order-capture console where THE PRICE IS
// NEVER TYPED. Every line binds to a governed tier; the server resolves it. Quote-before-place is the invariant —
// the quote auto-runs as the grid changes (no manual button). A non-tier / out-of-band line surfaces as guidance
// ("nearest tier …"), never a toast error, and routes through Deal Desk as a pending_ceo hold once placed.
//
// Real routes (api/.../routes/PricingRoutes.scala + CommerceRoutes.scala):
//   POST /api/v1/pricing/quote  { channelId, marketId, currency, customerId?, lines:[{sku,qty}] }
//     -> QuoteResp { lines:[{sku,qty,unitPriceExVat,adlpCategory,priceAgreementId,...}],
//                    subtotalExVat, vatTotal, totalIncVat, requiresException }
//     403 -> requires view:price_rule (layer-walled);  422 -> no governed tier (guidance, not failure)
//   POST /api/v1/orders  { type, soldToPartyId, billToPartyId, channelId, marketId, currency, paymentMethod, lines }
//     -> { orderNo, status, ... };  202 when status=pending_ceo;  409 credit block;  422 no tier
//
// DATA-LAYER WALL (doc 05): all money is the `commercial` layer. If the viewer lacks it, the figures COLLAPSE —
// the kit <Money> renders nothing (a LayerNote explains it), never £0.00.

// The seeded demo channel id (matches the seed constant the api.ts helpers post against). marketId() resolves the
// ctx market label ("UK") to its UUID; the demo party ids are created on place.
const DEMO_CHANNEL = '11111111-1111-1111-1111-111111111111';
const COMMERCIAL = 'commercial';

interface Line {
  id: number;
  sku: string;
  qty: string;
}

interface QuoteLineResp {
  sku: string;
  qty: number;
  unitPriceExVat: string;
  adlpCategory: string;
  priceAgreementId?: string | null;
}
interface QuoteResp {
  lines: QuoteLineResp[];
  subtotalExVat: string;
  vatTotal: string;
  totalIncVat: string;
  requiresException: boolean;
}

let SEQ = 1;
const blankLine = (): Line => ({ id: SEQ++, sku: '', qty: '1' });

export function OrderDesk({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const [lines, setLines] = useState<Line[]>([blankLine()]);
  const [placing, setPlacing] = useState(false);
  const [order, setOrder] = useState<any>(null);

  const canSeeMoney = !role?.layers || (role.layers as string[]).indexOf(COMMERCIAL) >= 0;
  const currency: string = ctx?.currency || 'GBP';
  const market = marketId(ctx?.market || 'UK');

  // The clean, quote-able lines: a SKU plus a positive qty. Unit price is NEVER captured — the server resolves it.
  const cleanLines = useMemo(
    () =>
      lines
        .filter((l) => l.sku.trim() !== '' && parseInt(l.qty, 10) > 0)
        .map((l) => ({ sku: l.sku.trim().toUpperCase(), qty: parseInt(l.qty, 10) })),
    [lines],
  );

  // Auto-quote — quote-before-place is continuous. React Query keys on the lines + the order context (entity /
  // market / period), so a grid edit or a context switch refetches; the call is disabled until there's a line.
  const quoteKey = JSON.stringify(cleanLines);
  const q = useQuery<QuoteResp, ApiError>({
    queryKey: ['order-quote', quoteKey, ctx?.entity, ctx?.market, ctx?.period, currency],
    queryFn: () =>
      request<QuoteResp>('/api/v1/pricing/quote', {
        method: 'POST',
        body: JSON.stringify({ channelId: DEMO_CHANNEL, marketId: market, currency, lines: cleanLines }),
      }),
    enabled: cleanLines.length > 0,
  });

  const err = q.error as ApiError | null;
  const forbidden = !!err?.forbidden;
  const notImplemented = !!err?.notImplemented;
  // A 422 (no governed tier) is GUIDANCE, not failure — the body still carries the nearest-tier hint + lines.
  const noTier = !!err && err.status === 422;
  const otherError = !!err && !forbidden && !notImplemented && !noTier;

  const quoteResult: QuoteResp | null = q.data ?? (noTier && err?.body && typeof err.body === 'object' ? (err.body as QuoteResp) : null);
  const noTierMsg =
    noTier && err?.body && typeof err.body === 'object'
      ? ((err.body as any).message ?? (err.body as any).detail ?? 'No governed tier matches these lines.')
      : null;

  const idle = cleanLines.length === 0;
  const loading = !idle && q.isLoading;
  const ready = !idle && !forbidden && !notImplemented && !otherError && (q.isSuccess || (noTier && !!quoteResult));

  const setLine = (id: number, patch: Partial<Line>) =>
    setLines((ls) => ls.map((l) => (l.id === id ? { ...l, ...patch } : l)));
  const removeLine = (id: number) => setLines((ls) => (ls.length > 1 ? ls.filter((l) => l.id !== id) : ls));
  const addLine = () => setLines((ls) => [...ls, blankLine()]);

  // A fresh edit clears the last placed order.
  const onEdit = (id: number, patch: Partial<Line>) => {
    setOrder(null);
    setLine(id, patch);
  };

  // Enter adds the next line and moves focus there (the agent's daily keyboard ceremony) — fixed Tab order.
  const onLineKey = (e: React.KeyboardEvent, idx: number) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      if (idx === lines.length - 1) addLine();
      requestAnimationFrame(() => {
        const next = document.querySelector<HTMLInputElement>(`[data-row="${idx + 1}"] [data-testid="sku"]`);
        next?.focus();
      });
    }
  };

  const qLines: QuoteLineResp[] = quoteResult?.lines || [];
  const requiresException = !!quoteResult?.requiresException || qLines.some((l) => l.adlpCategory === 'exception');
  const periodLocked = ctx?.period && /lock|clos/i.test(String(ctx?.periodStatus || ''));

  const onPlace = () => {
    if (placing || cleanLines.length === 0) return;
    setPlacing(true);
    // The demo flow creates the sold-to / bill-to parties, then places the order (mirrors api.ts placeOrder).
    Promise.all([
      request<{ id: string }>('/api/v1/parties', {
        method: 'POST',
        body: JSON.stringify({ displayName: 'Demo Branch', partyType: 'wholesaler', isOrganization: true }),
      }),
      request<{ id: string }>('/api/v1/parties', {
        method: 'POST',
        body: JSON.stringify({ displayName: 'Demo Master', partyType: 'wholesaler', isOrganization: true }),
      }),
    ])
      .then(([soldTo, billTo]) =>
        request<any>('/api/v1/orders', {
          method: 'POST',
          body: JSON.stringify({
            type: 'trade',
            soldToPartyId: soldTo.id,
            billToPartyId: billTo.id,
            channelId: DEMO_CHANNEL,
            marketId: market,
            currency,
            paymentMethod: 'stripe',
            lines: cleanLines,
          }),
        }),
      )
      .then((json) => {
        setOrder(json);
        if (json?.status === 'pending_ceo') {
          toast(`${json.orderNo} — exception held for CEO decision (Deal Desk)`, 'warn');
        } else {
          toast(`${json.orderNo} placed`, 'ok');
        }
      })
      .catch((e) => {
        if (e instanceof ApiError && e.status === 409) {
          toast('Credit block — this party is over its limit. Finance must extend terms.', 'err');
        } else if (e instanceof ApiError && e.status === 422) {
          toast(e.message || 'A line has no governed tier — resolve it before placing.', 'err');
        } else if (e instanceof ApiError) {
          toast(`Place failed (${e.status})`, 'err');
        } else {
          toast('Place failed — network error', 'err');
        }
      })
      .finally(() => setPlacing(false));
  };

  const adlpChip = (cat: string) =>
    cat === 'exception' ? <Chip s="exception">Exception</Chip> : <Chip s="standard">Standard</Chip>;

  return (
    <div className="page">
      <PageHead
        crumb={`Order Desk · ${ctx?.market || 'UK'} · ${ctx?.entity || 'GBP'}`}
        title="Order Desk"
        sub="Keyboard-first capture — the price is never typed. Every line binds to a governed tier; the server resolves and rejects ad-hoc numbers."
      />

      {/* ---- the multi-line entry grid (SKU · qty · resolved unit · ADLP) ---- */}
      <Card title="Order lines" icon={I.charger} aux={<span className="dim" style={{ fontSize: 11.5 }}>Enter ↵ adds a line</span>}>
        <div className="tablewrap">
          <table className="tbl ord">
            <thead>
              <tr>
                <th style={{ width: 220 }}>SKU</th>
                <th style={{ width: 110 }}>Qty</th>
                <th className="num">Unit ex-VAT</th>
                <th>ADLP</th>
                <th style={{ width: 44 }} />
              </tr>
            </thead>
            <tbody>
              {lines.map((l, idx) => {
                const ql = qLines.find((qq) => qq.sku === l.sku.trim().toUpperCase() && (parseInt(l.qty, 10) || 0) === qq.qty);
                return (
                  <tr key={l.id} data-row={idx}>
                    <td>
                      <input
                        className="fld"
                        data-testid="sku"
                        style={{ width: '100%', textTransform: 'uppercase' }}
                        value={l.sku}
                        placeholder="e.g. HV-310"
                        onChange={(e) => onEdit(l.id, { sku: e.target.value })}
                        onKeyDown={(e) => onLineKey(e, idx)}
                      />
                    </td>
                    <td>
                      <input
                        className="fld"
                        data-testid="qty"
                        style={{ width: '100%' }}
                        inputMode="numeric"
                        value={l.qty}
                        onChange={(e) => onEdit(l.id, { qty: e.target.value.replace(/[^0-9]/g, '') })}
                        onKeyDown={(e) => onLineKey(e, idx)}
                      />
                    </td>
                    <td className="num" data-testid="resolved-unit">
                      {ql ? (
                        canSeeMoney ? <Money value={ql.unitPriceExVat} ccy={currency} /> : <span className="dim">hidden</span>
                      ) : (
                        <span className="dim">—</span>
                      )}
                    </td>
                    <td>{ql ? adlpChip(ql.adlpCategory) : <span className="dim">—</span>}</td>
                    <td>
                      {lines.length > 1 && (
                        <span className="ibtn" title="Remove line" onClick={() => { setOrder(null); removeLine(l.id); }} style={{ cursor: 'pointer' }}>
                          {I.x({ size: 14 })}
                        </span>
                      )}
                    </td>
                  </tr>
                );
              })}
              <tr className="addrow" onClick={addLine}>
                <td colSpan={5} style={{ cursor: 'pointer', color: 'var(--accent)', fontWeight: 600 }}>
                  {I.charger({ size: 13 })} Add line
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </Card>

      {/* ---- the quote panel: ex-VAT · VAT · total-inc-VAT · tier + ADLP chips ---- */}
      <Card title="Quote" icon={I.scale} style={{ maxWidth: 620 }}>
        {idle && (
          <div className="dim" style={{ padding: '6px 2px', fontSize: 13 }} data-testid="quote-empty">
            Add a SKU and quantity — the quote resolves automatically.
          </div>
        )}
        {loading && (
          <div data-testid="quote-loading">
            <Skeleton lines={3} />
          </div>
        )}
        {forbidden && (
          <LayerNote>Pricing hidden — requires the <b>commercial</b> data layer.</LayerNote>
        )}
        {notImplemented && (
          <div style={{ display: 'grid', placeItems: 'center', gap: 10, padding: '26px 20px', textAlign: 'center' }} data-testid="quote-unbacked">
            <span style={{ width: 42, height: 42, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'var(--panel-2)' }}>{I.scale({ size: 20 })}</span>
            <div style={{ fontFamily: 'var(--font-disp)', fontSize: 17, fontWeight: 600 }}>Not available in this environment yet</div>
            <div className="dim" style={{ fontSize: 12.5, maxWidth: 420 }}>The pricing engine isn’t wired here yet — quotes resolve once the catalogue and price tiers are seeded.</div>
          </div>
        )}
        {otherError && (
          <div className="banner danger" data-testid="quote-error">
            {I.alert({ size: 16 })}
            <span>Could not resolve a quote (HTTP {err?.status}). Try again.</span>
          </div>
        )}
        {ready && quoteResult && (
          <div data-testid="quote">
            {/* Non-tier guidance — teaching, not an error. The hero shows WHY this line is out of band. */}
            {(requiresException || noTier) && (
              <div className="banner warn" style={{ marginBottom: 14 }} data-testid="nontier-guidance">
                {I.flag({ size: 16 })}
                <span>
                  <span className="bb">Out-of-band line.</span> {noTierMsg || 'No governed tier covers this price.'} Placing it raises an
                  ADLP price-tier request — it holds at <b>pending_ceo</b> on the Deal Desk and won&apos;t ship until approved.
                </span>
              </div>
            )}

            {!canSeeMoney ? (
              <LayerNote>Totals hidden — requires the <b>commercial</b> data layer.</LayerNote>
            ) : (
              <>
                <div className="kvrow">
                  <span className="dim">Resolved ex-VAT</span>
                  <span data-testid="subtotal-ex-vat"><Money value={quoteResult.subtotalExVat} ccy={currency} layer={COMMERCIAL} role={role} /></span>
                </div>
                <div className="kvrow">
                  <span className="dim">VAT</span>
                  <span data-testid="vat-total"><Money value={quoteResult.vatTotal} ccy={currency} layer={COMMERCIAL} role={role} /></span>
                </div>
                <div className="kvrow">
                  <span className="dim">Total inc VAT</span>
                  <span
                    data-testid="total-inc-vat"
                    style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 700 }}
                    className="num"
                  >
                    <Money value={quoteResult.totalIncVat} ccy={currency} layer={COMMERCIAL} role={role} />
                  </span>
                </div>
              </>
            )}

            {qLines.length === 0 ? (
              <div className="dim" style={{ padding: '6px 2px', fontSize: 12.5 }} data-testid="quote-no-lines">
                No resolvable lines in this quote.
              </div>
            ) : (
              /* tier + ADLP category — first-class, never hidden behind the total */
              <div className="kvrow" style={{ borderBottom: 'none', alignItems: 'flex-start' }}>
                <span className="dim">Tier · ADLP</span>
                <div className="row g8 wrap" style={{ justifyContent: 'flex-end' }}>
                  {qLines.map((ql, i) => (
                    <span key={i} className="row g6" data-testid="tier-chip">
                      <span className="mono dim" style={{ fontSize: 11 }}>{ql.sku}</span>
                      {ql.priceAgreementId ? <Chip s="approved">tier</Chip> : <Chip s="open">open list</Chip>}
                      {adlpChip(ql.adlpCategory)}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </Card>

      {/* ---- place ---- */}
      <Card style={{ maxWidth: 620 }}>
        <div className="row between">
          <span className="dim" style={{ fontSize: 12.5 }}>
            {periodLocked
              ? 'Period is read-only — orders post to the open period.'
              : 'Quote-before-place: lines must resolve to a governed tier.'}
          </span>
          <button
            className={'btn primary'}
            data-testid="place-btn"
            disabled={placing || !ready || cleanLines.length === 0}
            onClick={onPlace}
            title={!ready ? 'Resolve a quote first' : ''}
          >
            {placing ? I.refresh({ size: 14 }) : I.check({ size: 14 })}
            {requiresException ? ' Place (raises exception)' : ' Place order'}
          </button>
        </div>
      </Card>

      {/* ---- placed confirmation: order number + status chip; pending_ceo is loud ---- */}
      {order && (
        <Card style={{ maxWidth: 620 }}>
          <div className="confirm">
            <div className={'big' + (order.status === 'pending_ceo' ? ' warn' : '')}>
              {order.status === 'pending_ceo' ? I.flag() : I.check()}
            </div>
            <div style={{ fontFamily: 'var(--font-disp)', fontSize: 24, fontWeight: 600 }} data-testid="order-no">
              {order.orderNo}
            </div>
            <div className="dim" style={{ fontSize: 13.5, marginTop: 6, maxWidth: 440, marginInline: 'auto', lineHeight: 1.5 }}>
              {order.status === 'pending_ceo'
                ? 'An ADLP exception was raised — these lines are held for CEO decision on the Deal Desk and will not ship until approved.'
                : 'Confirmed and released to fulfilment.'}
            </div>
          </div>
          <div className="kvrow" style={{ borderBottom: 'none', justifyContent: 'center' }}>
            <Chip s={order.status}><span data-testid="order-status">{order.status === 'pending_ceo' ? 'Pending CEO' : order.status}</span></Chip>
          </div>
        </Card>
      )}
    </div>
  );
}
