import React, { useMemo, useState } from 'react';
import { PageHead } from './kit/kit';

// The forecast-engine explainer (doc 26, productized / spec/ui/15-engine.md): how the self-improving
// per-account engine works, why it is bulletproof (the honesty rules + the falsification discipline), and how
// it scales. Every number on this page is a real measurement from the backtest ledger — nothing illustrative.
// Ported to the desk kit (PageHead / .card / .tbl / .metric); the bespoke sliders + SVG bars are kept (no kit
// equivalent), restyled with the design tokens. Every data-testid and SVG aria-label is preserved verbatim.

const badgeStyle = (kind: 'struct' | 'stat' | 'guard'): React.CSSProperties => ({
  fontSize: 10.5, fontWeight: 700, borderRadius: 999, padding: '2px 9px', marginLeft: 8,
  ...(kind === 'struct' ? { background: '#1d4029', color: '#6ee7a0' }
    : kind === 'stat' ? { background: '#1d2a40', color: '#6eb2e7' }
    : { background: '#402a1d', color: '#e7b26e' }),
});

function Section({ title, badge, badgeKind, defaultOpen, children }: {
  title: string; badge?: string; badgeKind?: 'struct' | 'stat' | 'guard'; defaultOpen?: boolean; children: React.ReactNode;
}) {
  const [open, setOpen] = useState(!!defaultOpen);
  return (
    <div className="card" style={{ marginBottom: 14 }}>
      <div className="ct" style={{ cursor: 'pointer', marginBottom: open ? undefined : 0 }} onClick={() => setOpen(!open)} data-testid={`explainer-${title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`}>
        <div className="t">{title}{badge ? <span style={badgeStyle(badgeKind ?? 'guard')}>{badge}</span> : null}</div>
        <div className="aux dim">{open ? '▾ collapse' : '▸ expand'}</div>
      </div>
      {open ? <div style={{ fontSize: 14, lineHeight: 1.6 }}>{children}</div> : null}
    </div>
  );
}

// the depletion model, verbatim from Scala: monthly = max(0, v·(m+1) − shelf) − max(0, v·m − shelf)
function depletionCurve(shelf: number, velocity: number, horizon: number): number[] {
  const cum = Array.from({ length: horizon }, (_, i) => Math.max(0, velocity * (i + 1) - shelf));
  return cum.map((c, i) => c - (i === 0 ? 0 : cum[i - 1]));
}

const REGISTRY: { key: string; kind: 'structural' | 'statistical'; what: string; wins: string }[] = [
  { key: 'runrate3', kind: 'statistical', what: 'Trailing 3-month mean. The INCUMBENT — every challenger must beat it on censored evidence.', wins: 'Lumpy accounts where nothing extrapolates. The robust default.' },
  { key: 'seasonal_naive', kind: 'statistical', what: 'Same month last year; trailing mean fallback. The baseline every model must beat.', wins: 'Strongly annual series with stable level.' },
  { key: 'ewma', kind: 'statistical', what: 'Exponentially-weighted level, flat forecast.', wins: 'Dense, stable accounts.' },
  { key: 'croston_sba', kind: 'statistical', what: 'Intermittent-demand rate: sizes and gaps smoothed separately.', wins: 'Sparse B2B series ordering every N months.' },
  { key: 'seasonal_ets', kind: 'statistical', what: 'Holt-Winters: trend × 12-month multiplicative seasonality. Needs 24m of history.', wins: 'Long, trending, seasonal series.' },
  { key: 'holt_damped / holt_fast', kind: 'statistical', what: 'Damped linear trend, two parameterizations.', wins: 'Growing accounts that level models under-call.' },
  { key: 'seasonal_drift', kind: 'statistical', what: 'Last year’s shape scaled by measured YoY growth.', wins: 'Seasonal + growing.' },
  { key: 'depletion', kind: 'structural', what: 'The customer’s shelf must empty before they reorder: cumulative sell-in = max(0, velocity·m − shelf). Real telemetry, not a curve fit.', wins: 'Accounts with activation telemetry holding stock — called the Q2’25 Octopus collapse at 11% error while every statistical model was 70–130% off.' },
  { key: 'sell_through', kind: 'structural', what: 'Trailing 3-month ACTIVATION rate as the level — activations are demand truth; sell-in is lumpy batching on top.', wins: 'Hand-to-mouth installers; procurement-cycle buyers whose orders oscillate around the install rate.' },
  { key: 'order_book', kind: 'structural', what: 'Open deals × age-bucketed conversion (zombies convert at ~0) + new-business run-rate.', wins: 'Channels with a real pipeline discipline.' },
  { key: 'mrp_order_book', kind: 'structural', what: 'Open un-dispatched orders (<60d) ARE next month’s floor + organic flow scaled by measured book-coverage.', wins: 'Accounts with measured order→dispatch lag. Halved recent-origin totals: 31→17%, 17→9%, 19→9%.' },
  { key: 'retail_funnel (+_m)', kind: 'structural', what: 'Created volume × measured conversion per (payment × age) cohort, composed.', wins: 'Retail pipelines where payment mix drives conversion.' },
  { key: 'pantry_reversal', kind: 'structural', what: 'A quarter bought far above consumption ⇒ next quarter digests: 2·consumption − lastQ, clamped.', wins: 'Post-stocking-wave quarters (the Q2’25 autopsy, productized).' },
];

