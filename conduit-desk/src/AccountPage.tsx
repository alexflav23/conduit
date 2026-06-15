import React, { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useApi } from './lib/query';
import { ApiError } from './lib/client';
import { PageHead, Card, Chip, Coverage, LayerNote, num } from './kit/kit';
import { I } from './kit/icons';

// The shareable per-account page (/account/:id). Headline off the serial register, the forecasted depletion
// (runway + stockout + a forward confidence cone), the activation rate over time, and every MRPeasy delivery as a
// dated tranche scored by depletion. One fetch: /api/v1/h6q/shelf/{id}/detail.

type Ctx = { entity: string; market: string; period: string; scenario: string };

interface Delivery { dispatch_no?: string; date?: string | null; status?: string; shipped?: number; activated?: number; depletion_pct?: number }
interface ActPoint { period?: string; activated?: number }
interface ProjPoint { week?: string; expected_draw?: number; projected_on_shelf?: number; low?: number; high?: number }
interface HistPoint { week?: string; on_shelf?: number; activated?: number }
interface Forecast {
  on_shelf?: number; weekly_rate?: number; daily_rate?: number; runway_days?: number | null;
  stockout_date?: string | null; trend_pct?: number; method?: string; anchor?: string;
  history?: HistPoint[]; projection?: ProjPoint[];
}

// Short axis label: "Jun '26" for month-starts, else "Jun 15".
function fmtPeriod(p?: string): string {
  if (!p) return '';
  const [y, m, d] = p.split('-');
  const mon = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][parseInt(m, 10) - 1] ?? m;
  return d === '01' ? `${mon} '${(y ?? '').slice(2)}` : `${mon} ${parseInt(d, 10)}`;
}
interface Summary { name?: string; sector?: string; shipped?: number; activated?: number; on_shelf?: number }
interface AcctDetail {
  summary?: Summary | null;
  deliveries?: Delivery[];
  activations?: { daily?: ActPoint[]; weekly?: ActPoint[]; monthly?: ActPoint[] };
  forecast?: Forecast;
}

type Grain = 'daily' | 'weekly' | 'monthly';

function ActivationBars({ points }: { points: ActPoint[] }) {
  if (!points.length) return <div className="dim" style={{ fontSize: 12, padding: '8px 2px' }}>No activations recorded yet.</div>;
  const W = 760, H = 150, padT = 14, padB = 20;
  const max = Math.max(...points.map((p) => p.activated ?? 0), 1);
  const gap = 1.5;
  const bw = (W - gap * (points.length - 1)) / points.length;
  const showVals = points.length <= 32;          // value labels only when bars aren't too dense
  const tickEvery = Math.max(1, Math.round(points.length / 8)); // ~8 dated x-axis ticks
  return (
    <svg viewBox={'0 0 ' + W + ' ' + H} style={{ width: '100%', height: 'auto', display: 'block' }} role="img" aria-label="activation rate over time">
      <line x1={0} y1={H - padB} x2={W} y2={H - padB} stroke="var(--border)" strokeWidth={1} />
      {points.map((p, i) => {
        const h = ((p.activated ?? 0) / max) * (H - padT - padB);
        const xx = i * (bw + gap);
        const cx = xx + Math.max(bw, 0.6) / 2;
        return (
          <g key={p.period ?? i}>
            <rect x={xx} y={H - padB - h} width={Math.max(bw, 0.6)} height={h} rx={bw > 3 ? 1 : 0} fill="var(--accent)">
              <title>{p.period}: {num(p.activated)} activated</title>
            </rect>
            {showVals && (p.activated ?? 0) > 0 && (
              <text x={cx} y={H - padB - h - 3} textAnchor="middle" fontSize={8.5} fill="var(--muted)" fontFamily="var(--font-mono)">{num(p.activated)}</text>
            )}
            {i % tickEvery === 0 && (
              <text x={cx} y={H - 6} textAnchor="middle" fontSize={9} fill="var(--faint)" fontFamily="var(--font-mono)">{fmtPeriod(p.period)}</text>
            )}
          </g>
        );
      })}
    </svg>
  );
}

