import React, { useEffect, useState } from 'react';
import { marketId } from './api';
import { asArray } from './state';
import { PageHead, Card, Chip, LayerNote, EmptyRow, Skeleton, SkeletonRow } from './kit/kit';
import { I } from './kit/icons';
import { useApi } from './lib/query';
import { ApiError } from './lib/client';

// 28 — Forecast Runs (spec/ui/28-forecast-runs.md). Makes the self-improving tournament (doc 26) legible to a
// human: every forecast origin is an immutable, idempotent record (forecast_run + model_accuracy +
// policy_selection), so we can show HOW the forecast evolved between runs and the BASIS for that evolution —
// without re-running anything. Read-only; gated view:pipeline_coverage. The diff/narrative is computed by the
// pure RunDiff server-side — the UI never recomputes a figure.
//
// Auto-loads the run timeline on mount + when the market context changes (no Load button). Picking a row opens
// its report; the two newest origins seed the compare selectors. Four states everywhere: loading (skeleton) /
// empty (EmptyRow) / 403 (LayerNote — requires view:pipeline_coverage) / 404 (not available) / error.
//
// Real endpoints (api ForecastRunRoutes):
//   GET /api/v1/forecast/runs
//   GET /api/v1/forecast/runs/{origin}/report
//   GET /api/v1/forecast/runs/diff?from=&to=&group_by=&market=
//   GET /api/v1/forecast/runs/diff/accounts?from=&to=&market=
//   GET /api/v1/forecast/runs/account/{company}?from=&to=
// All gated view:pipeline_coverage (403 -> LayerNote). market is the resolved UUID from ctx.

type AnyRole = { layers?: string[] };
interface Props {
  role: AnyRole;
  ctx: { market?: string; entity?: string; period?: string; scenario?: string };
  toast: (m: string, k?: string) => void;
}

// total-level error chip: ok ≤15% · warn ≤40% · exception (danger) above
function errChip(pct: number | string | null | undefined): string {
  const n = pct == null ? 0 : Number(pct);
  return n <= 15 ? 'ok' : n <= 40 ? 'warn' : 'exception';
}
const signed = (v: any) => `${Number(v) > 0 ? '+' : ''}${Number(v).toLocaleString('en-GB')}`;
const grp = (v: any) => Number(v).toLocaleString('en-GB');

const asErr = (e: unknown): ApiError | null => (e instanceof ApiError ? e : null);

function NotAvailable({ testid }: { testid?: string }) {
  return (
    <div className="banner" data-testid={testid} style={{ padding: '14px 12px', color: 'var(--muted)' }}>
      {I.alert({ size: 15 })} Not available in this environment yet.
    </div>
  );
}

