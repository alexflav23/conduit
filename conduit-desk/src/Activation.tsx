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

const V2G = '#14b8a6'; // teal — distinct from the accent purple; marks Vehicle-to-Grid capacity (Q1'27+)

// Forecast model shared by the chart and the headline: working-day run-rate grown over 8 quarters at the observed
// YoY rate compounded quarterly (capped/floored), with the V2G era starting Q1'27. fcRate(dH) is the predicted
// rate at the end of the horizon — the V2G rate the headline names.
function forecastModel(points: CapacityPoint[]) {
  const n = points.length;
  if (n < 2) return null;
  const dayNum = (s: string) => Date.UTC(+s.slice(0, 4), +s.slice(5, 7) - 1, +s.slice(8, 10)) / 86400000;
  const dF = dayNum(points[n - 1].date);
  const Q = 365 / 4;
  const dH = dF + 8 * Q; // 8 quarters out
  const dV = dayNum('2027-01-01'); // V2G from Q1'27
  const currentRate = points[n - 1].avg_daily_mw ?? 0;
  const idxYrAgo = points.findIndex((p) => dayNum(p.date) >= dF - 365);
  const rateYrAgo = idxYrAgo >= 0 ? (points[idxYrAgo].avg_daily_mw ?? 0) : currentRate;
  const yoy = Math.min(2.2, Math.max(1.05, rateYrAgo > 0 ? currentRate / rateYrAgo : 1.4));
  const qGrowth = Math.pow(yoy, 1 / 4);
  const fcRate = (dn: number) => currentRate * Math.pow(qGrowth, (dn - dF) / Q);
  return { dayNum, d0: dayNum(points[0].date), dF, dV, dH, currentRate, qGrowth, fcRate, avg: points.map((p) => p.avg_daily_mw ?? 0) };
}

