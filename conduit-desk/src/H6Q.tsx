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
        <div className="sp" />
        <span className="dim" style={{ fontSize: 12 }}>{(market === H6Q_MARKET ? 'UK' : market)} · {period} · {scenario}</span>
      </div>

      {mode === 'matrix'
        ? <MatrixCard market={mkt} scenario={scenario} sid={sid} hasCommercial={hasCommercial} />
        : <ReconcileCard market={mkt} period={period} scenario={scenario} sid={sid} groupBy={groupBy} setGroupBy={setGroupBy} />}
    </>
  );
}

function MatrixCard({ market, scenario, sid, hasCommercial }: { market: string; scenario: Scenario; sid?: string; hasCommercial: boolean }) {
  const q = useApi<any[]>(
    ['h6q-matrix', market, sid],
    `/api/v1/h6q/coverage/matrix?market=${encodeURIComponent(market)}&scenario=${encodeURIComponent(sid ?? '')}`,
    { enabled: !!sid },
  );
  const err = q.error as ApiError | null;
  const rows = asArray<any>(q.data);
  const loading = !sid || q.isLoading;
  const state = phaseOf(loading, err, rows);

  const months = Array.from(new Set(rows.map((r) => r.month))).sort();
  const skus = Array.from(new Set(rows.map((r) => r.sku))).sort();
  const cell: Record<string, number> = {};
  const fam: Record<string, string> = {};
  const src: Record<string, string> = {};
  rows.forEach((r) => {
    cell[`${r.sku}|${r.month}`] = r.forecast;
    if (r.family) fam[r.sku] = r.family;
    if (r.source) src[r.sku] = r.source;
  });
  const colTotal = (m: string) => skus.reduce((a, s) => a + (cell[`${s}|${m}`] ?? 0), 0);
  const rowTotal = (s: string) => months.reduce((a, m) => a + (cell[`${s}|${m}`] ?? 0), 0);
  const grand = months.reduce((a, m) => a + colTotal(m), 0);
  const cols = (state === 'ready' ? months.length : 6) + 2;

  if (state === 'notImplemented') {
    return <NotBacked testid="h6q-matrix-unbacked" message="The demand matrix appears once the forecasting service is wired and a cycle has been published." />;
  }

  return (
    <Card title="Demand matrix" icon={I.trend}
      aux={<span className="dim" style={{ fontSize: 12 }}>Forecast units · all SKUs × all months · {scenario} · <b style={{ color: 'var(--text)' }} data-testid="h6q-grand-total">{state === 'ready' ? `${fmt(grand)} units` : '—'}</b></span>}
      style={{ padding: 0 }} className="tablewrap">
      {state === 'forbidden' && <LayerNote>Demand is hidden — requires the <code>volume</code> layer.</LayerNote>}
      {state === 'error' && <div className="banner danger" data-testid="h6q-board-error" style={{ margin: 14 }}>Couldn't load the demand matrix (HTTP {err?.status}).</div>}
      {(state !== 'forbidden' && state !== 'error') && (
        <table className="tbl">
          <thead><tr>
            <th style={{ position: 'sticky', left: 0, background: 'var(--surface)' }}>SKU</th>
            {(state === 'ready' ? months : []).map((m) => <th key={m} className="num">{m}</th>)}
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
                {months.map((m) => <td key={m} className="num">{fmt(cell[`${s}|${m}`] ?? 0)}</td>)}
                <td className="num"><b>{fmt(rowTotal(s))}</b></td>
              </tr>
            ))}
          </tbody>
          {state === 'ready' && (
            <tfoot><tr>
              <td style={{ position: 'sticky', left: 0, background: 'var(--surface)' }}><b>Total</b></td>
              {months.map((m) => <td key={m} className="num"><b data-testid={`h6q-coltotal-${m}`}>{fmt(colTotal(m))}</b></td>)}
              <td className="num"><b>{fmt(grand)}</b></td>
            </tr></tfoot>
          )}
        </table>
      )}
      <div className="layer-note" style={{ padding: '9px 14px' }}>{I.layers()}Total demand, fully visible — never one SKU at a time. <span className="src-model">Model</span> rows are the engine's 12k projections; <span className="src-human">human</span> rows are agent capture.{!hasCommercial && ' Revenue is layer-restricted for your role.'}</div>
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
          <EmptyRow cols={1}>No open cycle — there's nothing to capture right now.</EmptyRow>
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
