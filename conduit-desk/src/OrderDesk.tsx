import React, { useState, useEffect, useRef, useCallback } from 'react';
import { quote, placeOrder, QuoteLine } from './api';
import { PageHead, Card, Chip, Money, LayerNote, Skeleton } from './kit/kit';
import { I } from './kit/icons';

// Order Desk (spec/ui/01-order-desk.md · M4 · doc 24): the keyboard-first order-capture console where THE PRICE IS
// NEVER TYPED. Every line binds to a governed tier; the server resolves it. Quote-before-place is the invariant —
// the quote auto-runs as the grid changes (no manual button). A non-tier / out-of-band line surfaces as guidance
// ("nearest tier …"), never a toast error, and routes through Deal Desk as a pending_ceo hold once placed.
//
// DATA-LAYER WALL (doc 05): all money is the `commercial` layer. If the viewer lacks it, the figures COLLAPSE —
// the kit <Money> renders nothing (a LayerNote explains it), never £0.00.

interface Line {
  id: number;
  sku: string;
  qty: string;
}

const COMMERCIAL = 'commercial';
let SEQ = 1;
const blankLine = (): Line => ({ id: SEQ++, sku: '', qty: '1' });

export function OrderDesk({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const [lines, setLines] = useState<Line[]>([blankLine()]);
  const [quoteResult, setQuoteResult] = useState<any>(null);
  const [state, setState] = useState<'idle' | 'loading' | 'ready' | 'forbidden' | 'error'>('idle');
  const [errMsg, setErrMsg] = useState<string | null>(null);
  const [placing, setPlacing] = useState(false);
  const [order, setOrder] = useState<any>(null);

  const token: string = role?.token || '';
  const canSeeMoney = !role?.layers || (role.layers as string[]).indexOf(COMMERCIAL) >= 0;

  // The clean, quote-able lines: a SKU plus a positive qty. Unit price is NEVER captured — the server resolves it.
  const cleanLines = useCallback(
    (): QuoteLine[] =>
      lines
        .filter((l) => l.sku.trim() !== '' && parseInt(l.qty, 10) > 0)
        .map((l) => ({ sku: l.sku.trim().toUpperCase(), qty: parseInt(l.qty, 10) })),
    [lines],
  );

  // Auto-quote — debounced, re-runs on any grid change or when the order context (entity/market/period) moves.
  // No Get-quote button: quote-before-place is continuous. A fresh edit clears the last placed order.
  const debounce = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    const cl = cleanLines();
    setOrder(null);
    if (cl.length === 0) {
      setQuoteResult(null);
      setState('idle');
      setErrMsg(null);
      return;
    }
    setState('loading');
    if (debounce.current) clearTimeout(debounce.current);
    debounce.current = setTimeout(() => {
      quote(token, cl)
        .then(({ status, json }) => {
          if (status === 200) {
            setQuoteResult(json);
            setErrMsg(null);
            setState('ready');
          } else if (status === 403) {
            setState('forbidden');
          } else if (status === 422) {
            // Non-tier rejection — guidance, not failure. Surface the server's nearest-tier hint in the panel.
            setQuoteResult(json);
            setErrMsg(json?.message || json?.detail || 'No governed tier matches these lines.');
            setState('ready');
          } else {
            setErrMsg(`Quote failed (${status})`);
            setState('error');
          }
        })
        .catch(() => setState('error'));
    }, 280);
    return () => {
      if (debounce.current) clearTimeout(debounce.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [JSON.stringify(cleanLines()), ctx?.entity, ctx?.market, ctx?.period, token]);

  const setLine = (id: number, patch: Partial<Line>) =>
    setLines((ls) => ls.map((l) => (l.id === id ? { ...l, ...patch } : l)));
  const removeLine = (id: number) => setLines((ls) => (ls.length > 1 ? ls.filter((l) => l.id !== id) : ls));
  const addLine = () => setLines((ls) => [...ls, blankLine()]);

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

  const qLines: any[] = quoteResult?.lines || [];
  const requiresException = !!quoteResult?.requiresException || qLines.some((l) => l.adlpCategory === 'exception');
  const periodLocked = ctx?.period && /lock|clos/i.test(String(ctx?.periodStatus || ''));

  const onPlace = () => {
    if (placing) return;
    const cl = cleanLines();
    if (cl.length === 0) return;
    setPlacing(true);
    placeOrder(token, cl)
      .then(({ status, json }) => {
        if (status === 201 || status === 202) {
          setOrder(json);
          if (json?.status === 'pending_ceo') {
            toast(`${json.orderNo} — exception held for CEO decision (Deal Desk)`, 'warn');
          } else {
            toast(`${json.orderNo} placed`, 'ok');
          }
        } else if (status === 409) {
          toast('Credit block — this party is over its limit. Finance must extend terms.', 'err');
        } else if (status === 422) {
          toast(json?.message || 'A line has no governed tier — resolve it before placing.', 'err');
        } else {
          toast(`Place failed (${status})`, 'err');
        }
      })
      .catch(() => toast('Place failed — network error', 'err'))
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
                const ql = qLines.find((q) => q.sku === l.sku.trim().toUpperCase() && (parseInt(l.qty, 10) || 0) === q.qty);
                return (
                  <tr key={l.id} data-row={idx}>
                    <td>
                      <input
                        className="fld"
                        data-testid="sku"
                        style={{ width: '100%', textTransform: 'uppercase' }}
                        value={l.sku}
                        placeholder="e.g. HV-310"
                        onChange={(e) => setLine(l.id, { sku: e.target.value })}
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
                        onChange={(e) => setLine(l.id, { qty: e.target.value.replace(/[^0-9]/g, '') })}
                        onKeyDown={(e) => onLineKey(e, idx)}
                      />
                    </td>
                    <td className="num" data-testid="resolved-unit">
                      {ql ? (
                        canSeeMoney ? <Money value={ql.unitPriceExVat} ccy={ctx?.currency || 'GBP'} /> : <span className="dim">hidden</span>
                      ) : (
                        <span className="dim">—</span>
                      )}
                    </td>
                    <td>{ql ? adlpChip(ql.adlpCategory) : <span className="dim">—</span>}</td>
                    <td>
                      {lines.length > 1 && (
                        <span className="ibtn" title="Remove line" onClick={() => removeLine(l.id)} style={{ cursor: 'pointer' }}>
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
        {state === 'idle' && (
          <div className="dim" style={{ padding: '6px 2px', fontSize: 13 }} data-testid="quote-empty">
            Add a SKU and quantity — the quote resolves automatically.
          </div>
        )}
        {state === 'loading' && (
          <div data-testid="quote-loading">
            <Skeleton lines={3} />
          </div>
        )}
        {state === 'forbidden' && (
          <LayerNote>Pricing hidden — requires the <b>commercial</b> data layer.</LayerNote>
        )}
        {state === 'error' && (
          <div className="banner danger" data-testid="quote-error">
            {I.alert({ size: 16 })}
            <span>{errMsg || 'Could not resolve a quote. Try again.'}</span>
          </div>
        )}
        {state === 'ready' && quoteResult && (
          <div data-testid="quote">
            {/* Non-tier guidance — teaching, not an error. The hero shows WHY this line is out of band. */}
            {requiresException && (
              <div className="banner warn" style={{ marginBottom: 14 }} data-testid="nontier-guidance">
                {I.flag({ size: 16 })}
                <span>
                  <span className="bb">Out-of-band line.</span> {errMsg || 'No governed tier covers this price.'} Placing it raises an
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
                  <span data-testid="subtotal-ex-vat"><Money value={quoteResult.subtotalExVat} ccy={ctx?.currency || 'GBP'} layer={COMMERCIAL} role={role} /></span>
                </div>
                <div className="kvrow">
                  <span className="dim">VAT</span>
                  <span data-testid="vat-total"><Money value={quoteResult.vatTotal} ccy={ctx?.currency || 'GBP'} layer={COMMERCIAL} role={role} /></span>
                </div>
                <div className="kvrow">
                  <span className="dim">Total inc VAT</span>
                  <span
                    data-testid="total-inc-vat"
                    style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 700 }}
                    className="num"
                  >
                    <Money value={quoteResult.totalIncVat} ccy={ctx?.currency || 'GBP'} layer={COMMERCIAL} role={role} />
                  </span>
                </div>
              </>
            )}

            {/* tier + ADLP category — first-class, never hidden behind the total */}
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
            disabled={placing || state !== 'ready' || cleanLines().length === 0}
            onClick={onPlace}
            title={state !== 'ready' ? 'Resolve a quote first' : ''}
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
