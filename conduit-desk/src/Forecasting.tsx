import React, { useMemo, useState } from 'react';
import * as stylex from '@stylexjs/stylex';
import { colors } from './styles/tokens.stylex';

// The forecast-engine explainer (doc 26, productized): how the self-improving per-account engine works,
// why it is bulletproof (the honesty rules + the falsification discipline), and how it scales. Every
// number on this page is a real measurement from the backtest ledger — nothing illustrative.

const styles = stylex.create({
  wrap: { maxWidth: '1080px' },
  h2: { fontSize: '1.05rem', fontWeight: 700, color: '#b9a7e8', marginTop: '0.2rem', marginBottom: '0.6rem' },
  section: { backgroundColor: colors.surface, border: `1px solid ${colors.border}`, borderRadius: '12px', padding: '1rem 1.25rem', marginBottom: '0.9rem' },
  sectionHead: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', cursor: 'pointer' },
  sectionTitle: { fontSize: '1rem', fontWeight: 700 },
  badge: { fontSize: '0.7rem', fontWeight: 700, borderRadius: '999px', padding: '0.15rem 0.6rem', marginLeft: '0.5rem' },
  badgeStruct: { backgroundColor: '#1d4029', color: '#6ee7a0' },
  badgeStat: { backgroundColor: '#1d2a40', color: '#6eb2e7' },
  badgeGuard: { backgroundColor: '#402a1d', color: '#e7b26e' },
  muted: { color: colors.muted, fontSize: '0.85rem', lineHeight: 1.55 },
  body: { fontSize: '0.9rem', lineHeight: 1.6, marginTop: '0.6rem' },
  grid: { display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: '0.6rem', marginTop: '0.6rem' },
  card: { backgroundColor: colors.bg, border: `1px solid ${colors.border}`, borderRadius: '10px', padding: '0.7rem 0.85rem', cursor: 'pointer' },
  cardKey: { fontFamily: 'ui-monospace, monospace', fontSize: '0.8rem', color: colors.accent, fontWeight: 700 },
  cardBody: { fontSize: '0.78rem', color: colors.muted, marginTop: '0.35rem', lineHeight: 1.5 },
  table: { width: '100%', borderCollapse: 'collapse', marginTop: '0.6rem', fontSize: '0.82rem' },
  th: { textAlign: 'left', color: colors.muted, fontWeight: 600, padding: '0.35rem 0.6rem', borderBottom: `1px solid ${colors.border}` },
  td: { padding: '0.35rem 0.6rem', borderBottom: `1px solid ${colors.border}` },
  num: { textAlign: 'right', fontVariantNumeric: 'tabular-nums' },
  good: { color: '#6ee7a0', fontWeight: 700 },
  bad: { color: '#e76e6e', fontWeight: 700 },
  warn2: { color: '#e7c76e', fontWeight: 700 },
  kpiRow: { display: 'flex', gap: '0.7rem', flexWrap: 'wrap', marginTop: '0.6rem' },
  kpi: { backgroundColor: colors.bg, border: `1px solid ${colors.border}`, borderRadius: '10px', padding: '0.7rem 1rem', minWidth: '170px' },
  kpiBig: { display: 'block', fontSize: '1.35rem', fontWeight: 800, color: colors.accent },
  kpiLabel: { fontSize: '0.72rem', color: colors.muted },
  slider: { width: '100%' },
  sliderRow: { display: 'flex', gap: '1.2rem', alignItems: 'center', marginTop: '0.6rem' },
  sliderBox: { flexGrow: 1 },
  mono: { fontFamily: 'ui-monospace, monospace', fontSize: '0.82rem' },
  stepBtn: { backgroundColor: 'transparent', color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.35rem 0.9rem', cursor: 'pointer', fontWeight: 600 },
  stepBtnActive: { backgroundColor: colors.accent, color: '#fff', borderColor: colors.accent },
  pipeline: { display: 'flex', gap: '0.4rem', flexWrap: 'wrap', alignItems: 'stretch', marginTop: '0.6rem' },
  pipeBox: { backgroundColor: colors.bg, border: `1px solid ${colors.border}`, borderRadius: '10px', padding: '0.6rem 0.8rem', fontSize: '0.78rem', flexBasis: '150px', flexGrow: 1 },
  pipeTitle: { fontWeight: 700, color: colors.accent, fontSize: '0.8rem' },
  arrow: { alignSelf: 'center', color: colors.muted, fontWeight: 700 },
  flag: { backgroundColor: '#2a1430', border: '1px solid #5a2a6a', borderRadius: '10px', padding: '0.7rem 1rem', marginTop: '0.6rem', fontSize: '0.85rem', lineHeight: 1.55 },
});

function Section({ title, badge, badgeStyle, defaultOpen, children }: {
  title: string; badge?: string; badgeStyle?: object; defaultOpen?: boolean; children: React.ReactNode;
}) {
  const [open, setOpen] = useState(!!defaultOpen);
  return (
    <div {...stylex.props(styles.section)}>
      <div {...stylex.props(styles.sectionHead)} onClick={() => setOpen(!open)} data-testid={`explainer-${title.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`}>
        <span {...stylex.props(styles.sectionTitle)}>
          {title}
          {badge ? <span {...stylex.props(styles.badge, badgeStyle as never)}>{badge}</span> : null}
        </span>
        <span {...stylex.props(styles.muted)}>{open ? '▾ collapse' : '▸ expand'}</span>
      </div>
      {open ? <div {...stylex.props(styles.body)}>{children}</div> : null}
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
    <div {...stylex.props(styles.wrap)}>
      <div {...stylex.props(styles.h2)}>How the Conduit forecast engine works — and why you can trust it</div>
      <div {...stylex.props(styles.muted)}>
        Every number on this page is a real measurement from the engine’s own immutable ledger. The system’s defining property is that it is
        <b> falsifiable</b>: every claim below was earned against history the model was never allowed to see.
      </div>

      <div {...stylex.props(styles.kpiRow)}>
        <div {...stylex.props(styles.kpi)}><span {...stylex.props(styles.kpiBig)}>9–16%</span><span {...stylex.props(styles.kpiLabel)}>total-level error, recent closed quarters</span></div>
        <div {...stylex.props(styles.kpi)}><span {...stylex.props(styles.kpiBig)}>3,649</span><span {...stylex.props(styles.kpiLabel)}>independent account×SKU series</span></div>
        <div {...stylex.props(styles.kpi)}><span {...stylex.props(styles.kpiBig)}>14</span><span {...stylex.props(styles.kpiLabel)}>deterministic models in the registry</span></div>
        <div {...stylex.props(styles.kpi)}><span {...stylex.props(styles.kpiBig)}>7</span><span {...stylex.props(styles.kpiLabel)}>falsified ideas, documented, never retried</span></div>
        <div {...stylex.props(styles.kpi)}><span {...stylex.props(styles.kpiBig)}>0</span><span {...stylex.props(styles.kpiLabel)}>Python / ML infra dependencies</span></div>
      </div>

      <div style={{ height: '1rem' }} />

      <Section title="The pipeline — data to bottom line" defaultOpen>
        <div {...stylex.props(styles.pipeline)}>
          <div {...stylex.props(styles.pipeBox)}><div {...stylex.props(styles.pipeTitle)}>1 · Ground truth</div>Serial-attributed dispatches (every charger’s serial joined to its dispatch and buyer) + live activations from the device fleet + open orders + deals + Stripe + SMMT TAM. History arrives as git-versioned NDJSON snapshots; backtests pin the data SHA.</div>
          <div {...stylex.props(styles.arrow)}>→</div>
          <div {...stylex.props(styles.pipeBox)}><div {...stylex.props(styles.pipeTitle)}>2 · Censored series</div>Per account×SKU, the monthly demand series AS OF a chosen origin — everything (history, shelf, velocity, open book, lags) computed strictly from rows before that date. The world as a forecaster would have seen it.</div>
          <div {...stylex.props(styles.arrow)}>→</div>
          <div {...stylex.props(styles.pipeBox)}><div {...stylex.props(styles.pipeTitle)}>3 · The registry</div>14 pure, deterministic models — statistical shapes + structural models that read real telemetry (shelf, activations, order books). Same inputs ⇒ same outputs, forever. A universal clamp bounds every model at 3× the series’ largest month.</div>
          <div {...stylex.props(styles.arrow)}>→</div>
          <div {...stylex.props(styles.pipeBox)}><div {...stylex.props(styles.pipeTitle)}>4 · The tournament</div>Per account, candidates are ranked on their own scored track record; guards demote anything unstable; the run-rate incumbent must be beaten to be displaced.</div>
          <div {...stylex.props(styles.arrow)}>→</div>
          <div {...stylex.props(styles.pipeBox)}><div {...stylex.props(styles.pipeTitle)}>5 · Bands + £</div>P80/P50/P20 from the model’s own measured error spread; revenue = units × each account’s contract tier (never a stored number); seasonality enters at measured pass-through, not raw TAM.</div>
        </div>
      </Section>

      <Section title="Why it is bulletproof — the honesty rules" badge="THE CORE" badgeStyle={styles.badgeGuard}>
        <table {...stylex.props(styles.table)}>
          <tbody>
            <tr><td {...stylex.props(styles.td)}><b>No leakage, ever</b></td><td {...stylex.props(styles.td)}>Every feature is computed from rows strictly before the forecast origin. A deal that closed after the origin appears OPEN. An activation after the origin doesn’t exist. The backtest cannot cheat because the queries cannot see the future.</td></tr>
            <tr><td {...stylex.props(styles.td)}><b>Append-only, immutable</b></td><td {...stylex.props(styles.td)}>Every forecast run is a permanent row set — re-publishing supersedes, never deletes. Contrast: the H6Q workbook overwrites its forecasts with actuals in place, so its closed-quarter accuracy is unrecoverable. Conduit’s can never be.</td></tr>
            <tr><td {...stylex.props(styles.td)}><b>Deterministic</b></td><td {...stylex.props(styles.td)}>Pure Scala functions — no randomness, no clock, no GPU. (data git SHA, model version) ⇒ bit-identical forecast, reproducible in any audit, forever.</td></tr>
            <tr><td {...stylex.props(styles.td)}><b>Scored at the served grain</b></td><td {...stylex.props(styles.td)}>Selection is judged on QUARTER totals — the number the business actually reads — after measuring that monthly-grain selection picks flattering shapes that miss the quarter.</td></tr>
            <tr><td {...stylex.props(styles.td)}><b>Business actuals stay visible</b></td><td {...stylex.props(styles.td)}>Reports separate “what the company shipped” (the full dispatch log) from “what the model could score” (accounts with enough history), with coverage % stated per quarter. The model is never allowed to present its subset as reality.</td></tr>
            <tr><td {...stylex.props(styles.td)}><b>Humans on identical terms</b></td><td {...stylex.props(styles.td)}>The human plan (H6Q) is scored with the same machinery, same censoring, same grain — from now on, permanently.</td></tr>
          </tbody>
        </table>
      </Section>

      <Section title="The falsification discipline — seven ideas the harness killed" badge="WHY TRUST IT" badgeStyle={styles.badgeGuard}>
        <div {...stylex.props(styles.muted)}>
          A system is only trustworthy if it can reject its own plausible ideas. Every change must improve the 8-quarter backtest means or it is reverted —
          including its evidence rows, so a dead idea can’t haunt future selections. These all sounded right. The data said no.
        </div>
        <table {...stylex.props(styles.table)}>
          <thead><tr><th {...stylex.props(styles.th)}>Idea</th><th {...stylex.props(styles.th)}>What it tried</th><th {...stylex.props(styles.th)}>Verdict</th></tr></thead>
          <tbody>
            {NEGATIVES.map(([name, what, verdict]) => (
              <tr key={name}>
                <td {...stylex.props(styles.td)}><b>{name}</b></td>
                <td {...stylex.props(styles.td)}>{what}</td>
                <td {...stylex.props(styles.td, styles.bad)}>{verdict}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Section>

      <Section title="The model registry — 14 deterministic models">
        <div {...stylex.props(styles.muted)}>
          Statistical models read the series’ own shape. Structural models read the physical world — shelf stock, activations, open order books.
          Structure is privileged: a telemetry measurement is not a curve fit, so structural challengers face a lower bar against the incumbent.
        </div>
        <div {...stylex.props(styles.grid)}>
          {REGISTRY.map((m) => (
            <div key={m.key} {...stylex.props(styles.card)}>
              <span {...stylex.props(styles.cardKey)}>{m.key}</span>
              <span {...stylex.props(styles.badge, m.kind === 'structural' ? styles.badgeStruct : styles.badgeStat)}>{m.kind}</span>
              <div {...stylex.props(styles.cardBody)}><b>What:</b> {m.what}</div>
              <div {...stylex.props(styles.cardBody)}><b>Wins where:</b> {m.wins}</div>
            </div>
          ))}
        </div>
      </Section>

      <Section title="Try it — the depletion model, live" badge="INTERACTIVE" badgeStyle={styles.badgeStruct}>
        <div {...stylex.props(styles.muted)}>
          This is the exact arithmetic that called the Q2’25 Octopus collapse at 11% error (shelf 5,003 × velocity ≈ 2,950/mo) while every
          statistical model said 6,500–8,800. The customer’s shelf must empty before they reorder — drag the sliders.
        </div>
        <div {...stylex.props(styles.sliderRow)}>
          <div {...stylex.props(styles.sliderBox)}>
            <div {...stylex.props(styles.muted)}>Shelf stock (shipped − activated): <b {...stylex.props(styles.mono)}>{shelf.toLocaleString()}</b></div>
            <input {...stylex.props(styles.slider)} data-testid="shelf-slider" type="range" min={0} max={15000} step={100} value={shelf} onChange={(e) => setShelf(Number(e.target.value))} />
          </div>
          <div {...stylex.props(styles.sliderBox)}>
            <div {...stylex.props(styles.muted)}>Activation velocity (installs/month): <b {...stylex.props(styles.mono)}>{velocity.toLocaleString()}</b></div>
            <input {...stylex.props(styles.slider)} data-testid="velocity-slider" type="range" min={100} max={6000} step={50} value={velocity} onChange={(e) => setVelocity(Number(e.target.value))} />
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
        <div {...stylex.props(styles.muted)}>
          Quarter total: <b {...stylex.props(styles.mono)}>{Math.round(curve.slice(0, 3).reduce((a, b) => a + b, 0)).toLocaleString()}</b> units —
          months stay at zero until the shelf is consumed, then sell-in resumes at the install rate. No statistical model can produce this shape from sell-in alone.
        </div>
      </Section>

      <Section title="The tournament — how an account gets its champion" badge="INTERACTIVE" badgeStyle={styles.badgeStat}>
        <div style={{ display: 'flex', gap: '0.4rem', flexWrap: 'wrap', marginBottom: '0.6rem' }}>
          {tournamentSteps.map((s, i) => (
            <button key={s.t} {...stylex.props(styles.stepBtn, step === i && styles.stepBtnActive)} data-testid={`tstep-${i}`} onClick={() => setStep(i)}>{s.t}</button>
          ))}
        </div>
        <div {...stylex.props(styles.body)}><b>{tournamentSteps[step].t}.</b> {tournamentSteps[step].d}</div>
        <div {...stylex.props(styles.flag)}>
          The keystone is step 6: <b>per-series model selection overfits noise</b> — we measured an all-run-rate baseline BEATING the unconstrained
          tournament. The incumbent prior is what turns a model zoo into a disciplined system: deviations from the simple default must be EARNED,
          per account, on evidence the challenger never saw the answer to.
        </div>
      </Section>

      <Section title="The learning loop — why early quarters look bad and recent ones don’t">
        <div {...stylex.props(styles.muted)}>
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
        <div {...stylex.props(styles.muted)}>
          Q2’25 (red) is the stocking-wave collapse: every model extrapolated the Q1’25 ramp; the only signal that called it was shelf telemetry, which
          had no track record yet — an honest selector could not have picked it. Choosing it retroactively would be leakage. The system that admits
          this is the one whose recent single-digit errors you can believe. (* Q2’26 scored on its closed months.)
        </div>
      </Section>

      <Section title="Ranges, not points — P80 / P50 / P20">
        <table {...stylex.props(styles.table)}>
          <tbody>
            <tr><td {...stylex.props(styles.td)}><b>P80</b></td><td {...stylex.props(styles.td)}>The dependable number — ~80% probability of meeting or beating it. Commit supply and cash against this.</td></tr>
            <tr><td {...stylex.props(styles.td)}><b>P50</b></td><td {...stylex.props(styles.td)}>The model’s central number, NEVER recentered — bias-chasing was tested and measurably degrades it.</td></tr>
            <tr><td {...stylex.props(styles.td)}><b>P20</b></td><td {...stylex.props(styles.td)}>Blue sky — ~20% probability. The stretch case.</td></tr>
          </tbody>
        </table>
        <div {...stylex.props(styles.body)}>
          The spread is <b>empirical</b>: quantiles of per-sector actual÷forecast ratios over the last three CLOSED origins — the spread the model has
          actually exhibited, not an assumed ±X%. Currently <span {...stylex.props(styles.mono)}>×0.896 / ×1.396</span> around P50: asymmetric, because
          history says this model misses LOW more than high — the blue sky is genuinely fatter than the downside. The band narrows by itself as
          quarters close. <b>Today:</b> Q3’26 P80 £9.3M · P50 £10.1M · P20 £13.4M all-in.
        </div>
      </Section>

      <Section title="Seasonality — measured pass-through, not assumed (β = −0.38)">
        <div {...stylex.props(styles.body)}>
          The UK BEV market does 56% of its year in H2 (Q4 alone = 30.3%). Naively scaling our Q4 by TAM seasonality would add ~£3M of phantom revenue —
          and Q4’25 proved it: market share-of-year 30.3%, our sell-in share <b>22.5%</b>. Regressing our quarter shares on the market’s gives
          <b> β = −0.38</b>: sell-in <b>inverts</b> market seasonality, because the channel stocks AHEAD of the high season and depletes into it
          (the Q1’25 wave was that stocking). Validated where it was exposed: predicted Q4’25 share 22.6% vs actual 22.5%.
        </div>
        <table {...stylex.props(styles.table)}>
          <thead><tr><th {...stylex.props(styles.th)}></th><th {...stylex.props(styles.th, styles.num)}>Q1</th><th {...stylex.props(styles.th, styles.num)}>Q2</th><th {...stylex.props(styles.th, styles.num)}>Q3</th><th {...stylex.props(styles.th, styles.num)}>Q4</th></tr></thead>
          <tbody>
            <tr><td {...stylex.props(styles.td)}>Market share-of-year (SMMT, 6-yr)</td><td {...stylex.props(styles.td, styles.num)}>21.9%</td><td {...stylex.props(styles.td, styles.num)}>21.8%</td><td {...stylex.props(styles.td, styles.num)}>25.9%</td><td {...stylex.props(styles.td, styles.num, styles.warn2)}>30.3%</td></tr>
            <tr><td {...stylex.props(styles.td)}>Our sell-in share (2025 actual)</td><td {...stylex.props(styles.td, styles.num)}>25.7%</td><td {...stylex.props(styles.td, styles.num)}>27.0%</td><td {...stylex.props(styles.td, styles.num)}>24.8%</td><td {...stylex.props(styles.td, styles.num, styles.good)}>22.5%</td></tr>
          </tbody>
        </table>
        <div {...stylex.props(styles.muted)}>β recomputes from the dispatch log + SMMT profile on every refresh — a second year of data sharpens it automatically. Per-market βs activate the same way as new markets accrue a year of history.</div>
      </Section>

      <Section title="Every level of the business, handled">
        <table {...stylex.props(styles.table)}>
          <thead><tr><th {...stylex.props(styles.th)}>Level</th><th {...stylex.props(styles.th)}>Mechanism</th></tr></thead>
          <tbody>
            <tr><td {...stylex.props(styles.td)}><b>Serial</b></td><td {...stylex.props(styles.td)}>Every charger’s serial attributed to its buyer at dispatch; activation flips it in real time via the Pulsar stream.</td></tr>
            <tr><td {...stylex.props(styles.td)}><b>Account×SKU</b></td><td {...stylex.props(styles.td)}>Its own censored series, its own champion, its own measured lags (order→dispatch, reorder point), its own contract tier for £.</td></tr>
            <tr><td {...stylex.props(styles.td)}><b>Account</b></td><td {...stylex.props(styles.td)}>Selection at account grain (SKUs summed — no arbitrary-variant bugs); shelf/velocity/runway state live; reorder signal fires at the account’s measured threshold.</td></tr>
            <tr><td {...stylex.props(styles.td)}><b>Channel</b></td><td {...stylex.props(styles.td)}>Aggregation of account champions + ASP×mix tracking (blended £490–497 stable while Energy 49→29% and Wholesale 18→39% of volume rotated underneath).</td></tr>
            <tr><td {...stylex.props(styles.td)}><b>Market</b></td><td {...stylex.props(styles.td)}>TAM series + measured pass-through β per market; seasonality learned per series, never hand-configured (an inverted Australian season needs zero code).</td></tr>
            <tr><td {...stylex.props(styles.td)}><b>Company</b></td><td {...stylex.props(styles.td)}>The bottom line: P80/P50/P20 in units and £, D2C carried on its own curve, supply commitments (the Volex/Luxshare ladder) reading the same rows.</td></tr>
          </tbody>
        </table>
      </Section>

      <Section title="Self-updating — three loops, three speeds">
        <table {...stylex.props(styles.table)}>
          <thead><tr><th {...stylex.props(styles.th)}>Cadence</th><th {...stylex.props(styles.th)}>Mechanism</th><th {...stylex.props(styles.th)}>What moves</th></tr></thead>
          <tbody>
            <tr><td {...stylex.props(styles.td)}><b>Per activation</b></td><td {...stylex.props(styles.td)}>Pulsar consumer (live device fleet)</td><td {...stylex.props(styles.td)}>Shelf, velocity, runway per account; reorder signal at the measured threshold</td></tr>
            <tr><td {...stylex.props(styles.td)}><b>Every 6 hours</b></td><td {...stylex.props(styles.td)}>Feed refresher (incremental scrapes + reload + rescore + republish, snapshots git-committed)</td><td {...stylex.props(styles.td)}>Open-quarter nowcast, live forecast rows, ASP, bands</td></tr>
            <tr><td {...stylex.props(styles.td)}><b>Quarter close</b></td><td {...stylex.props(styles.td)}>Calendar-derived origins — no human edit</td><td {...stylex.props(styles.td)}>A new evidence origin for every account; champions re-ranked; β and bands deepen</td></tr>
          </tbody>
        </table>
      </Section>

      <Section title="Why it scales — many markets, many seasonalities, many criteria">
        <div {...stylex.props(styles.body)}>
          Nothing in the system is global. Selection is per-series; seasonality is learned per series; lags are measured per account; βs are measured
          per market. Adding a market adds <b>rows, not assumptions</b> — 23 markets is the same machine with more keys, sharded trivially because
          markets are independent. Compute is linear: 3,649 series fit+score in ~6 minutes per origin on a laptop; reads are materialized
          (0.25 s). And the property that keeps a growing model zoo safe at scale is the incumbent guard: 50 new candidate models cannot cause
          curve-fit chaos, because every one of them must beat the boring default per series, on evidence, before touching a single forecast.
        </div>
        <div {...stylex.props(styles.flag)}>
          If a model class someday needs covariates we can’t express cheaply (weather, tariffs, promos), the registry contract — a pure function over a
          censored history — admits any sidecar as just another candidate. It gets no special treatment: it wins series in the tournament or it doesn’t ship.
          Seven falsified ideas say the bar is real.
        </div>
      </Section>

      <Section title="Conduit vs the human plan (H6Q) — scored on identical terms">
        <table {...stylex.props(styles.table)}>
          <thead><tr><th {...stylex.props(styles.th)}></th><th {...stylex.props(styles.th, styles.num)}>Q3’26</th><th {...stylex.props(styles.th, styles.num)}>Q4’26</th></tr></thead>
          <tbody>
            <tr><td {...stylex.props(styles.td)}>Conduit P50</td><td {...stylex.props(styles.td, styles.num)}>16,641u</td><td {...stylex.props(styles.td, styles.num)}>15,819u</td></tr>
            <tr><td {...stylex.props(styles.td)}>H6Q ex-Motability</td><td {...stylex.props(styles.td, styles.num)}>18,242u</td><td {...stylex.props(styles.td, styles.num)}>16,975u</td></tr>
            <tr><td {...stylex.props(styles.td)}>H6Q inc-Motability</td><td {...stylex.props(styles.td, styles.num, styles.warn2)}>22,942u</td><td {...stylex.props(styles.td, styles.num, styles.warn2)}>22,679u</td></tr>
            <tr><td {...stylex.props(styles.td)}>Conduit P20 (blue sky)</td><td {...stylex.props(styles.td, styles.num)}>23,231u</td><td {...stylex.props(styles.td, styles.num)}>22,083u</td></tr>
          </tbody>
        </table>
        <div {...stylex.props(styles.body)}>
          On reality the two agree (FY’25 within 0.1%, the open quarter within 1–4%). Ex-new-programs they agree within 7–10%. The entire forward gap is
          <b> one named bet</b>: Motability (~4,700–5,700u/quarter) — and H6Q inc-Motability lands on Conduit’s P20 almost to the unit.
          <b> The official plan is the blue-sky scenario, contingent on one program.</b> The model carries none of it because no unit has ever shipped
          under it — and the moment the first one does, the engine starts pricing it in automatically. Buy-side decisions ride P50/P80; Motability is
          tracked as the named P50→P20 mover.
        </div>
      </Section>
    </div>
  );
}