// Forward depletion: the projected on-shelf line falling toward the stockout, inside a low/high confidence cone.
// Actual on-shelf history (solid green) flowing into the forecast (dashed accent + cone), on a dated x-axis,
// with "now" and forecast-stockout markers.
function DepletionChart({ history, proj, onShelf }: { history: HistPoint[]; proj: ProjPoint[]; onShelf: number }) {
  const hist = history ?? [];
  if (!proj.length && hist.length < 2) return null;
  const W = 760, H = 220, padL = 6, padR = 6, padT = 20, padB = 26;
  const dates = [...hist.map((h) => h.week ?? ''), ...proj.map((p) => p.week ?? '')];
  const n = Math.max(dates.length, 2);
  const maxV = Math.max(onShelf, ...hist.map((h) => h.on_shelf ?? 0), ...proj.map((p) => p.high ?? p.projected_on_shelf ?? 0), 1) * 1.1;
  const x = (i: number) => padL + (i * (W - padL - padR)) / (n - 1);
  const y = (v: number) => H - padB - (v / maxV) * (H - padT - padB);
  const histN = hist.length;
  const bIdx = Math.max(histN - 1, 0);                 // "now" = last actual point
  const fX = (j: number) => x(histN + j);
  const histPath = hist.map((h, i) => (i ? 'L' : 'M') + x(i).toFixed(1) + ' ' + y(h.on_shelf ?? 0).toFixed(1)).join(' ');
  const fcPath = `M ${x(bIdx).toFixed(1)} ${y(onShelf).toFixed(1)} ` + proj.map((p, j) => `L ${fX(j).toFixed(1)} ${y(p.projected_on_shelf ?? 0).toFixed(1)}`).join(' ');
  const cone =
    `M ${x(bIdx).toFixed(1)} ${y(onShelf).toFixed(1)} ` + proj.map((p, j) => `L ${fX(j).toFixed(1)} ${y(p.high ?? 0).toFixed(1)}`).join(' ') + ' ' +
    proj.slice().reverse().map((p, j) => `L ${fX(proj.length - 1 - j).toFixed(1)} ${y(p.low ?? 0).toFixed(1)}`).join(' ') + ` L ${x(bIdx).toFixed(1)} ${y(onShelf).toFixed(1)} Z`;
  const zeroJ = proj.findIndex((p) => (p.projected_on_shelf ?? 1) <= 0);
  const tickEvery = Math.max(1, Math.round(n / 8));
  return (
    <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 'auto', display: 'block' }} role="img" aria-label="on-shelf actuals + forecasted depletion">
      <line x1={0} y1={y(0)} x2={W} y2={y(0)} stroke="var(--border)" strokeWidth={1} />
      {proj.length > 0 && <path d={cone} fill="var(--accent)" opacity={0.12} />}
      {hist.length > 1 && <path d={histPath} fill="none" stroke="var(--ok)" strokeWidth={2.5} />}
      {proj.length > 0 && <path d={fcPath} fill="none" stroke="var(--accent-bright)" strokeWidth={2.5} strokeDasharray="5 4" />}
      {histN > 0 && (
        <g>
          <line x1={x(bIdx)} y1={padT - 6} x2={x(bIdx)} y2={H - padB} stroke="var(--muted)" strokeWidth={1} strokeDasharray="2 3" />
          <text x={x(bIdx)} y={padT - 9} textAnchor="middle" fontSize={9} fill="var(--muted)" fontFamily="var(--font-mono)">now</text>
        </g>
      )}
      {zeroJ >= 0 && (
        <g>
          <line x1={fX(zeroJ)} y1={padT} x2={fX(zeroJ)} y2={H - padB} stroke="var(--danger)" strokeWidth={1.5} strokeDasharray="4 3" />
          <text x={Math.min(fX(zeroJ), W - 26)} y={padT - 9} textAnchor="middle" fontSize={9} fill="var(--danger)" fontFamily="var(--font-mono)">stockout</text>
        </g>
      )}
      {dates.map((d, i) => (i % tickEvery === 0 ? <text key={i} x={x(i)} y={H - 8} textAnchor="middle" fontSize={9} fill="var(--faint)" fontFamily="var(--font-mono)">{fmtPeriod(d)}</text> : null))}
    </svg>
  );
}

