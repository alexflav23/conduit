import React, { useCallback, useEffect, useState } from 'react';
import {
  authToken, taxQuote, getTaxRates, proposeTaxRate, activateTaxRate, getTaxRouting, getTaxNexus, TAX_DEMO_ENTITY,
  getSellingEntities, proposeSellingEntity, activateSellingEntity, getVatExposure,
  type TaxQuoteInput,
} from './api';
import { PageHead, Card, Chip, Money, LayerNote, AuditRef, EmptyRow, SkeletonRow, useToast } from './kit/kit';
import { tableState, asArray, type ApiResult } from './state';
import { I } from './kit/icons';

// Tax (doc 16 / spec/ui/11-tax.md): the hero is EXPLAINABILITY. A determination tester resolves the
// place-of-supply and shows the multi-level jurisdiction breakdown WITH its reasoning (supply kind +
// reverse-charge), not just a total. The rate-table admin is effective-dated, two-person (propose →
// activate, self-activation blocked). Routing / selling-entities / VAT-exposure round it out. Rates and
// components ride the `commercial` layer; routing + selling-entities touch `inter_entity` and COLLAPSE.

const Sec = ({ children }: { children: React.ReactNode }) => (
  <div className="dim" style={{ fontSize: 12, textTransform: 'uppercase', letterSpacing: '0.06em', margin: '16px 0 10px' }}>{children}</div>
);

type Props = { role: any; ctx: any; toast: (m: string, k?: string) => void };

