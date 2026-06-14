import React, { useState } from 'react';
import {
  taxQuote, getTaxRates, proposeTaxRate, activateTaxRate, getTaxRouting, getTaxNexus, TAX_DEMO_ENTITY,
  getSellingEntities, proposeSellingEntity, activateSellingEntity, getVatExposure, requestVatRemittance,
  type TaxQuoteInput,
} from './api';
import { PageHead, Card, Chip, LoadBar } from './kit/kit';
import { I } from './kit/icons';

// The Tax desk (M13-Tax, doc 16 / spec/ui/14-tax.md): the determination engine made tangible. A live quote
// tester shows the resolved place-of-supply + the multi-level jurisdiction breakdown (US state+county+district,
// CA GST+PST, UK VAT); the rate-table admin manages effective-dated rates with maker-checker governance
// (propose → CFO activates). Tax is a QUOTE, not a rate column. Ported to the desk kit, testids preserved.

const SectionLabel = ({ children }: { children: React.ReactNode }) => (
  <div className="dim" style={{ fontSize: 12, textTransform: 'uppercase', letterSpacing: '0.06em', margin: '14px 0 10px' }}>{children}</div>
);

export function Tax({ token }: { token: string }) {
  const [q, setQ] = useState<TaxQuoteInput>({
    shipFromJurisdiction: 'GB', shipToJurisdiction: 'US', shipToRegion: 'CA', shipToPostcode: '90001',
    partyTaxStatus: 'consumer', buyerTaxId: '', currency: 'USD', taxableAmount: '100.00',
  });
  const [result, setResult] = useState<any>(null);
  const [rates, setRates] = useState<any[]>([]);
  const [rateJuris, setRateJuris] = useState('GB');
  const [nexus, setNexus] = useState<any[]>([]);
  const [routing, setRouting] = useState<any[]>([]);
  const [pr, setPr] = useState({ jurisdiction: 'FR', tax_type: 'VAT', region: '', postcode_prefix: '', level: 'national', name: 'France VAT', rate_pct: '20.0', effective_from: '2026-01-01' });
  const [proposeStatus, setProposeStatus] = useState<string | null>(null);
  const [sellingEntities, setSellingEntities] = useState<any[]>([]);
  const [se, setSe] = useState({ jurisdiction: 'DE', entity_id: TAX_DEMO_ENTITY, effective_from: '2026-01-01' });
  const [seStatus, setSeStatus] = useState<string | null>(null);
  const [exposure, setExposure] = useState<any[]>([]);
  const [rm, setRm] = useState({ jurisdiction: 'GB', period_key: '2026-06', amount: '50.00', currency: 'GBP', reference: '' });
  const [rmStatus, setRmStatus] = useState<string | null>(null);

  const set = (k: keyof TaxQuoteInput) => (e: any) => setQ({ ...q, [k]: e.target.value });

  const runQuote = async () => {
    const r = await taxQuote(token, q);
    setResult(r.status === 200 ? r.json : { error: `${r.status}: ${r.json?.message ?? ''}` });
  };
  const loadRates = async (juris?: string) => setRates(await getTaxRates(token, (juris ?? rateJuris) || undefined).then((r) => (Array.isArray(r.json) ? r.json : [])));
  const loadNexus = async () => setNexus(await getTaxNexus(token, TAX_DEMO_ENTITY).then((r) => (Array.isArray(r.json) ? r.json : [])));
  const loadRouting = async () => setRouting(await getTaxRouting(token).then((r) => (Array.isArray(r.json) ? r.json : [])));

  const propose = async () => {
    const r = await proposeTaxRate(token, {
      ...pr, region: pr.region || null, postcode_prefix: pr.postcode_prefix || null,
      tax_category_code: 'goods_standard', rate_pct: Number(pr.rate_pct), kind: 'standard',
    });
    setProposeStatus(r.status === 200 ? `proposed draft ${r.json.id?.slice(0, 8)}` : `failed (${r.status})`);
    if (r.status === 200) { setRateJuris(pr.jurisdiction); await loadRates(pr.jurisdiction); }
  };
  const activate = async (id: string) => {
    const r = await activateTaxRate(token, id);
    setProposeStatus(r.status === 200 ? 'activated' : `activate failed (${r.status}: ${r.json?.message ?? ''})`);
    await loadRates();
  };

  const loadSelling = async () => setSellingEntities(await getSellingEntities(token).then((r) => (Array.isArray(r.json) ? r.json : [])));
  const proposeSe = async () => {
    const r = await proposeSellingEntity(token, se.jurisdiction, se.entity_id, se.effective_from);
    setSeStatus(r.status === 200 ? `proposed ${r.json.id?.slice(0, 8)}` : `failed (${r.status})`);
    if (r.status === 200) await loadSelling();
  };
  const activateSe = async (id: string) => {
    const r = await activateSellingEntity(token, id);
    setSeStatus(r.status === 200 ? 'activated' : `activate failed (${r.status}: ${r.json?.message ?? ''})`);
    await loadSelling();
  };
  const loadExposure = async () => setExposure(await getVatExposure(token, TAX_DEMO_ENTITY).then((r) => (Array.isArray(r.json) ? r.json : [])));
  const remit = async () => {
    const r = await requestVatRemittance(token, {
      entity_id: TAX_DEMO_ENTITY, jurisdiction: rm.jurisdiction, period_key: rm.period_key,
      amount: Number(rm.amount), currency: rm.currency, reference: rm.reference || undefined,
    });
    setRmStatus(r.status === 200 ? `remittance ${r.json.status}` : `failed (${r.status})`);
  };

  const metric = (label: string, value: React.ReactNode, testid: string, accent?: boolean) => (
    <div className="metric"><div className="ml">{label}</div><div className={'mv' + (accent ? ' accent' : '')} style={{ fontSize: 22 }} data-testid={testid}>{value}</div></div>
  );

  return (
    <>
      <PageHead title="Tax engine" sub="Determination as a quote — multi-level jurisdiction breakdown + effective-dated rate-table governance" />

      <Card title="Quote tester" icon={I.globe} aux={<span className="dim" style={{ fontSize: 12 }}>supply facts in, tax + jurisdiction breakdown out</span>}>
        <div className="row g8" style={{ flexWrap: 'wrap', marginBottom: 10 }}>
          <span className="dim">Ship from</span>
          <input className="fld" style={{ width: 60 }} data-testid="tax-from" value={q.shipFromJurisdiction} onChange={set('shipFromJurisdiction')} />
          <span className="dim">to</span>
          <input className="fld" style={{ width: 60 }} data-testid="tax-to" value={q.shipToJurisdiction} onChange={set('shipToJurisdiction')} />
          <input className="fld" style={{ width: 80 }} data-testid="tax-region" value={q.shipToRegion} onChange={set('shipToRegion')} placeholder="region" />
          <input className="fld" style={{ width: 100 }} data-testid="tax-postcode" value={q.shipToPostcode} onChange={set('shipToPostcode')} placeholder="postcode" />
        </div>
        <div className="row g8" style={{ flexWrap: 'wrap' }}>
          <span className="dim">Buyer</span>
          <select className="fld sel" data-testid="tax-party-status" value={q.partyTaxStatus} onChange={set('partyTaxStatus')}>
            <option value="consumer">consumer</option>
            <option value="business">business</option>
            <option value="business_with_vat_id">business + VAT id</option>
          </select>
          <input className="fld" style={{ width: 140 }} data-testid="tax-vatid" value={q.buyerTaxId} onChange={set('buyerTaxId')} placeholder="VAT id" />
          <span className="dim">Amount</span>
          <input className="fld" style={{ width: 90 }} data-testid="tax-amount" value={q.taxableAmount} onChange={set('taxableAmount')} />
          <input className="fld" style={{ width: 64 }} data-testid="tax-currency" value={q.currency} onChange={set('currency')} />
          <button className="btn primary" data-testid="tax-quote-btn" onClick={runQuote}>Quote</button>
        </div>
        {result && !result.error && (
          <div style={{ marginTop: 14 }}>
            <div className="row" style={{ gap: 28, flexWrap: 'wrap', marginBottom: 12 }}>
              {metric('Supply kind', result.supplyKind, 'tax-supply-kind', true)}
              {metric('Total tax', `${Number(result.taxTotal).toFixed(2)} ${result.currency}`, 'tax-total')}
              {metric('Reverse charge', String(result.reverseCharge), 'tax-reverse')}
            </div>
            <div className="tablewrap">
              <table className="tbl" data-testid="tax-components">
                <thead><tr><th>Level</th><th>Jurisdiction</th><th>Name</th><th>Type</th><th className="num">Rate %</th><th className="num">Amount</th></tr></thead>
                <tbody>
                  {(result.lines?.[0]?.components ?? []).map((c: any, i: number) => (
                    <tr key={i} data-testid="tax-comp-row">
                      <td>{c.level}</td><td>{c.jurisdiction}{c.region ? `/${c.region}` : ''}</td><td>{c.name}</td>
                      <td>{c.taxType}</td><td className="num">{c.ratePct}</td><td className="num">{Number(c.amount).toFixed(2)}</td>
                    </tr>
                  ))}
                  {(result.lines?.[0]?.components ?? []).length === 0 && <tr><td className="dim" colSpan={6} style={{ padding: '12px' }}>No tax components (zero-rated / reverse-charge / export).</td></tr>}
                </tbody>
              </table>
            </div>
          </div>
        )}
        {result?.error && <span className="dim" data-testid="tax-error" style={{ display: 'block', marginTop: 10 }}>{result.error}</span>}
      </Card>

      <Card title="Rate table" icon={I.list} aux={<span className="dim" style={{ fontSize: 12 }}>effective-dated, multi-level — a change is a new dated row, never an edit</span>}>
        <LoadBar>
          <span className="dim">Jurisdiction</span>
          <input className="fld" style={{ width: 70 }} data-testid="tax-rate-juris" value={rateJuris} onChange={(e) => setRateJuris(e.target.value)} />
          <button className="btn primary" data-testid="tax-rates-load" onClick={() => loadRates()}>Load rates</button>
        </LoadBar>
        <div className="tablewrap" style={{ marginTop: 12 }}>
          <table className="tbl" data-testid="tax-rates-table">
            <thead><tr><th>Type</th><th>Region</th><th>Postcode</th><th>Level</th><th>Name</th><th className="num">Rate %</th><th>From</th><th>To</th><th>Status</th><th /></tr></thead>
            <tbody>
              {rates.map((r, i) => (
                <tr key={i} data-testid="tax-rate-row">
                  <td>{r.tax_type}</td><td>{r.region ?? '—'}</td><td>{r.postcode_prefix ?? '—'}</td><td>{r.level}</td><td>{r.name}</td>
                  <td className="num">{r.rate_pct ?? '—'}</td><td>{r.effective_from}</td><td>{r.effective_to ?? '—'}</td>
                  <td>{r.status === 'draft' ? <Chip s="draft">draft</Chip> : <Chip s={r.status}>{r.status}</Chip>}</td>
                  <td>{r.status === 'draft' && <button className="btn sm" data-testid="tax-activate" onClick={() => activate(r.id)}>Activate (CFO)</button>}</td>
                </tr>
              ))}
              {rates.length === 0 && <tr><td className="dim" colSpan={10} style={{ padding: '12px' }}>Load a jurisdiction's rates.</td></tr>}
            </tbody>
          </table>
        </div>
        <SectionLabel>Propose a rate (tax specialist) → CFO activates</SectionLabel>
        <div className="row g8" style={{ flexWrap: 'wrap' }}>
          <input className="fld" style={{ width: 60 }} data-testid="tax-pr-juris" value={pr.jurisdiction} onChange={(e) => setPr({ ...pr, jurisdiction: e.target.value })} placeholder="juris" />
          <input className="fld" style={{ width: 80 }} data-testid="tax-pr-type" value={pr.tax_type} onChange={(e) => setPr({ ...pr, tax_type: e.target.value })} placeholder="type" />
          <input className="fld" style={{ width: 80 }} data-testid="tax-pr-region" value={pr.region} onChange={(e) => setPr({ ...pr, region: e.target.value })} placeholder="region" />
          <input className="fld" style={{ width: 90 }} data-testid="tax-pr-level" value={pr.level} onChange={(e) => setPr({ ...pr, level: e.target.value })} placeholder="level" />
          <input className="fld" style={{ width: 120 }} data-testid="tax-pr-name" value={pr.name} onChange={(e) => setPr({ ...pr, name: e.target.value })} placeholder="name" />
          <input className="fld" style={{ width: 60 }} data-testid="tax-pr-rate" value={pr.rate_pct} onChange={(e) => setPr({ ...pr, rate_pct: e.target.value })} placeholder="rate" />
          <input className="fld" style={{ width: 110 }} data-testid="tax-pr-from" value={pr.effective_from} onChange={(e) => setPr({ ...pr, effective_from: e.target.value })} placeholder="from" />
          <button className="btn primary" data-testid="tax-propose-btn" onClick={propose}>Propose</button>
          {proposeStatus && <span className="dim" data-testid="tax-propose-status">{proposeStatus}</span>}
        </div>
      </Card>

      <Card title="Provider routing" icon={I.globe} aux={<span className="dim" style={{ fontSize: 12 }}>which engine serves a market (flipping to a vendor is a row)</span>}>
        <div className="row g8">
          <button className="btn primary" data-testid="tax-routing-load" onClick={loadRouting}>Load routing</button>
          <button className="btn" data-testid="tax-nexus-load" onClick={loadNexus}>Load nexus board</button>
        </div>
        <div className="tablewrap" style={{ marginTop: 12 }}>
          <table className="tbl" data-testid="tax-routing-table">
            <thead><tr><th>Jurisdiction</th><th>Tax type</th><th>Provider</th><th className="num">Priority</th></tr></thead>
            <tbody>
              {routing.map((r, i) => (
                <tr key={i} data-testid="tax-routing-row">
                  <td>{r.jurisdiction ?? 'all'}</td><td>{r.tax_type ?? 'all'}</td><td>{r.provider}</td><td className="num">{r.priority}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {nexus.length > 0 && (
          <div className="tablewrap" style={{ marginTop: 14 }}>
            <table className="tbl" data-testid="tax-nexus-table">
              <thead><tr><th>Jurisdiction</th><th>Region</th><th className="num">Sales</th><th className="num">Threshold</th><th>Status</th></tr></thead>
              <tbody>
                {nexus.map((n, i) => (
                  <tr key={i} data-testid="tax-nexus-row">
                    <td>{n.jurisdiction}</td><td>{n.region}</td><td className="num">{n.sales_to_date ?? '—'}</td><td className="num">{n.threshold_amount ?? '—'}</td><td>{n.status}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <Card title="Operating entities" icon={I.globe} aux={<span className="dim" style={{ fontSize: 12 }}>which Hypervolt entity books a jurisdiction (re-point by config)</span>}>
        <div className="row g8"><button className="btn primary" data-testid="tax-se-load" onClick={loadSelling}>Load entity map</button></div>
        <div className="tablewrap" style={{ marginTop: 12 }}>
          <table className="tbl" data-testid="tax-se-table">
            <thead><tr><th>Jurisdiction</th><th>Entity</th><th>Currency</th><th>From</th><th>To</th><th>Status</th><th /></tr></thead>
            <tbody>
              {sellingEntities.map((s, i) => (
                <tr key={i} data-testid="tax-se-row">
                  <td>{s.jurisdiction}</td><td>{s.entity_name}</td><td>{s.functional_currency}</td><td>{s.effective_from}</td><td>{s.effective_to ?? '—'}</td>
                  <td>{s.status === 'draft' ? <Chip s="draft">draft</Chip> : <Chip s={s.status}>{s.status}</Chip>}</td>
                  <td>{s.status === 'draft' && <button className="btn sm" data-testid="tax-se-activate" onClick={() => activateSe(s.id)}>Activate (CFO)</button>}</td>
                </tr>
              ))}
              {sellingEntities.length === 0 && <tr><td className="dim" colSpan={7} style={{ padding: '12px' }}>Load the entity map.</td></tr>}
            </tbody>
          </table>
        </div>
        <div className="row g8" style={{ marginTop: 12, flexWrap: 'wrap' }}>
          <input className="fld" style={{ width: 60 }} data-testid="tax-se-juris" value={se.jurisdiction} onChange={(e) => setSe({ ...se, jurisdiction: e.target.value })} placeholder="juris" />
          <input className="fld" style={{ width: 300 }} data-testid="tax-se-entity" value={se.entity_id} onChange={(e) => setSe({ ...se, entity_id: e.target.value })} placeholder="entity id" />
          <input className="fld" style={{ width: 110 }} data-testid="tax-se-from" value={se.effective_from} onChange={(e) => setSe({ ...se, effective_from: e.target.value })} placeholder="from" />
          <button className="btn primary" data-testid="tax-se-propose" onClick={proposeSe}>Propose map (admin)</button>
          {seStatus && <span className="dim" data-testid="tax-se-status">{seStatus}</span>}
        </div>
      </Card>

      <Card title="VAT exposure" icon={I.scale} aux={<span className="dim" style={{ fontSize: 12 }}>accrued − reversed − remitted = outstanding, per entity × jurisdiction × period</span>}>
        <div className="row g8"><button className="btn primary" data-testid="tax-exposure-load" onClick={loadExposure}>Load exposure</button></div>
        <div className="tablewrap" style={{ marginTop: 12 }}>
          <table className="tbl" data-testid="tax-exposure-table">
            <thead><tr><th>Jurisdiction</th><th>Period</th><th className="num">Accrued</th><th className="num">Reversed</th><th className="num">Remitted</th><th className="num">Outstanding</th></tr></thead>
            <tbody>
              {exposure.map((x, i) => (
                <tr key={i} data-testid="tax-exposure-row">
                  <td>{x.jurisdiction}</td><td>{x.period}</td><td className="num">{x.accrued}</td><td className="num">{x.reversed}</td><td className="num">{x.remitted}</td>
                  <td className="num" style={{ fontWeight: 700 }}>{x.outstanding}</td>
                </tr>
              ))}
              {exposure.length === 0 && <tr><td className="dim" colSpan={6} style={{ padding: '12px' }}>No VAT exposure for this entity yet.</td></tr>}
            </tbody>
          </table>
        </div>
        <SectionLabel>Remit VAT (depletes the VAT ledger; performed off the API by the consumer)</SectionLabel>
        <div className="row g8" style={{ flexWrap: 'wrap' }}>
          <input className="fld" style={{ width: 60 }} data-testid="tax-rm-juris" value={rm.jurisdiction} onChange={(e) => setRm({ ...rm, jurisdiction: e.target.value })} placeholder="juris" />
          <input className="fld" style={{ width: 90 }} data-testid="tax-rm-period" value={rm.period_key} onChange={(e) => setRm({ ...rm, period_key: e.target.value })} placeholder="period" />
          <input className="fld" style={{ width: 80 }} data-testid="tax-rm-amount" value={rm.amount} onChange={(e) => setRm({ ...rm, amount: e.target.value })} placeholder="amount" />
          <input className="fld" style={{ width: 120 }} data-testid="tax-rm-ref" value={rm.reference} onChange={(e) => setRm({ ...rm, reference: e.target.value })} placeholder="reference" />
          <button className="btn primary" data-testid="tax-rm-submit" onClick={remit}>Remit</button>
          {rmStatus && <span className="dim" data-testid="tax-rm-status">{rmStatus}</span>}
        </div>
      </Card>
    </>
  );
}
