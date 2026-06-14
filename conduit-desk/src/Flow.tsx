import React, { useState } from 'react';
import { marketId } from './api';
import { useApi } from './lib/query';
import { ApiError } from './lib/client';
import { PageHead, Card, Chip, Drawer, AuditRef, LayerNote, Skeleton, EmptyRow, num, gbp } from './kit/kit';
import { I } from './kit/icons';

// Flow (spec/ui/04-flow.md, doc 20 D9): the 7-stage demand→cash waterfall — forecast → CM-committed →
// produced → delivered → ordered → shipped → revenue — where the GAPS BETWEEN STAGES are the story, and
// every figure traces to its TigerBeetle transfers. Unit stages are `volume`; revenue is `commercial`;
// COGS/margin is `profitability` (collapse, never zero). Auto-loads on mount + ctx change. No load button.
//
// Real endpoints (api H6QRoutes):
//   GET /api/v1/h6q/waterfall?variant=<uuid>&period=<YYYY-MM> -> { stages{...}, revenue_ex_vat, conversion }
//   GET /api/v1/h6q/ledger?market=<uuid>&period=<YYYY-MM>     -> { totals, recognitions[] }
//   GET /api/v1/h6q/variants                                  -> [{ id, sku, family }]
// Both waterfall + ledger require view:pipeline_coverage (403 -> LayerNote, volume layer).

const FLOW_STAGES: Array<[string, string, string]> = [
  ['sales_forecast', 'Forecast', 'intent'],
  ['cm_committed', 'Committed', 'firm PO'],
  ['cm_produced', 'Produced', 'CM built'],
  ['delivered', 'Delivered', 'received'],
  ['ordered', 'Ordered', 'sold'],
  ['shipped', 'Shipped', 'dispatched'],
];
const MONTHS = ['2026-08', '2026-09', '2026-10'];

type Ctx = { entity: string; market: string; period: string; scenario: string };
type Role = { token: string; name: string; title: string; layers: string[] };

type Variant = { id: string; sku: string; family: string };
type Waterfall = {
  stages?: Record<string, number>;
  revenue_ex_vat?: string | null;
};
type Recognition = {
  invoice_no?: string;
  revenue_ex_vat?: string | number;
  vat?: string | number;
  cogs?: string | number;
  gross_margin?: string | number;
  ar_transfer_id?: string;
  cogs_transfer_id?: string;
};
type Ledger = {
  totals?: { revenue_ex_vat?: string | number; vat?: string | number; cogs?: string | number; gross_margin?: string | number };
  recognitions?: Recognition[];
};

const isForbidden = (e: ApiError | null | undefined) => !!e && e.forbidden;
const isNotImplemented = (e: ApiError | null | undefined) => !!e && e.notImplemented;

function NotAvailable({ testid }: { testid?: string }) {
  return (
    <div className="banner" data-testid={testid} style={{ padding: '14px 12px', color: 'var(--muted)' }}>
      {I.alert({ size: 15 })} Not available in this environment yet.
    </div>
  );
}

