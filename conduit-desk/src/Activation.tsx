import React, { useState } from 'react';
import { useApi } from './lib/query';
import { marketId } from './api';
import { PageHead, Card, Chip, Money, LayerNote, AuditRef, EmptyRow, LoadBar, SkeletonRow, num, gbp } from './kit/kit';
import { I } from './kit/icons';

// 21 — Activation ingest + warranty provision (spec/ui/21-activation.md). The sell-through + after-sales
// surface (doc 07 M8): charger ACTIVATIONS ingested first-write-wins from the UFE placement stream (the real
// "a unit went live at a customer", distinct from sell-in / dispatch), and the WARRANTY PROVISION each
// activation opens, releasing straight-line over the term from the activation date (not dispatch).
//
// Backend: the activation feed + capacity-connected trend are live (ActivationRoutes, off the serial register).
// The warranty provision register is still M8/Phase-2 (no route yet) — its read 404s and renders the honest "Not
// available in this environment yet" panel, never a stuck skeleton. 401/403 (forbidden) renders the layer wall.
//
// Re-fetches on a context-market switch and on the in-page market filter (both feed the query key). Activation
// identity is the `volume` layer; warranty provision money is `profitability` and COLLAPSES (never £0).

type AnyRole = { layers?: string[] };

interface ActivationProps {
  role: AnyRole;
  ctx: { market?: string; entity?: string; period?: string; scenario?: string };
  toast: (m: string, k?: string) => void;
}

interface ActivationRow {
  sn?: string;
  serial?: string;
  activated_at?: string;
  activatedAt?: string;
  installer?: string;
  owner?: string;
  market?: string;
}

interface SellInVsThrough {
  dispatched?: number | null;
  activated?: number | null;
}

interface ActivationFeed {
  rows?: ActivationRow[];
  total?: number;
  sellInVsThrough?: SellInVsThrough;
  sell_in_vs_through?: SellInVsThrough;
}

interface WarrantyRow {
  sn?: string;
  serial?: string;
  owner?: string;
  provision?: number | string | null;
  outstanding?: number | string | null;
  pct?: number | string;
  releasedPct?: number | string;
  audit_ref?: string;
  auditRef?: string;
}

interface WarrantyTotals {
  provision?: number | string | null;
  outstanding?: number | string | null;
  released?: number | string | null;
}

interface WarrantyRegister {
  rows?: WarrantyRow[];
  totals?: WarrantyTotals;
  hasCost?: boolean;
}

const fmtPct = (n: number) => (Number.isFinite(n) ? n : 0).toFixed(0);

interface CapacityPoint { date: string; daily_units?: number; daily_mw?: number; avg_daily_mw?: number; cumulative_mw?: number }
interface CapacityHeadline { total_units?: number; total_mw?: number; current_avg_daily_mw?: number; in_window_units?: number }
interface Capacity { kw_per_unit?: number; window_months?: number; smoothing_days?: number; as_of?: string; headline?: CapacityHeadline; points?: CapacityPoint[] }

