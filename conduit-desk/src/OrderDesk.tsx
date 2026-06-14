import React, { useState } from 'react';
import { quote, placeOrder, QuoteLine } from './api';
import { PageHead, Card, Chip, Money } from './kit/kit';
import { I } from './kit/icons';

// Order capture (M4 / spec/ui/02-order.md): SKU + qty + optional list price → governed quote (the server
// rejects any non-tier price) → place. Standard vs exception ADLP is the server's call; an exception line
// holds pending_ceo. Ported to the desk kit, testids preserved.

export function OrderDesk({ token }: { token: string }) {
  const [sku, setSku] = useState('HV-310');
  const [qty, setQty] = useState('2');
  const [unitPrice, setUnitPrice] = useState('');
  const [quoteResult, setQuoteResult] = useState<any>(null);
  const [order, setOrder] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);

  const lines = (): QuoteLine[] => [{ sku, qty: parseInt(qty, 10), unitPriceExVat: unitPrice.trim() === '' ? undefined : unitPrice.trim() }];

  const onQuote = async () => {
    setError(null); setOrder(null);
    const { status, json } = await quote(token, lines());
    if (status === 200) setQuoteResult(json); else setError(`Quote failed (${status})`);
  };
  const onPlace = async () => {
    setError(null);
    const { status, json } = await placeOrder(token, lines());
    if (status === 201 || status === 202) setOrder(json); else setError(`Place failed (${status})`);
  };

  const line = quoteResult?.lines?.[0];
  return (
    <>
      <PageHead title="Order desk" sub="Capture a governed order — every price is a contract tier; the server rejects ad-hoc numbers" />
      <Card title="New order" icon={I.charger} style={{ maxWidth: 560 }}>
        <div className="kv" style={{ gridTemplateColumns: '130px 1fr', rowGap: 12 }}>
          <span className="k">SKU</span>
          <input className="fld" data-testid="sku" value={sku} onChange={(e) => setSku(e.target.value)} />
          <span className="k">Qty</span>
          <input className="fld" data-testid="qty" value={qty} onChange={(e) => setQty(e.target.value)} />
          <span className="k">Unit price (opt)</span>
          <input className="fld" data-testid="unit-price" value={unitPrice} onChange={(e) => setUnitPrice(e.target.value)} placeholder="list price" />
        </div>
        <div className="row g8" style={{ marginTop: 16 }}>
          <button className="btn primary" data-testid="quote-btn" onClick={onQuote}>{I.scale({ size: 14 })} Get quote</button>
          <button className="btn" data-testid="place-btn" onClick={onPlace}>{I.check({ size: 14 })} Place order</button>
        </div>
      </Card>
      {error && <Card style={{ maxWidth: 560 }}><span className="dim" data-testid="error">{error}</span></Card>}
      {quoteResult && (
        <Card title="Quote" icon={I.scale} style={{ maxWidth: 560 }}>
          <div data-testid="quote">
            <div className="kvrow"><span className="dim">Resolved ex-VAT</span><span className="num" data-testid="resolved-ex-vat">{line?.resolvedExVat}</span></div>
            <div className="kvrow"><span className="dim">VAT total</span><span className="num" data-testid="vat-total">{quoteResult.vatTotal}</span></div>
            <div className="kvrow"><span className="dim">Total inc VAT</span><span className="num" style={{ fontFamily: 'var(--font-disp)', fontSize: 24, fontWeight: 700 }} data-testid="total-inc-vat">{quoteResult.totalIncVat}</span></div>
            <div className="kvrow" style={{ borderBottom: 'none' }}><span className="dim">ADLP</span><Chip s={line?.adlpCategory === 'exception' ? 'exception' : 'ok'}><span data-testid="adlp">{line?.adlpCategory === 'exception' ? 'Exception' : 'Standard'}</span></Chip></div>
          </div>
        </Card>
      )}
      {order && (
        <Card title="Order placed" icon={I.check} style={{ maxWidth: 560 }}>
          <div data-testid="order">
            <div className="kvrow"><span className="dim">Order</span><span className="mono" data-testid="order-no">{order.orderNo}</span></div>
            <div className="kvrow" style={{ borderBottom: 'none' }}><span className="dim">Status</span><Chip s={order.status}><span data-testid="order-status">{order.status}</span></Chip></div>
          </div>
        </Card>
      )}
    </>
  );
}