export function Flow({ role, ctx }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const r = role as Role;
  const c = ctx as Ctx;
  const hasCommercial = r.layers.indexOf('commercial') >= 0;
  const hasProfit = r.layers.indexOf('profitability') >= 0;

  const month = MONTHS.indexOf(c.period) >= 0 ? c.period : '2026-09';
  const mkt = marketId(c.market);

  const variantsQ = useApi<Variant[]>(['flow-variants'], '/api/v1/h6q/variants');
  const variants = Array.isArray(variantsQ.data) ? variantsQ.data : [];

  const [variant, setVariant] = useState<string>('');
  const selectedVariant = variant || variants[0]?.id || '';

  const wfPath = (m: string) => `/api/v1/h6q/waterfall?variant=${encodeURIComponent(selectedVariant)}&period=${m}`;
  const wfEnabled = !!selectedVariant;
  const wf0 = useApi<Waterfall>(['flow-wf', selectedVariant, MONTHS[0]], wfPath(MONTHS[0]), { enabled: wfEnabled });
  const wf1 = useApi<Waterfall>(['flow-wf', selectedVariant, MONTHS[1]], wfPath(MONTHS[1]), { enabled: wfEnabled });
  const wf2 = useApi<Waterfall>(['flow-wf', selectedVariant, MONTHS[2]], wfPath(MONTHS[2]), { enabled: wfEnabled });
  const monthQueries = { [MONTHS[0]]: wf0, [MONTHS[1]]: wf1, [MONTHS[2]]: wf2 };
  const grid: Record<string, Waterfall | undefined> = {
    [MONTHS[0]]: wf0.data,
    [MONTHS[1]]: wf1.data,
    [MONTHS[2]]: wf2.data,
  };

  const wfLoading = wfEnabled ? Object.values(monthQueries).some((q) => q.isLoading) : variantsQ.isLoading;
  const wfError = (Object.values(monthQueries).find((q) => q.error)?.error ?? variantsQ.error) as ApiError | undefined;
  const wfForbidden = isForbidden(wfError);
  const wfNotImpl = isNotImplemented(wfError);
  const wfOtherError = wfError && !wfForbidden && !wfNotImpl ? wfError : null;

  const ledgerQ = useApi<Ledger>(['flow-ledger', mkt, month], `/api/v1/h6q/ledger?market=${encodeURIComponent(mkt)}&period=${month}`, {
    enabled: hasCommercial && !!mkt,
  });
  const ledger = ledgerQ.data ?? null;
  const ledgerForbidden = isForbidden(ledgerQ.error as ApiError);
  const ledgerNotImpl = isNotImplemented(ledgerQ.error as ApiError);

  const wf = grid[month];
  const stages = wf
    ? FLOW_STAGES.map(([k, label, sub]) => ({ k, label, sub, qty: wf.stages?.[k] ?? 0 }))
    : [];

  const [drill, setDrill] = useState<Recognition | null>(null);

  const gapChip = (prev: number, cur: number, idx: number) => {
    if (!prev || cur >= prev) return null;
    const ratio = cur / prev;
    if (ratio >= 0.93) return null;
    const sev = ratio < 0.78 ? 'danger' : 'warn';
    return <span className={'gap ' + sev}>{num(prev - cur)} {idx === 2 ? 'short' : 'gap'}</span>;
  };

  const openDrill = (rec: Recognition) => {
    if (!hasCommercial) return;
    setDrill(rec);
  };

  const right = (
    <div className="row g8">
      <div className="seg" data-testid="flow-month">
        {MONTHS.map((m) => (
          <button key={m} className={month === m ? 'on' : ''} disabled title="period set in the top bar context">
            {m.slice(5)}
          </button>
        ))}
      </div>
      <select
        className="fld sel"
        data-testid="flow-variant"
        value={selectedVariant}
        disabled={variantsQ.isLoading || variants.length === 0}
        onChange={(e) => setVariant(e.target.value)}
      >
        {variants.length === 0 && <option value="">{variantsQ.isLoading ? 'Loading…' : 'No products'}</option>}
        {variants.map((v) => (
          <option key={v.id} value={v.id}>{v.sku} · {v.family}</option>
        ))}
      </select>
    </div>
  );

  const variantLabel = variants.find((v) => v.id === selectedVariant)?.sku ?? 'product';

  return (
    <div className="page">
      <PageHead
        crumb={`H6Q · Flow · ${c.market}`}
        title="Flow"
        sub="One demand, seven variants — never conflated. Forecast → committed → produced → delivered → ordered → shipped → revenue. Money is traced, not asserted."
        right={right}
      />

      <Card
        title={`Waterfall · ${variantLabel} · ${month}`}
        icon={I.trend}
        aux={<span className="dim" style={{ fontSize: 12 }}>conversion between stages · gaps are the story</span>}
        style={{ marginBottom: 12 }}
        className="tablewrap"
      >
        {wfLoading && (
          <div className="row g12" data-testid="flow-loading" style={{ padding: '8px 0' }}>
            {FLOW_STAGES.map((s) => <Skeleton key={s[0]} w={132} h={86} />)}
          </div>
        )}

        {!wfLoading && wfForbidden && (
          <LayerNote>hidden — the demand waterfall requires the <b>volume</b> layer.</LayerNote>
        )}

        {!wfLoading && !wfForbidden && wfNotImpl && (
          <NotAvailable testid="flow-notimpl" />
        )}

        {!wfLoading && !wfForbidden && !wfNotImpl && wfOtherError && (
          <div className="banner danger" data-testid="flow-error">{I.alert({ size: 15 })} Waterfall failed ({wfOtherError.status}).</div>
        )}

        {!wfLoading && !wfForbidden && !wfNotImpl && !wfOtherError && !wf && (
          <div className="dim" data-testid="flow-empty" style={{ padding: '18px 4px' }}>No data for this variant/period.</div>
        )}

        {!wfLoading && !wfForbidden && !wfNotImpl && !wfOtherError && wf && (
          <>
            <div className="wf" data-testid="flow-waterfall">
              {stages.map((st, i) => (
                <React.Fragment key={st.k}>
                  {i > 0 && (
                    <div className="conn">
                      <span className="conv">{stages[i - 1].qty ? Math.round((st.qty / stages[i - 1].qty) * 100) + '%' : '—'}</span>
                      {I.arrowR()}
                      {gapChip(stages[i - 1].qty, st.qty, i)}
                    </div>
                  )}
                  <div className="stage" data-testid={`flow-stage-${st.k}`}>
                    <div className="box">
                      <div className="vlabel">{st.label}</div>
                      <div className="qty num">{num(st.qty)}</div>
                      <div className="sub2">{st.sub}</div>
                    </div>
                  </div>
                </React.Fragment>
              ))}
              {hasCommercial && wf.revenue_ex_vat != null && (
                <>
                  <div className="conn">
                    <span className="conv">{gbp(Math.round(parseFloat(String(wf.revenue_ex_vat)) / Math.max(1, wf.stages?.shipped ?? 1)))}/u</span>
                    {I.arrowR()}
                  </div>
                  <div className="stage" data-testid="flow-stage-revenue">
                    <div className="box rev">
                      <div className="vlabel" style={{ color: 'var(--accent-bright)' }}>Revenue ex-VAT</div>
                      <div className="qty num hv-gradient-text">{gbp(wf.revenue_ex_vat)}</div>
                      <div className="sub2 row g6">{I.check({ size: 11, style: { color: 'var(--ok)' } })} ledger-proven</div>
                    </div>
                  </div>
                </>
              )}
            </div>
            {!hasCommercial && (
              <LayerNote>The revenue stage is layer-restricted for your role — the waterfall ends at Shipped (requires <b>commercial</b>).</LayerNote>
            )}
          </>
        )}
      </Card>

      <div className="grid" style={{ gridTemplateColumns: '1.1fr 1fr', alignItems: 'start' }}>
        <Card title="Evolution — the same demand ages across periods" icon={I.clock} className="tablewrap">
          {wfLoading && <Skeleton lines={6} />}
          {!wfLoading && wfForbidden && (
            <LayerNote>hidden — the demand waterfall requires the <b>volume</b> layer.</LayerNote>
          )}
          {!wfLoading && !wfForbidden && wfNotImpl && <NotAvailable />}
          {!wfLoading && !wfForbidden && !wfNotImpl && !wfOtherError && (
            <table className="mx" data-testid="flow-evolution">
              <thead>
                <tr>
                  <th>Variant</th>
                  {MONTHS.map((m) => <th key={m} style={{ color: m === month ? 'var(--text)' : undefined }}>{m.slice(5)}</th>)}
                </tr>
              </thead>
              <tbody>
                {FLOW_STAGES.map(([k, label]) => (
                  <tr key={k} data-testid={`flow-evo-${k}`}>
                    <td>{label}</td>
                    {MONTHS.map((m) => (
                      <td key={m} style={{ fontWeight: m === month ? 700 : 400, color: m === month ? 'var(--text)' : 'var(--muted)' }}>
                        {grid[m]?.stages ? num(grid[m]!.stages![k]) : '—'}
                      </td>
                    ))}
                  </tr>
                ))}
                {hasCommercial && (
                  <tr data-testid="flow-evo-revenue">
                    <td style={{ color: 'var(--accent-bright)', fontWeight: 600 }}>Revenue ex-VAT</td>
                    {MONTHS.map((m) => (
                      <td key={m} style={{ color: 'var(--accent-bright)', fontWeight: m === month ? 700 : 400 }}>
                        {grid[m]?.revenue_ex_vat != null ? gbp(grid[m]!.revenue_ex_vat) : '—'}
                      </td>
                    ))}
                  </tr>
                )}
              </tbody>
            </table>
          )}
          <LayerNote>A stage at a lower number is a business state, not an error — Oct is partial (forecast firm, production still behind commitment).</LayerNote>
        </Card>

        {hasCommercial && (
          <Card title={`Immutable ledger · recognised revenue (${month})`} icon={I.shield} className="tablewrap">
            {ledgerQ.isLoading && <Skeleton lines={5} />}
            {!ledgerQ.isLoading && ledgerForbidden && (
              <LayerNote>hidden — recognised revenue requires the <b>commercial</b> layer.</LayerNote>
            )}
            {!ledgerQ.isLoading && !ledgerForbidden && ledgerNotImpl && <NotAvailable testid="ledger-notimpl" />}
            {!ledgerQ.isLoading && !ledgerForbidden && !ledgerNotImpl && !ledger && (
              <div className="dim" data-testid="ledger-empty" style={{ padding: '18px 4px' }}>No recognised revenue for this period.</div>
            )}
            {!ledgerQ.isLoading && !ledgerForbidden && !ledgerNotImpl && ledger && (
              <>
                <div className="row g8 wrap" style={{ marginBottom: 12 }} data-testid="ledger-totals">
                  <Chip s="neutral">Revenue {gbp(ledger.totals?.revenue_ex_vat)}</Chip>
                  <Chip s="neutral">VAT {gbp(ledger.totals?.vat)}</Chip>
                  {hasProfit && <Chip s="neutral">COGS {gbp(ledger.totals?.cogs)}</Chip>}
                  {hasProfit && <Chip s="ok">GM {gbp(ledger.totals?.gross_margin)}</Chip>}
                </div>
                <table className="tbl" data-testid="ledger-table">
                  <thead>
                    <tr>
                      <th>Invoice</th>
                      <th className="num">Revenue</th>
                      <th className="num">VAT</th>
                      {hasProfit && <th className="num">Margin</th>}
                      <th>AR transfer</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(ledger.recognitions ?? []).length === 0 ? (
                      <EmptyRow cols={hasProfit ? 5 : 4}>No revenue recognitions yet.</EmptyRow>
                    ) : (
                      (ledger.recognitions ?? []).map((rec, i) => (
                        <tr key={i} data-testid="ledger-row">
                          <td><b>{rec.invoice_no ?? '—'}</b></td>
                          <td className="num">{gbp(rec.revenue_ex_vat)}</td>
                          <td className="num">{gbp(rec.vat)}</td>
                          {hasProfit && <td className="num" style={{ color: 'var(--ok)' }}>{gbp(rec.gross_margin)}</td>}
                          <td style={{ cursor: 'pointer' }} onClick={() => openDrill(rec)} title="drill to TigerBeetle transfers">
                            <AuditRef id={(rec.ar_transfer_id ?? '').slice(0, 18) + '…'} />
                          </td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
                <div className="dim" style={{ fontSize: 11.5, marginTop: 10, lineHeight: 1.5 }}>
                  Each figure posts DR AR / CR Revenue + VAT and DR COGS / CR Inventory in the immutable log — the transfer ids are the proof.
                </div>
              </>
            )}
          </Card>
        )}
      </div>

      <Drawer
        open={!!drill}
        onClose={() => setDrill(null)}
        chip={drill && <Chip s="ok">recognised</Chip>}
        title={drill ? `Invoice ${drill.invoice_no ?? '—'}` : ''}
        sub="TigerBeetle transfers — the proof this figure is real"
        width={520}
      >
        {drill && (
          <>
            <div className="kvrow"><span className="muted">Revenue ex-VAT</span><span className="num">{gbp(drill.revenue_ex_vat)}</span></div>
            <div className="kvrow"><span className="muted">VAT</span><span className="num">{gbp(drill.vat)}</span></div>
            {hasProfit && <div className="kvrow"><span className="muted">COGS</span><span className="num">{gbp(drill.cogs)}</span></div>}
            {hasProfit && <div className="kvrow"><span className="muted">Gross margin</span><span className="num" style={{ color: 'var(--ok)' }}>{gbp(drill.gross_margin)}</span></div>}
            <div style={{ marginTop: 16, marginBottom: 8, fontSize: 11, letterSpacing: '0.08em', textTransform: 'uppercase', color: 'var(--faint)', fontWeight: 700 }}>Postings</div>
            <table className="tbl">
              <thead><tr><th>Posting</th><th>Transfer id</th></tr></thead>
              <tbody>
                <tr>
                  <td>DR AR / CR Revenue + VAT</td>
                  <td><AuditRef id={drill.ar_transfer_id ?? '—'} /></td>
                </tr>
                {hasProfit && drill.cogs_transfer_id && (
                  <tr>
                    <td>DR COGS / CR Inventory</td>
                    <td><AuditRef id={drill.cogs_transfer_id} /></td>
                  </tr>
                )}
              </tbody>
            </table>
          </>
        )}
      </Drawer>
    </div>
  );
}