// Capacity-connected chart: the smoothed daily run-rate of MW going live (area) over the cumulative MW online
// (right-axis line). Inline SVG — no chart lib, matching the rest of the desk. X-axis carries dated month ticks.
function CapacityChart({ points }: { points: CapacityPoint[] }) {
  const W = 980, H = 240, padL = 8, padR = 8, padT = 14, padB = 26;
  const n = points.length;
  if (n < 2) return <div className="dim" style={{ padding: 20 }}>Not enough history to chart.</div>;
  const avg = points.map((p) => p.avg_daily_mw ?? 0);
  const cum = points.map((p) => p.cumulative_mw ?? 0);
  const maxAvg = Math.max(...avg, 0.001);
  const maxCum = Math.max(...cum, 0.001);
  const x = (i: number) => padL + (i / (n - 1)) * (W - padL - padR);
  const yA = (v: number) => padT + (1 - v / maxAvg) * (H - padT - padB);
  const yC = (v: number) => padT + (1 - v / maxCum) * (H - padT - padB);
  const areaPts = avg.map((v, i) => `${x(i).toFixed(1)},${yA(v).toFixed(1)}`).join(' ');
  const area = `${padL},${(H - padB).toFixed(1)} ${areaPts} ${x(n - 1).toFixed(1)},${(H - padB).toFixed(1)}`;
  const cumLine = cum.map((v, i) => `${x(i).toFixed(1)},${yC(v).toFixed(1)}`).join(' ');
  // Month-boundary ticks (1st of each month present), thinned so labels never collide.
  const firsts = points.map((p, i) => ({ i, d: p.date })).filter((p) => p.d.slice(8, 10) === '01');
  const step = Math.ceil(firsts.length / 8);
  const ticks = firsts.filter((_, k) => k % step === 0);
  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" style={{ display: 'block' }} preserveAspectRatio="none">
      <defs>
        <linearGradient id="capfill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="var(--accent)" stopOpacity="0.42" />
          <stop offset="100%" stopColor="var(--accent)" stopOpacity="0.03" />
        </linearGradient>
      </defs>
      {ticks.map((t) => (
        <g key={t.i}>
          <line x1={x(t.i)} y1={padT} x2={x(t.i)} y2={H - padB} stroke="var(--border)" strokeWidth={1} strokeDasharray="2 4" opacity={0.5} />
          <text x={x(t.i)} y={H - 8} fontSize={10} fill="var(--faint)" textAnchor="middle">{t.d.slice(0, 7)}</text>
        </g>
      ))}
      <polygon points={area} fill="url(#capfill)" />
      <polyline points={areaPts} fill="none" stroke="var(--accent)" strokeWidth={2} strokeLinejoin="round" />
      <polyline points={cumLine} fill="none" stroke="var(--ok)" strokeWidth={1.5} strokeDasharray="5 4" opacity={0.85} />
    </svg>
  );
}

// An honest "endpoint not built" panel (404). Distinct from a stuck skeleton or a £0.
function NotAvailable({ which }: { which: string }) {
  return (
    <div
      data-testid="activation-not-available"
      style={{ padding: '28px 18px', textAlign: 'center', color: 'var(--muted)', border: '1px dashed var(--border)', borderRadius: 10, background: 'var(--bg-2)' }}
    >
      <div style={{ marginBottom: 6, color: 'var(--faint)' }}>{I.wifiOff({ size: 22 })}</div>
      <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600, color: 'var(--text)' }}>Not available in this environment yet</div>
      <div className="dim" style={{ fontSize: 12, marginTop: 4 }}>The {which} (M8 — activation ingest) isn't built in this deployment.</div>
    </div>
  );
}