const CONVERGENCE = [
  { origin: 'Q3’24', err: 26.3, cov: 45 },
  { origin: 'Q4’24', err: 60.2, cov: 52 },
  { origin: 'Q1’25', err: 0.4, cov: 60 },
  { origin: 'Q2’25', err: 229.1, cov: 33 },
  { origin: 'Q3’25', err: 8.6, cov: 72 },
  { origin: 'Q4’25', err: 16.2, cov: 78 },
  { origin: 'Q1’26', err: 9.4, cov: 79 },
  { origin: 'Q2’26*', err: 9.7, cov: 80 },
];

const NEGATIVES = [
  ['Unconditional bias correction', 'recentering P50 on the median historical error', '37.2 → 44.5% — quarter errors mean-revert; error-chasing chases noise'],
  ['Persistence-gated ramp', 'trend continuation gated on two consecutive rises', '37.2 → 40.0%'],
  ['pipeline_velocity', 'HubSpot creation volume × lag-ratio as a lead', '37.2 → 45.6% — hijacked good blends, then missed'],
  ['Shelf guard', 'cap ANY forecast at the depletion curve when shelf ≥ velocity (4 trigger variants)', 'wins only at the Q2’25 anomaly, loses 7/8 origins — a shelf jump can’t distinguish overhang from growth'],
  ['Recency-decayed evidence', 'λ=0.8 per quarter of evidence age', '72.6 → 74.8% — small-window instability in disguise (M-competition, 3rd confirmation)'],
  ['depletion_fast', '3-month velocity window variant', 'wash, 52 adoptions, didn’t fix the bias it targeted'],
  ['sell_through ranking prior', '0.9 prior to overindex sell-through', 'promoted it onto procurement-cycle accounts where it loses: 50.7 → 64.8%'],
];

const td: React.CSSProperties = { padding: '6px 10px', borderBottom: '1px solid var(--border)' };
const tdNum: React.CSSProperties = { ...td, textAlign: 'right', fontVariantNumeric: 'tabular-nums' };
const good: React.CSSProperties = { color: '#6ee7a0', fontWeight: 700 };
const bad: React.CSSProperties = { color: '#e76e6e', fontWeight: 700 };
const warn2: React.CSSProperties = { color: '#e7c76e', fontWeight: 700 };
const mono: React.CSSProperties = { fontFamily: 'var(--font-mono)', fontSize: 13 };

