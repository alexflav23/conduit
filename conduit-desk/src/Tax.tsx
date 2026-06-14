import React, { useState } from 'react';
import { useApi, request } from './lib/query';
import { ApiError } from './lib/client';
import { TAX_DEMO_ENTITY, type TaxQuoteInput } from './api';
import { PageHead, Card, Chip, Money, LayerNote, AuditRef, EmptyRow, SkeletonRow, Skeleton } from './kit/kit';
import { I } from './kit/icons';

// Tax (doc 16 / spec/ui/11-tax.md): the hero is EXPLAINABILITY. A determination tester resolves the
// place-of-supply and shows the multi-level jurisdiction breakdown WITH its reasoning (supply kind +
// reverse-charge), not just a total. The rate-table admin is effective-dated, two-person (propose →
// activate, self-activation blocked). Routing / selling-entities / VAT-exposure round it out. Rates and
// components ride the `commercial` layer; routing + selling-entities touch `inter_entity` and COLLAPSE.
//
// Backing routes (TaxRoutes):
//   POST /api/v1/tax/quote                                   determination engine (create:tax_quote)
//   GET  /api/v1/tax/rates?jurisdiction=                     effective-dated rate table (view:tax_rate)
//   POST /api/v1/tax/rates                                   propose a draft rate (create:tax_rate)
//   POST /api/v1/tax/rates/{id}/activate                     activate a draft (approve:tax_rate)
//   GET  /api/v1/tax/routing                                 provider routing (view:tax_routing)
//   GET  /api/v1/tax/nexus?entity_id=                        economic-nexus board (view:nexus_profile)
//   GET  /api/v1/tax/selling-entities                        seller-of-record map (view:selling_entity)
//   POST /api/v1/tax/selling-entities                        propose a map (create:selling_entity)
//   POST /api/v1/tax/selling-entities/{id}/activate          activate a map (approve:selling_entity)
//   GET  /api/v1/tax/vat/exposure?entity_id=                 VAT exposure board (view:tax_quote)

const Sec = ({ children }: { children: React.ReactNode }) => (
  <div className="dim" style={{ fontSize: 12, textTransform: 'uppercase', letterSpacing: '0.06em', margin: '16px 0 10px' }}>{children}</div>
);

type Props = { role: any; ctx: any; toast: (m: string, k?: string) => void };

interface TaxComponent { level?: string; jurisdiction?: string; region?: string; name?: string; taxType?: string; ratePct?: number | string; amount?: number | string }
interface TaxLine { components?: TaxComponent[] }
interface TaxResult { supplyKind?: string; reverseCharge?: boolean; taxTotal?: number | string; currency?: string; lines?: TaxLine[] }
interface RateRow { id?: string; tax_type?: string; region?: string | null; postcode_prefix?: string | null; level?: string; name?: string; rate_pct?: number | string | null; effective_from?: string; effective_to?: string | null; status?: string; activated_by?: string; proposed_by?: string; proposer?: string; maker?: string }
interface RoutingRow { jurisdiction?: string; tax_type?: string; provider?: string; priority?: number }
interface NexusRow { jurisdiction?: string; region?: string; sales_to_date?: number | string; threshold_amount?: number | string; status?: string }
interface SellingRow { id?: string; jurisdiction?: string; entity_name?: string; functional_currency?: string; effective_from?: string; effective_to?: string | null; status?: string; proposed_by?: string; proposer?: string; maker?: string }
interface ExposureRow { jurisdiction?: string; period?: string; currency?: string; accrued?: number | string; reversed?: number | string; remitted?: number | string; outstanding?: number | string }

const asArray = <T,>(x: unknown): T[] => (Array.isArray(x) ? (x as T[]) : []);

// The honest unbacked-environment panel (a 404 from the route — the endpoint isn't wired here).
function NotBacked({ what }: { what: string }) {
  return (
    <div style={{ display: 'grid', placeItems: 'center', gap: 10, padding: '30px 24px', textAlign: 'center' }}>
      <span style={{ width: 44, height: 44, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'var(--panel-2)' }}>{I.globe({ size: 22 })}</span>
      <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>Not available in this environment yet</div>
      <div className="dim" style={{ fontSize: 12.5, maxWidth: 460 }}>{what}</div>
    </div>
  );
}