export function Activation({ role, ctx, toast }: ActivationProps) {
  const layers = role?.layers ?? [];
  const canSeeProvision = layers.length === 0 || layers.indexOf('profitability') >= 0;

  const [market, setMarket] = useState('all');

  const feedQ = (() => {
    const mk = market !== 'all' ? market : ctx?.market || 'all';
    const q = mk && mk !== 'all' ? `?market=${encodeURIComponent(marketId(mk))}&limit=60` : '?limit=60';
    return q;
  })();

  const feedApi = useApi<ActivationFeed>(['activations', market, ctx?.market], `/api/v1/activations${feedQ}`);
  const warrApi = useApi<WarrantyRegister>(['warranty-provisions', ctx?.market], '/api/v1/warranty/provisions');
  const capApi = useApi<Capacity>(['activation-capacity'], '/api/v1/activations/capacity?months=24&smoothing=28');
  const cap = capApi.data ?? null;
  const capPts: CapacityPoint[] = Array.isArray(cap?.points) ? cap!.points! : [];
  const capH = cap?.headline ?? null;

  const feedErr = feedApi.error;
  const feedForbidden = feedErr?.forbidden ?? false;
  const feedNotImpl = feedErr?.notImplemented ?? false;
  const feedOther = !!feedErr && !feedForbidden && !feedNotImpl;
  const feedReady = !feedApi.isLoading && !feedErr;

  const warrErr = warrApi.error;
  const warrForbidden = warrErr?.forbidden ?? false;
  const warrNotImpl = warrErr?.notImplemented ?? false;
  const warrOther = !!warrErr && !warrForbidden && !warrNotImpl;
  const warrReady = !warrApi.isLoading && !warrErr;

  const feed = feedApi.data ?? null;
  const warr = warrApi.data ?? null;

  const acts: ActivationRow[] = Array.isArray(feed?.rows) ? feed!.rows! : [];
  const sit = feed?.sellInVsThrough ?? feed?.sell_in_vs_through ?? null;
  const dispatched = sit?.dispatched ?? null;
  const activated = sit?.activated ?? null;
  const throughPct = dispatched ? ((activated ?? 0) / dispatched) * 100 : 0;
  const feedTotal = feed?.total ?? acts.length;

  const wrows: WarrantyRow[] = Array.isArray(warr?.rows) ? warr!.rows! : [];
  const wt = warr?.totals ?? null;
  // The register reports whether the cost layer was projected in; honour it but also respect role.layers.
  const provisionVisible = canSeeProvision && (warr ? warr.hasCost !== false : true);

  const heroNote =
    feedNotImpl ? 'not available yet' : feedForbidden ? 'requires the volume layer' : feedOther ? 'failed to load' : '';

  return (
    <div className="page" style={{ maxWidth: 1320 }}>
      <PageHead
        crumb="Sell-through & after-sales · doc 07 M8"
        title="Activation & Warranty"
        sub={
          <span style={{ display: 'block', maxWidth: 820 }}>
            Activations are the real sale signal — a unit went live at a customer — ingested first-write-wins from
            the placement stream (a later version never overrides the first). Each opens a warranty provision that
            releases straight-line over the term. The warranty clock starts at <b>activation, not dispatch</b>.
          </span>
        }
      />

      {/* Capacity connected — the smoothed daily MW run-rate over the cumulative fleet MW online */}
      <Card style={{ marginBottom: 14, padding: 0 }} className="tablewrap">
        <div className="row g12" style={{ padding: '14px 18px 6px', alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <div style={{ flex: 1, minWidth: 200 }}>
            <div className="muted" style={{ fontSize: 'var(--fs-small)' }}>Capacity connected</div>
            <div className="dim" style={{ fontSize: 'var(--fs-xs)', marginTop: 2 }}>
              How much EV-charging capacity we're actually bringing online — each activated charger is a single-phase
              32&nbsp;A install ({cap?.kw_per_unit ?? 7.4}&nbsp;kW). Daily run-rate is a {cap?.smoothing_days ?? 28}-day trailing mean.
            </div>
          </div>
          <div style={{ textAlign: 'right' }}>
            <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600, color: 'var(--accent)' }}>
              {capApi.isLoading ? <span className="skel skel-line" style={{ width: 80, height: 22, display: 'inline-block' }} /> : `${(capH?.current_avg_daily_mw ?? 0).toFixed(2)} MW/day`}
            </div>
            <div className="dim" style={{ fontSize: 'var(--fs-xs)' }}>current run-rate</div>
          </div>
          <div style={{ textAlign: 'right', borderLeft: '1px solid var(--border)', paddingLeft: 14 }}>
            <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600, color: 'var(--ok)' }}>
              {capApi.isLoading ? <span className="skel skel-line" style={{ width: 80, height: 22, display: 'inline-block' }} /> : `${num(Math.round(capH?.total_mw ?? 0))} MW`}
            </div>
            <div className="dim" style={{ fontSize: 'var(--fs-xs)' }}>{num(capH?.total_units ?? 0)} chargers online</div>
          </div>
        </div>
        {capApi.isLoading ? (
          <div className="skel skel-line" style={{ height: 200, margin: '8px 18px 18px', borderRadius: 8 }} />
        ) : capApi.error ? (
          <div className="dim" style={{ padding: '0 18px 18px', fontSize: 'var(--fs-xs)' }}>
            {capApi.error.notImplemented ? 'Capacity trend not available in this environment yet.' : capApi.error.forbidden ? 'Requires the volume layer.' : `Could not load the capacity trend (${capApi.error.status}).`}
          </div>
        ) : (
          <>
            <CapacityChart points={capPts} />
            <div className="row g16" style={{ padding: '4px 18px 12px', fontSize: 'var(--fs-xs)' }}>
              <span className="dim"><span style={{ display: 'inline-block', width: 18, height: 3, background: 'var(--accent)', verticalAlign: 'middle', marginRight: 5 }} />MW connected per day ({cap?.smoothing_days ?? 28}-day mean)</span>
              <span className="dim"><span style={{ display: 'inline-block', width: 18, height: 0, borderTop: '2px dashed var(--ok)', verticalAlign: 'middle', marginRight: 5 }} />cumulative MW online</span>
              {cap?.as_of && <span className="dim" style={{ marginLeft: 'auto' }}>as of {cap.as_of}</span>}
            </div>
          </>
        )}
      </Card>

      {/* sell-in → sell-through hero + provision summary */}
      <div className="grid" style={{ gridTemplateColumns: '1.6fr 1fr 1fr', marginBottom: 14, alignItems: 'stretch' }}>
        <Card>
          <div className="muted" style={{ fontSize: 'var(--fs-small)', marginBottom: 'var(--sp-2)' }}>Sell-in → sell-through</div>
          {feedApi.isLoading ? (
            <div className="skel skel-line" style={{ width: 220, height: 26 }} />
          ) : feedErr ? (
            <div className="dim" style={{ fontSize: 'var(--fs-xs)' }}>{heroNote}</div>
          ) : (
            <>
              <div className="row g12" style={{ alignItems: 'flex-end' }}>
                <div>
                  <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600 }}>{dispatched != null ? num(dispatched) : '—'}</div>
                  <div className="dim" style={{ fontSize: 'var(--fs-xs)' }}>dispatched (sell-in)</div>
                </div>
                <I.arrowR style={{ color: 'var(--faint)', marginBottom: 14 }} />
                <div>
                  <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600, color: 'var(--ok)' }}>{activated != null ? num(activated) : '—'}</div>
                  <div className="dim" style={{ fontSize: 'var(--fs-xs)' }}>activated (sell-through)</div>
                </div>
              </div>
              {sit && (
                <div style={{ marginTop: 12 }}>
                  <div style={{ height: 6, borderRadius: 4, background: 'var(--surface3)', overflow: 'hidden' }}>
                    <div style={{ width: `${Math.min(throughPct, 100)}%`, height: '100%', background: 'var(--ok)' }} />
                  </div>
                  <div className="dim" style={{ fontSize: 'var(--fs-xs)', marginTop: 4 }}>
                    {fmtPct(throughPct)}% of dispatched units are live — the rest are on a shelf, feeding H6Q depletion.
                  </div>
                </div>
              )}
            </>
          )}
        </Card>

        <Card>
          <div className="muted" style={{ fontSize: 'var(--fs-small)' }}>Warranty provision</div>
          <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600, marginTop: 3 }}>
            {warrApi.isLoading ? (
              <div className="skel skel-line" style={{ width: 90, height: 22 }} />
            ) : warrReady && provisionVisible ? (
              <Money value={wt?.provision ?? null} />
            ) : (
              <span className="dim">hidden</span>
            )}
          </div>
          <div className="dim" style={{ fontSize: 'var(--fs-xs)' }}>
            {warrNotImpl ? 'not available yet' : warrReady && provisionVisible ? 'total opened' : 'requires profitability'}
          </div>
        </Card>

        <Card>
          <div className="muted" style={{ fontSize: 'var(--fs-small)' }}>Outstanding liability</div>
          <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600, marginTop: 3, color: warrReady && provisionVisible ? 'var(--warn)' : undefined }}>
            {warrApi.isLoading ? (
              <div className="skel skel-line" style={{ width: 90, height: 22 }} />
            ) : warrReady && provisionVisible ? (
              <Money value={wt?.outstanding ?? null} />
            ) : (
              <span className="dim">hidden</span>
            )}
          </div>
          <div className="dim" style={{ fontSize: 'var(--fs-xs)' }}>
            {warrNotImpl ? 'not available yet' : warrReady && provisionVisible ? `${gbp(wt?.released)} released to date` : 'requires profitability'}
          </div>
        </Card>
      </div>

      <div className="grid" style={{ gridTemplateColumns: '1fr 1fr', alignItems: 'start' }}>
        {/* Activation feed — volume layer */}
        <Card title="Activation feed" icon={I.wifi} aux="first-write-wins · sell-through signal" style={{ padding: 0 }} className="tablewrap">
          <LoadBar>
            <div className="seg">
              {['all', 'UK', 'IE'].map((m) => (
                <button key={m} className={market === m ? 'on' : ''} onClick={() => setMarket(m)}>{m === 'all' ? 'All' : m}</button>
              ))}
            </div>
            <div style={{ flex: 1 }} />
            <span className="dim" style={{ fontSize: 'var(--fs-small)' }}>
              {feedReady ? `${num(feedTotal)} activations` : feedApi.isLoading ? 'loading…' : ''}
            </span>
          </LoadBar>
          {feedNotImpl ? (
            <div style={{ padding: 16 }}><NotAvailable which="activation feed" /></div>
          ) : (
            <div style={{ maxHeight: 460, overflowY: 'auto' }}>
              <table className="tbl">
                <thead><tr><th>Serial</th><th>Activated</th><th>Installer</th><th>Owner</th><th>Mkt</th></tr></thead>
                <tbody>
                  {feedApi.isLoading && <SkeletonRow cols={5} />}
                  {feedForbidden && (
                    <tr><td colSpan={5}><LayerNote>Activation feed hidden — requires the <b>volume</b> layer (<code>view:activation</code>).</LayerNote></td></tr>
                  )}
                  {feedOther && (
                    <tr><td colSpan={5}><div className="banner danger">Could not load activations ({feedErr?.status}).</div></td></tr>
                  )}
                  {feedReady && acts.map((a, i) => (
                    <tr key={a.sn ?? a.serial ?? i}>
                      <td className="mono">{a.sn ?? a.serial}</td>
                      <td className="dim">{a.activated_at ?? a.activatedAt}</td>
                      <td>{a.installer}</td>
                      <td className="dim">{a.owner}</td>
                      <td><Chip s="neutral">{a.market}</Chip></td>
                    </tr>
                  ))}
                  {feedReady && acts.length === 0 && <EmptyRow cols={5}>No activations in this market yet — units are dispatched but not live.</EmptyRow>}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        {/* Warranty provision register — profitability layer (collapses) */}
        <Card title="Warranty provision register" icon={I.shield} aux="straight-line release from activation date" style={{ padding: 0 }} className="tablewrap">
          {warrNotImpl ? (
            <div style={{ padding: 16 }}><NotAvailable which="warranty provision register" /></div>
          ) : (
            <>
              <div style={{ maxHeight: 502, overflowY: 'auto' }}>
                <table className="tbl">
                  <thead><tr><th>Serial</th><th>Owner</th><th className="num">Provision</th><th style={{ width: 140 }}>Released</th><th className="num">Outstanding</th><th>Ref</th></tr></thead>
                  <tbody>
                    {warrApi.isLoading && <SkeletonRow cols={6} />}
                    {warrForbidden && (
                      <tr><td colSpan={6}><LayerNote>Warranty provision hidden — requires the <b>profitability</b> layer.</LayerNote></td></tr>
                    )}
                    {warrOther && (
                      <tr><td colSpan={6}><div className="banner danger">Could not load the provision register ({warrErr?.status}).</div></td></tr>
                    )}
                    {warrReady && wrows.map((w, i) => {
                      const pct = Number(w.pct ?? w.releasedPct ?? 0);
                      return (
                        <tr key={w.sn ?? w.serial ?? i}>
                          <td className="mono">{w.sn ?? w.serial}</td>
                          <td className="dim" style={{ fontSize: 'var(--fs-small)' }}>{w.owner}</td>
                          <td className="num">{provisionVisible ? <Money value={w.provision ?? null} /> : <span className="dim">—</span>}</td>
                          <td>
                            {provisionVisible ? (
                              <div className="row g6" style={{ alignItems: 'center' }}>
                                <div style={{ flex: 1, height: 5, borderRadius: 3, background: 'var(--surface3)', overflow: 'hidden' }}>
                                  <div style={{ width: `${Math.min(pct, 100)}%`, height: '100%', background: 'var(--ok)' }} />
                                </div>
                                <span className="dim" style={{ fontSize: 10 }}>{fmtPct(pct)}%</span>
                              </div>
                            ) : <span className="dim">— layer</span>}
                          </td>
                          <td className="num">{provisionVisible ? <Money value={w.outstanding ?? null} /> : <span className="dim">—</span>}</td>
                          <td>{(w.audit_ref ?? w.auditRef) ? <AuditRef id={w.audit_ref ?? w.auditRef} /> : <span className="dim">—</span>}</td>
                        </tr>
                      );
                    })}
                    {warrReady && wrows.length === 0 && <EmptyRow cols={6}>No warranty provisions opened yet.</EmptyRow>}
                  </tbody>
                </table>
              </div>
              {warrReady && !provisionVisible && (
                <LayerNote>Provision figures hidden — requires the <b>profitability</b> layer.</LayerNote>
              )}
              {warrReady && provisionVisible && (
                <div className="layer-note" style={{ padding: '10px 16px' }}>
                  <I.clock />The warranty clock starts the day a unit activates. A warranty claim from Returns draws this provision down.
                </div>
              )}
            </>
          )}
        </Card>
      </div>
    </div>
  );
}