export function Forecasting(_props: { token: string }) {
  const [shelf, setShelf] = useState(5000);
  const [velocity, setVelocity] = useState(2950);
  const [step, setStep] = useState(0);
  const curve = useMemo(() => depletionCurve(shelf, velocity, 6), [shelf, velocity]);
  const maxBar = Math.max(...curve, 1);

  const tournamentSteps = [
    { t: '1 · Evidence', d: 'For ONE account, fetch every scored (origin, model, forecast, actual) cell from quarters strictly BEFORE today — summed to account grain so no SKU’s number is arbitrarily picked. An account first visible in Q4’24 has exactly the evidence it earned since; nothing is borrowed, nothing is global.' },
    { t: '2 · Anomaly exclusion', d: 'A past quarter that even the BEST single model missed by >150% is an anomaly (a collapse, a data artifact) — it would drown every candidate’s signal, so it is dropped from selection evidence when ≥3 origins remain. The quarter still counts in the public scoreboard; it just can’t poison selection.' },
    { t: '3 · Candidates', d: 'Every registry model as a single + inverse-error blends of the top-2/top-3 + structural hedges (each telemetry/book model paired with the top statisticals — structure that explains PART of a channel earns PART of the weight). Judged at the QUARTER grain: the business reads quarter totals, so within-quarter shape can’t flatter a candidate.' },
    { t: '4 · Stability band', d: 'Near-ties (within 10% pooled) resolve to the candidate with the best WORST origin — preferring the policy that has never blown up over the marginally-better one that has.' },
    { t: '5 · Guards', d: 'Winner’s worst origin >150%? Unforecastable (pooled >50%)? → demoted to the run-rate. Bounded badness is a property of the system, not a hope.' },
    { t: '6 · The incumbent prior', d: 'runrate3 is the incumbent: a STATISTICAL challenger must beat it by >20% on censored evidence (per-series selection overfits noise — measured, the all-runrate baseline beat the unconstrained tournament). STRUCTURAL challengers (real telemetry, real order books) need only beat it at all — a shelf measurement is not a curve fit.' },
    { t: '7 · Champion', d: 'The surviving policy is materialized to policy_selection (millisecond reads — measured 21 min → 0.25 s) and publishes the live rows the H6Q board serves. Every selection is reproducible from the ledger, forever.' },
  ];

  return (
    <>
      <PageHead title="Forecast engine" sub="How the self-improving per-account engine works — and why every number on this page is falsifiable" />

      <div className="dim" style={{ fontSize: 14, lineHeight: 1.55, marginBottom: 14, maxWidth: 1080 }}>
        Every number on this page is a real measurement from the engine’s own immutable ledger. The system’s defining property is that it is
        <b> falsifiable</b>: every claim below was earned against history the model was never allowed to see.
      </div>

      <div className="row" style={{ gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>
        {[['9–16%', 'total-level error, recent closed quarters'], ['3,649', 'independent account×SKU series'], ['14', 'deterministic models in the registry'], ['7', 'falsified ideas, documented, never retried'], ['0', 'Python / ML infra dependencies']].map(([v, l]) => (
          <div key={l} className="metric" style={{ minWidth: 170, background: 'var(--bg-2)', border: '1px solid var(--border)', borderRadius: 10, padding: '12px 16px' }}>
            <div className="mv accent" style={{ fontSize: 24 }}>{v}</div>
            <div className="ml" style={{ marginTop: 4 }}>{l}</div>
          </div>
        ))}
      </div>

      <Section title="The pipeline — data to bottom line" defaultOpen>
        <div className="row g8" style={{ flexWrap: 'wrap', alignItems: 'stretch' }}>
          {[
            ['1 · Ground truth', 'Serial-attributed dispatches (every charger’s serial joined to its dispatch and buyer) + live activations from the device fleet + open orders + deals + Stripe + SMMT TAM. History arrives as git-versioned NDJSON snapshots; backtests pin the data SHA.'],
            ['2 · Censored series', 'Per account×SKU, the monthly demand series AS OF a chosen origin — everything (history, shelf, velocity, open book, lags) computed strictly from rows before that date. The world as a forecaster would have seen it.'],
            ['3 · The registry', '14 pure, deterministic models — statistical shapes + structural models that read real telemetry (shelf, activations, order books). Same inputs ⇒ same outputs, forever. A universal clamp bounds every model at 3× the series’ largest month.'],
            ['4 · The tournament', 'Per account, candidates are ranked on their own scored track record; guards demote anything unstable; the run-rate incumbent must be beaten to be displaced.'],
            ['5 · Bands + £', 'P80/P50/P20 from the model’s own measured error spread; revenue = units × each account’s contract tier (never a stored number); seasonality enters at measured pass-through, not raw TAM.'],
          ].map(([t, b], i, a) => (
            <React.Fragment key={t}>
              <div style={{ background: 'var(--bg-2)', border: '1px solid var(--border)', borderRadius: 10, padding: '10px 13px', fontSize: 12.5, flexBasis: 150, flexGrow: 1 }}>
                <div style={{ fontWeight: 700, color: 'var(--accent)', fontSize: 13 }}>{t}</div>{b}
              </div>
              {i < a.length - 1 && <div className="dim" style={{ alignSelf: 'center', fontWeight: 700 }}>→</div>}
            </React.Fragment>
          ))}
        </div>
      </Section>

      <Section title="Why it is bulletproof — the honesty rules" badge="THE CORE" badgeKind="guard">
        <table className="tbl"><tbody>
          <tr><td style={td}><b>No leakage, ever</b></td><td style={td}>Every feature is computed from rows strictly before the forecast origin. A deal that closed after the origin appears OPEN. An activation after the origin doesn’t exist. The backtest cannot cheat because the queries cannot see the future.</td></tr>
          <tr><td style={td}><b>Append-only, immutable</b></td><td style={td}>Every forecast run is a permanent row set — re-publishing supersedes, never deletes. Contrast: the H6Q workbook overwrites its forecasts with actuals in place, so its closed-quarter accuracy is unrecoverable. Conduit’s can never be.</td></tr>
          <tr><td style={td}><b>Deterministic</b></td><td style={td}>Pure Scala functions — no randomness, no clock, no GPU. (data git SHA, model version) ⇒ bit-identical forecast, reproducible in any audit, forever.</td></tr>
          <tr><td style={td}><b>Scored at the served grain</b></td><td style={td}>Selection is judged on QUARTER totals — the number the business actually reads — after measuring that monthly-grain selection picks flattering shapes that miss the quarter.</td></tr>
          <tr><td style={td}><b>Business actuals stay visible</b></td><td style={td}>Reports separate “what the company shipped” (the full dispatch log) from “what the model could score” (accounts with enough history), with coverage % stated per quarter. The model is never allowed to present its subset as reality.</td></tr>
          <tr><td style={td}><b>Humans on identical terms</b></td><td style={td}>The human plan (H6Q) is scored with the same machinery, same censoring, same grain — from now on, permanently.</td></tr>
        </tbody></table>
      </Section>

      <Section title="The falsification discipline — seven ideas the harness killed" badge="WHY TRUST IT" badgeKind="guard">
        <div className="dim" style={{ marginBottom: 8 }}>
          A system is only trustworthy if it can reject its own plausible ideas. Every change must improve the 8-quarter backtest means or it is reverted —
          including its evidence rows, so a dead idea can’t haunt future selections. These all sounded right. The data said no.
        </div>
        <table className="tbl">
          <thead><tr><th>Idea</th><th>What it tried</th><th>Verdict</th></tr></thead>
          <tbody>
            {NEGATIVES.map(([name, what, verdict]) => (
              <tr key={name}><td style={td}><b>{name}</b></td><td style={td}>{what}</td><td style={{ ...td, ...bad }}>{verdict}</td></tr>
            ))}
          </tbody>
        </table>
      </Section>

      <Section title="The model registry — 14 deterministic models">
        <div className="dim" style={{ marginBottom: 10 }}>
          Statistical models read the series’ own shape. Structural models read the physical world — shelf stock, activations, open order books.
          Structure is privileged: a telemetry measurement is not a curve fit, so structural challengers face a lower bar against the incumbent.
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 10 }}>
          {REGISTRY.map((m) => (
            <div key={m.key} style={{ background: 'var(--bg-2)', border: '1px solid var(--border)', borderRadius: 10, padding: '11px 13px' }}>
              <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12.5, color: 'var(--accent)', fontWeight: 700 }}>{m.key}</span>
              <span style={badgeStyle(m.kind === 'structural' ? 'struct' : 'stat')}>{m.kind}</span>
              <div className="dim" style={{ fontSize: 12.5, marginTop: 6, lineHeight: 1.5 }}><b>What:</b> {m.what}</div>
              <div className="dim" style={{ fontSize: 12.5, marginTop: 4, lineHeight: 1.5 }}><b>Wins where:</b> {m.wins}</div>
            </div>
          ))}
        </div>
      </Section>

      <Section title="Try it — the depletion model, live" badge="INTERACTIVE" badgeKind="struct">
        <div className="dim" style={{ marginBottom: 10 }}>
          This is the exact arithmetic that called the Q2’25 Octopus collapse at 11% error (shelf 5,003 × velocity ≈ 2,950/mo) while every
          statistical model said 6,500–8,800. The customer’s shelf must empty before they reorder — drag the sliders.
        </div>
        <div className="row" style={{ gap: 19, alignItems: 'center' }}>
          <div style={{ flexGrow: 1 }}>
            <div className="dim">Shelf stock (shipped − activated): <b style={mono}>{shelf.toLocaleString()}</b></div>
            <input style={{ width: '100%' }} data-testid="shelf-slider" type="range" min={0} max={15000} step={100} value={shelf} onChange={(e) => setShelf(Number(e.target.value))} />
          </div>
          <div style={{ flexGrow: 1 }}>
            <div className="dim">Activation velocity (installs/month): <b style={mono}>{velocity.toLocaleString()}</b></div>
            <input style={{ width: '100%' }} data-testid="velocity-slider" type="range" min={100} max={6000} step={50} value={velocity} onChange={(e) => setVelocity(Number(e.target.value))} />
          </div>
        </div>
        <svg width="100%" height="150" viewBox="0 0 720 150" preserveAspectRatio="none" role="img" aria-label="depletion forecast bars">
          {curve.map((v, i) => {
            const h = (v / maxBar) * 110;
            return (
              <g key={i}>
                <rect x={20 + i * 115} y={130 - h} width={84} height={Math.max(h, 1)} fill="#962DFF" opacity={0.85} rx={4} />
                <text x={62 + i * 115} y={126 - h} textAnchor="middle" fill="#e8e8f0" fontSize="13" fontFamily="ui-monospace">{Math.round(v).toLocaleString()}</text>
                <text x={62 + i * 115} y={147} textAnchor="middle" fill="#9aa0b4" fontSize="11">M{i + 1}</text>
              </g>
            );
          })}
        </svg>
        <div className="dim">
          Quarter total: <b style={mono}>{Math.round(curve.slice(0, 3).reduce((a, b) => a + b, 0)).toLocaleString()}</b> units —
          months stay at zero until the shelf is consumed, then sell-in resumes at the install rate. No statistical model can produce this shape from sell-in alone.
        </div>
      </Section>

      <Section title="The tournament — how an account gets its champion" badge="INTERACTIVE" badgeKind="stat">
        <div className="row g6" style={{ flexWrap: 'wrap', marginBottom: 10 }}>
          {tournamentSteps.map((s, i) => (
            <button key={s.t} className={'btn sm' + (step === i ? ' primary' : '')} data-testid={`tstep-${i}`} onClick={() => setStep(i)}>{s.t}</button>
          ))}
        </div>
        <div style={{ fontSize: 14, lineHeight: 1.6 }}><b>{tournamentSteps[step].t}.</b> {tournamentSteps[step].d}</div>
        <div style={{ background: '#2a1430', border: '1px solid #5a2a6a', borderRadius: 10, padding: '11px 16px', marginTop: 10, fontSize: 13.5, lineHeight: 1.55 }}>
          The keystone is step 6: <b>per-series model selection overfits noise</b> — we measured an all-run-rate baseline BEATING the unconstrained
          tournament. The incumbent prior is what turns a model zoo into a disciplined system: deviations from the simple default must be EARNED,
          per account, on evidence the challenger never saw the answer to.
        </div>
      </Section>

      <Section title="The learning loop — why early quarters look bad and recent ones don’t">
        <div className="dim" style={{ marginBottom: 8 }}>
          Total-level error by forecast origin (each bar = what the system would have predicted THEN, knowing only what existed then; coverage = the
          share of business units on scoreable accounts). The collapse from 229% to single digits is evidence accruing per account — not tuning.
        </div>
        <svg width="100%" height="190" viewBox="0 0 760 190" preserveAspectRatio="none" role="img" aria-label="error convergence by origin">
          {CONVERGENCE.map((c, i) => {
            const h = Math.min(c.err, 120) * 1.1;
            const isAnomaly = c.err > 200;
            return (
              <g key={c.origin}>
                <rect x={25 + i * 92} y={150 - h} width={62} height={Math.max(h, 2)} fill={isAnomaly ? '#e76e6e' : c.err <= 20 ? '#30d158' : '#e7c76e'} opacity={0.85} rx={4} />
                <text x={56 + i * 92} y={144 - h} textAnchor="middle" fill="#e8e8f0" fontSize="12" fontFamily="ui-monospace">{c.err}%</text>
                <text x={56 + i * 92} y={168} textAnchor="middle" fill="#9aa0b4" fontSize="11">{c.origin}</text>
                <text x={56 + i * 92} y={184} textAnchor="middle" fill="#5a5f75" fontSize="10">cov {c.cov}%</text>
              </g>
            );
          })}
        </svg>
        <div className="dim">
          Q2’25 (red) is the stocking-wave collapse: every model extrapolated the Q1’25 ramp; the only signal that called it was shelf telemetry, which
          had no track record yet — an honest selector could not have picked it. Choosing it retroactively would be leakage. The system that admits
          this is the one whose recent single-digit errors you can believe. (* Q2’26 scored on its closed months.)
        </div>
      </Section>

      <Section title="Ranges, not points — P80 / P50 / P20">
        <table className="tbl"><tbody>
          <tr><td style={td}><b>P80</b></td><td style={td}>The dependable number — ~80% probability of meeting or beating it. Commit supply and cash against this.</td></tr>
          <tr><td style={td}><b>P50</b></td><td style={td}>The model’s central number, NEVER recentered — bias-chasing was tested and measurably degrades it.</td></tr>
          <tr><td style={td}><b>P20</b></td><td style={td}>Blue sky — ~20% probability. The stretch case.</td></tr>
        </tbody></table>
        <div style={{ fontSize: 14, lineHeight: 1.6, marginTop: 10 }}>
          The spread is <b>empirical</b>: quantiles of per-sector actual÷forecast ratios over the last three CLOSED origins — the spread the model has
          actually exhibited, not an assumed ±X%. Currently <span style={mono}>×0.896 / ×1.396</span> around P50: asymmetric, because
          history says this model misses LOW more than high — the blue sky is genuinely fatter than the downside. The band narrows by itself as
          quarters close. <b>Today:</b> Q3’26 P80 £9.3M · P50 £10.1M · P20 £13.4M all-in.
        </div>
      </Section>

      <Section title="Seasonality — measured pass-through, not assumed (β = −0.38)">
        <div style={{ fontSize: 14, lineHeight: 1.6 }}>
          The UK BEV market does 56% of its year in H2 (Q4 alone = 30.3%). Naively scaling our Q4 by TAM seasonality would add ~£3M of phantom revenue —
          and Q4’25 proved it: market share-of-year 30.3%, our sell-in share <b>22.5%</b>. Regressing our quarter shares on the market’s gives
          <b> β = −0.38</b>: sell-in <b>inverts</b> market seasonality, because the channel stocks AHEAD of the high season and depletes into it
          (the Q1’25 wave was that stocking). Validated where it was exposed: predicted Q4’25 share 22.6% vs actual 22.5%.
        </div>
        <table className="tbl" style={{ marginTop: 10 }}>
          <thead><tr><th></th><th className="num">Q1</th><th className="num">Q2</th><th className="num">Q3</th><th className="num">Q4</th></tr></thead>
          <tbody>
            <tr><td style={td}>Market share-of-year (SMMT, 6-yr)</td><td style={tdNum}>21.9%</td><td style={tdNum}>21.8%</td><td style={tdNum}>25.9%</td><td style={{ ...tdNum, ...warn2 }}>30.3%</td></tr>
            <tr><td style={td}>Our sell-in share (2025 actual)</td><td style={tdNum}>25.7%</td><td style={tdNum}>27.0%</td><td style={tdNum}>24.8%</td><td style={{ ...tdNum, ...good }}>22.5%</td></tr>
          </tbody>
        </table>
        <div className="dim" style={{ marginTop: 8 }}>β recomputes from the dispatch log + SMMT profile on every refresh — a second year of data sharpens it automatically. Per-market βs activate the same way as new markets accrue a year of history.</div>
      </Section>

      <Section title="Every level of the business, handled">
        <table className="tbl">
          <thead><tr><th>Level</th><th>Mechanism</th></tr></thead>
          <tbody>
            <tr><td style={td}><b>Serial</b></td><td style={td}>Every charger’s serial attributed to its buyer at dispatch; activation flips it in real time via the Pulsar stream.</td></tr>
            <tr><td style={td}><b>Account×SKU</b></td><td style={td}>Its own censored series, its own champion, its own measured lags (order→dispatch, reorder point), its own contract tier for £.</td></tr>
            <tr><td style={td}><b>Account</b></td><td style={td}>Selection at account grain (SKUs summed — no arbitrary-variant bugs); shelf/velocity/runway state live; reorder signal fires at the account’s measured threshold.</td></tr>
            <tr><td style={td}><b>Channel</b></td><td style={td}>Aggregation of account champions + ASP×mix tracking (blended £490–497 stable while Energy 49→29% and Wholesale 18→39% of volume rotated underneath).</td></tr>
            <tr><td style={td}><b>Market</b></td><td style={td}>TAM series + measured pass-through β per market; seasonality learned per series, never hand-configured (an inverted Australian season needs zero code).</td></tr>
            <tr><td style={td}><b>Company</b></td><td style={td}>The bottom line: P80/P50/P20 in units and £, D2C carried on its own curve, supply commitments (the Volex/Luxshare ladder) reading the same rows.</td></tr>
          </tbody>
        </table>
      </Section>

      <Section title="Self-updating — three loops, three speeds">
        <table className="tbl">
          <thead><tr><th>Cadence</th><th>Mechanism</th><th>What moves</th></tr></thead>
          <tbody>
            <tr><td style={td}><b>Per activation</b></td><td style={td}>Pulsar consumer (live device fleet)</td><td style={td}>Shelf, velocity, runway per account; reorder signal at the measured threshold</td></tr>
            <tr><td style={td}><b>Every 6 hours</b></td><td style={td}>Feed refresher (incremental scrapes + reload + rescore + republish, snapshots git-committed)</td><td style={td}>Open-quarter nowcast, live forecast rows, ASP, bands</td></tr>
            <tr><td style={td}><b>Quarter close</b></td><td style={td}>Calendar-derived origins — no human edit</td><td style={td}>A new evidence origin for every account; champions re-ranked; β and bands deepen</td></tr>
          </tbody>
        </table>
      </Section>

      <Section title="Why it scales — many markets, many seasonalities, many criteria">
        <div style={{ fontSize: 14, lineHeight: 1.6 }}>
          Nothing in the system is global. Selection is per-series; seasonality is learned per series; lags are measured per account; βs are measured
          per market. Adding a market adds <b>rows, not assumptions</b> — 23 markets is the same machine with more keys, sharded trivially because
          markets are independent. Compute is linear: 3,649 series fit+score in ~6 minutes per origin on a laptop; reads are materialized
          (0.25 s). And the property that keeps a growing model zoo safe at scale is the incumbent guard: 50 new candidate models cannot cause
          curve-fit chaos, because every one of them must beat the boring default per series, on evidence, before touching a single forecast.
        </div>
        <div style={{ background: '#2a1430', border: '1px solid #5a2a6a', borderRadius: 10, padding: '11px 16px', marginTop: 10, fontSize: 13.5, lineHeight: 1.55 }}>
          If a model class someday needs covariates we can’t express cheaply (weather, tariffs, promos), the registry contract — a pure function over a
          censored history — admits any sidecar as just another candidate. It gets no special treatment: it wins series in the tournament or it doesn’t ship.
          Seven falsified ideas say the bar is real.
        </div>
      </Section>

      <Section title="Conduit vs the human plan (H6Q) — scored on identical terms">
        <table className="tbl">
          <thead><tr><th></th><th className="num">Q3’26</th><th className="num">Q4’26</th></tr></thead>
          <tbody>
            <tr><td style={td}>Conduit P50</td><td style={tdNum}>16,641u</td><td style={tdNum}>15,819u</td></tr>
            <tr><td style={td}>H6Q ex-Motability</td><td style={tdNum}>18,242u</td><td style={tdNum}>16,975u</td></tr>
            <tr><td style={td}>H6Q inc-Motability</td><td style={{ ...tdNum, ...warn2 }}>22,942u</td><td style={{ ...tdNum, ...warn2 }}>22,679u</td></tr>
            <tr><td style={td}>Conduit P20 (blue sky)</td><td style={tdNum}>23,231u</td><td style={tdNum}>22,083u</td></tr>
          </tbody>
        </table>
        <div style={{ fontSize: 14, lineHeight: 1.6, marginTop: 10 }}>
          On reality the two agree (FY’25 within 0.1%, the open quarter within 1–4%). Ex-new-programs they agree within 7–10%. The entire forward gap is
          <b> one named bet</b>: Motability (~4,700–5,700u/quarter) — and H6Q inc-Motability lands on Conduit’s P20 almost to the unit.
          <b> The official plan is the blue-sky scenario, contingent on one program.</b> The model carries none of it because no unit has ever shipped
          under it — and the moment the first one does, the engine starts pricing it in automatically. Buy-side decisions ride P50/P80; Motability is
          tracked as the named P50→P20 mover.
        </div>
      </Section>
    </>
  );
}