export function Tax({ role, ctx, toast }: Props) {
  const layers: string[] = (role?.layers as string[]) || [];
  const hasCommercial = layers.indexOf('commercial') >= 0;
  const hasInterEntity = layers.indexOf('inter_entity') >= 0;
  const viewer: string = role?.name || role?.token || '';

  // ----- determination tester (an explicit test action, not a load) -----
  const [q, setQ] = useState<TaxQuoteInput>({
    shipFromJurisdiction: 'GB', shipToJurisdiction: 'US', shipToRegion: 'CA', shipToPostcode: '90001',
    partyTaxStatus: 'consumer', buyerTaxId: '', currency: 'USD', taxableAmount: '100.00',
  });
  const [quoting, setQuoting] = useState(false);
  const [quoteErr, setQuoteErr] = useState<ApiError | null>(null);
  const [result, setResult] = useState<TaxResult | null>(null);
  const setF = (k: keyof TaxQuoteInput) => (e: any) => setQ({ ...q, [k]: e.target.value });

  const runQuote = async () => {
    setQuoting(true);
    setQuoteErr(null);
    try {
      const r = await request<TaxResult>('/api/v1/tax/quote', {
        method: 'POST',
        body: JSON.stringify({
          context: 'quote_preview',
          entityId: TAX_DEMO_ENTITY,
          shipFrom: { jurisdiction: q.shipFromJurisdiction, region: null, postcode: null },
          shipTo: { jurisdiction: q.shipToJurisdiction, region: q.shipToRegion || null, postcode: q.shipToPostcode || null },
          partyTaxStatus: q.partyTaxStatus,
          buyerTaxId: q.buyerTaxId || null,
          incoterm: null,
          currency: q.currency,
          asOf: '2026-06-01',
          lines: [{ ref: 'l1', productVariantId: null, taxCategoryCode: 'goods_standard', hsCode: null, qty: 1, taxableAmount: q.taxableAmount }],
        }),
      });
      setResult(r);
    } catch (e) {
      const ae = e as ApiError;
      setResult(null);
      setQuoteErr(ae);
      if (!ae?.forbidden) toast(`Quote failed (${ae?.status ?? '—'})`, 'err');
    } finally {
      setQuoting(false);
    }
  };

  // ----- the auto-loaded tables (React Query, keyed on the ctx/jurisdiction they scope by) -----
  const [rateJuris, setRateJuris] = useState('GB');

  const rates = useApi<RateRow[]>(
    ['tax-rates', rateJuris, ctx?.entity, ctx?.market],
    `/api/v1/tax/rates${rateJuris ? `?jurisdiction=${encodeURIComponent(rateJuris)}` : ''}`,
    { enabled: hasCommercial },
  );
  const routing = useApi<RoutingRow[]>(
    ['tax-routing', ctx?.entity, ctx?.market],
    '/api/v1/tax/routing',
    { enabled: hasInterEntity },
  );
  const nexus = useApi<NexusRow[]>(
    ['tax-nexus', ctx?.entity, ctx?.market],
    `/api/v1/tax/nexus?entity_id=${encodeURIComponent(TAX_DEMO_ENTITY)}`,
    { enabled: hasCommercial },
  );
  const selling = useApi<SellingRow[]>(
    ['tax-selling-entities', ctx?.entity, ctx?.market],
    '/api/v1/tax/selling-entities',
    { enabled: hasInterEntity },
  );
  const exposure = useApi<ExposureRow[]>(
    ['tax-vat-exposure', ctx?.entity, ctx?.market, ctx?.period],
    `/api/v1/tax/vat/exposure?entity_id=${encodeURIComponent(TAX_DEMO_ENTITY)}`,
    { enabled: hasCommercial },
  );

  const rateRows = asArray<RateRow>(rates.data);
  const routingRows = asArray<RoutingRow>(routing.data);
  const nexusRows = asArray<NexusRow>(nexus.data);
  const sellingRows = asArray<SellingRow>(selling.data);
  const exposureRows = asArray<ExposureRow>(exposure.data);

  // ----- propose / activate (maker-checker) -----
  const [pr, setPr] = useState({ jurisdiction: 'FR', tax_type: 'VAT', region: '', level: 'national', name: 'France VAT', rate_pct: '20.0', effective_from: '2026-01-01' });
  const [busy, setBusy] = useState(false);

  const propose = async () => {
    setBusy(true);
    try {
      const r = await request<{ id?: string }>('/api/v1/tax/rates', {
        method: 'POST',
        body: JSON.stringify({
          tax_type: pr.tax_type,
          jurisdiction: pr.jurisdiction,
          region: pr.region || null,
          postcode_prefix: null,
          level: pr.level,
          tax_category_code: 'goods_standard',
          name: pr.name,
          rate_pct: Number(pr.rate_pct),
          kind: 'standard',
          effective_from: pr.effective_from,
        }),
      });
      toast(`Proposed draft ${String(r?.id ?? '').slice(0, 8)} — needs a second person`, 'ok');
      setRateJuris(pr.jurisdiction);
      await rates.refetch();
    } catch (e) {
      toast(`Propose failed (${(e as ApiError)?.status ?? '—'})`, 'err');
    } finally {
      setBusy(false);
    }
  };
  const activate = async (row: RateRow) => {
    if (!row.id) return;
    setBusy(true);
    try {
      await request(`/api/v1/tax/rates/${row.id}/activate`, { method: 'POST' });
      toast('Rate activated', 'ok');
      await rates.refetch();
    } catch (e) {
      const ae = e as ApiError;
      toast(`Activate failed (${ae?.status ?? '—'}: ${ae?.message ?? ''})`, 'err');
    } finally {
      setBusy(false);
    }
  };

  const [se, setSe] = useState({ jurisdiction: 'DE', entity_id: TAX_DEMO_ENTITY, effective_from: '2026-01-01' });
  const proposeSe = async () => {
    setBusy(true);
    try {
      const r = await request<{ id?: string }>('/api/v1/tax/selling-entities', {
        method: 'POST',
        body: JSON.stringify({ jurisdiction: se.jurisdiction, entity_id: se.entity_id, effective_from: se.effective_from }),
      });
      toast(`Proposed entity map ${String(r?.id ?? '').slice(0, 8)}`, 'ok');
      await selling.refetch();
    } catch (e) {
      toast(`Propose failed (${(e as ApiError)?.status ?? '—'})`, 'err');
    } finally {
      setBusy(false);
    }
  };
  const activateSe = async (row: SellingRow) => {
    if (!row.id) return;
    setBusy(true);
    try {
      await request(`/api/v1/tax/selling-entities/${row.id}/activate`, { method: 'POST' });
      toast('Entity map activated', 'ok');
      await selling.refetch();
    } catch (e) {
      const ae = e as ApiError;
      toast(`Activate failed (${ae?.status ?? '—'}: ${ae?.message ?? ''})`, 'err');
    } finally {
      setBusy(false);
    }
  };

  // self-activation guard: the proposer of a draft may not activate it themselves.
  const proposedByViewer = (row: RateRow | SellingRow) => !!viewer && (row.proposed_by === viewer || row.proposer === viewer || row.maker === viewer);

  const metric = (label: string, value: React.ReactNode, testid: string, accent?: boolean) => (
    <div className="metric"><div className="ml">{label}</div><div className={'mv' + (accent ? ' accent' : '')} style={{ fontSize: 22 }} data-testid={testid}>{value}</div></div>
  );

  const components: TaxComponent[] = result?.lines?.[0]?.components ?? [];

  return (
    <>
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
          <button className="btn primary" data-testid="tax-quote-btn" onClick={runQuote} disabled={quoting || !hasCommercial}>{quoting ? 'Quoting…' : 'Quote'}</button>
        </div>

        {!hasCommercial && (
          <div style={{ marginTop: 14 }}><LayerNote>Rates and tax amounts are hidden — requires <b>commercial</b>.</LayerNote></div>
        )}

        {hasCommercial && quoting && (
          <div style={{ marginTop: 14 }} data-testid="tax-quote-loading"><Skeleton lines={4} /></div>
        )}

        {hasCommercial && !quoting && quoteErr?.forbidden && (
          <div style={{ marginTop: 14 }}><LayerNote>Determination is hidden — requires <b>commercial</b>.</LayerNote></div>
        )}

        {hasCommercial && !quoting && quoteErr?.notImplemented && (
          <div style={{ marginTop: 14 }} data-testid="tax-quote-unbacked"><NotBacked what="The determination engine appears once the tax service is wired in this environment." /></div>
        )}

        {hasCommercial && !quoting && quoteErr && !quoteErr.forbidden && !quoteErr.notImplemented && (
          <div className="dim" data-testid="tax-error" style={{ display: 'block', marginTop: 12 }}>Determination failed — {quoteErr.status}: {quoteErr.message ?? 'see logs'}.</div>
        )}

        {hasCommercial && !quoting && !quoteErr && result === null && (
          <div className="dim" style={{ marginTop: 14, fontSize: 12 }}>Enter the supply facts and run a quote — the breakdown explains every component.</div>
        )}

        {hasCommercial && !quoting && !quoteErr && result && (
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
                  {components.map((c, i) => (
                    <tr key={i} data-testid="tax-comp-row">
                      <td>{c.level}</td><td className="mono">{c.jurisdiction}{c.region ? `/${c.region}` : ''}</td><td>{c.name}</td>
                      <td>{c.taxType}</td><td className="num">{c.ratePct}</td><td className="num"><Money value={c.amount} ccy={result.currency} layer="commercial" role={role} /></td>
                    </tr>
                  ))}
                  {components.length === 0 && (
                    <EmptyRow cols={6}>No tax components — zero-rated, reverse-charge or export.</EmptyRow>
                  )}
                </tbody>
              </table>
            </div>
          </div>
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
        ) : rates.error?.notImplemented ? (
          <div data-testid="tax-rates-unbacked"><NotBacked what="The effective-dated rate table appears once the tax service is wired in this environment." /></div>
        ) : (
          <div className="tablewrap" style={{ marginTop: 12 }}>
            <table className="tbl" data-testid="tax-rates-table">
              <thead><tr><th>Type</th><th>Region</th><th>Postcode</th><th>Level</th><th>Name</th><th className="num">Rate %</th><th>From</th><th>To</th><th>Status</th><th /></tr></thead>
              <tbody>
                {rates.isLoading && <SkeletonRow cols={10} />}
                {!rates.isLoading && rates.error?.forbidden && <EmptyRow cols={10}><LayerNote>Rate table hidden — requires <b>commercial</b>.</LayerNote></EmptyRow>}
                {!rates.isLoading && rates.error && !rates.error.forbidden && !rates.error.notImplemented && <EmptyRow cols={10}>Could not load rates ({rates.error.status}).</EmptyRow>}
                {!rates.isLoading && !rates.error && rateRows.length === 0 && <EmptyRow cols={10}>No rates for {rateJuris} yet.</EmptyRow>}
                {!rates.isLoading && !rates.error && rateRows.map((r, i) => {
                  const selfBlocked = r.status === 'draft' && proposedByViewer(r);
                  return (
                    <tr key={r.id ?? i} data-testid="tax-rate-row">
                      <td>{r.tax_type}</td><td>{r.region ?? '—'}</td><td>{r.postcode_prefix ?? '—'}</td><td>{r.level}</td><td>{r.name}</td>
                      <td className="num">{r.rate_pct ?? '—'}</td><td className="mono">{r.effective_from}</td><td className="mono">{r.effective_to ?? '—'}</td>
                      <td><Chip s={r.status ?? 'neutral'}>{r.status}</Chip></td>
                      <td>
                        {r.status === 'draft' && (
                          <button className="btn sm" data-testid="tax-activate" disabled={selfBlocked || busy}
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
        {hasCommercial && !rates.error?.notImplemented && (
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
              <button className="btn primary" data-testid="tax-propose-btn" onClick={propose} disabled={busy}>Propose</button>
            </div>
          </>
        )}
      </Card>

      {/* ===== Provider routing + economic-nexus board ===== */}
      <Card title="Provider routing" icon={I.globe} aux={<span className="dim" style={{ fontSize: 12 }}>which engine serves a market — flipping to a vendor is a row, not a deploy</span>}>
        {routing.error?.notImplemented ? (
          <div data-testid="tax-routing-unbacked"><NotBacked what="Provider routing appears once the tax service is wired in this environment." /></div>
        ) : (
          <div className="tablewrap">
            <table className="tbl" data-testid="tax-routing-table">
              <thead><tr><th>Jurisdiction</th><th>Tax type</th><th>Provider</th><th className="num">Priority</th></tr></thead>
              <tbody>
                {!hasInterEntity && <EmptyRow cols={4}><LayerNote>Routing hidden — requires <b>inter_entity</b>.</LayerNote></EmptyRow>}
                {hasInterEntity && routing.isLoading && <SkeletonRow cols={4} />}
                {hasInterEntity && !routing.isLoading && routing.error?.forbidden && <EmptyRow cols={4}><LayerNote>Routing hidden — requires <b>inter_entity</b>.</LayerNote></EmptyRow>}
                {hasInterEntity && !routing.isLoading && routing.error && !routing.error.forbidden && !routing.error.notImplemented && <EmptyRow cols={4}>Could not load routing ({routing.error.status}).</EmptyRow>}
                {hasInterEntity && !routing.isLoading && !routing.error && routingRows.length === 0 && <EmptyRow cols={4}>No provider routing configured.</EmptyRow>}
                {hasInterEntity && !routing.isLoading && !routing.error && routingRows.map((r, i) => (
                  <tr key={i} data-testid="tax-routing-row">
                    <td className="mono">{r.jurisdiction ?? 'all'}</td><td>{r.tax_type ?? 'all'}</td><td>{r.provider}</td><td className="num">{r.priority}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <Sec>Economic-nexus board</Sec>
        {nexus.error?.notImplemented ? (
          <div data-testid="tax-nexus-unbacked"><NotBacked what="The economic-nexus board appears once the tax service is wired in this environment." /></div>
        ) : (
          <div className="tablewrap">
            <table className="tbl" data-testid="tax-nexus-table">
              <thead><tr><th>Jurisdiction</th><th>Region</th><th className="num">Sales</th><th className="num">Threshold</th><th>Status</th></tr></thead>
              <tbody>
                {!hasCommercial && <EmptyRow cols={5}><LayerNote>Nexus board hidden — requires <b>commercial</b>.</LayerNote></EmptyRow>}
                {hasCommercial && nexus.isLoading && <SkeletonRow cols={5} />}
                {hasCommercial && !nexus.isLoading && nexus.error?.forbidden && <EmptyRow cols={5}><LayerNote>Nexus board hidden — requires <b>commercial</b>.</LayerNote></EmptyRow>}
                {hasCommercial && !nexus.isLoading && nexus.error && !nexus.error.forbidden && !nexus.error.notImplemented && <EmptyRow cols={5}>Could not load nexus ({nexus.error.status}).</EmptyRow>}
                {hasCommercial && !nexus.isLoading && !nexus.error && nexusRows.length === 0 && <EmptyRow cols={5}>No nexus tracked for this entity.</EmptyRow>}
                {hasCommercial && !nexus.isLoading && !nexus.error && nexusRows.map((n, i) => (
                  <tr key={i} data-testid="tax-nexus-row">
                    <td className="mono">{n.jurisdiction}</td><td>{n.region}</td>
                    <td className="num"><Money value={n.sales_to_date} layer="commercial" role={role} /></td>
                    <td className="num"><Money value={n.threshold_amount} layer="commercial" role={role} /></td>
                    <td><Chip s={n.status ?? 'neutral'}>{n.status}</Chip></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* ===== Selling entities (seller-of-record) — inter_entity, maker-checker ===== */}
      <Card title="Selling entities" icon={I.scale} aux={<span className="dim" style={{ fontSize: 12 }}>which Hypervolt entity is seller-of-record per jurisdiction — effective-dated, two-person</span>}>
        {selling.error?.notImplemented ? (
          <div data-testid="tax-se-unbacked"><NotBacked what="The seller-of-record map appears once the tax service is wired in this environment." /></div>
        ) : (
          <div className="tablewrap">
            <table className="tbl" data-testid="tax-se-table">
              <thead><tr><th>Jurisdiction</th><th>Entity</th><th>Currency</th><th>From</th><th>To</th><th>Status</th><th /></tr></thead>
              <tbody>
                {!hasInterEntity && <EmptyRow cols={7}><LayerNote>Seller-of-record map hidden — requires <b>inter_entity</b>.</LayerNote></EmptyRow>}
                {hasInterEntity && selling.isLoading && <SkeletonRow cols={7} />}
                {hasInterEntity && !selling.isLoading && selling.error?.forbidden && <EmptyRow cols={7}><LayerNote>Seller-of-record map hidden — requires <b>inter_entity</b>.</LayerNote></EmptyRow>}
                {hasInterEntity && !selling.isLoading && selling.error && !selling.error.forbidden && !selling.error.notImplemented && <EmptyRow cols={7}>Could not load the entity map ({selling.error.status}).</EmptyRow>}
                {hasInterEntity && !selling.isLoading && !selling.error && sellingRows.length === 0 && <EmptyRow cols={7}>No seller-of-record map yet.</EmptyRow>}
                {hasInterEntity && !selling.isLoading && !selling.error && sellingRows.map((s, i) => {
                  const selfBlocked = s.status === 'draft' && proposedByViewer(s);
                  return (
                    <tr key={s.id ?? i} data-testid="tax-se-row">
                      <td className="mono">{s.jurisdiction}</td><td>{s.entity_name}</td><td>{s.functional_currency}</td>
                      <td className="mono">{s.effective_from}</td><td className="mono">{s.effective_to ?? '—'}</td>
                      <td><Chip s={s.status ?? 'neutral'}>{s.status}</Chip></td>
                      <td>{s.status === 'draft' && (
                        <button className="btn sm" data-testid="tax-se-activate" disabled={selfBlocked || busy}
                          title={selfBlocked ? 'You proposed this map — a second person must activate it' : 'Activate (CFO)'}
                          onClick={() => activateSe(s)}>Activate (CFO)</button>
                      )}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {hasInterEntity && !selling.error?.notImplemented && (
          <div className="row g8" style={{ marginTop: 12, flexWrap: 'wrap' }}>
            <input className="fld" style={{ width: 60 }} data-testid="tax-se-juris" value={se.jurisdiction} onChange={(e) => setSe({ ...se, jurisdiction: e.target.value })} placeholder="juris" />
            <input className="fld" style={{ width: 300 }} data-testid="tax-se-entity" value={se.entity_id} onChange={(e) => setSe({ ...se, entity_id: e.target.value })} placeholder="entity id" />
            <input className="fld" style={{ width: 110 }} data-testid="tax-se-from" value={se.effective_from} onChange={(e) => setSe({ ...se, effective_from: e.target.value })} placeholder="from" />
            <button className="btn primary" data-testid="tax-se-propose" onClick={proposeSe} disabled={busy}>Propose map (admin)</button>
          </div>
        )}
      </Card>

      {/* ===== VAT exposure board ===== */}
      <Card title="VAT exposure" icon={I.scale} aux={<span className="dim" style={{ fontSize: 12 }}>accrued − reversed − remitted = outstanding, per entity × jurisdiction × period</span>}>
        {exposure.error?.notImplemented ? (
          <div data-testid="tax-exposure-unbacked"><NotBacked what="The VAT exposure board appears once the tax service is wired in this environment." /></div>
        ) : (
          <div className="tablewrap">
            <table className="tbl" data-testid="tax-exposure-table">
              <thead><tr><th>Jurisdiction</th><th>Period</th><th className="num">Accrued</th><th className="num">Reversed</th><th className="num">Remitted</th><th className="num">Outstanding</th></tr></thead>
              <tbody>
                {!hasCommercial && <EmptyRow cols={6}><LayerNote>VAT exposure hidden — requires <b>commercial</b>.</LayerNote></EmptyRow>}
                {hasCommercial && exposure.isLoading && <SkeletonRow cols={6} />}
                {hasCommercial && !exposure.isLoading && exposure.error?.forbidden && <EmptyRow cols={6}><LayerNote>VAT exposure hidden — requires <b>commercial</b>.</LayerNote></EmptyRow>}
                {hasCommercial && !exposure.isLoading && exposure.error && !exposure.error.forbidden && !exposure.error.notImplemented && <EmptyRow cols={6}>Could not load exposure ({exposure.error.status}).</EmptyRow>}
                {hasCommercial && !exposure.isLoading && !exposure.error && exposureRows.length === 0 && <EmptyRow cols={6}>No VAT exposure for this entity yet.</EmptyRow>}
                {hasCommercial && !exposure.isLoading && !exposure.error && exposureRows.map((x, i) => (
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
        )}
        <div className="dim" style={{ fontSize: 12, marginTop: 10 }}>Remittance depletes the VAT ledger and is performed by the consumer off the close cycle — not an ad-hoc desk action.</div>
      </Card>
    </>
  );
}
