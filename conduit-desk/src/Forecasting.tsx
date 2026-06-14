import React from 'react';
import { PageHead, Card, Coverage, EmptyRow, LayerNote, Skeleton, SkeletonRow, num } from './kit/kit';
import { I } from './kit/icons';
import { useApi } from './lib/query';
import { ApiError } from './lib/client';

// Forecast Engine (spec/ui/15) — the self-improving forecast's glass box (doc 26). Read-only, gated
// view:pipeline_coverage (same gate as the H6Q board). The rolling-origin backtest scores every model per origin;
// the champion is the lowest mean-abs-error model in the bake-off (model_accuracy). Origins are immutable,
// idempotent records, so every figure is reproducible.
//
// Real endpoints (api ForecastRunRoutes):
//   GET /api/v1/forecast/runs                      -> [{ origin, accounts, forecast_units, actual_units,
//                                                       total_level_error_pct, last_selected_at, model_runs }]
//   GET /api/v1/forecast/runs/{origin}/report      -> { origin, stats, policy_mix[], segments[],
//                                                       model_runs[], model_accuracy[] }
// Both require view:pipeline_coverage (403 -> LayerNote). 404 -> "not available yet". AUTO-LOADS on mount and
// whenever ctx changes (the cache key carries the ctx scope so a context switch refetches).

interface OriginRow {
  origin: string;
  accounts: number;
  forecast_units: number | string;
  actual_units: number | string;
  total_level_error_pct: number | string;
  last_selected_at?: string;
  model_runs?: number;
}
interface ModelAccuracy {
  model_key: string;
  scored: number;
  mean_abs_error: number | string;
  total_abs_error: number | string;
  structural?: boolean;
}
interface SegmentRow {
  segment: string;
  accounts: number;
  forecast_units: number | string;
  actual_units: number | string;
  total_level_error_pct: number | string;
}
interface ModelRun {
  model_key: string;
  model_version: number;
  purpose: string;
  data_sha?: string | null;
  params_hash?: string | null;
  created_at?: string;
}
interface ReportData {
  origin?: string;
  stats?: { accounts?: number; forecast_units?: number | string; actual_units?: number | string; total_level_error_pct?: number | string; structural_share?: number | string };
  segments?: SegmentRow[];
  model_runs?: ModelRun[];
  model_accuracy?: ModelAccuracy[];
}

interface AccuracyPoint { q: string; forecast: number; actual: number; error: number }

const N = (v: number | string | null | undefined): number => (v == null ? 0 : Number(v));
const errColor = (e: number) => (e < 12 ? 'var(--ok)' : e < 18 ? 'var(--warn)' : 'var(--danger)');

const isForbidden = (e: ApiError | null | undefined) => !!e && e.forbidden;
const isNotImplemented = (e: ApiError | null | undefined) => !!e && e.notImplemented;

function NotAvailable({ testid }: { testid?: string }) {
  return (
    <div className="banner" data-testid={testid} style={{ padding: '14px 12px', color: 'var(--muted)' }}>
      {I.alert({ size: 15 })} Not available in this environment yet.
    </div>
  );
}

function AccuracyChart({ series }: { series: AccuracyPoint[] }) {
  const W = 900;
  const H = 200;
  const pad = 32;
  const vals = series.flatMap((s) => [s.forecast, s.actual]);
  const max = Math.max(...vals, 1) * 1.08;
  const min = Math.min(...vals, 0) * 0.92;
  const x = (i: number) => pad + (i * (W - pad * 2)) / Math.max(series.length - 1, 1);
  const y = (v: number) => H - pad - ((v - min) / Math.max(max - min, 1)) * (H - pad * 2);
  const path = (key: 'forecast' | 'actual') =>
    series.map((s, i) => (i ? 'L' : 'M') + x(i).toFixed(1) + ' ' + y(s[key]).toFixed(1)).join(' ');
  return (
    <div>
      <svg viewBox={'0 0 ' + W + ' ' + H} style={{ width: '100%', height: 'auto' }} role="img" aria-label="forecast accuracy by rolling origin">
        <path d={path('forecast')} fill="none" stroke="var(--accent)" strokeWidth={2} strokeDasharray="5 4" />
        <path d={path('actual')} fill="none" stroke="var(--ok)" strokeWidth={2.5} />
        {series.map((s, i) => (
          <g key={s.q}>
            <circle cx={x(i)} cy={y(s.actual)} r={3.5} fill="var(--ok)" />
            <circle cx={x(i)} cy={y(s.forecast)} r={3} fill="var(--accent)" />
            <text x={x(i)} y={H - 8} textAnchor="middle" fontSize={10} fill="var(--faint)" fontFamily="var(--font-mono)">{s.q}</text>
            <text x={x(i)} y={y(Math.max(s.actual, s.forecast)) - 8} textAnchor="middle" fontSize={9.5} fill={s.error < 10 ? 'var(--ok)' : 'var(--warn)'} fontFamily="var(--font-mono)">{s.error.toFixed(0)}%</text>
          </g>
        ))}
      </svg>
      <div className="row g12" style={{ marginTop: 6, fontSize: 11.5 }}>
        <span className="row g6"><span style={{ width: 16, height: 2, background: 'var(--ok)', display: 'inline-block' }} />actual</span>
        <span className="row g6"><span style={{ width: 16, height: 0, borderTop: '2px dashed var(--accent)', display: 'inline-block' }} />forecast</span>
        <span className="dim">· error % labelled per origin — bending toward truth</span>
      </div>
    </div>
  );
}

