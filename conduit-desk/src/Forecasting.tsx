import React, { useEffect, useState } from 'react';
import { PageHead, Card, Money, Coverage, EmptyRow, LayerNote, SkeletonRow, num } from './kit/kit';
import { I } from './kit/icons';
import { apiFetch } from './api';
import { tableState, asArray, type TableState } from './state';

// Forecast Engine (spec/ui/15) — the self-improving forecast's glass box (doc 26). The rolling-origin backtest
// picks a champion model per account by lowest error (argmin over model_accuracy, no hardcoding); its accuracy
// ledger trends toward truth; and the live model rows it authored fill the H6Q spine. The hero is "the machine
// EARNED this forecast": the champion is shown beating its rivals, and the error trend bending toward truth.
//
// Read-mostly (the engine runs in the backtest loop / LivePublish — no mutations here, so no maker-checker).
// AUTO-LOADS on mount and whenever ctx changes; four states throughout. The revenue projection is `commercial`
// (units → contract tier) — for a volume-only viewer the server withholds it and `Money` COLLAPSES (never £0.00).

interface Runner { model: string; error: number }
interface Champion {
  account: string;
  champion: string;
  error: number;
  runners: Runner[];
  units: number;
  revenue: number | string | null;
  activations: number;
  shelf?: number | null;
}
interface AccuracyPoint { q: string; forecast: number; actual: number; error: number; coverage?: number }
interface SpineRow { account: string; sku: string; source: 'model' | 'human' | string; qty: number; deviation: number }
interface EngineData {
  sha?: string;
  at?: string;
  liveRows?: number;
  hasRevenue?: boolean;
  accuracy?: AccuracyPoint[];
  champions?: Champion[];
  spine?: SpineRow[];
}

const errColor = (e: number) => (e < 12 ? 'var(--ok)' : e < 18 ? 'var(--warn)' : 'var(--danger)');
const devColor = (d: number) => (d < 8 ? 'var(--ok)' : d < 15 ? 'var(--warn)' : 'var(--danger)');

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

