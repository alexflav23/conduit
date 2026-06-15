import React, { useMemo, useState } from 'react';
import { useApi, request } from './lib/query';
import { ApiError } from './lib/client';
import { marketId, H6Q_MARKET, ForecastLine } from './api';
import {
  PageHead, Card, Chip, Coverage, EmptyRow, LayerNote, SkeletonRow, Skeleton, num,
} from './kit/kit';
import { I } from './kit/icons';
import { asArray } from './state';

// H6Q — the deepest demand board (doc 20 D7/D8). Two personas, one domain: the weekly bottom-up CAPTURE
// (agents submit their portion, SKU-mix-aware) and the dense COVERAGE BOARD (market × period × scenario ×
// group-by) where human capture meets the 12k model rows. Units are the `volume` layer; any revenue
// projection is `commercial` and COLLAPSES when withheld. Model rows are honestly marked vs human capture.
//
// Backing routes (H6QRoutes): GET /api/v1/h6q/scenarios, /variants, /my-forecasts,
// /coverage/matrix?market&scenario, /coverage?market&period&scenario&group_by, /coverage/reconcile,
// POST /my-forecasts/{company_id}/submit. The board is gated on view:pipeline_coverage (403 -> LayerNote);
// capture is own-scope create:forecast. Scenario labels (P20/P50/P80) resolve to UUIDs via /scenarios.

const SCENARIOS = ['P20', 'P50', 'P80'] as const;
type Scenario = typeof SCENARIOS[number];
// The demand matrix can pivot its row axis: by SKU (the model/human capture) or — allocated by activation
// share — by account, sector, or market. account/sector/market come from /coverage/matrix?group_by=.
type MatrixDim = 'sku' | 'account' | 'sector' | 'market';
const fmt = (n: number) => num(n);

interface ScenarioRow { id: string; type?: string; toggle_basis?: unknown }

// One render state from a React Query result, layer-aware: loading / forbidden / notImplemented / error /
// empty / ready. Mirrors the shared table-state machine but over the typed ApiError the production client throws.
type Phase = 'loading' | 'forbidden' | 'notImplemented' | 'error' | 'empty' | 'ready';
function phaseOf(isLoading: boolean, err: ApiError | null, rows: unknown[]): Phase {
  if (err?.forbidden) return 'forbidden';
  if (err?.notImplemented) return 'notImplemented';
  if (err) return 'error';
  if (isLoading) return 'loading';
  return rows.length === 0 ? 'empty' : 'ready';
}

// The shared "this endpoint isn't built in this environment" panel — never a stuck skeleton.
function NotBacked({ testid, message }: { testid: string; message: string }) {
  return (
    <Card style={{ padding: '34px 28px', textAlign: 'center' }} data-testid={testid}>
      <div style={{ display: 'grid', placeItems: 'center', gap: 10 }}>
        <span style={{ width: 44, height: 44, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'var(--panel-2)' }}>{I.trend({ size: 22 })}</span>
        <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>Not available in this environment yet</div>
        <div className="dim" style={{ fontSize: 12.5, maxWidth: 460 }}>{message}</div>
      </div>
    </Card>
  );
}