export function Forecasting({ ctx }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const scope = [ctx?.entity, ctx?.market, ctx?.scenario, ctx?.period];

  const runsQ = useApi<OriginRow[]>(['forecast-runs', ...scope], '/api/v1/forecast/runs');
  const runs = Array.isArray(runsQ.data) ? runsQ.data : [];

  const runsForbidden = isForbidden(runsQ.error as ApiError);
  const runsNotImpl = isNotImplemented(runsQ.error as ApiError);
  const runsOtherError = runsQ.error && !runsForbidden && !runsNotImpl ? (runsQ.error as ApiError) : null;

  // The latest origin (the timeline is ordered DESC) anchors the champion bake-off + per-segment outturn report.
  const latestOrigin = runs[0]?.origin ?? '';
  const reportQ = useApi<ReportData>(
    ['forecast-report', latestOrigin, ...scope],
    `/api/v1/forecast/runs/${encodeURIComponent(latestOrigin)}/report`,
    { enabled: !!latestOrigin },
  );
  const report = reportQ.data ?? null;
  const accuracy = report?.model_accuracy ?? [];
  const segments = report?.segments ?? [];
  const modelRuns = report?.model_runs ?? [];
  const championKey = accuracy[0]?.model_key; // lowest mean-abs-error — the model the bake-off picked

  const reportForbidden = isForbidden(reportQ.error as ApiError);
  const reportNotImpl = isNotImplemented(reportQ.error as ApiError);
  const reportOtherError = reportQ.error && !reportForbidden && !reportNotImpl ? (reportQ.error as ApiError) : null;

  // The accuracy-over-time series is the run timeline itself: each origin's forecast vs actual + scored error.
  const series: AccuracyPoint[] = runs
    .slice()
    .reverse()
    .map((r) => ({
      q: r.origin,
      forecast: N(r.forecast_units),
      actual: N(r.actual_units),
      error: N(r.total_level_error_pct),
    }));

  const dataSha = modelRuns.find((m) => m.data_sha)?.data_sha ?? undefined;

  const right =
    !runsQ.isLoading && latestOrigin ? (
      <span className="stale"><span className="pulse" />backtest {latestOrigin}{runs[0]?.last_selected_at ? ' · ' + runs[0].last_selected_at.slice(0, 10) : ''}</span>
    ) : undefined;

  const liveRows = runs.reduce((acc, r) => acc + N(r.model_runs), 0);

  return (
    <div className="page" style={{ maxWidth: 1320 }}>
      <PageHead
        crumb="Self-improving forecast (doc 26) · the machine earned this"
        title="Forecast Engine"
        sub={
          <span style={{ display: 'block', maxWidth: 820 }}>
            The rolling-origin backtest scores every model per origin — the champion is the lowest mean-abs-error
            model in the bake-off, no hardcoding. Its accuracy ledger trends toward truth across the{' '}
            {runsQ.isLoading ? '—' : num(liveRows)} model runs it has authored.
          </span>
        }
        right={right}
      />

      {runsForbidden ? (
        <Card title="Forecast Engine" icon={I.shield}>
          <LayerNote>The Forecast Engine is withheld — requires the <b>view:pipeline_coverage</b> permission.</LayerNote>
        </Card>
      ) : runsNotImpl ? (
        <Card title="Forecast Engine" icon={I.trend}>
          <NotAvailable testid="forecast-notimpl" />
        </Card>
      ) : runsOtherError ? (
        <Card title="Accuracy over time" icon={I.trend}>
          <div className="banner danger" data-testid="forecast-error">
            {I.alert({ size: 15 })} Could not load the forecast engine ({runsOtherError.status}). The backtest ledger is unavailable.
          </div>
        </Card>
      ) : (
        <>
          <Card
            title="Accuracy over time"
            icon={I.trend}
            aux="train ≤ Q → predict Q+1 → score · the error trend is the credibility metric"
            style={{ marginBottom: 14 }}
          >
            {runsQ.isLoading ? (
              <Skeleton lines={4} />
            ) : series.length === 0 ? (
              <div className="dim" data-testid="forecast-accuracy-empty" style={{ padding: '8px 2px' }}>No backtest run yet.</div>
            ) : (
              <AccuracyChart series={series} />
            )}
          </Card>

          <Card
            title="Champion board"
            icon={I.layers}
            aux="argmin over model_accuracy · the model it picked, and the rivals it beat"
            style={{ padding: 0, marginBottom: 14 }}
            className="tablewrap"
          >
            <table className="tbl">
              <thead>
                <tr>
                  <th>Model</th>
                  <th>Class</th>
                  <th className="num">Scored</th>
                  <th className="num">Mean abs error</th>
                  <th className="num">Total abs error</th>
                  <th>Verdict</th>
                </tr>
              </thead>
              <tbody>
                {reportForbidden ? (
                  <tr><td colSpan={6}><LayerNote>hidden — the bake-off requires the <b>view:pipeline_coverage</b> permission.</LayerNote></td></tr>
                ) : reportNotImpl ? (
                  <tr><td colSpan={6}><NotAvailable /></td></tr>
                ) : reportOtherError ? (
                  <tr><td colSpan={6}><div className="banner danger">{I.alert({ size: 15 })} Champion board failed ({reportOtherError.status}).</div></td></tr>
                ) : !latestOrigin || reportQ.isLoading ? (
                  <SkeletonRow cols={6} />
                ) : accuracy.length === 0 ? (
                  <EmptyRow cols={6}>No backtest run yet — the champion board fills once an origin is scored.</EmptyRow>
                ) : (
                  accuracy.map((m, i) => (
                    <tr key={m.model_key + i}>
                      <td>
                        <span className="chip accent mono" style={{ fontFamily: 'var(--font-mono)' }}>{m.model_key}</span>
                      </td>
                      <td>
                        {m.structural ? (
                          <span className="chip accent"><I.cpu size={11} />structural</span>
                        ) : (
                          <span className="chip neutral">statistical</span>
                        )}
                      </td>
                      <td className="num mono">{num(m.scored)}</td>
                      <td className="num"><b style={{ color: errColor(N(m.mean_abs_error)) }}>{N(m.mean_abs_error).toFixed(2)}</b></td>
                      <td className="num mono">{N(m.total_abs_error).toFixed(2)}</td>
                      <td>
                        {m.model_key === championKey ? (
                          <span className="chip plum"><I.check size={11} />champion</span>
                        ) : (
                          <span className="dim">beaten</span>
                        )}
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
            <div className="layer-note" style={{ padding: '10px 16px' }}>
              <I.shield />
              Reproducible: same data + code ⇒ same champion.
              {dataSha ? (
                <>
                  {' '}The pinning data SHA <span className="mono" style={{ fontFamily: 'var(--font-mono)' }}>{String(dataSha).slice(0, 12)}</span> stamps which run scored these.
                </>
              ) : null}
            </div>
          </Card>

          <Card
            title="Outturn by segment"
            icon={I.list}
            aux="the per-segment forecast vs actual the champion authored · the coverage each earned"
            style={{ padding: 0 }}
            className="tablewrap"
          >
            <table className="tbl">
              <thead>
                <tr>
                  <th>Segment</th>
                  <th className="num">Accounts</th>
                  <th className="num">Forecast</th>
                  <th className="num">Actual</th>
                  <th className="num">Coverage</th>
                  <th className="num">Error</th>
                </tr>
              </thead>
              <tbody>
                {reportForbidden ? (
                  <tr><td colSpan={6}><LayerNote>hidden — segment outturn requires the <b>view:pipeline_coverage</b> permission.</LayerNote></td></tr>
                ) : reportNotImpl ? (
                  <tr><td colSpan={6}><NotAvailable /></td></tr>
                ) : reportOtherError ? (
                  <tr><td colSpan={6}><div className="banner danger">{I.alert({ size: 15 })} Outturn failed ({reportOtherError.status}).</div></td></tr>
                ) : !latestOrigin || reportQ.isLoading ? (
                  <SkeletonRow cols={6} />
                ) : segments.length === 0 ? (
                  <EmptyRow cols={6}>No segment outturn yet — the engine has not scored this origin.</EmptyRow>
                ) : (
                  segments.map((s, i) => {
                    const err = N(s.total_level_error_pct);
                    return (
                      <tr key={s.segment + i}>
                        <td><b>{s.segment}</b></td>
                        <td className="num mono">{num(s.accounts)}</td>
                        <td className="num mono">{num(s.forecast_units)}</td>
                        <td className="num mono">{num(s.actual_units)}</td>
                        <td className="num" style={{ width: 120 }}><Coverage pct={Math.max(0, 100 - err)} /></td>
                        <td className="num mono"><span style={{ color: errColor(err) }}>{err.toFixed(1)}%</span></td>
                      </tr>
                    );
                  })
                )}
              </tbody>
            </table>
          </Card>
        </>
      )}
    </div>
  );
}