// Capacity chart: the smoothed working-day run-rate of MW going live (solid area = actuals), then a forecast
// growth curve over the next 8 quarters (dashed, grounded in observed YoY growth compounded quarterly). From
// Q1'27 the forecast is V2G capacity, shaded with a distinct teal gradient + label. No cumulative line — just how
// the install rate has evolved and where it's headed. Inline SVG, quarter ticks across the whole span.
function CapacityChart({ points }: { points: CapacityPoint[] }) {
  const W = 980, H = 372, padL = 8, padR = 8, padT = 22, padB = 26;
  const m = forecastModel(points);
  if (!m) return <div className="dim" style={{ padding: 20 }}>Not enough history to chart.</div>;
  const { dayNum, d0, dF, dV, dH, fcRate, avg } = m;
  const maxAvg = Math.max(...avg, fcRate(dH), 0.001) * 1.06;

  const baseY = H - padB;
  const x = (dn: number) => padL + ((dn - d0) / (dH - d0)) * (W - padL - padR);
  const yA = (v: number) => padT + (1 - v / maxAvg) * (baseY - padT);
  const xi = (i: number) => x(dayNum(points[i].date));

  const areaPts = avg.map((v, i) => `${xi(i).toFixed(1)},${yA(v).toFixed(1)}`).join(' ');
  const histArea = `${x(d0).toFixed(1)},${baseY} ${areaPts} ${x(dF).toFixed(1)},${baseY}`;

  const ST = 10; // sample step (days) for a smooth forecast curve
  const fcSeg = (from: number, to: number) => {
    const pts: string[] = [];
    for (let dn = from; dn < to; dn += ST) pts.push(`${x(dn).toFixed(1)},${yA(fcRate(dn)).toFixed(1)}`);
    pts.push(`${x(to).toFixed(1)},${yA(fcRate(to)).toFixed(1)}`);
    return pts;
  };
  const preEnd = Math.min(dV, dH);
  const linePre = fcSeg(dF, preEnd);
  const areaPre = `${x(dF).toFixed(1)},${baseY} ${linePre.join(' ')} ${x(preEnd).toFixed(1)},${baseY}`;
  const hasV2G = dV < dH;
  const v0 = Math.max(dV, dF);
  const lineV2G = hasV2G ? fcSeg(v0, dH) : [];
  const areaV2G = hasV2G ? `${x(v0).toFixed(1)},${baseY} ${lineV2G.join(' ')} ${x(dH).toFixed(1)},${baseY}` : '';

  const xF = x(dF), xV = x(dV), xH = x(dH), xPre = x(preEnd);

  // Quarter ticks across the whole span, thinned so labels never collide.
  const ticks: { dn: number; label: string }[] = [];
  let q = new Date(Date.UTC(+points[0].date.slice(0, 4), 0, 1));
  while (q.getTime() / 86400000 <= dH) {
    const dn = q.getTime() / 86400000;
    if (dn >= d0 && q.getUTCMonth() % 3 === 0) ticks.push({ dn, label: `Q${q.getUTCMonth() / 3 + 1}'${String(q.getUTCFullYear()).slice(2)}` });
    q = new Date(Date.UTC(q.getUTCFullYear(), q.getUTCMonth() + 1, 1));
  }
  const step = Math.max(1, Math.ceil(ticks.length / 9));
  const shown = ticks.filter((_, k) => k % step === 0);

  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" style={{ display: 'block' }} preserveAspectRatio="none">
      <defs>
        <linearGradient id="capfill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="var(--accent)" stopOpacity="0.42" />
          <stop offset="100%" stopColor="var(--accent)" stopOpacity="0.03" />
        </linearGradient>
        <linearGradient id="capfc" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="var(--accent)" stopOpacity="0.20" />
          <stop offset="100%" stopColor="var(--accent)" stopOpacity="0.02" />
        </linearGradient>
        <linearGradient id="capv2g" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={V2G} stopOpacity="0.30" />
          <stop offset="100%" stopColor={V2G} stopOpacity="0.03" />
        </linearGradient>
      </defs>
      {/* V2G region background band */}
      {hasV2G && <rect x={xV} y={padT} width={xH - xV} height={baseY - padT} fill={V2G} opacity={0.05} />}
      {shown.map((t) => (
        <g key={t.dn}>
          <line x1={x(t.dn)} y1={padT} x2={x(t.dn)} y2={baseY} stroke="var(--border)" strokeWidth={1} strokeDasharray="2 4" opacity={0.5} />
          <text x={x(t.dn)} y={H - 8} fontSize={10} fill="var(--faint)" textAnchor="middle">{t.label}</text>
        </g>
      ))}
      {/* forecast — growth over the next 8 quarters */}
      <polygon points={areaPre} fill="url(#capfc)" />
      <polyline points={linePre.join(' ')} fill="none" stroke="var(--accent)" strokeWidth={2} strokeDasharray="6 4" opacity={0.85} strokeLinejoin="round" />
      <text x={(xF + xPre) / 2} y={padT - 8} fontSize={10} fill="var(--faint)" textAnchor="middle">forecast</text>
      {/* V2G capacity — Q1'27 onward */}
      {hasV2G && <polygon points={areaV2G} fill="url(#capv2g)" />}
      {hasV2G && <polyline points={lineV2G.join(' ')} fill="none" stroke={V2G} strokeWidth={2} strokeDasharray="6 4" strokeLinejoin="round" />}
      {hasV2G && <line x1={xV} y1={padT} x2={xV} y2={baseY} stroke={V2G} strokeWidth={1} strokeDasharray="3 3" opacity={0.8} />}
      {hasV2G && <text x={(xV + xH) / 2} y={padT - 8} fontSize={10} fill={V2G} textAnchor="middle" fontWeight={600}>V2G capacity</text>}
      {/* now divider */}
      <line x1={xF} y1={padT} x2={xF} y2={baseY} stroke="var(--faint)" strokeWidth={1} opacity={0.7} />
      {/* actuals */}
      <polygon points={histArea} fill="url(#capfill)" />
      <polyline points={areaPts} fill="none" stroke="var(--accent)" strokeWidth={2} strokeLinejoin="round" />
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

// "How fast is that?" — frames Hypervolt's install pace against AI-data-centre scale, the energy story of the
// moment. At the current average install rate (MW per working day → ×260 working days ≈ MW/year), how long would
// it take to connect as much grid power as some of the data centres being built right now? The capacities are
// approximate ANNOUNCED/PUBLISHED figures — external reference points, clearly not Hypervolt data.
// Sell-in/through, warranty and the feed/register tables are hidden for now (kept in code) — the page focuses on
// the capacity story. Flip to true to bring them back.
const SHOW_LEGACY_SECTIONS = false;

interface DcRef { name: string; power: number; note: string; slug: string; mono: string; tint: string }
const DATA_CENTRES: DcRef[] = [
  { name: 'Typical hyperscale data centre', power: 100, note: '≈100 MW', slug: '', mono: 'DC', tint: '#6b7280' },
  { name: 'xAI · Colossus (Memphis)', power: 300, note: '≈300 MW reported', slug: '', mono: 'xAI', tint: '#111827' },
  { name: 'Meta · Prometheus', power: 1000, note: '≈1 GW · online 2026', slug: 'meta', mono: 'M', tint: '#0866FF' },
  { name: 'OpenAI · Stargate (Abilene)', power: 1200, note: '≈1.2 GW · phase 1', slug: 'openai', mono: 'AI', tint: '#10A37F' },
  { name: 'Amazon · Project Rainier', power: 2200, note: '≈2.2 GW · for Anthropic', slug: 'amazonwebservices', mono: 'AWS', tint: '#FF9900' },
  { name: 'Meta · Hyperion (Louisiana)', power: 5000, note: '≈5 GW planned', slug: 'meta', mono: 'M', tint: '#0866FF' },
  { name: 'OpenAI · Stargate (full programme)', power: 10000, note: '≈10 GW planned', slug: 'openai', mono: 'AI', tint: '#10A37F' },
];

// Brand logo via the Simple Icons CDN (white glyph on a dark badge); falls back to a brand-coloured monogram if the
// slug is missing or the image fails — so a screenshot never shows a broken image.
function LogoBadge({ slug, mono, tint, alt }: { slug: string; mono: string; tint: string; alt: string }) {
  const [err, setErr] = useState(false);
  if (slug && !err)
    return (
      <div style={{ width: 44, height: 44, borderRadius: 10, background: 'var(--surface3)', border: '1px solid var(--border)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <img src={`https://cdn.simpleicons.org/${slug}/white`} width={26} height={26} alt={alt} onError={() => setErr(true)} style={{ objectFit: 'contain', opacity: 0.95 }} />
      </div>
    );
  return (
    <div style={{ width: 44, height: 44, borderRadius: 10, background: tint, color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 700, fontSize: 14, letterSpacing: '-0.02em', border: '1px solid var(--border)' }}>{mono}</div>
  );
}

const fmtPower = (mw: number) => (mw >= 1000 ? `${(mw / 1000).toFixed(mw % 1000 === 0 ? 0 : 1)} GW` : `${mw} MW`);
const fmtSpan = (years: number) =>
  !Number.isFinite(years) || years <= 0 ? '—' : years >= 1.5 ? `${years.toFixed(1)} years` : `${Math.max(1, Math.round(years * 12))} months`;

function DataCentreCompare({ fm, loading }: { fm: ReturnType<typeof forecastModel>; loading: boolean }) {
  // Time-to-connect integrates the FORECAST growth curve, not a flat rate: with annual capacity growing at g = YoY,
  // cumulative over T years = A0·(gᵀ−1)/ln g, so T to reach P MW = ln(1 + P·ln g / A0) / ln g.
  const A0 = fm ? fm.currentRate * 260 : 0; // MW/year at today's pace (~260 working days)
  const g = fm ? Math.pow(fm.qGrowth, 4) : 1; // annual growth factor (YoY)
  const lng = Math.log(g);
  const yearsTo = (p: number) => {
    if (A0 <= 0) return Infinity;
    if (Math.abs(lng) < 1e-6) return p / A0;
    const inner = 1 + (p * lng) / A0;
    return inner > 0 ? Math.log(inner) / lng : Infinity;
  };
  const spans = DATA_CENTRES.map((d) => ({ ...d, years: yearsTo(d.power) }));
  const maxYears = Math.max(...spans.map((s) => (Number.isFinite(s.years) ? s.years : 0)), 0.001);
  const growthPct = Math.round((g - 1) * 100);
  return (
    <Card style={{ marginBottom: 14, padding: 0, minHeight: '100vh', display: 'flex', flexDirection: 'column' }} className="tablewrap">
      <div style={{ padding: '32px 32px 10px' }}>
        <div style={{ fontFamily: 'var(--font-disp)', fontSize: 30, fontWeight: 700 }}>How fast is that?</div>
        <div className="dim" style={{ fontSize: 15, marginTop: 8, maxWidth: 920, lineHeight: 1.5 }}>
          AI data centres are the energy story of the moment. On our forecast trajectory — install pace growing{' '}
          {loading ? '…' : <b>≈{growthPct}%/year</b>} from {loading ? '…' : `≈${num(Math.round(A0))} MW`}/year today — here's how long it would take Hypervolt to connect as much grid power as one of them.
        </div>
      </div>
      <div style={{ flex: 1, padding: '8px 32px', display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: 4 }}>
        {spans.map((s) => (
          <div key={s.name} className="row" style={{ alignItems: 'center', gap: 18, padding: '22px 0', borderBottom: '1px solid var(--border)' }}>
            <LogoBadge slug={s.slug} mono={s.mono} tint={s.tint} alt={s.name} />
            <div style={{ width: 280, minWidth: 280 }}>
              <div style={{ fontSize: 19, fontWeight: 600 }}>{s.name}</div>
              <div className="dim" style={{ fontSize: 13, marginTop: 2 }}>{s.note}</div>
            </div>
            <div style={{ width: 84, textAlign: 'right', fontFamily: 'var(--font-disp)', fontSize: 20, color: 'var(--faint)' }}>{fmtPower(s.power)}</div>
            <div style={{ flex: 1, height: 22, borderRadius: 11, background: 'var(--surface3)', overflow: 'hidden' }}>
              <div style={{ width: `${Math.max(3, ((Number.isFinite(s.years) ? s.years : maxYears) / maxYears) * 100)}%`, height: '100%', background: 'var(--accent)', opacity: 0.85 }} />
            </div>
            <div style={{ width: 150, textAlign: 'right', fontFamily: 'var(--font-disp)', fontSize: 30, fontWeight: 700 }}>
              {loading ? <span className="skel skel-line" style={{ width: 110, height: 28, display: 'inline-block' }} /> : fmtSpan(s.years)}
            </div>
          </div>
        ))}
      </div>
      <div className="dim" style={{ fontSize: 12, padding: '0 32px 24px' }}>
        Data-centre capacities are approximate announced/published figures — external reference points, not Hypervolt data. Times integrate the forecast growth curve (compounding), assuming the trajectory continues.
      </div>
    </Card>
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
  const fm = forecastModel(capPts);
  const v2gRate = fm ? fm.fcRate(fm.dH) : 0; // predicted rate at the end of the forecast horizon (V2G era)

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
            <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600, color: V2G }}>
              {capApi.isLoading ? <span className="skel skel-line" style={{ width: 90, height: 22, display: 'inline-block' }} /> : `${v2gRate.toFixed(2)} MW/day`}
            </div>
            <div className="dim" style={{ fontSize: 'var(--fs-xs)' }}>predicted V2G rate</div>
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
              <span className="dim"><span style={{ display: 'inline-block', width: 18, height: 0, borderTop: '2px dashed var(--accent)', verticalAlign: 'middle', marginRight: 5, opacity: 0.85 }} />forecast (8 quarters)</span>
              <span className="dim"><span style={{ display: 'inline-block', width: 18, height: 0, borderTop: '2px dashed #14b8a6', verticalAlign: 'middle', marginRight: 5 }} />V2G capacity (Q1&rsquo;27+)</span>
              {cap?.as_of && <span className="dim" style={{ marginLeft: 'auto' }}>as of {cap.as_of}</span>}
            </div>
          </>
        )}
      </Card>

      {/* AI-data-centre comparison — "at current speed, how long to connect that much power?" */}
      <DataCentreCompare fm={fm} loading={capApi.isLoading} />

      {SHOW_LEGACY_SECTIONS && (<>
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
      </>)}
    </div>
  );
}
