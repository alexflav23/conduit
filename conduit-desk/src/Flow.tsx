import React, { useState } from 'react';
import { getVariants, getWaterfall, getLedger, H6Q_MARKET } from './api';
import { PageHead, Card, Chip } from './kit/kit';
import { I } from './kit/icons';

// The "Flow" desk (design spec doc 20 §2.3/§2.6 / spec/ui/06-flow.md): the H6Q VARIANTS — forecast →
// committed → produced → delivered → ordered → shipped → revenue — and how they evolve over time, with
// revenue drilling to the immutable TigerBeetle log. Ported to the desk kit (.tbl), testids preserved.

const VARIANTS: Array<{ key: string; label: string }> = [
  { key: 'sales_forecast', label: 'Forecast' },
  { key: 'cm_committed', label: 'Committed (firm PO)' },
  { key: 'cm_produced', label: 'Produced' },
  { key: 'delivered', label: 'Delivered' },
  { key: 'ordered', label: 'Ordered (sold)' },
  { key: 'shipped', label: 'Shipped (dispatched)' },
];
const MONTHS = ['2026-08', '2026-09', '2026-10'];

export function Flow({ token }: { token: string }) {
  const [variants, setVariants] = useState<any[]>([]);
  const [variant, setVariant] = useState<string>('');
  const [grid, setGrid] = useState<Record<string, any>>({}); // period -> waterfall json
  const [ledger, setLedger] = useState<any | null>(null);
  const [error, setError] = useState<string | null>(null);

  const init = async () => {
    setError(null);
    const v = await getVariants(token);
    setVariants(v.json ?? []);
    if ((v.json ?? []).length) {
      const first = v.json[0].id;
      setVariant(first);
      await load(first);
    }
  };

  const load = async (vid: string) => {
    setError(null);
    const cells: Record<string, any> = {};
    for (const m of MONTHS) {
      const wf = await getWaterfall(token, vid, m);
      if (wf.status === 200) cells[m] = wf.json;
    }
    setGrid(cells);
    const led = await getLedger(token, H6Q_MARKET, MONTHS[1]); // the focus month
    if (led.status === 200) setLedger(led.json);
    else setError(`Ledger ${led.status}`);
  };

  const cell = (period: string, key: string): string => {
    const wf = grid[period];
    if (!wf) return '—';
    if (key === 'revenue_ex_vat') return wf.revenue_ex_vat ?? '0';
    return String(wf?.stages?.[key] ?? 0);
  };

  return (
    <>
      <PageHead
        title="Flow"
        sub="The H6Q variants over time — forecast → committed → produced → delivered → ordered → shipped → revenue"
        right={
          <div className="row g8">
            <button className="btn primary" data-testid="flow-load" onClick={init}>{I.refresh({ size: 14 })} Load flow</button>
            {variant && (
              <select className="fld sel" data-testid="flow-variant" value={variant} onChange={(e) => { setVariant(e.target.value); load(e.target.value); }}>
                {variants.map((v) => <option key={v.id} value={v.id}>{v.sku} — {v.family}</option>)}
              </select>
            )}
            {error && <span className="dim" data-testid="flow-error" style={{ color: 'var(--danger)' }}>{error}</span>}
          </div>
        }
      />

      <Card title="H6Q variants over time" icon={I.trend} aux={<span className="dim" style={{ fontSize: 12 }}>the same demand, each stage distinct</span>}>
        <div className="tablewrap">
          <table className="tbl" data-testid="flow-grid">
            <thead><tr><th>Variant</th>{MONTHS.map((m) => <th key={m} className="num">{m}</th>)}</tr></thead>
            <tbody>
              {VARIANTS.map((vr) => (
                <tr key={vr.key} data-testid={`flow-row-${vr.key}`}>
                  <td style={{ fontWeight: 600 }}>{vr.label}</td>
                  {MONTHS.map((m) => <td key={m} className="num" data-testid={`flow-cell-${vr.key}-${m}`}>{cell(m, vr.key)}</td>)}
                </tr>
              ))}
              <tr data-testid="flow-row-revenue">
                <td style={{ fontWeight: 700, color: 'var(--accent)' }}>Revenue ex-VAT (£)</td>
                {MONTHS.map((m) => <td key={m} className="num" style={{ fontWeight: 700, color: 'var(--accent)' }}>{cell(m, 'revenue_ex_vat')}</td>)}
              </tr>
            </tbody>
          </table>
        </div>
      </Card>

      <Card title={`Immutable ledger — recognised revenue traced to TigerBeetle (${MONTHS[1]})`} icon={I.scale}>
        {ledger && (
          <>
            <div className="row g8" style={{ flexWrap: 'wrap', marginBottom: 12 }} data-testid="ledger-totals">
              <Chip s="neutral">Revenue £{ledger.totals?.revenue_ex_vat ?? '0'}</Chip>
              <Chip s="neutral">VAT £{ledger.totals?.vat ?? '0'}</Chip>
              <Chip s="neutral">COGS £{ledger.totals?.cogs ?? '0'}</Chip>
              <Chip s="neutral">Gross margin £{ledger.totals?.gross_margin ?? '0'}</Chip>
            </div>
            <div className="tablewrap">
              <table className="tbl" data-testid="ledger-table">
                <thead><tr>
                  <th>Invoice</th><th className="num">Revenue</th><th className="num">VAT</th><th className="num">COGS</th><th className="num">Margin</th><th>AR transfer (TigerBeetle id)</th>
                </tr></thead>
                <tbody>
                  {(ledger.recognitions ?? []).map((r: any, i: number) => (
                    <tr key={i} data-testid="ledger-row">
                      <td>{r.invoice_no ?? '—'}</td>
                      <td className="num">{r.revenue_ex_vat}</td>
                      <td className="num">{r.vat}</td>
                      <td className="num">{r.cogs}</td>
                      <td className="num">{r.gross_margin}</td>
                      <td className="mono dim">{(r.ar_transfer_id ?? '').slice(0, 24)}…</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="dim" style={{ marginTop: 10, fontSize: 12.5 }}>Each revenue figure is posted as DR AR / CR Revenue + VAT and DR COGS / CR INV in the immutable log — the transfer ids above are the proof.</p>
          </>
        )}
      </Card>
    </>
  );
}