export function AccountPage({ role }: { token: string; role: any; ctx: Ctx; toast: (m: string, k?: 'ok' | 'warn' | 'err') => void }) {
  const { id } = useParams();
  const navigate = useNavigate();
  const [grain, setGrain] = useState<Grain>('monthly');

  const q = useApi<AcctDetail>(['account-detail', id ?? ''], `/api/v1/h6q/shelf/${id}/detail`, { enabled: !!id });
  const err = q.error as ApiError | null;
  const d = q.data ?? {};
  const s = d.summary ?? {};
  const f = d.forecast ?? {};
  const deliveries = Array.isArray(d.deliveries) ? d.deliveries : [];
  const series = (d.activations?.[grain] ?? []) as ActPoint[];
  const sellThrough = s.shipped ? ((s.activated ?? 0) / s.shipped) * 100 : 0;
  const runwayState = f.runway_days == null ? 'neutral' : f.runway_days <= 14 ? 'danger' : f.runway_days <= 30 ? 'warn' : 'ok';
  const runwayLabel = f.runway_days == null ? 'n/a' : f.runway_days >= 365 ? '1y+' : num(f.runway_days) + 'd';

  return (
    <>
      <PageHead
        crumb={<span style={{ cursor: 'pointer' }} onClick={() => navigate('/shelf')}>← H6Q · Shelf</span>}
        title={s.name ?? (id ?? '').slice(0, 8)}
        sub="Per-account status — forecasted depletion, activation history, and every delivery as a dated tranche."
        right={<Chip s={runwayState}>{runwayLabel} forecast runway</Chip>}
      />

      {q.isLoading && <Card title="Loading…" icon={I.battery}><div className="dim" style={{ padding: 16 }}>Loading account…</div></Card>}
      {err?.forbidden && <Card title="Account" icon={I.battery}><LayerNote>hidden — requires <b>view:pipeline_coverage</b></LayerNote></Card>}
      {err && !err.forbidden && <Card title="Account" icon={I.battery}><div className="banner danger" style={{ margin: 8 }}>Couldn't load this account (HTTP {err.status}).</div></Card>}

      {!q.isLoading && !err && (
        <>
          <div className="grid" style={{ gridTemplateColumns: 'repeat(3,1fr)', gap: 12, marginBottom: 16 }}>
            {[['Shipped', s.shipped, 'var(--text)'], ['Activated', s.activated, 'var(--ok)'], ['On-shelf', s.on_shelf, 'var(--accent-bright)']].map(([k, v, c]) => (
              <Card key={k as string} title={k as string} icon={I.battery}>
                <div className="num" style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600, color: c as string, padding: '2px 2px 6px' }}>{num(v as number)}</div>
              </Card>
            ))}
          </div>

          <Card title="Forecasted depletion" icon={I.pulse}
            aux={<span className="dim" style={{ fontSize: 11.5 }}>{f.method}</span>}>
            <div className="grid" style={{ gridTemplateColumns: 'repeat(4,1fr)', gap: 10, marginBottom: 14 }}>
              <div className="card" style={{ padding: '11px 13px', background: 'var(--bg-2)' }}>
                <div className="fldlabel">Forecast runway</div>
                <div style={{ fontFamily: 'var(--font-disp)', fontSize: 22, fontWeight: 600, marginTop: 2, color: runwayState === 'danger' ? 'var(--danger)' : runwayState === 'warn' ? 'var(--warn)' : 'var(--text)' }}>{runwayLabel}</div>
              </div>
              <div className="card" style={{ padding: '11px 13px', background: 'var(--bg-2)' }}>
                <div className="fldlabel">Forecast stockout</div>
                <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600, marginTop: 4 }}>{f.stockout_date ?? '—'}</div>
              </div>
              <div className="card" style={{ padding: '11px 13px', background: 'var(--bg-2)' }}>
                <div className="fldlabel">Draw rate</div>
                <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600, marginTop: 4 }}>{num(f.weekly_rate)}/wk</div>
                <div className="dim" style={{ fontSize: 10.5 }}>{num(f.daily_rate)}/day</div>
              </div>
              <div className="card" style={{ padding: '11px 13px', background: 'var(--bg-2)' }}>
                <div className="fldlabel">Trend (4wk)</div>
                <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600, marginTop: 4, color: (f.trend_pct ?? 0) > 3 ? 'var(--ok)' : (f.trend_pct ?? 0) < -3 ? 'var(--warn)' : 'var(--muted)' }}>
                  {(f.trend_pct ?? 0) > 0 ? '+' : ''}{f.trend_pct ?? 0}%
                </div>
              </div>
            </div>
            <DepletionChart history={Array.isArray(f.history) ? f.history : []} proj={Array.isArray(f.projection) ? f.projection : []} onShelf={f.on_shelf ?? s.on_shelf ?? 0} />
            <div className="row g12" style={{ fontSize: 11, marginTop: 6, alignItems: 'center' }}>
              <span className="row g6"><span style={{ width: 16, height: 2.5, background: 'var(--ok)', display: 'inline-block' }} />actual on-shelf</span>
              <span className="row g6"><span style={{ width: 16, height: 0, borderTop: '2.5px dashed var(--accent-bright)', display: 'inline-block' }} />forecast</span>
              <span className="dim">shaded = confidence cone (widens with horizon); the red line is the forecast stockout.</span>
            </div>
          </Card>

          <div style={{ height: 16 }} />
          <Card title="Activation rate over time" icon={I.trend}
            aux={
              <div className="row g6">
                {(['daily', 'weekly', 'monthly'] as Grain[]).map((g) => (
                  <button key={g} className={'btn sm' + (grain === g ? ' primary' : '')} data-testid={`act-grain-${g}`} onClick={() => setGrain(g)} style={{ textTransform: 'capitalize' }}>{g}</button>
                ))}
              </div>
            }>
            <ActivationBars points={series} />
            <div className="dim" style={{ fontSize: 11, marginTop: 6 }}>Sell-through {sellThrough.toFixed(0)}% — {num(s.activated)} of {num(s.shipped)} shipped have activated.</div>
          </Card>

          <div style={{ height: 16 }} />
          <Card title="Deliveries" icon={I.battery}
            aux={<span className="dim" style={{ fontSize: 11.5 }}>each MRPeasy shipment as a dated tranche, scored by depletion</span>}
            className="tablewrap" style={{ padding: 0 }}>
            {deliveries.length === 0 ? (
              <div className="dim" style={{ fontSize: 12, padding: 14 }}>No deliveries recorded for this account.</div>
            ) : (
              <table className="tbl" data-testid="acct-deliveries">
                <thead>
                  <tr><th>Tranche</th><th>Date</th><th className="num">Units</th><th className="num">Activated</th><th style={{ width: 150 }}>Depletion</th></tr>
                </thead>
                <tbody>
                  {deliveries.map((dl, i) => (
                    <tr key={dl.dispatch_no ?? i} data-testid="acct-delivery-row">
                      <td style={{ fontFamily: 'var(--font-mono)' }}>{dl.dispatch_no}</td>
                      <td className="dim">{dl.date ? dl.date.slice(0, 10) : '—'}</td>
                      <td className="num">{num(dl.shipped)}</td>
                      <td className="num" style={{ color: 'var(--ok)' }}>{num(dl.activated)}</td>
                      <td><Coverage pct={dl.depletion_pct ?? 0} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>
        </>
      )}
    </>
  );
}
