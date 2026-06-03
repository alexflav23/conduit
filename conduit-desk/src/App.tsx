import React, { useState } from 'react';
import * as stylex from '@stylexjs/stylex';
import { colors } from './styles/tokens.stylex';
import { quote, placeOrder, QuoteLine } from './api';

const styles = stylex.create({
  page: {
    minHeight: '100vh',
    backgroundColor: colors.bg,
    color: colors.text,
    fontFamily: 'system-ui, -apple-system, Segoe UI, Roboto, sans-serif',
    padding: '2rem',
  },
  title: { fontSize: '1.5rem', fontWeight: 700, marginBottom: '1.5rem' },
  accent: { color: colors.accent },
  card: {
    backgroundColor: colors.surface,
    border: `1px solid ${colors.border}`,
    borderRadius: '14px',
    padding: '1.25rem',
    marginBottom: '1.25rem',
    maxWidth: '560px',
  },
  row: { display: 'flex', gap: '0.75rem', alignItems: 'center', marginBottom: '0.75rem', flexWrap: 'wrap' },
  label: { color: colors.muted, fontSize: '0.8rem', width: '110px' },
  input: {
    backgroundColor: colors.bg,
    color: colors.text,
    border: `1px solid ${colors.border}`,
    borderRadius: '8px',
    padding: '0.5rem 0.7rem',
    fontSize: '0.95rem',
    flexGrow: 1,
  },
  button: {
    backgroundColor: colors.accent,
    color: '#fff',
    border: 'none',
    borderRadius: '10px',
    padding: '0.6rem 1.1rem',
    fontSize: '0.95rem',
    fontWeight: 600,
    cursor: 'pointer',
    marginRight: '0.75rem',
  },
  big: { fontSize: '1.6rem', fontWeight: 700 },
  kv: { display: 'flex', justifyContent: 'space-between', padding: '0.3rem 0', borderBottom: `1px solid ${colors.border}` },
  chipStandard: { backgroundColor: colors.ok, color: '#06210f', padding: '0.2rem 0.6rem', borderRadius: '999px', fontWeight: 700, fontSize: '0.8rem' },
  chipException: { backgroundColor: colors.warn, color: '#3a2400', padding: '0.2rem 0.6rem', borderRadius: '999px', fontWeight: 700, fontSize: '0.8rem' },
});

export function App() {
  const [token, setToken] = useState('dev:agent-e2e');
  const [sku, setSku] = useState('HV-310');
  const [qty, setQty] = useState('2');
  const [unitPrice, setUnitPrice] = useState('');
  const [quoteResult, setQuoteResult] = useState<any>(null);
  const [order, setOrder] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);

  const lines = (): QuoteLine[] => [
    { sku, qty: parseInt(qty, 10), unitPriceExVat: unitPrice.trim() === '' ? undefined : unitPrice.trim() },
  ];

  const onQuote = async () => {
    setError(null);
    setOrder(null);
    const { status, json } = await quote(token, lines());
    if (status === 200) setQuoteResult(json);
    else setError(`Quote failed (${status}): ${json?.message ?? ''}`);
  };

  const onPlace = async () => {
    setError(null);
    const { status, json } = await placeOrder(token, lines());
    if (status === 201 || status === 202) setOrder(json);
    else setError(`Place failed (${status}): ${json?.message ?? ''}`);
  };

  const line = quoteResult?.lines?.[0];

  return (
    <div {...stylex.props(styles.page)}>
      <div {...stylex.props(styles.title)}>
        <span {...stylex.props(styles.accent)}>Conduit</span> — Order Desk
      </div>

      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.row)}>
          <span {...stylex.props(styles.label)}>Auth token</span>
          <input {...stylex.props(styles.input)} data-testid="token" value={token} onChange={(e) => setToken(e.target.value)} />
        </div>
        <div {...stylex.props(styles.row)}>
          <span {...stylex.props(styles.label)}>SKU</span>
          <input {...stylex.props(styles.input)} data-testid="sku" value={sku} onChange={(e) => setSku(e.target.value)} />
        </div>
        <div {...stylex.props(styles.row)}>
          <span {...stylex.props(styles.label)}>Qty</span>
          <input {...stylex.props(styles.input)} data-testid="qty" value={qty} onChange={(e) => setQty(e.target.value)} />
        </div>
        <div {...stylex.props(styles.row)}>
          <span {...stylex.props(styles.label)}>Unit price (opt)</span>
          <input {...stylex.props(styles.input)} data-testid="unit-price" value={unitPrice} onChange={(e) => setUnitPrice(e.target.value)} placeholder="list price" />
        </div>
        <button {...stylex.props(styles.button)} data-testid="quote-btn" onClick={onQuote}>Get quote</button>
        <button {...stylex.props(styles.button)} data-testid="place-btn" onClick={onPlace}>Place order</button>
      </div>

      {error && <div {...stylex.props(styles.card)} data-testid="error">{error}</div>}

      {quoteResult && (
        <div {...stylex.props(styles.card)} data-testid="quote">
          <div {...stylex.props(styles.kv)}><span>Resolved ex-VAT</span><span data-testid="resolved-ex-vat">{line?.resolvedExVat}</span></div>
          <div {...stylex.props(styles.kv)}><span>Applied discount %</span><span data-testid="discount">{line?.appliedDiscountPct}</span></div>
          <div {...stylex.props(styles.kv)}><span>VAT total</span><span data-testid="vat-total">{quoteResult.vatTotal}</span></div>
          <div {...stylex.props(styles.kv)}>
            <span>Total inc VAT</span>
            <span {...stylex.props(styles.big)} data-testid="total-inc-vat">{quoteResult.totalIncVat}</span>
          </div>
          <div {...stylex.props(styles.kv)}>
            <span>ADLP</span>
            <span
              {...stylex.props(line?.adlpCategory === 'exception' ? styles.chipException : styles.chipStandard)}
              data-testid="adlp"
            >
              {line?.adlpCategory === 'exception' ? 'Exception' : 'Standard'}
            </span>
          </div>
        </div>
      )}

      {order && (
        <div {...stylex.props(styles.card)} data-testid="order">
          <div {...stylex.props(styles.kv)}><span>Order</span><span data-testid="order-no">{order.orderNo}</span></div>
          <div {...stylex.props(styles.kv)}><span>Status</span><span data-testid="order-status">{order.status}</span></div>
        </div>
      )}
    </div>
  );
}