export function H6Q({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const [view, setView] = useState<'board' | 'capture'>('board');
  const market = (ctx && ctx.market) || H6Q_MARKET;
  const period = (ctx && ctx.period) || '2026-09';

  // Resolve scenario labels -> UUIDs once. Keyed only on the endpoint; it's reference data, not ctx-scoped.
  const scenariosQ = useApi<ScenarioRow[]>(['h6q-scenarios'], '/api/v1/h6q/scenarios');
  const scenarioIds = useMemo(() => {
    const map: Record<string, string> = {};
    asArray<ScenarioRow>(scenariosQ.data).forEach((s) => { if (!s.toggle_basis && s.type) map[s.type] = s.id; });
    return map;
  }, [scenariosQ.data]);

  return (
    <div className="page">
      <PageHead
        crumb={`H6Q · ${(ctx && ctx.market) || 'UK'} · ${period}`}
        title="Demand (H6Q)"
        sub="The whole forecast at once — every SKU × every month, human capture and the model meeting as one demand truth."
        right={
          <div className="seg">
            <button className={view === 'board' ? 'on' : ''} data-testid="h6q-tab-board" onClick={() => setView('board')}>Coverage board</button>
            <button className={view === 'capture' ? 'on' : ''} data-testid="h6q-tab-capture" onClick={() => setView('capture')}>My forecast</button>
          </div>
        }
      />
      {view === 'board'
        ? <Board role={role} market={market} period={period} scenarioIds={scenarioIds} />
        : <Capture role={role} toast={toast} ctx={ctx} scenarioIds={scenarioIds} />}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Coverage board: grand total + the full SKU × month matrix; scenario toggle; reconcile (branch/agent).
// ---------------------------------------------------------------------------
function Board({ role, market, period, scenarioIds }: { role: any; market: string; period: string; scenarioIds: Record<string, string> }) {
  const [mode, setMode] = useState<'matrix' | 'reconcile'>('matrix');
  const [scenario, setScenario] = useState<Scenario>('P50');
  const [groupBy, setGroupBy] = useState<'branch' | 'agent'>('branch');
  const [dim, setDim] = useState<MatrixDim>('sku');

  const hasCommercial = Array.isArray(role?.layers) && role.layers.indexOf('commercial') >= 0;
  const mkt = marketId(market);
  const sid = scenarioIds[scenario];

  return (
    <>
      <div className="loadbar">
        <div className="seg">
          <button className={mode === 'matrix' ? 'on' : ''} data-testid="h6q-mode-matrix" onClick={() => setMode('matrix')}>Demand matrix</button>
          <button className={mode === 'reconcile' ? 'on' : ''} data-testid="h6q-mode-reconcile" onClick={() => setMode('reconcile')}>Reconcile (branch ≡ agent)</button>
        </div>
        <div className="seg">
          {SCENARIOS.map((s) => (
            <button key={s} className={scenario === s ? 'on' : ''} data-testid={`h6q-scenario-${s}`} onClick={() => setScenario(s)} style={{ fontFamily: 'var(--font-mono)' }}>{s}</button>
          ))}
        </div>
        {mode === 'matrix' && (
          <div className="seg">
            {(['sku', 'account', 'sector', 'market'] as MatrixDim[]).map((d) => (
              <button key={d} className={dim === d ? 'on' : ''} data-testid={`h6q-dim-${d}`} onClick={() => setDim(d)} style={{ textTransform: 'capitalize' }}>{d}</button>
            ))}
          </div>
        )}
        <div className="sp" />
        <span className="dim" style={{ fontSize: 12 }}>{(market === H6Q_MARKET ? 'UK' : market)} · {period} · {scenario}</span>
      </div>

      {mode !== 'matrix'
        ? <ReconcileCard market={mkt} period={period} scenario={scenario} sid={sid} groupBy={groupBy} setGroupBy={setGroupBy} />
        : dim === 'sector'
          ? <DemandBoardCard market={mkt} scenario={scenario} sid={sid} hasCommercial={hasCommercial} />
          : <MatrixCard market={mkt} scenario={scenario} sid={sid} hasCommercial={hasCommercial} dim={dim} />}
    </>
  );
}

function MatrixCard({ market, scenario, sid, hasCommercial, dim }: { market: string; scenario: Scenario; sid?: string; hasCommercial: boolean; dim: MatrixDim }) {
  const groupParam = dim === 'sku' ? '' : `&group_by=${dim}`;
  const q = useApi<any[]>(
    ['h6q-matrix', market, sid, dim],
    `/api/v1/h6q/coverage/matrix?market=${encodeURIComponent(market)}&scenario=${encodeURIComponent(sid ?? '')}${groupParam}`,
    { enabled: !!sid },
  );
  const err = q.error as ApiError | null;
  const rows = asArray<any>(q.data);
  const loading = !sid || q.isLoading;
  const state = phaseOf(loading, err, rows);

  // Preserve the backend's row order (SKU is alphabetical; account is busiest-first with "Other" last).
  const months = Array.from(new Set(rows.map((r) => r.month))).sort();
  const skus = Array.from(new Set(rows.map((r) => r.key ?? r.sku)));
  const dimLabel = dim === 'sku' ? 'SKU' : dim === 'account' ? 'Account' : dim === 'sector' ? 'Sector' : 'Market';
  const cell: Record<string, number> = {};
  const fam: Record<string, string> = {};
  const src: Record<string, string> = {};
  rows.forEach((r) => {
    const k = r.key ?? r.sku;
    cell[`${k}|${r.month}`] = r.forecast;
    if (r.family) fam[k] = r.family;
    if (r.source) src[k] = r.source;
  });
  const rowTotal = (s: string) => months.reduce((a, m) => a + (cell[`${s}|${m}`] ?? 0), 0);
  const grand = months.reduce((a, m) => a + skus.reduce((b, s) => b + (cell[`${s}|${m}`] ?? 0), 0), 0);

  // Quarter grouping (Excel-style outline): months roll up into quarters, collapsed by default. Each quarter
  // header carries a +/− toggle that expands to reveal its months (then a quarter-subtotal column) or collapses
  // back to a single quarter total.
  const MONTH_ABBR = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const monthShort = (m: string) => MONTH_ABBR[parseInt(m.slice(5, 7)) - 1] ?? m;
  const quarterOf = (m: string) => `${m.slice(0, 4)}-Q${Math.ceil(parseInt(m.slice(5, 7)) / 3)}`;
  const quarters: { key: string; months: string[] }[] = [];
  months.forEach((m) => {
    const qk = quarterOf(m);
    let qq = quarters.find((x) => x.key === qk);
    if (!qq) { qq = { key: qk, months: [] }; quarters.push(qq); }
    qq.months.push(m);
  });
  const [expandedQ, setExpandedQ] = useState<Set<string>>(new Set());
  const toggleQ = (qk: string) => setExpandedQ((s) => { const n = new Set(s); if (n.has(qk)) n.delete(qk); else n.add(qk); return n; });
  type Col = { kind: 'month'; month: string } | { kind: 'quarter'; key: string; months: string[] };
  const viewCols: Col[] = quarters.flatMap((qq) =>
    expandedQ.has(qq.key)
      ? [...qq.months.map((m) => ({ kind: 'month', month: m } as Col)), { kind: 'quarter', key: qq.key, months: qq.months } as Col]
      : [{ kind: 'quarter', key: qq.key, months: qq.months } as Col],
  );
  const colKey = (c: Col) => (c.kind === 'month' ? c.month : 'q' + c.key);
  const qShort = (qk: string) => qk.slice(5);
  const cellCol = (rowKey: string, c: Col) => (c.kind === 'month' ? (cell[`${rowKey}|${c.month}`] ?? 0) : c.months.reduce((a, m) => a + (cell[`${rowKey}|${m}`] ?? 0), 0));
  const colTotalCol = (c: Col) => skus.reduce((a, s) => a + cellCol(s, c), 0);
  const cols = (state === 'ready' ? viewCols.length : 6) + 2;

  if (state === 'notImplemented') {
    return <NotBacked testid="h6q-matrix-unbacked" message="The demand matrix appears once the forecasting service is wired and a cycle has been published." />;
  }

  return (
    <Card title="Demand matrix" icon={I.trend}
      aux={<span className="dim" style={{ fontSize: 12 }}>Forecast units · by {dimLabel.toLowerCase()} × all months{dim !== 'sku' ? ' · allocated by activation share' : ''} · {scenario} · <b style={{ color: 'var(--text)' }} data-testid="h6q-grand-total">{state === 'ready' ? `${fmt(grand)} units` : '—'}</b></span>}
      style={{ padding: 0 }} className="tablewrap">
      {state === 'forbidden' && <LayerNote>Demand is hidden — requires the <code>volume</code> layer.</LayerNote>}
      {state === 'error' && <div className="banner danger" data-testid="h6q-board-error" style={{ margin: 14 }}>Couldn't load the demand matrix (HTTP {err?.status}).</div>}
      {(state !== 'forbidden' && state !== 'error') && (
        <table className="tbl">
          <thead><tr>
            <th style={{ position: 'sticky', left: 0, background: 'var(--surface)' }}>{dimLabel}</th>
            {(state === 'ready' ? viewCols : []).map((c) => (
              <th key={colKey(c)} className="num">
                {c.kind === 'quarter' ? (
                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, justifyContent: 'flex-end' }}>
                    <button
                      onClick={() => toggleQ(c.key)}
                      data-testid={`h6q-q-toggle-${qShort(c.key)}`}
                      title={expandedQ.has(c.key) ? 'Collapse to quarter' : 'Expand months'}
                      style={{ width: 15, height: 15, lineHeight: '12px', textAlign: 'center', borderRadius: 3, border: '1px solid var(--border)', background: 'var(--panel-2)', color: 'var(--muted)', cursor: 'pointer', fontSize: 12, padding: 0 }}
                    >{expandedQ.has(c.key) ? '−' : '+'}</button>
                    {qShort(c.key)}
                  </span>
                ) : monthShort(c.month)}
              </th>
            ))}
            {state === 'ready' && <th className="num">Total</th>}
          </tr></thead>
          <tbody>
            {state === 'loading' && <><SkeletonRow cols={cols} /><SkeletonRow cols={cols} /><SkeletonRow cols={cols} /></>}
            {state === 'empty' && <EmptyRow cols={cols}>No open cycle — no forecast for {scenario} yet. Submit on “My forecast”, or import an H6Q.</EmptyRow>}
            {state === 'ready' && skus.map((s) => (
              <tr key={s} data-testid="h6q-matrix-row">
                <td style={{ position: 'sticky', left: 0, background: 'var(--surface)' }}>
                  <b className="mono" style={{ fontFamily: 'var(--font-mono)', fontSize: 11.5 }}>{s}</b>
                  <div className="row g6" style={{ marginTop: 2 }}>
                    <span className="dim" style={{ fontSize: 10.5 }}>{fam[s] ?? ''}</span>
                    <SourceBadge source={src[s]} />
                  </div>
                </td>
                {viewCols.map((c) => <td key={colKey(c)} className="num" style={c.kind === 'quarter' ? { fontWeight: 600 } : undefined}>{fmt(cellCol(s, c))}</td>)}
                <td className="num"><b>{fmt(rowTotal(s))}</b></td>
              </tr>
            ))}
          </tbody>
          {state === 'ready' && (
            <tfoot><tr>
              <td style={{ position: 'sticky', left: 0, background: 'var(--surface)' }}><b>Total</b></td>
              {viewCols.map((c) => <td key={colKey(c)} className="num"><b data-testid={c.kind === 'month' ? `h6q-coltotal-${c.month}` : `h6q-qtotal-${qShort(c.key)}`}>{fmt(colTotalCol(c))}</b></td>)}
              <td className="num"><b>{fmt(grand)}</b></td>
            </tr></tfoot>
          )}
        </table>
      )}
      <div className="layer-note" style={{ padding: '9px 14px' }}>{I.layers()}Total demand, fully visible — never one SKU at a time. <span className="src-model">Model</span> rows are the engine's 12k projections; <span className="src-human">human</span> rows are agent capture.{!hasCommercial && ' Revenue is layer-restricted for your role.'}</div>
    </Card>
  );
}

// ---------------------------------------------------------------------------
// Demand board (the "By sector" view): segment rows with quarterly shape, trend (sparkline + QoQ), shipped,
// attainment, and revenue — each segment expandable to its contributing accounts (the base forecast unit).
// ---------------------------------------------------------------------------
interface QCell { q: string; units: number; shipped?: number; attainment?: number | null; eoq?: number | null; state?: string }
interface BoardRow {
  key?: string;
  label?: string;
  quarters?: QCell[];
  forecast?: number;
  shipped?: number;
  forecast_attainment?: number | null;
  trend?: { qoq_pct: number; spark: number[] };
  revenue?: string;
  contributors?: BoardRow[] | null;
}
interface BoardData {
  months?: string[];
  currency?: string;
  fx_rate?: string;
  as_of?: string;
  segments?: BoardRow[];
  total?: BoardRow;
}

// Attainment % with industry-correct semantics: prior quarter = actual, in-progress = QTD → forecast EOQ,
// future = nothing (no forward attainment on physical installs).
function AttnPct({ pct }: { pct?: number | null }) {
  if (pct == null) return <span className="dim">—</span>;
  const v = pct * 100;
  const c = v >= 95 ? 'var(--ok)' : v >= 70 ? 'var(--warn)' : 'var(--danger)';
  return <span style={{ color: c, fontWeight: 600 }}>{v.toFixed(0)}%</span>;
}
function QuarterCell({ c }: { c?: QCell }) {
  if (!c) return <td className="num dim">—</td>;
  return (
    <td className="num">
      <div>{num(c.units)}</div>
      {c.state === 'prior' && c.attainment != null && (
        <div style={{ fontSize: 10 }}><AttnPct pct={c.attainment} /></div>
      )}
      {c.state === 'current' && (
        <div style={{ fontSize: 10 }}><AttnPct pct={c.attainment} />{c.eoq != null && <span className="dim"> → {(c.eoq * 100).toFixed(0)}%</span>}</div>
      )}
    </td>
  );
}

function Spark({ data }: { data?: number[] }) {
  if (!data || data.length < 2) return null;
  const W = 64, H = 20, max = Math.max(...data, 1), min = Math.min(...data, 0);
  const x = (i: number) => (i * W) / (data.length - 1);
  const y = (v: number) => H - 2 - ((v - min) / Math.max(max - min, 1)) * (H - 4);
  const d = data.map((v, i) => (i ? 'L' : 'M') + x(i).toFixed(1) + ' ' + y(v).toFixed(1)).join(' ');
  return <svg viewBox={`0 0 ${W} ${H}`} width={W} height={H} style={{ display: 'block' }} aria-hidden><path d={d} fill="none" stroke="var(--ok)" strokeWidth={1.5} /></svg>;
}

function TrendCell({ t }: { t?: { qoq_pct: number; spark: number[] } }) {
  const q = t?.qoq_pct ?? 0;
  const color = q > 0 ? 'var(--ok)' : q < 0 ? 'var(--warn)' : 'var(--muted)';
  return (
    <div className="row g8" style={{ alignItems: 'center', justifyContent: 'flex-end' }}>
      <Spark data={t?.spark} />
      <span style={{ color, fontFamily: 'var(--font-mono)', fontSize: 11.5, minWidth: 44, textAlign: 'right' }}>{q > 0 ? '▲' : q < 0 ? '▼' : '–'}{Math.abs(q)}%</span>
    </div>
  );
}

function DemandRow({ r, qkeys, depth, expanded, toggle, money }: { r: BoardRow; qkeys: string[]; depth: number; expanded: Set<string>; toggle: (k: string) => void; money: (v?: string) => string }) {
  const qmap: Record<string, QCell> = {};
  (r.quarters ?? []).forEach((x) => { qmap[x.q] = x; });
  const kids = Array.isArray(r.contributors) ? r.contributors : [];
  const open = expanded.has(r.key ?? '');
  return (
    <>
      <tr data-testid="demand-row" style={depth > 0 ? { background: 'var(--bg-2)' } : undefined}>
        <td style={{ position: 'sticky', left: 0, background: depth > 0 ? 'var(--bg-2)' : 'var(--surface)', paddingLeft: 14 + depth * 18 }}>
          {kids.length > 0 ? (
            <button onClick={() => toggle(r.key ?? '')} data-testid={`demand-expand-${r.key}`} title={open ? 'Collapse' : 'Expand accounts'}
              style={{ width: 16, height: 16, marginRight: 7, borderRadius: 3, border: '1px solid var(--border)', background: 'var(--panel-2)', color: 'var(--muted)', cursor: 'pointer', fontSize: 12, padding: 0, lineHeight: '13px' }}>{open ? '−' : '+'}</button>
          ) : <span style={{ display: 'inline-block', width: 23 }} />}
          <b style={depth > 0 ? { fontWeight: 400, fontSize: 12.5 } : undefined}>{r.label}</b>
          {kids.length > 0 ? <span className="dim" style={{ fontSize: 10.5, marginLeft: 6 }}>{kids.length} accounts</span> : null}
        </td>
        {qkeys.map((q) => <QuarterCell key={q} c={qmap[q]} />)}
        <td><TrendCell t={r.trend} /></td>
        <td className="num"><b>{num(r.forecast)}</b></td>
        <td className="num dim">{num(r.shipped)}</td>
        <td style={{ width: 130 }}>{r.forecast_attainment == null ? <span className="dim">—</span> : <Coverage pct={r.forecast_attainment * 100} />}</td>
        <td className="num" style={{ fontWeight: 600 }}>{money(r.revenue)}</td>
      </tr>
      {open && kids.map((c) => <DemandRow key={c.key} r={c} qkeys={qkeys} depth={depth + 1} expanded={expanded} toggle={toggle} money={money} />)}
    </>
  );
}

function DemandBoardCard({ market, scenario, sid, hasCommercial }: { market: string; scenario: Scenario; sid?: string; hasCommercial: boolean }) {
  const [ccy, setCcy] = useState<'GBP' | 'USD'>('GBP');
  const q = useApi<BoardData>(
    ['h6q-board', market, sid, ccy],
    `/api/v1/h6q/demand-board?market=${encodeURIComponent(market)}&scenario=${encodeURIComponent(sid ?? '')}&currency=${ccy}`,
    { enabled: !!sid },
  );
  const err = q.error as ApiError | null;
  const d = q.data ?? {};
  const segments = Array.isArray(d.segments) ? d.segments : [];
  const total = d.total;
  const state = phaseOf(!sid || q.isLoading, err, segments);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const toggle = (k: string) => setExpanded((s) => { const n = new Set(s); if (n.has(k)) n.delete(k); else n.add(k); return n; });
  const qkeys = ((total?.quarters ?? segments[0]?.quarters ?? []) as { q: string }[]).map((x) => x.q);
  const cols = qkeys.length + 5;
  // Format money in the board's reported currency (falls back to GBP server-side if a pair isn't seeded).
  const sym = (d.currency ?? ccy) === 'USD' ? '$' : '£';
  const money = (v?: string) => sym + (Number(v ?? 0)).toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

  if (state === 'notImplemented') return <NotBacked testid="h6q-board-unbacked" message="The demand board appears once a forecast cycle is published." />;

  return (
    <Card title="Demand board" icon={I.trend}
      aux={
        <div className="row g8" style={{ alignItems: 'center' }}>
          <div className="seg">
            {(['GBP', 'USD'] as const).map((c) => (
              <button key={c} className={ccy === c ? 'on' : ''} data-testid={`h6q-ccy-${c}`} onClick={() => setCcy(c)}>{c}</button>
            ))}
          </div>
          <span className="dim" style={{ fontSize: 12 }}>by segment · revenue at each segment&rsquo;s net tier price · {scenario} · <b style={{ color: 'var(--text)' }} data-testid="h6q-board-revenue">{state === 'ready' && total ? money(total.revenue) : '—'}</b></span>
        </div>
      }
      style={{ padding: 0 }} className="tablewrap">
      {state === 'forbidden' && <LayerNote>Demand is hidden — requires the <code>volume</code> layer.</LayerNote>}
      {state === 'error' && <div className="banner danger" data-testid="h6q-board-error" style={{ margin: 14 }}>Couldn&rsquo;t load the demand board (HTTP {err?.status}).</div>}
      {(state !== 'forbidden' && state !== 'error') && (
        <table className="tbl">
          <thead><tr>
            <th style={{ position: 'sticky', left: 0, background: 'var(--surface)' }}>Segment</th>
            {qkeys.map((qk) => <th key={qk} className="num">{qk}</th>)}
            <th>Trend</th>
            <th className="num">Forecast</th>
            <th className="num">Shipped</th>
            <th style={{ width: 130 }}>Fcst attain<span className="dim" style={{ fontWeight: 400 }}> · yr</span></th>
            <th className="num">Revenue</th>
          </tr></thead>
          <tbody>
            {state === 'loading' && <><SkeletonRow cols={cols} /><SkeletonRow cols={cols} /><SkeletonRow cols={cols} /></>}
            {state === 'empty' && <EmptyRow cols={cols}>No forecast for {scenario} yet.</EmptyRow>}
            {state === 'ready' && segments.map((s) => <DemandRow key={s.key} r={s} qkeys={qkeys} depth={0} expanded={expanded} toggle={toggle} money={money} />)}
          </tbody>
          {state === 'ready' && total && (
            <tfoot><tr>
              <td style={{ position: 'sticky', left: 0, background: 'var(--surface)' }}><b>Total</b></td>
              {qkeys.map((qk) => <QuarterCell key={qk} c={(total.quarters ?? []).find((x) => x.q === qk)} />)}
              <td></td>
              <td className="num"><b>{num(total.forecast)}</b></td>
              <td className="num dim">{num(total.shipped)}</td>
              <td style={{ width: 130 }}>{total.forecast_attainment == null ? <span className="dim">—</span> : <Coverage pct={total.forecast_attainment * 100} />}</td>
              <td className="num"><b>{money(total.revenue)}</b></td>
            </tr></tfoot>
          )}
        </table>
      )}
      <div className="layer-note" style={{ padding: '9px 14px' }}>{I.layers()}Attainment is time-aware: prior quarters show actual shipped ÷ forecast; the quarter in progress shows quarter-to-date <b>→</b> a run-rate forecast to end-of-quarter; future quarters have none. <b>Fcst attain · yr</b> is the pro-rata run-rate for the full year. Revenue = forecast units × the segment&rsquo;s net tier price.{!hasCommercial && ' Revenue is layer-restricted for your role.'}</div>
    </Card>
  );
}

function SourceBadge({ source }: { source?: string }) {
  if (!source) return null;
  const model = source === 'model';
  return <span className={model ? 'src-model' : 'src-human'} style={{ fontSize: 9.5, fontWeight: 700, letterSpacing: '0.06em', textTransform: 'uppercase' }}>{model ? 'model' : 'human'}</span>;
}

function ReconcileCard({ market, period, scenario, sid, groupBy, setGroupBy }: {
  market: string; period: string; scenario: Scenario; sid?: string;
  groupBy: 'branch' | 'agent'; setGroupBy: (g: 'branch' | 'agent') => void;
}) {
  const enabled = !!sid;
  const covQ = useApi<any[]>(
    ['h6q-coverage', market, period, sid, groupBy],
    `/api/v1/h6q/coverage?market=${encodeURIComponent(market)}&period=${encodeURIComponent(period)}&scenario=${encodeURIComponent(sid ?? '')}&group_by=${groupBy}`,
    { enabled },
  );
  const recQ = useApi<{ ties?: boolean }>(
    ['h6q-reconcile', market, period, sid],
    `/api/v1/h6q/coverage/reconcile?market=${encodeURIComponent(market)}&period=${encodeURIComponent(period)}&scenario=${encodeURIComponent(sid ?? '')}`,
    { enabled },
  );

  const err = covQ.error as ApiError | null;
  const rows = asArray<any>(covQ.data);
  const loading = !enabled || covQ.isLoading;
  const state = phaseOf(loading, err, rows);
  const ties = recQ.data && typeof recQ.data === 'object' ? (recQ.data.ties ?? null) : null;
  const label = groupBy === 'agent' ? 'Agent' : 'Branch · billing entity';

  if (state === 'notImplemented') {
    return <NotBacked testid="h6q-reconcile-unbacked" message="The reconciliation view appears once bottom-up agent submissions exist for this cycle." />;
  }

  return (
    <Card style={{ padding: 0 }} className="tablewrap">
      <div className="loadbar" style={{ padding: '13px 16px', margin: 0, borderBottom: '1px solid var(--border)' }}>
        <div className="seg">
          <button className={groupBy === 'branch' ? 'on' : ''} data-testid="h6q-by-branch" onClick={() => setGroupBy('branch')}>By branch</button>
          <button className={groupBy === 'agent' ? 'on' : ''} data-testid="h6q-by-agent" onClick={() => setGroupBy('agent')}>By agent</button>
        </div>
        {ties !== null && <Chip s={ties ? 'ok' : 'danger'}><span data-testid="h6q-reconcile">{ties ? 'Σ branch ≡ Σ agent ✓' : 'reconcile mismatch'}</span></Chip>}
        <div className="sp" />
        <span className="dim" style={{ fontSize: 12 }}>{period} · {scenario} · forecast vs shipped vs activated</span>
      </div>
      {state === 'forbidden' && <LayerNote>Reconciliation is hidden — requires the <code>volume</code> layer.</LayerNote>}
      {state === 'error' && <div className="banner danger" data-testid="h6q-board-error" style={{ margin: 14 }}>Couldn't load reconciliation (HTTP {err?.status}).</div>}
      {(state !== 'forbidden' && state !== 'error') && (
        <table className="tbl">
          <thead><tr>
            <th>{label}</th>
            <th className="num">Forecast</th><th className="num">Shipped</th><th className="num">Activated</th>
            <th style={{ width: 150 }}>Coverage</th>
          </tr></thead>
          <tbody>
            {state === 'loading' && <><SkeletonRow cols={5} /><SkeletonRow cols={5} /></>}
            {state === 'empty' && <EmptyRow cols={5}>No {groupBy} rows at {period} — this view needs bottom-up agent submissions for {scenario}.</EmptyRow>}
            {state === 'ready' && rows.map((r, i) => {
              const id = groupBy === 'agent' ? r.agent_user_id : r.branch_company_id;
              const cov = r.coverage_pct == null ? 0 : parseFloat(r.coverage_pct) * 100;
              return (
                <tr key={i} data-testid="h6q-board-row">
                  <td><b>{r.label ?? r.name ?? (id ? String(id).slice(0, 8) : '—')}</b>{r.legalName && <div className="dim" style={{ fontSize: 10.5 }}>{r.legalName}{r.vat && <> · <span className="mono" style={{ fontFamily: 'var(--font-mono)' }}>{r.vat}</span></>}</div>}</td>
                  <td className="num">{fmt(r.forecast_qty ?? 0)}</td>
                  <td className="num">{fmt(r.shipped_qty ?? 0)}</td>
                  <td className="num">{fmt(r.activated_qty ?? 0)}</td>
                  <td><Coverage pct={cov} /></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      <div className="layer-note" style={{ padding: '10px 16px' }}>{I.layers()}The accountability view: each agent's capture must reconcile to the branch total. A bare city is ambiguous — <b>CEF Leeds</b> and <b>Rexel Leeds</b> are different legal entities.</div>
    </Card>
  );
}

// ---------------------------------------------------------------------------
// Capture: my accounts + open cycle → submit. SKU-mix-aware (per-SKU × demand-band split, shown not hidden).
// Optimised for the weekly repeat ceremony.
// ---------------------------------------------------------------------------
function Capture({ toast, ctx, scenarioIds }: { role: any; toast: (m: string, k?: string) => void; ctx: any; scenarioIds: Record<string, string> }) {
  const period = (ctx && ctx.period) || '2026-09';

  const mineQ = useApi<{ cycle: string | null; accounts: any[] }>(['h6q-my-forecasts'], '/api/v1/h6q/my-forecasts');
  const variantsQ = useApi<any[]>(['h6q-variants'], '/api/v1/h6q/variants');

  const [account, setAccount] = useState<string | null>(null);
  const [grid, setGrid] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  const err = mineQ.error as ApiError | null;
  const cycle = mineQ.data?.cycle ?? null;
  const accounts = asArray<any>(mineQ.data?.accounts);
  const variants = asArray<any>(variantsQ.data);
  const state = phaseOf(mineQ.isLoading, err, accounts);

  // Default the selected account to the first one the principal owns this cycle, without a load effect.
  const selected = account ?? (accounts.length ? accounts[0].company_id : null);

  const setCell = (variant: string, band: Scenario, value: string) =>
    setGrid((g) => ({ ...g, [`${variant}|${band}`]: value }));

  const submit = () => {
    if (!selected || !cycle) return;
    setSubmitting(true);
    const lines: ForecastLine[] = [];
    variants.forEach((v) => SCENARIOS.forEach((b) => {
      const raw = grid[`${v.id}|${b}`];
      if (raw && raw.trim() !== '' && scenarioIds[b]) lines.push({ variant: v.id, period, scenario: scenarioIds[b], qty: parseInt(raw, 10) || 0 });
    }));
    request<{ versioned?: number }>(`/api/v1/h6q/my-forecasts/${selected}/submit`, {
      method: 'POST',
      body: JSON.stringify({ cycle, lines }),
    })
      .then((r) => { toast(`Submitted — ${r?.versioned ?? lines.length} cells versioned into cycle ${cycle}`, 'ok'); })
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.forbidden) toast('Not permitted to submit this forecast', 'err');
        else toast(`Submit failed (${e instanceof ApiError ? e.status : 'error'})`, 'err');
      })
      .finally(() => setSubmitting(false));
  };

  if (state === 'forbidden') {
    return <Card title="My forecast" icon={I.layers}><LayerNote>Capture is hidden — requires the <code>volume</code> layer.</LayerNote></Card>;
  }
  if (state === 'notImplemented') {
    return <NotBacked testid="h6q-capture-unbacked" message="Forecast capture appears once the forecasting service is wired and a cycle is open." />;
  }
  if (state === 'error') {
    return <Card title="My forecast" icon={I.layers}><div className="banner danger" data-testid="h6q-error">Couldn't load your accounts (HTTP {err?.status}).</div></Card>;
  }

  return (
    <>
      <div className="loadbar">
        <span className="fldlabel">Account</span>
        {state === 'loading'
          ? <Skeleton w={260} h={30} />
          : (
            <select className="fld sel" style={{ minWidth: 280 }} data-testid="h6q-account" value={selected ?? ''} onChange={(e) => setAccount(e.target.value)}>
              {accounts.map((a) => <option key={a.company_id} value={a.company_id}>{a.name}{a.status ? ` — ${a.status}` : ''}</option>)}
            </select>
          )}
        {cycle && <Chip s="neutral"><span data-testid="h6q-cycle">cycle {cycle} open</span></Chip>}
        <div className="sp" />
        <button className="btn primary" data-testid="h6q-submit" disabled={!selected || !cycle || submitting} onClick={submit}>
          {I.check({ size: 14 })} Submit my forecast
        </button>
      </div>

      {state === 'empty' && (
        <Card title="My forecast" icon={I.layers}>
          <div className="dim" style={{ padding: '18px 12px', textAlign: 'center' }}>No open cycle — there's nothing to capture right now.</div>
        </Card>
      )}

      {state === 'ready' && selected && (
        <Card style={{ padding: 0 }} className="tablewrap"
          title={`Your portion — units by SKU × demand band (${period})`} icon={I.charger}>
          <table className="tbl">
            <thead><tr><th>SKU</th>{SCENARIOS.map((b) => <th key={b} className="num" style={{ fontFamily: 'var(--font-mono)' }}>{b}</th>)}</tr></thead>
            <tbody>
              {variantsQ.isLoading
                ? <SkeletonRow cols={SCENARIOS.length + 1} />
                : variants.length === 0
                  ? <EmptyRow cols={SCENARIOS.length + 1}>No SKUs in the catalogue yet.</EmptyRow>
                  : variants.map((v) => (
                    <tr key={v.id}>
                      <td>
                        <b className="mono" style={{ fontFamily: 'var(--font-mono)', fontSize: 11.5 }}>{v.sku}</b>
                        <div className="dim" style={{ fontSize: 10.5 }}>{v.family}</div>
                      </td>
                      {SCENARIOS.map((b) => (
                        <td key={b} className="num">
                          <input className="cellinput" data-testid={`h6q-qty-${v.sku}-${b}`} value={grid[`${v.id}|${b}`] ?? ''} onChange={(e) => setCell(v.id, b, e.target.value)} placeholder="0" inputMode="numeric" />
                        </td>
                      ))}
                    </tr>
                  ))}
            </tbody>
          </table>
          <div className="layer-note" style={{ padding: '9px 14px' }}>{I.layers()}Bottom-up capture — the rollup sums every agent's portion each cycle. This is the part that must reconcile to the branch total. Keep the per-SKU split visible; never collapse the mix to one number.</div>
        </Card>
      )}
    </>
  );
}