export function Tax({ role, toast }: Props) {
  const token = authToken();
  const layers: string[] = (role?.layers as string[]) || [];
  const hasCommercial = layers.indexOf('commercial') >= 0;
  const hasInterEntity = layers.indexOf('inter_entity') >= 0;
  const viewer: string = role?.name || role?.token || '';
  const [toastNode] = useToast();

  // ----- determination tester (an explicit test action, not a load) -----
  const [q, setQ] = useState<TaxQuoteInput>({
    shipFromJurisdiction: 'GB', shipToJurisdiction: 'US', shipToRegion: 'CA', shipToPostcode: '90001',
    partyTaxStatus: 'consumer', buyerTaxId: '', currency: 'USD', taxableAmount: '100.00',
  });
  const [quoteRes, setQuoteRes] = useState<ApiResult | null>(null);
  const [result, setResult] = useState<any>(null);
  const setF = (k: keyof TaxQuoteInput) => (e: any) => setQ({ ...q, [k]: e.target.value });

  const runQuote = async () => {
    setQuoteRes(null);
    const r = await taxQuote(token, q);
    setQuoteRes(r);
    setResult(r.status === 200 ? r.json : null);
    if (r.status !== 200 && r.status !== 403) toast(`Quote failed (${r.status})`, 'err');
  };

  // ----- the auto-loaded tables -----
  const [rateJuris, setRateJuris] = useState('GB');
  const [ratesRes, setRatesRes] = useState<ApiResult | null>(null);
  const [routingRes, setRoutingRes] = useState<ApiResult | null>(null);
  const [nexusRes, setNexusRes] = useState<ApiResult | null>(null);
  const [seRes, setSeRes] = useState<ApiResult | null>(null);
  const [expRes, setExpRes] = useState<ApiResult | null>(null);

  const loadRates = useCallback(async (juris: string) => {
    setRatesRes(null);
    setRatesRes(await getTaxRates(token, juris || undefined));
  }, [token]);

  const loadAll = useCallback(async () => {
    setRoutingRes(null); setNexusRes(null); setSeRes(null); setExpRes(null);
    const [routing, nexus, sellingE, exp] = await Promise.all([
      getTaxRouting(token), getTaxNexus(token, TAX_DEMO_ENTITY), getSellingEntities(token), getVatExposure(token, TAX_DEMO_ENTITY),
    ]);
    setRoutingRes(routing); setNexusRes(nexus); setSeRes(sellingE); setExpRes(exp);
  }, [token]);

  useEffect(() => { loadAll(); }, [loadAll]);
  useEffect(() => { loadRates(rateJuris); }, [loadRates, rateJuris]);

  // ----- propose / activate (maker-checker) -----
  const [pr, setPr] = useState({ jurisdiction: 'FR', tax_type: 'VAT', region: '', level: 'national', name: 'France VAT', rate_pct: '20.0', effective_from: '2026-01-01' });
  const propose = async () => {
    const r = await proposeTaxRate(token, {
      ...pr, region: pr.region || null, postcode_prefix: null,
      tax_category_code: 'goods_standard', rate_pct: Number(pr.rate_pct), kind: 'standard',
    });
    if (r.status === 200) { toast(`Proposed draft ${String(r.json?.id ?? '').slice(0, 8)} — needs a second person`, 'ok'); setRateJuris(pr.jurisdiction); loadRates(pr.jurisdiction); }
    else toast(`Propose failed (${r.status})`, 'err');
  };
  const activate = async (row: any) => {
    const r = await activateTaxRate(token, row.id);
    if (r.status === 200) { toast('Rate activated', 'ok'); loadRates(rateJuris); }
    else toast(`Activate failed (${r.status}: ${r.json?.message ?? ''})`, 'err');
  };

  const [se, setSe] = useState({ jurisdiction: 'DE', entity_id: TAX_DEMO_ENTITY, effective_from: '2026-01-01' });
  const proposeSe = async () => {
    const r = await proposeSellingEntity(token, se.jurisdiction, se.entity_id, se.effective_from);
    if (r.status === 200) { toast(`Proposed entity map ${String(r.json?.id ?? '').slice(0, 8)}`, 'ok'); setSeRes(await getSellingEntities(token)); }
    else toast(`Propose failed (${r.status})`, 'err');
  };
  const activateSe = async (row: any) => {
    const r = await activateSellingEntity(token, row.id);
    if (r.status === 200) { toast('Entity map activated', 'ok'); setSeRes(await getSellingEntities(token)); }
    else toast(`Activate failed (${r.status}: ${r.json?.message ?? ''})`, 'err');
  };

  // self-activation guard: the proposer of a draft may not activate it themselves.
  const proposedByViewer = (row: any) => !!viewer && (row.proposed_by === viewer || row.proposer === viewer || row.maker === viewer);

  const rates = asArray<any>(ratesRes?.json);
  const routing = asArray<any>(routingRes?.json);
  const nexus = asArray<any>(nexusRes?.json);
  const sellingEntities = asArray<any>(seRes?.json);
  const exposure = asArray<any>(expRes?.json);
  const ratesSt = tableState(ratesRes, ratesRes?.json);
  const routingSt = tableState(routingRes, routingRes?.json);
  const nexusSt = tableState(nexusRes, nexusRes?.json);
  const seSt = tableState(seRes, seRes?.json);
  const expSt = tableState(expRes, expRes?.json);

  const metric = (label: string, value: React.ReactNode, testid: string, accent?: boolean) => (
    <div className="metric"><div className="ml">{label}</div><div className={'mv' + (accent ? ' accent' : '')} style={{ fontSize: 22 }} data-testid={testid}>{value}</div></div>
  );

  return (
    <>
      {toastNode}
      <PageHead crumb="Finance / Govern" title="Tax" sub="Determination as an explainable quote — the why, not just the rate — over an effective-dated, two-person rate table" />

      {/* ===== Determination tester — the hero: WHY this rate ===== */}
      <Card title="Determination tester" icon={I.globe} aux={<span className="dim" style={{ fontSize: 12 }}>supply facts in → place-of-supply, reverse-charge & the per-component reasoning out</span>}>
        <div className="row g8" style={{ flexWrap: 'wrap', marginBottom: 10 }}>
          <span className="dim">Ship from</span>
          <input className="fld" style={{ width: 60 }} data-testid="tax-from" value={q.shipFromJurisdiction} onChange={setF('shipFromJurisdiction')} />
          <span className="dim">to</span>
          <input className="fld" style={{ width: 60 }} data-testid="tax-to" value={q.shipToJurisdiction} onChange={setF('shipToJurisdiction')} />
          <input className="fld" style={{ width: 80 }} data-testid="tax-region" value={q.shipToRegion ?? ''} onChange={setF('shipToRegion')} placeholder="region" />
          <input className="fld" style={{ width: 110 }} data-testid="tax-postcode" value={q.shipToPostcode ?? ''} onChange={setF('shipToPostcode')} placeholder="postcode / ZIP" />
        </div>
        <div className="row g8" style={{ flexWrap: 'wrap' }}>
          <span className="dim">Buyer</span>
          <select className="fld sel" data-testid="tax-party-status" value={q.partyTaxStatus} onChange={setF('partyTaxStatus')}>
            <option value="consumer">consumer</option>
            <option value="business">business</option>
            <option value="business_with_vat_id">business + VAT id</option>
          </select>
          <input className="fld" style={{ width: 140 }} data-testid="tax-vatid" value={q.buyerTaxId ?? ''} onChange={setF('buyerTaxId')} placeholder="VAT id" />
          <span className="dim">Amount</span>
          <input className="fld" style={{ width: 90 }} data-testid="tax-amount" value={q.taxableAmount} onChange={setF('taxableAmount')} />
          <input className="fld" style={{ width: 64 }} data-testid="tax-currency" value={q.currency} onChange={setF('currency')} />
          <button className="btn primary" data-testid="tax-quote-btn" onClick={runQuote}>Quote</button>
        </div>

        {!hasCommercial && (
          <div style={{ marginTop: 14 }}><LayerNote>Rates and tax amounts are hidden — requires <b>commercial</b>.</LayerNote></div>
        )}

        {hasCommercial && quoteRes === null && result === null && (
          <div className="dim" style={{ marginTop: 14, fontSize: 12 }}>Enter the supply facts and run a quote — the breakdown explains every component.</div>
        )}

        {hasCommercial && result && (
          <div style={{ marginTop: 14 }}>
            <div className="row" style={{ gap: 28, flexWrap: 'wrap', marginBottom: 6 }}>
              {metric('Supply kind', <span data-testid="tax-supply-kind">{result.supplyKind}</span>, 'tax-supply-kind-m', true)}
              {metric('Reverse charge', <Chip s={result.reverseCharge ? 'warn' : 'ok'}>{result.reverseCharge ? 'yes — buyer accounts' : 'no'}</Chip>, 'tax-reverse')}
              {metric('Total tax', <Money value={result.taxTotal} ccy={result.currency} layer="commercial" role={role} />, 'tax-total')}
            </div>
            <div className="dim" style={{ fontSize: 12, margin: '4px 0 12px' }} data-testid="tax-why">
              {result.reverseCharge
                ? 'Reverse charge: no VAT is charged here — the buyer self-accounts in their jurisdiction.'
                : result.supplyKind === 'export'
                  ? 'Export: zero-rated out of the origin jurisdiction.'
                  : `${result.supplyKind === 'intra_community' || result.supplyKind === 'ic' ? 'Intra-community' : 'Domestic'} supply — tax resolves to the components below.`}
            </div>
            <div className="tablewrap">
              <table className="tbl" data-testid="tax-components">
                <thead><tr><th>Level</th><th>Jurisdiction</th><th>Name</th><th>Type</th><th className="num">Rate %</th><th className="num">Amount</th></tr></thead>
                <tbody>
                  {(result.lines?.[0]?.components ?? []).map((c: any, i: number) => (
                    <tr key={i} data-testid="tax-comp-row">
                      <td>{c.level}</td><td className="mono">{c.jurisdiction}{c.region ? `/${c.region}` : ''}</td><td>{c.name}</td>
                      <td>{c.taxType}</td><td className="num">{c.ratePct}</td><td className="num"><Money value={c.amount} ccy={result.currency} layer="commercial" role={role} /></td>
                    </tr>
                  ))}
                  {(result.lines?.[0]?.components ?? []).length === 0 && (
                    <EmptyRow cols={6}>No tax components — zero-rated, reverse-charge or export.</EmptyRow>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        )}
        {quoteRes && quoteRes.status >= 400 && quoteRes.status !== 403 && (
          <div className="dim" data-testid="tax-error" style={{ display: 'block', marginTop: 12 }}>Determination failed — {quoteRes.status}: {(quoteRes.json as any)?.message ?? 'see logs'}.</div>
        )}
      </Card>

      {/* ===== Rate table — effective-dated, maker-checker (never edit-in-place) ===== */}
      <Card title="Rate table" icon={I.list} aux={<span className="dim" style={{ fontSize: 12 }}>effective-dated, multi-level — a change is a new dated row, never an edit</span>}>
        <div className="row g8" style={{ marginBottom: 4 }}>
          <span className="dim">Jurisdiction</span>
          <input className="fld" style={{ width: 70 }} data-testid="tax-rate-juris" value={rateJuris} onChange={(e) => setRateJuris(e.target.value.toUpperCase())} />
          <span className="dim" style={{ fontSize: 12 }}>auto-loads as you change it</span>
        </div>
        {!hasCommercial ? (
          <div style={{ marginTop: 12 }}><LayerNote>Rate table hidden — requires <b>commercial</b>.</LayerNote></div>
        ) : (
          <div className="tablewrap" style={{ marginTop: 12 }}>
            <table className="tbl" data-testid="tax-rates-table">
              <thead><tr><th>Type</th><th>Region</th><th>Postcode</th><th>Level</th><th>Name</th><th className="num">Rate %</th><th>From</th><th>To</th><th>Status</th><th /></tr></thead>
              <tbody>
                {ratesSt === 'loading' && <SkeletonRow cols={10} />}
                {ratesSt === 'forbidden' && <EmptyRow cols={10}><LayerNote>Rate table hidden — requires <b>commercial</b>.</LayerNote></EmptyRow>}
                {ratesSt === 'error' && <EmptyRow cols={10}>Could not load rates ({ratesRes?.status}).</EmptyRow>}
                {ratesSt === 'empty' && <EmptyRow cols={10}>No rates for {rateJuris} yet.</EmptyRow>}
                {ratesSt === 'ready' && rates.map((r, i) => {
                  const selfBlocked = r.status === 'draft' && proposedByViewer(r);
                  return (
                    <tr key={r.id ?? i} data-testid="tax-rate-row">
                      <td>{r.tax_type}</td><td>{r.region ?? '—'}</td><td>{r.postcode_prefix ?? '—'}</td><td>{r.level}</td><td>{r.name}</td>
                      <td className="num">{r.rate_pct ?? '—'}</td><td className="mono">{r.effective_from}</td><td className="mono">{r.effective_to ?? '—'}</td>
                      <td><Chip s={r.status}>{r.status}</Chip></td>
                      <td>
                        {r.status === 'draft' && (
                          <button className="btn sm" data-testid="tax-activate" disabled={selfBlocked}
                            title={selfBlocked ? 'You proposed this rate — a second person must activate it' : 'Activate (CFO)'}
                            onClick={() => activate(r)}>Activate (CFO)</button>
                        )}
                        {r.status !== 'draft' && r.activated_by && <AuditRef id={String(r.activated_by).slice(0, 8)} />}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {hasCommercial && (
          <>
            <Sec>Propose a rate (tax specialist) → a second person activates</Sec>
            <div className="row g8" style={{ flexWrap: 'wrap' }}>
              <input className="fld" style={{ width: 60 }} data-testid="tax-pr-juris" value={pr.jurisdiction} onChange={(e) => setPr({ ...pr, jurisdiction: e.target.value })} placeholder="juris" />
              <input className="fld" style={{ width: 80 }} data-testid="tax-pr-type" value={pr.tax_type} onChange={(e) => setPr({ ...pr, tax_type: e.target.value })} placeholder="type" />
              <input className="fld" style={{ width: 80 }} data-testid="tax-pr-region" value={pr.region} onChange={(e) => setPr({ ...pr, region: e.target.value })} placeholder="region" />
              <input className="fld" style={{ width: 90 }} data-testid="tax-pr-level" value={pr.level} onChange={(e) => setPr({ ...pr, level: e.target.value })} placeholder="level" />
              <input className="fld" style={{ width: 120 }} data-testid="tax-pr-name" value={pr.name} onChange={(e) => setPr({ ...pr, name: e.target.value })} placeholder="name" />
              <input className="fld" style={{ width: 60 }} data-testid="tax-pr-rate" value={pr.rate_pct} onChange={(e) => setPr({ ...pr, rate_pct: e.target.value })} placeholder="rate" />
              <input className="fld" style={{ width: 110 }} data-testid="tax-pr-from" value={pr.effective_from} onChange={(e) => setPr({ ...pr, effective_from: e.target.value })} placeholder="from" />
              <button className="btn primary" data-testid="tax-propose-btn" onClick={propose}>Propose</button>
            </div>
          </>
        )}
      </Card>

      {/* ===== Provider routing + economic-nexus board ===== */}
      <Card title="Provider routing" icon={I.globe} aux={<span className="dim" style={{ fontSize: 12 }}>which engine serves a market — flipping to a vendor is a row, not a deploy</span>}>
        <div className="tablewrap">
          <table className="tbl" data-testid="tax-routing-table">
            <thead><tr><th>Jurisdiction</th><th>Tax type</th><th>Provider</th><th className="num">Priority</th></tr></thead>
            <tbody>
              {!hasInterEntity && <EmptyRow cols={4}><LayerNote>Routing hidden — requires <b>inter_entity</b>.</LayerNote></EmptyRow>}
              {hasInterEntity && routingSt === 'loading' && <SkeletonRow cols={4} />}
              {hasInterEntity && routingSt === 'forbidden' && <EmptyRow cols={4}><LayerNote>Routing hidden — requires <b>inter_entity</b>.</LayerNote></EmptyRow>}
              {hasInterEntity && routingSt === 'error' && <EmptyRow cols={4}>Could not load routing ({routingRes?.status}).</EmptyRow>}
              {hasInterEntity && routingSt === 'empty' && <EmptyRow cols={4}>No provider routing configured.</EmptyRow>}
              {hasInterEntity && routingSt === 'ready' && routing.map((r, i) => (
                <tr key={i} data-testid="tax-routing-row">
                  <td className="mono">{r.jurisdiction ?? 'all'}</td><td>{r.tax_type ?? 'all'}</td><td>{r.provider}</td><td className="num">{r.priority}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <Sec>Economic-nexus board</Sec>
        <div className="tablewrap">
          <table className="tbl" data-testid="tax-nexus-table">
            <thead><tr><th>Jurisdiction</th><th>Region</th><th className="num">Sales</th><th className="num">Threshold</th><th>Status</th></tr></thead>
            <tbody>
              {!hasCommercial && <EmptyRow cols={5}><LayerNote>Nexus board hidden — requires <b>commercial</b>.</LayerNote></EmptyRow>}
              {hasCommercial && nexusSt === 'loading' && <SkeletonRow cols={5} />}
              {hasCommercial && nexusSt === 'forbidden' && <EmptyRow cols={5}><LayerNote>Nexus board hidden — requires <b>commercial</b>.</LayerNote></EmptyRow>}
              {hasCommercial && nexusSt === 'error' && <EmptyRow cols={5}>Could not load nexus ({nexusRes?.status}).</EmptyRow>}
              {hasCommercial && nexusSt === 'empty' && <EmptyRow cols={5}>No nexus tracked for this entity.</EmptyRow>}
              {hasCommercial && nexusSt === 'ready' && nexus.map((n, i) => (
                <tr key={i} data-testid="tax-nexus-row">
                  <td className="mono">{n.jurisdiction}</td><td>{n.region}</td>
                  <td className="num"><Money value={n.sales_to_date} layer="commercial" role={role} /></td>
                  <td className="num"><Money value={n.threshold_amount} layer="commercial" role={role} /></td>
                  <td><Chip s={n.status}>{n.status}</Chip></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      {/* ===== Selling entities (seller-of-record) — inter_entity, maker-checker ===== */}
      <Card title="Selling entities" icon={I.scale} aux={<span className="dim" style={{ fontSize: 12 }}>which Hypervolt entity is seller-of-record per jurisdiction — effective-dated, two-person</span>}>
        <div className="tablewrap">
          <table className="tbl" data-testid="tax-se-table">
            <thead><tr><th>Jurisdiction</th><th>Entity</th><th>Currency</th><th>From</th><th>To</th><th>Status</th><th /></tr></thead>
            <tbody>
              {!hasInterEntity && <EmptyRow cols={7}><LayerNote>Seller-of-record map hidden — requires <b>inter_entity</b>.</LayerNote></EmptyRow>}
              {hasInterEntity && seSt === 'loading' && <SkeletonRow cols={7} />}
              {hasInterEntity && seSt === 'forbidden' && <EmptyRow cols={7}><LayerNote>Seller-of-record map hidden — requires <b>inter_entity</b>.</LayerNote></EmptyRow>}
              {hasInterEntity && seSt === 'error' && <EmptyRow cols={7}>Could not load the entity map ({seRes?.status}).</EmptyRow>}
              {hasInterEntity && seSt === 'empty' && <EmptyRow cols={7}>No seller-of-record map yet.</EmptyRow>}
              {hasInterEntity && seSt === 'ready' && sellingEntities.map((s, i) => {
                const selfBlocked = s.status === 'draft' && proposedByViewer(s);
                return (
                  <tr key={s.id ?? i} data-testid="tax-se-row">
                    <td className="mono">{s.jurisdiction}</td><td>{s.entity_name}</td><td>{s.functional_currency}</td>
                    <td className="mono">{s.effective_from}</td><td className="mono">{s.effective_to ?? '—'}</td>
                    <td><Chip s={s.status}>{s.status}</Chip></td>
                    <td>{s.status === 'draft' && (
                      <button className="btn sm" data-testid="tax-se-activate" disabled={selfBlocked}
                        title={selfBlocked ? 'You proposed this map — a second person must activate it' : 'Activate (CFO)'}
                        onClick={() => activateSe(s)}>Activate (CFO)</button>
                    )}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
        {hasInterEntity && (
          <div className="row g8" style={{ marginTop: 12, flexWrap: 'wrap' }}>
            <input className="fld" style={{ width: 60 }} data-testid="tax-se-juris" value={se.jurisdiction} onChange={(e) => setSe({ ...se, jurisdiction: e.target.value })} placeholder="juris" />
            <input className="fld" style={{ width: 300 }} data-testid="tax-se-entity" value={se.entity_id} onChange={(e) => setSe({ ...se, entity_id: e.target.value })} placeholder="entity id" />
            <input className="fld" style={{ width: 110 }} data-testid="tax-se-from" value={se.effective_from} onChange={(e) => setSe({ ...se, effective_from: e.target.value })} placeholder="from" />
            <button className="btn primary" data-testid="tax-se-propose" onClick={proposeSe}>Propose map (admin)</button>
          </div>
        )}
      </Card>

      {/* ===== VAT exposure board ===== */}
      <Card title="VAT exposure" icon={I.scale} aux={<span className="dim" style={{ fontSize: 12 }}>accrued − reversed − remitted = outstanding, per entity × jurisdiction × period</span>}>
        <div className="tablewrap">
          <table className="tbl" data-testid="tax-exposure-table">
            <thead><tr><th>Jurisdiction</th><th>Period</th><th className="num">Accrued</th><th className="num">Reversed</th><th className="num">Remitted</th><th className="num">Outstanding</th></tr></thead>
            <tbody>
              {!hasCommercial && <EmptyRow cols={6}><LayerNote>VAT exposure hidden — requires <b>commercial</b>.</LayerNote></EmptyRow>}
              {hasCommercial && expSt === 'loading' && <SkeletonRow cols={6} />}
              {hasCommercial && expSt === 'forbidden' && <EmptyRow cols={6}><LayerNote>VAT exposure hidden — requires <b>commercial</b>.</LayerNote></EmptyRow>}
              {hasCommercial && expSt === 'error' && <EmptyRow cols={6}>Could not load exposure ({expRes?.status}).</EmptyRow>}
              {hasCommercial && expSt === 'empty' && <EmptyRow cols={6}>No VAT exposure for this entity yet.</EmptyRow>}
              {hasCommercial && expSt === 'ready' && exposure.map((x, i) => (
                <tr key={i} data-testid="tax-exposure-row">
                  <td className="mono">{x.jurisdiction}</td><td className="mono">{x.period}</td>
                  <td className="num"><Money value={x.accrued} ccy={x.currency} layer="commercial" role={role} /></td>
                  <td className="num"><Money value={x.reversed} ccy={x.currency} layer="commercial" role={role} /></td>
                  <td className="num"><Money value={x.remitted} ccy={x.currency} layer="commercial" role={role} /></td>
                  <td className="num" style={{ fontWeight: 700 }}><Money value={x.outstanding} ccy={x.currency} layer="commercial" role={role} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="dim" style={{ fontSize: 12, marginTop: 10 }}>Remittance depletes the VAT ledger and is performed by the consumer off the close cycle — not an ad-hoc desk action.</div>
      </Card>
    </>
  );
}
