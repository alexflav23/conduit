import React, { useState } from 'react';
import { getPnl, getCashWaterfall, getCreditTerms, setCreditTerms, FINANCE_MARKET } from './api';
import { PageHead, Card, LoadBar } from './kit/kit';
import { I } from './kit/icons';

// The Finance desk (M13 / spec/ui/09-finance.md): the P&L recognised on dispatch (proved on the immutable
// ledger), the cash waterfall (open invoices bucketed by each contact's contractual due date), and
// per-invoice-contact credit-terms admin. Ported to the desk kit (.metric / .tbl), testids preserved.

export function Finance({ token }: { token: string }) {
  const [period, setPeriod] = useState('2026-09');
  const [pnl, setPnl] = useState<any | null>(null);
  const [wf, setWf] = useState<any[]>([]);
  const [party, setParty] = useState('');
  const [terms, setTerms] = useState<any | null>(null);
  const [days, setDays] = useState('');
  const [status, setStatus] = useState<string | null>(null);

  const loadPnl = async () => { const r = await getPnl(token, FINANCE_MARKET, period); setPnl(r.status === 200 ? r.json : null); };
  const loadWf = async () => { const r = await getCashWaterfall(token, 'GBP'); setWf(Array.isArray(r.json) ? r.json : []); };
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

  const metric = (label: string, value: React.ReactNode, testid?: string, accent?: boolean) => (
    <div className="metric" style={{ minWidth: 140 }}>
      <div className="ml">{label}</div>
      <div className={'mv' + (accent ? ' accent' : '')} style={{ fontSize: 26 }} data-testid={testid}>{value}</div>
    </div>
  );

  return (
    <>
      <PageHead title="Finance" sub="ASC-606 P&L recognised on dispatch, the cash waterfall, and per-contact credit terms" />

      <Card title={`P&L (${period})`} icon={I.sessions} style={{ maxWidth: 860 }}
        aux={<LoadBar><span className="dim">Period</span><input className="fld" style={{ width: 90 }} data-testid="fin-period" value={period} onChange={(e) => setPeriod(e.target.value)} /><button className="btn primary sm" data-testid="fin-load-pnl" onClick={loadPnl}>Load P&amp;L</button></LoadBar>}>
        <div className="row" style={{ gap: 28, flexWrap: 'wrap' }} data-testid="fin-pnl">
          {metric('Revenue (ex VAT)', m(pnl?.revenue_ex_vat), 'fin-revenue')}
          {metric('VAT', m(pnl?.vat))}
          {metric('COGS', m(pnl?.cogs))}
          {metric('Gross margin', m(pnl?.gross_margin), 'fin-margin', true)}
        </div>
      </Card>

      <Card title="Cash waterfall (expected collections, GBP)" icon={I.trend} style={{ maxWidth: 860 }}
        aux={<button className="btn primary sm" data-testid="fin-load-wf" onClick={loadWf}>{I.refresh({ size: 13 })} Load cash waterfall</button>}>
        <div className="tablewrap">
          <table className="tbl" data-testid="fin-waterfall">
            <thead><tr><th>Due month</th><th>Currency</th><th className="num">Expected cash</th><th className="num">Invoices</th></tr></thead>
            <tbody>
              {wf.map((r, i) => (
                <tr key={i} data-testid="fin-wf-row">
                  <td>{r.due_month}</td><td>{r.currency}</td><td className="num">{m(r.expected_cash)}</td><td className="num">{r.invoices}</td>
                </tr>
              ))}
              {wf.length === 0 && <tr><td className="dim" colSpan={4} style={{ padding: '14px 12px' }}>No open invoices — load to refresh.</td></tr>}
            </tbody>
          </table>
        </div>
      </Card>

      <Card title="Credit terms" icon={I.sessions} style={{ maxWidth: 860 }} aux={<span className="dim" style={{ fontSize: 12 }}>per invoice contact — drives the due date + waterfall</span>}>
        <LoadBar>
          <span className="dim">Party id</span>
          <input className="fld" style={{ width: 320 }} data-testid="fin-party" value={party} onChange={(e) => setParty(e.target.value)} placeholder="party uuid" />
          <button className="btn primary" data-testid="fin-load-terms" onClick={loadTerms}>Load</button>
        </LoadBar>
        {terms && (
          <div className="row g8" style={{ marginTop: 12, flexWrap: 'wrap' }}>
            <span className="dim">Payment terms (days)</span>
            <input className="fld" style={{ width: 80 }} data-testid="fin-terms-days" value={days} onChange={(e) => setDays(e.target.value)} />
            <span className="dim">credit limit: {m(terms.credit_limit)}</span>
            <button className="btn primary" data-testid="fin-save-terms" onClick={saveTerms}>Save</button>
            {status && <span className="dim" data-testid="fin-terms-status">{status}</span>}
          </div>
        )}
      </Card>
    </>
  );
}
