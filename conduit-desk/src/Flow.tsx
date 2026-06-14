import React, { useState, useEffect } from 'react';
import { getWaterfall, getLedger } from './api';
import { PageHead, Card, Chip, Drawer, AuditRef, LayerNote, Skeleton, SkeletonRow, EmptyRow, num, gbp } from './kit/kit';
import { I } from './kit/icons';

// Flow (spec/ui/04-flow.md, doc 20 D9): the 7-stage demand→cash waterfall — forecast → CM-committed →
// produced → delivered → ordered → shipped → revenue — where the GAPS BETWEEN STAGES are the story, and
// every figure traces to its TigerBeetle transfers. Unit stages are `volume`; revenue is `commercial`;
// COGS/margin is `profitability` (collapse, never zero). Auto-loads on mount + ctx change. No load button.

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

export function Flow({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const r = role as Role;
  const c = ctx as Ctx;
  const hasVolume = r.layers.indexOf('volume') >= 0;
  const hasCommercial = r.layers.indexOf('commercial') >= 0;
  const hasProfit = r.layers.indexOf('profitability') >= 0;

  const month = MONTHS.indexOf(c.period) >= 0 ? c.period : '2026-09';
  const [variant, setVariant] = useState('ALL');
  const [grid, setGrid] = useState<Record<string, any>>({}); // period -> waterfall json
  const [ledger, setLedger] = useState<any | null>(null);
  const [loading, setLoading] = useState(true);
  const [forbidden, setForbidden] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [drill, setDrill] = useState<any | null>(null);

  useEffect(() => {
    let live = true;
    setLoading(true);
    setForbidden(false);
    setError(null);
    (async () => {
      try {
        const wfs = await Promise.all(MONTHS.map((m) => getWaterfall(r.token, variant, m)));
        if (!live) return;
        if (wfs.some((w) => w.status === 403)) {
          setForbidden(true);
          setLoading(false);
          return;
        }
        const bad = wfs.find((w) => w.status >= 400);
        if (bad) {
          setError(`Waterfall failed (${bad.status})`);
          setLoading(false);
          return;
        }
        const g: Record<string, any> = {};
        MONTHS.forEach((m, i) => { g[m] = wfs[i].json; });
        setGrid(g);
        const led = await getLedger(r.token, c.market, month);
        if (!live) return;
        if (led.status === 200) setLedger(led.json);
        else setLedger(null);
        setLoading(false);
      } catch (e) {
        if (live) { setError('Network error'); setLoading(false); }
      }
    })();
    return () => { live = false; };
  }, [r.token, c.market, variant, month]);

  const wf = grid[month];
  const stages = wf ? FLOW_STAGES.map(([k, label, sub]) => ({ k, label, sub, qty: wf.stages?.[k] ?? 0 })) : [];

  const gapChip = (prev: number, cur: number, idx: number) => {
    if (!prev || cur >= prev) return null;
    const ratio = cur / prev;
    if (ratio >= 0.93) return null;
    const sev = ratio < 0.78 ? 'danger' : 'warn';
    return <span className={'gap ' + sev}>{num(prev - cur)} {idx === 2 ? 'short' : 'gap'}</span>;
  };

  const openDrill = (rec: any) => {
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
      <select className="fld sel" data-testid="flow-variant" value={variant} onChange={(e) => setVariant(e.target.value)}>
        <option value="ALL">All products (master)</option>
      </select>
    </div>
  );

  return (
    <div className="page">
      <PageHead
        crumb={`H6Q · Flow · ${c.market}`}
        title="Flow"
        sub="One demand, seven variants — never conflated. Forecast → committed → produced → delivered → ordered → shipped → revenue. Money is traced, not asserted."
        right={right}
      />

      <Card
        title={`Waterfall · ${variant === 'ALL' ? 'All products' : variant} · ${month}`}
        icon={I.trend}
        aux={<span className="dim" style={{ fontSize: 12 }}>conversion between stages · gaps are the story</span>}
        style={{ marginBottom: 12 }}
        className="tablewrap"
      >
        {loading && (
          <div className="row g12" data-testid="flow-loading" style={{ padding: '8px 0' }}>
            {FLOW_STAGES.map((s) => <Skeleton key={s[0]} w={132} h={86} />)}
          </div>
        )}

        {!loading && forbidden && (
          <LayerNote>hidden — the demand waterfall requires the <b>volume</b> layer.</LayerNote>
        )}

        {!loading && error && (
          <div className="banner danger" data-testid="flow-error">{I.alert({ size: 15 })} {error}</div>
        )}

        {!loading && !forbidden && !error && !wf && (
          <div className="dim" data-testid="flow-empty" style={{ padding: '18px 4px' }}>No data for this variant/period.</div>
        )}

        {!loading && !forbidden && !error && wf && (
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
                    <span className="conv">{gbp(Math.round(parseFloat(wf.revenue_ex_vat) / Math.max(1, wf.stages?.shipped ?? 1)))}/u</span>
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
          {loading && <Skeleton lines={6} />}
          {!loading && !forbidden && !error && (
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
                        {grid[m]?.stages ? num(grid[m].stages[k]) : '—'}
                      </td>
                    ))}
                  </tr>
                ))}
                {hasCommercial && (
                  <tr data-testid="flow-evo-revenue">
                    <td style={{ color: 'var(--accent-bright)', fontWeight: 600 }}>Revenue ex-VAT</td>
                    {MONTHS.map((m) => (
                      <td key={m} style={{ color: 'var(--accent-bright)', fontWeight: m === month ? 700 : 400 }}>
                        {grid[m]?.revenue_ex_vat != null ? gbp(grid[m].revenue_ex_vat) : '—'}
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
            {loading && <Skeleton lines={5} />}
            {!loading && !ledger && (
              <div className="dim" data-testid="ledger-empty" style={{ padding: '18px 4px' }}>No recognised revenue for this period.</div>
            )}
            {!loading && ledger && (
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
                      (ledger.recognitions ?? []).map((rec: any, i: number) => (
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