export function ForecastRuns({ role, ctx, toast }: Props) {
  const market = ctx?.market || '';
  const mkt = market ? marketId(market) : '';
  const scope = [ctx?.entity, market, ctx?.scenario, ctx?.period];

  const [openOrigin, setOpenOrigin] = useState<string | null>(null);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [groupBy, setGroupBy] = useState('segment');
  const [drillId, setDrillId] = useState<string | null>(null);

  // --- run timeline: auto-loads on mount + whenever the market context changes (the key carries the scope) ---
  const runsQ = useApi<any[]>(['forecast-runs', ...scope], '/api/v1/forecast/runs');
  const runs = asArray<any>(runsQ.data);
  const runsErr = asErr(runsQ.error);

  // seed the compare selectors with the two most recent origins (rows are origin-desc) once they load
  useEffect(() => {
    if (!runs.length) return;
    setTo(runs[0].origin);
    setFrom(runs.length >= 2 ? runs[1].origin : runs[0].origin);
  }, [runsQ.dataUpdatedAt]); // eslint-disable-line react-hooks/exhaustive-deps

  const state = runsQ.isLoading ? 'loading'
    : runsErr?.forbidden ? 'forbidden'
    : runsErr?.notImplemented ? 'notimpl'
    : runsErr ? 'error'
    : runs.length === 0 ? 'empty'
    : 'ready';

  // --- per-run report (opened from a timeline row; the route ignores market) ---
  const reportQ = useApi<any>(
    ['forecast-report', openOrigin, ...scope],
    `/api/v1/forecast/runs/${encodeURIComponent(openOrigin ?? '')}/report`,
    { enabled: !!openOrigin },
  );
  const rep = openOrigin ? reportQ.data ?? null : null;
  const reportErr = asErr(reportQ.error);

  const openReport = (origin: string) => setOpenOrigin((prev) => (prev === origin ? null : origin));

  // --- compare two runs (pure RunDiff, server-side). axis 'account' uses the dedicated accounts endpoint. ---
  const diffAxis = groupBy === 'account' ? 'segment' : groupBy;
  const diffPath = (() => {
    const q = new URLSearchParams({ from, to, group_by: diffAxis });
    if (mkt) q.set('market', mkt);
    return `/api/v1/forecast/runs/diff?${q.toString()}`;
  })();
  const diffQ = useApi<any>(
    ['forecast-diff', from, to, diffAxis, mkt, ...scope],
    diffPath,
    { enabled: state === 'ready' && !!from && !!to },
  );
  const diffData = diffQ.data ?? null;
  const diffErr = asErr(diffQ.error);

  const accountsPath = (() => {
    const q = new URLSearchParams({ from, to });
    if (mkt) q.set('market', mkt);
    return `/api/v1/forecast/runs/diff/accounts?${q.toString()}`;
  })();
  const accountsQ = useApi<any[]>(
    ['forecast-accounts', from, to, mkt, ...scope],
    accountsPath,
    { enabled: state === 'ready' && groupBy === 'account' && !!from && !!to },
  );
  const accountRows = asArray<any>(accountsQ.data);

  // --- one account's drill-down (the bake-off + per-SKU depletion snapshot, from→to) ---
  const drillQ = useApi<any>(
    ['forecast-account', drillId, from, to],
    `/api/v1/forecast/runs/account/${encodeURIComponent(drillId ?? '')}?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
    { enabled: !!drillId && !!from && !!to },
  );
  const drill = drillId ? drillQ.data ?? null : null;
  const openDrill = (companyId: string) => setDrillId((prev) => (prev === companyId ? null : companyId));

  // surface non-terminal failures as toasts (forbidden/404 render inline, not a toast)
  useEffect(() => { if (reportErr && !reportErr.forbidden && !reportErr.notImplemented) toast(`report failed: ${reportErr.status}`, 'err'); }, [reportErr, toast]);
  useEffect(() => { if (diffErr && !diffErr.forbidden && !diffErr.notImplemented) toast(`compare failed: ${diffErr.status}`, 'err'); }, [diffErr, toast]);

  const dv = !from || !to || diffQ.isLoading || (diffQ.fetchStatus === 'idle' && !diffData)
    ? 'idle'
    : diffErr?.forbidden ? 'forbidden'
    : diffErr?.notImplemented ? 'notimpl'
    : diffErr ? 'error'
    : 'ready';

  const stat = (label: string, value: React.ReactNode, testid: string, accent?: boolean) => (
    <div className="metric" style={{ minWidth: 130 }}>
      <div className="ml">{label}</div>
      <div className={'mv' + (accent ? ' accent' : '')} style={{ fontSize: 22 }} data-testid={testid}>{value}</div>
    </div>
  );
  const sectionLabel = (t: string, style?: React.CSSProperties) => (
    <div className="dim" style={{ fontSize: 12, textTransform: 'uppercase', letterSpacing: '0.06em', ...style }}>{t}</div>
  );

  return (
    <>
      <PageHead
        title="Forecast runs"
        sub="The tournament's run history — the per-run report (the basis) and a human-readable diff of how the forecast evolved"
        right={<span className="chip neutral"><span className="d" />read-only · reproducible</span>}
      />

      <Card title="Run timeline" icon={I.clock} aux={<span className="dim" style={{ fontSize: 12 }}>each origin is an immutable, reproducible record — newest first</span>}>
        {state === 'forbidden'
          ? <LayerNote>hidden — requires <span className="mono">view:pipeline_coverage</span></LayerNote>
          : state === 'notimpl'
          ? <NotAvailable testid="fr-notimpl" />
          : state === 'error'
          ? <div className="banner danger" data-testid="fr-error">{I.alert({ size: 15 })} Couldn't load forecast runs ({runsErr?.status}). The run timeline is served from the stored tournament record.</div>
          : (
        <div className="tablewrap">
          <table className="tbl" data-testid="fr-runs">
            <thead><tr>
              <th>Origin</th><th className="num">Accounts</th><th className="num">Forecast</th><th className="num">Actual</th>
              <th>Total-level error</th><th className="num">Model runs</th><th>Last scored</th><th></th>
            </tr></thead>
            <tbody>
              {state === 'loading' && <><SkeletonRow cols={8} /><SkeletonRow cols={8} /><SkeletonRow cols={8} /></>}
              {state === 'empty' && <EmptyRow cols={8}><span data-testid="fr-empty">No forecast runs yet — the backtest loop writes one record per origin.</span></EmptyRow>}
              {state === 'ready' && runs.map((r: any, i: number) => (
                <tr key={r.origin ?? i} data-testid="fr-run-row" className={openOrigin === r.origin ? 'sel' : ''}>
                  <td><b className="mono">{r.origin}</b></td>
                  <td className="num">{grp(r.accounts)}</td>
                  <td className="num">{grp(r.forecast_units)}</td>
                  <td className="num">{grp(r.actual_units)}</td>
                  <td><Chip s={errChip(r.total_level_error_pct)}>{r.total_level_error_pct}%</Chip></td>
                  <td className="num">{grp(r.model_runs)}</td>
                  <td className="dim mono" style={{ fontSize: 11.5 }}>{(r.last_selected_at ?? '').slice(0, 10) || '—'}</td>
                  <td><button className="btn sm" data-testid="fr-open" onClick={() => openReport(r.origin)}>{openOrigin === r.origin ? 'Hide' : 'Report'}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        )}
      </Card>

      {openOrigin && (
        <Card title={`Run report · ${openOrigin}`} icon={I.pulse} aux={<span className="dim" style={{ fontSize: 12 }}>the basis the champions were chosen on</span>}>
          {reportQ.isLoading && <Skeleton lines={4} />}
          {reportErr?.forbidden && <LayerNote>hidden — requires <span className="mono">view:pipeline_coverage</span></LayerNote>}
          {reportErr?.notImplemented && <NotAvailable />}
          {reportErr && !reportErr.forbidden && !reportErr.notImplemented && <div className="banner danger">{I.alert({ size: 15 })} Couldn't load the report for {openOrigin} ({reportErr.status}).</div>}
          {!reportQ.isLoading && !reportErr && !rep && <EmptyRow cols={1}>No report for this origin.</EmptyRow>}
          {rep && (
          <div data-testid="fr-report">
            <div className="row" style={{ gap: 26, flexWrap: 'wrap', marginBottom: 14 }}>
              {stat('Accounts', grp(rep.stats?.accounts), 'fr-stat-accounts')}
              {stat('Forecast units', grp(rep.stats?.forecast_units), 'fr-stat-forecast')}
              {stat('Actual units', grp(rep.stats?.actual_units), 'fr-stat-actual')}
              {stat('Total-level error', `${rep.stats?.total_level_error_pct}%`, 'fr-stat-error', true)}
              {stat('Structural champ', `${Math.round(Number(rep.stats?.structural_share) * 100)}%`, 'fr-stat-structural')}
            </div>

            <div className="grid" style={{ gridTemplateColumns: 'repeat(2, 1fr)' }}>
              <div>
                {sectionLabel('Outturn by segment', { marginBottom: 8 })}
                <div className="tablewrap"><table className="tbl" data-testid="fr-segments">
                  <thead><tr><th>Segment</th><th className="num">Accts</th><th className="num">Forecast</th><th className="num">Actual</th><th>Error</th></tr></thead>
                  <tbody>
                    {asArray<any>(rep.segments).map((s: any, i: number) => (
                      <tr key={i}><td>{s.segment}</td><td className="num">{grp(s.accounts)}</td><td className="num">{grp(s.forecast_units)}</td><td className="num">{grp(s.actual_units)}</td><td><Chip s={errChip(s.total_level_error_pct)}>{s.total_level_error_pct}%</Chip></td></tr>
                    ))}
                    {asArray<any>(rep.segments).length === 0 && <EmptyRow cols={5}>No segment outturn.</EmptyRow>}
                  </tbody>
                </table></div>
              </div>
              <div>
                {sectionLabel('Champion model mix', { marginBottom: 8 })}
                <div className="tablewrap"><table className="tbl" data-testid="fr-policy-mix">
                  <thead><tr><th>Policy</th><th className="num">Accounts won</th></tr></thead>
                  <tbody>
                    {asArray<any>(rep.policy_mix).map((m: any, i: number) => (
                      <tr key={i}><td className="mono">{m.policy_key}</td><td className="num">{grp(m.accounts)}</td></tr>
                    ))}
                    {asArray<any>(rep.policy_mix).length === 0 && <EmptyRow cols={2}>No champions selected.</EmptyRow>}
                  </tbody>
                </table></div>
              </div>
            </div>

            {sectionLabel('Model bake-off (lowest mean abs error wins the account) — the basis a champion was chosen on', { margin: '14px 0 8px' })}
            <div className="tablewrap"><table className="tbl" data-testid="fr-accuracy">
              <thead><tr><th>Model</th><th>Kind</th><th className="num">Scored</th><th className="num">Mean abs error</th><th className="num">Total abs error</th></tr></thead>
              <tbody>
                {asArray<any>(rep.model_accuracy).map((m: any, i: number) => (
                  <tr key={i}><td className="mono">{m.model_key}</td><td><Chip s={m.structural ? 'accent' : 'neutral'}>{m.structural ? 'structural' : 'statistical'}</Chip></td><td className="num">{grp(m.scored)}</td><td className="num">{m.mean_abs_error}</td><td className="num">{m.total_abs_error}</td></tr>
                ))}
                {asArray<any>(rep.model_accuracy).length === 0 && <EmptyRow cols={5}>No scored models for this origin.</EmptyRow>}
              </tbody>
            </table></div>

            {sectionLabel('Run provenance — the pins (data SHA + params hash)', { margin: '14px 0 8px' })}
            <div className="tablewrap"><table className="tbl" data-testid="fr-models">
              <thead><tr><th>Model</th><th className="num">Ver</th><th>Purpose</th><th>Data SHA</th><th>Params</th><th>Ran at</th></tr></thead>
              <tbody>
                {asArray<any>(rep.model_runs).map((m: any, i: number) => (
                  <tr key={i}><td className="mono">{m.model_key}</td><td className="num">{m.model_version}</td><td>{m.purpose}</td><td className="mono dim">{(m.data_sha ?? '—')?.toString().slice(0, 10)}</td><td className="mono dim">{(m.params_hash ?? '—')?.toString().slice(0, 10)}</td><td className="dim mono" style={{ fontSize: 11.5 }}>{(m.created_at ?? '').slice(0, 16)}</td></tr>
                ))}
                {asArray<any>(rep.model_runs).length === 0 && <EmptyRow cols={6}>No provenance recorded.</EmptyRow>}
              </tbody>
            </table></div>
          </div>
          )}
        </Card>
      )}

      <Card title="Compare two runs" icon={I.scale} aux={<span className="dim" style={{ fontSize: 12 }}>how the forecast evolved — and the basis for it (pure RunDiff, server-side)</span>}>
        <div className="row g8" style={{ flexWrap: 'wrap', alignItems: 'center' }}>
          <span className="dim">From</span>
          <select className="fld sel" data-testid="fr-from" value={from} onChange={(e) => setFrom(e.target.value)} disabled={state !== 'ready'}>
            <option value="">—</option>{runs.map((r: any) => <option key={r.origin} value={r.origin}>{r.origin}</option>)}
          </select>
          <span className="dim">To</span>
          <select className="fld sel" data-testid="fr-to" value={to} onChange={(e) => setTo(e.target.value)} disabled={state !== 'ready'}>
            <option value="">—</option>{runs.map((r: any) => <option key={r.origin} value={r.origin}>{r.origin}</option>)}
          </select>
          {market && <span className="dim" style={{ fontSize: 12 }}>· scoped to ctx market</span>}
        </div>

        {state === 'ready' && (!from || !to) && <div className="dim" style={{ marginTop: 12 }} data-testid="fr-diff-empty">Pick two origins to see how the forecast evolved.</div>}

        {from && to && dv === 'forbidden' && <div style={{ marginTop: 12 }}><LayerNote>hidden — requires <span className="mono">view:pipeline_coverage</span></LayerNote></div>}
        {from && to && dv === 'notimpl' && <div style={{ marginTop: 12 }}><NotAvailable testid="fr-diff-notimpl" /></div>}
        {from && to && dv === 'error' && <div className="banner danger" style={{ marginTop: 12 }}>{I.alert({ size: 15 })} Couldn't compute the diff ({diffErr?.status}).</div>}
        {from && to && dv === 'idle' && diffQ.isLoading && <div style={{ marginTop: 12 }}><Skeleton lines={3} /></div>}

        {dv === 'ready' && diffData && (
          <div data-testid="fr-diff" style={{ marginTop: 14 }}>
            <div className="lineage" data-testid="fr-narrative" style={{ marginBottom: 14, whiteSpace: 'pre-wrap' }}>
              {asArray<string>(diffData.narrative).length
                ? asArray<string>(diffData.narrative).map((n) => `• ${n}`).join('\n')
                : 'No material change between these runs.'}
            </div>

            <div className="row" style={{ gap: 26, flexWrap: 'wrap', marginBottom: 14 }}>
              {stat('Error Δ', `${Number(diffData.error_delta_pct) > 0 ? '+' : ''}${diffData.error_delta_pct} pts`, 'fr-diff-error', true)}
              {stat('Accounts added', grp(diffData.accounts_added), 'fr-diff-added')}
              {stat('Accounts dropped', grp(diffData.accounts_dropped), 'fr-diff-dropped')}
              {stat('Champion changes', asArray<any>(diffData.champion_changes).length, 'fr-diff-changes')}
            </div>

            <div className="row g8" style={{ flexWrap: 'wrap', marginBottom: 10, alignItems: 'center' }}>
              <span className="dim">Browse delta by</span>
              <div className="seg">
                {['segment', 'channel', 'market', 'account'].map((a) => (
                  <button key={a} className={groupBy === a ? 'on' : ''} data-testid={`fr-by-${a}`} onClick={() => setGroupBy(a)}>{a[0].toUpperCase() + a.slice(1)}</button>
                ))}
              </div>
            </div>

            {groupBy === 'account' ? (
              <div className="tablewrap" style={{ marginBottom: 14 }}>
                <table className="tbl" data-testid="fr-accounts">
                  <thead><tr>
                    <th>Account</th><th>Champion</th><th className="num">Forecast Δ</th><th className="num">Error Δ</th>
                    <th className="num">On-shelf</th><th className="num">Shelf Δ</th><th className="num">Rate /mo</th><th className="num">Rate Δ</th><th className="num">Runway (d)</th><th></th>
                  </tr></thead>
                  <tbody>
                    {accountsQ.isLoading && <><SkeletonRow cols={10} /><SkeletonRow cols={10} /></>}
                    {!accountsQ.isLoading && accountRows.map((a: any, i: number) => (
                      <React.Fragment key={a.company_id ?? i}>
                        <tr data-testid="fr-account-row">
                          <td><b>{a.name}</b></td>
                          <td>{a.champion_changed
                            ? <span><span className="mono dim">{a.from_policy}</span> → <Chip s="accent">{a.to_policy}</Chip></span>
                            : <span className="mono dim">{a.to_policy ?? '—'}</span>}</td>
                          <td className="num">{signed(a.forecast_delta)}</td>
                          <td className="num"><Chip s={Number(a.error_delta_pct) <= 0 ? 'ok' : 'warn'}>{Number(a.error_delta_pct) > 0 ? '+' : ''}{a.error_delta_pct}</Chip></td>
                          <td className="num">{grp(a.shelf_stock)}</td>
                          <td className="num"><span className={Number(a.shelf_delta) < 0 ? 'dim' : ''}>{signed(a.shelf_delta)}</span></td>
                          <td className="num">{grp(a.depletion_rate)}</td>
                          <td className="num"><Chip s={Number(a.rate_delta) >= 0 ? 'ok' : 'warn'}>{signed(a.rate_delta)}</Chip></td>
                          <td className="num">{a.runway_days == null ? '—' : Math.round(Number(a.runway_days))}</td>
                          <td><button className="btn sm" data-testid="fr-account-drill" onClick={() => openDrill(a.company_id)}>{drillId === a.company_id ? 'Hide' : 'Why'}</button></td>
                        </tr>
                        {drillId === a.company_id && (
                          <tr data-testid="fr-drill"><td colSpan={10} style={{ background: 'var(--bg-2)' }}>
                            {drillQ.isLoading ? <div style={{ padding: '10px 4px' }}><Skeleton lines={2} /></div> : drill ? (
                            <div className="grid" style={{ gridTemplateColumns: 'repeat(2, 1fr)', padding: '10px 4px' }}>
                              <div>
                                {sectionLabel('Participating models (the bake-off)', { fontSize: 11.5, marginBottom: 6 })}
                                <table className="tbl"><thead><tr><th>Model</th><th>Kind</th><th className="num">Mean err</th><th></th></tr></thead><tbody>
                                  {asArray<any>(drill.participants).map((m: any, j: number) => (
                                    <tr key={j}><td className="mono">{m.model_key}</td><td><Chip s={m.structural ? 'accent' : 'neutral'}>{m.structural ? 'structural' : 'statistical'}</Chip></td><td className="num">{m.mean_abs_error}</td><td>{m.is_champion && <Chip s="champion">champion</Chip>}</td></tr>
                                  ))}
                                  {asArray<any>(drill.participants).length === 0 && <EmptyRow cols={4}>No models recorded.</EmptyRow>}
                                </tbody></table>
                              </div>
                              <div>
                                {sectionLabel(`Depletion by SKU — snapshot ${from} → ${to}`, { fontSize: 11.5, marginBottom: 6 })}
                                <table className="tbl"><thead><tr><th>SKU</th><th className="num">Shelf {from}</th><th className="num">Shelf {to}</th><th className="num">Rate {from}</th><th className="num">Rate {to}</th><th className="num">Rate Δ</th><th className="num">Runway (d)</th></tr></thead><tbody>
                                  {asArray<any>(drill.depletion).map((d: any, j: number) => (
                                    <tr key={j}><td className="mono">{d.sku}</td><td className="num dim">{grp(d.from_shelf)}</td><td className="num">{grp(d.shelf_stock)}</td><td className="num dim">{grp(d.from_rate)}</td><td className="num">{grp(d.depletion_rate)}</td><td className="num"><Chip s={Number(d.rate_delta) >= 0 ? 'ok' : 'warn'}>{signed(d.rate_delta)}</Chip></td><td className="num">{d.runway_days == null ? '—' : Math.round(Number(d.runway_days))}</td></tr>
                                  ))}
                                  {asArray<any>(drill.depletion).length === 0 && <EmptyRow cols={7}>No depletion snapshot.</EmptyRow>}
                                </tbody></table>
                              </div>
                            </div>
                            ) : <div className="dim" style={{ padding: '10px 4px' }}>No drill-down for this account.</div>}
                          </td></tr>
                        )}
                      </React.Fragment>
                    ))}
                    {!accountsQ.isLoading && accountRows.length === 0 && <EmptyRow cols={10}><span data-testid="fr-accounts-empty">No accounts on this filter.</span></EmptyRow>}
                  </tbody>
                </table>
              </div>
            ) : (
              <div className="tablewrap" style={{ marginBottom: 14 }}>
                <table className="tbl" data-testid="fr-breakdown">
                  <thead><tr>
                    <th>{groupBy[0].toUpperCase() + groupBy.slice(1)}</th>
                    <th className="num">From error</th><th className="num">To error</th><th className="num">Error Δ</th>
                    <th className="num">Forecast Δ</th><th className="num">Actual Δ</th><th className="num">Accts</th>
                  </tr></thead>
                  <tbody>
                    {asArray<any>(diffData.breakdown).map((b: any, i: number) => (
                      <tr key={i} data-testid="fr-breakdown-row">
                        <td><b>{b.cell}</b></td>
                        <td className="num">{b.from_error_pct}%</td>
                        <td className="num">{b.to_error_pct}%</td>
                        <td className="num"><Chip s={Number(b.error_delta_pct) <= 0 ? 'ok' : 'warn'}>{Number(b.error_delta_pct) > 0 ? '+' : ''}{b.error_delta_pct}</Chip></td>
                        <td className="num">{signed(b.forecast_delta)}</td>
                        <td className="num">{signed(b.actual_delta)}</td>
                        <td className="num">{grp(b.to_accounts)}</td>
                      </tr>
                    ))}
                    {asArray<any>(diffData.breakdown).length === 0 && <EmptyRow cols={7}>No cells on this axis.</EmptyRow>}
                  </tbody>
                </table>
              </div>
            )}

            {sectionLabel('Champion changes (account → from-policy → to-policy)', { marginBottom: 8 })}
            <div className="tablewrap"><table className="tbl" data-testid="fr-champion-changes">
              <thead><tr><th>Account</th><th>From</th><th></th><th>To</th></tr></thead>
              <tbody>
                {asArray<any>(diffData.champion_changes).map((c: any, i: number) => (
                  <tr key={i}><td className="mono">{(c.company_id ?? '').slice(0, 8)}</td><td className="mono dim">{c.from}</td><td className="dim">→</td><td><Chip s="accent">{c.to}</Chip></td></tr>
                ))}
                {asArray<any>(diffData.champion_changes).length === 0 && <EmptyRow cols={4}>No champion changed between these runs.</EmptyRow>}
              </tbody>
            </table></div>
          </div>
        )}
      </Card>
    </>
  );
}