export function Forecasting({ role, ctx }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const [res, setRes] = useState<{ status: number; json: any } | null>(null);

  useEffect(() => {
    let live = true;
    setRes(null);
    const q = new URLSearchParams();
    if (ctx?.market) q.set('market', ctx.market);
    if (ctx?.scenario) q.set('scenario', ctx.scenario);
    if (ctx?.entity) q.set('entity', ctx.entity);
    const qs = q.toString();
    apiFetch('/api/v1/forecast/engine' + (qs ? '?' + qs : '')).then((r) => {
      if (live) setRes(r);
    });
    return () => {
      live = false;
    };
  }, [ctx?.market, ctx?.scenario, ctx?.entity]);

  const data: EngineData = (res?.status === 200 && res.json) || {};
  const champions = asArray<Champion>(data.champions);
  const spine = asArray<SpineRow>(data.spine);
  const accuracy = asArray<AccuracyPoint>(data.accuracy);
  const hasRevenue = !!data.hasRevenue;
  const champState: TableState = tableState(res, data.champions);
  const spineState: TableState = tableState(res, data.spine);

  const right =
    res?.status === 200 && data.sha ? (
      <span className="stale"><span className="pulse" />backtest {data.sha}{data.at ? ' · ' + data.at : ''}</span>
    ) : undefined;

  return (
    <div className="page" style={{ maxWidth: 1320 }}>
      <PageHead
        crumb="Self-improving forecast (doc 26) · the machine earned this"
        title="Forecast Engine"
        sub={
          <span style={{ display: 'block', maxWidth: 820 }}>
            The rolling-origin backtest selects a champion model per account by lowest error — no hardcoding. Its
            accuracy ledger trends toward truth, and the {res?.status === 200 ? num(data.liveRows ?? 0) : '—'} model
            rows it authored fill the H6Q spine.
          </span>
        }
        right={right}
      />

      {res !== null && (res.status === 401 || res.status === 403) ? (
        <Card title="Forecast Engine" icon={I.shield}>
          <LayerNote>The Forecast Engine is withheld — requires the <b>view:forecast</b> permission.</LayerNote>
        </Card>
      ) : res !== null && res.status >= 400 ? (
        <Card title="Accuracy over time" icon={I.trend}>
          <div className="banner danger">Could not load the forecast engine ({res.status}). The backtest ledger is unavailable.</div>
        </Card>
      ) : (
        <>
          <Card
            title="Accuracy over time"
            icon={I.trend}
            aux="train ≤ Q → predict Q+1 → score · the error trend is the credibility metric"
            style={{ marginBottom: 14 }}
          >
            {res === null ? (
              <SkeletonRow cols={1} />
            ) : accuracy.length === 0 ? (
              <div className="dim" style={{ padding: '8px 2px' }}>No backtest run yet.</div>
            ) : (
              <AccuracyChart series={accuracy} />
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
                  <th>Account</th>
                  <th>Champion</th>
                  <th className="num">Backtest error</th>
                  <th>Beat</th>
                  <th className="num">Forecast</th>
                  <th className="num">Revenue proj.</th>
                  <th className="num">Activations</th>
                  <th className="num">Shelf</th>
                </tr>
              </thead>
              <tbody>
                {champState === 'loading' ? (
                  <SkeletonRow cols={8} />
                ) : champState === 'empty' ? (
                  <EmptyRow cols={8}>No backtest run yet — the champion board fills once an origin is scored.</EmptyRow>
                ) : (
                  champions.map((c, i) => (
                    <tr key={c.account + i}>
                      <td><b>{c.account}</b></td>
                      <td><span className="chip accent mono" style={{ fontFamily: 'var(--font-mono)' }}>{c.champion}</span></td>
                      <td className="num"><b style={{ color: errColor(c.error) }}>{c.error.toFixed(1)}%</b></td>
                      <td>
                        <div className="row g6 wrap">
                          {asArray<Runner>(c.runners).slice(0, 3).map((r, j) => (
                            <span key={r.model + j} className="chip neutral" style={{ fontSize: 9.5, padding: '0 6px' }}>
                              {r.model} {r.error.toFixed(0)}%
                            </span>
                          ))}
                        </div>
                      </td>
                      <td className="num mono">{num(c.units)}</td>
                      <td className="num">
                        {hasRevenue ? (
                          <Money value={c.revenue} role={role} layer="commercial" />
                        ) : (
                          <span className="dim">hidden</span>
                        )}
                      </td>
                      <td className="num mono">{num(c.activations)}</td>
                      <td className="num mono">{c.shelf == null ? <span className="dim">—</span> : num(c.shelf)}</td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
            {!hasRevenue && champState === 'ready' ? (
              <LayerNote>Revenue projection hidden — requires the <b>commercial</b> data layer. Units are <b>volume</b>.</LayerNote>
            ) : null}
            <div className="layer-note" style={{ padding: '10px 16px' }}>
              <I.shield />
              Reproducible: same data + code ⇒ same champion.
              {data.sha ? (
                <>
                  {' '}The backtest SHA <span className="mono" style={{ fontFamily: 'var(--font-mono)' }}>{data.sha}</span> stamps which run picked these.
                </>
              ) : null}
            </div>
          </Card>

          <Card
            title="Model vs human spine"
            icon={I.list}
            aux="the H6Q rows the engine authored, vs human capture · the deviation each earned"
            style={{ padding: 0 }}
            className="tablewrap"
          >
            <table className="tbl">
              <thead>
                <tr>
                  <th>Account</th>
                  <th>SKU</th>
                  <th>Source</th>
                  <th className="num">Qty</th>
                  <th className="num">Coverage</th>
                  <th className="num">Deviation</th>
                </tr>
              </thead>
              <tbody>
                {spineState === 'loading' ? (
                  <SkeletonRow cols={6} />
                ) : spineState === 'empty' ? (
                  <EmptyRow cols={6}>No spine rows yet — the engine has not authored model rows for this scope.</EmptyRow>
                ) : (
                  spine.map((r, i) => (
                    <tr key={r.account + r.sku + i}>
                      <td>{r.account}</td>
                      <td className="dim mono">{r.sku}</td>
                      <td>
                        {r.source === 'model' ? (
                          <span className="chip accent"><I.cpu size={11} />model</span>
                        ) : (
                          <span className="chip neutral"><I.user size={11} />human</span>
                        )}
                      </td>
                      <td className="num mono">{num(r.qty)}</td>
                      <td className="num" style={{ width: 120 }}><Coverage pct={Math.max(0, 100 - r.deviation)} /></td>
                      <td className="num mono"><span style={{ color: devColor(r.deviation) }}>{r.deviation.toFixed(1)}%</span></td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </Card>
        </>
      )}
    </div>
  );
}
