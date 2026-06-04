import React, { useState } from 'react';
import * as stylex from '@stylexjs/stylex';
import { colors } from './styles/tokens.stylex';
import {
  getMyForecasts, getScenarios, getVariants, submitForecast,
  getCoverage, getReconcile, getNotifications, H6Q_MARKET, ForecastLine,
} from './api';

const styles = stylex.create({
  card: { backgroundColor: colors.surface, border: `1px solid ${colors.border}`, borderRadius: '14px', padding: '1.25rem', marginBottom: '1.25rem', maxWidth: '860px' },
  section: { fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.06em', color: colors.muted, marginBottom: '0.6rem' },
  row: { display: 'flex', gap: '0.75rem', alignItems: 'center', marginBottom: '0.6rem', flexWrap: 'wrap' },
  subnav: { display: 'flex', gap: '0.5rem', marginBottom: '1rem' },
  subtab: { backgroundColor: 'transparent', color: colors.muted, border: `1px solid ${colors.border}`, borderRadius: '999px', padding: '0.35rem 0.9rem', fontWeight: 600, cursor: 'pointer' },
  subtabActive: { backgroundColor: colors.accent, color: '#fff', border: `1px solid ${colors.accent}` },
  button: { backgroundColor: colors.accent, color: '#fff', border: 'none', borderRadius: '10px', padding: '0.55rem 1.1rem', fontSize: '0.95rem', fontWeight: 600, cursor: 'pointer', marginRight: '0.75rem' },
  ghost: { backgroundColor: 'transparent', color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '999px', padding: '0.4rem 0.9rem', fontWeight: 600, cursor: 'pointer', marginRight: '0.5rem' },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '0.9rem' },
  th: { textAlign: 'left', color: colors.muted, fontSize: '0.72rem', textTransform: 'uppercase', letterSpacing: '0.05em', padding: '0.5rem 0.6rem', borderBottom: `1px solid ${colors.border}` },
  td: { padding: '0.5rem 0.6rem', borderBottom: `1px solid ${colors.border}` },
  qty: { backgroundColor: colors.bg, color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.4rem 0.5rem', width: '84px', textAlign: 'right' },
  bandHead: { fontFamily: 'monospace' },
  chip: { padding: '0.2rem 0.6rem', borderRadius: '999px', fontWeight: 700, fontSize: '0.78rem' },
  ok: { backgroundColor: colors.ok, color: '#06210f' },
  warn: { backgroundColor: colors.warn, color: '#3a2400' },
  muted: { backgroundColor: colors.border, color: colors.text },
  total: { fontWeight: 800, fontSize: '1.1rem' },
  cov: { fontWeight: 700 },
});

const PERIOD = '2026-09';
const BANDS = ['P20', 'P50', 'P80'] as const;

export function H6Q({ token }: { token: string }) {
  const [view, setView] = useState<'capture' | 'board'>('capture');
  return (
    <div>
      <div {...stylex.props(styles.subnav)}>
        <button {...stylex.props(styles.subtab, view === 'capture' && styles.subtabActive)} data-testid="h6q-tab-capture" onClick={() => setView('capture')}>My forecast</button>
        <button {...stylex.props(styles.subtab, view === 'board' && styles.subtabActive)} data-testid="h6q-tab-board" onClick={() => setView('board')}>Coverage board</button>
      </div>
      {view === 'capture' ? <Capture token={token} /> : <Board token={token} />}
    </div>
  );
}

// The sales agent updates *their portion* — per owned account, per SKU, per demand band (P20/P50/P80) for the
// horizon month. This is the bottom-up capture; the rollup sums these parts automatically every cycle.
function Capture({ token }: { token: string }) {
  const [cycle, setCycle] = useState<string | null>(null);
  const [accounts, setAccounts] = useState<any[]>([]);
  const [account, setAccount] = useState<string | null>(null);
  const [variants, setVariants] = useState<any[]>([]);
  const [scenarios, setScenarios] = useState<Record<string, string>>({});
  const [grid, setGrid] = useState<Record<string, string>>({}); // key `${variantId}|${band}` -> qty
  const [status, setStatus] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    setError(null); setStatus(null);
    const mine = await getMyForecasts(token);
    if (mine.status !== 200) { setError(`Load failed (${mine.status})`); return; }
    setCycle(mine.json.cycle);
    setAccounts(mine.json.accounts ?? []);
    if ((mine.json.accounts ?? []).length) setAccount(mine.json.accounts[0].company_id);
    const v = await getVariants(token); setVariants(v.json ?? []);
    const sc = await getScenarios(token);
    const map: Record<string, string> = {};
    (sc.json ?? []).forEach((s: any) => { if (!s.toggle_basis) map[s.type] = s.id; });
    setScenarios(map);
  };

  const setCell = (variant: string, band: string, value: string) =>
    setGrid((g) => ({ ...g, [`${variant}|${band}`]: value }));

  const submit = async () => {
    if (!account || !cycle) return;
    setError(null);
    const lines: ForecastLine[] = [];
    variants.forEach((v) => BANDS.forEach((b) => {
      const raw = grid[`${v.id}|${b}`];
      if (raw && raw.trim() !== '' && scenarios[b]) lines.push({ variant: v.id, period: PERIOD, scenario: scenarios[b], qty: parseInt(raw, 10) || 0 });
    }));
    const res = await submitForecast(token, account, cycle, lines);
    if (res.status === 200) setStatus(`submitted (${res.json.versioned} updated)`);
    else setError(`Submit failed (${res.status}): ${res.json?.message ?? ''}`);
  };

  return (
    <div {...stylex.props(styles.card)}>
      <div {...stylex.props(styles.row)}>
        <button {...stylex.props(styles.button)} data-testid="h6q-load-mine" onClick={load}>Load my accounts</button>
        {cycle && <span {...stylex.props(styles.chip, styles.muted)} data-testid="h6q-cycle">cycle open</span>}
        {error && <span data-testid="h6q-error" style={{ color: colors.warn }}>{error}</span>}
      </div>

      {account && (
        <>
          <div {...stylex.props(styles.section)} style={{ marginTop: '0.5rem' }}>Account</div>
          <div {...stylex.props(styles.row)}>
            <select {...stylex.props(styles.qty)} style={{ width: '260px' }} data-testid="h6q-account" value={account} onChange={(e) => setAccount(e.target.value)}>
              {accounts.map((a) => <option key={a.company_id} value={a.company_id}>{a.name} — {a.status}</option>)}
            </select>
          </div>
          <div {...stylex.props(styles.section)} style={{ marginTop: '0.75rem' }}>Your portion — units by SKU × demand band ({PERIOD})</div>
          <table {...stylex.props(styles.table)}>
            <thead>
              <tr>
                <th {...stylex.props(styles.th)}>SKU</th>
                {BANDS.map((b) => <th key={b} {...stylex.props(styles.th, styles.bandHead)}>{b}</th>)}
              </tr>
            </thead>
            <tbody>
              {variants.map((v) => (
                <tr key={v.id}>
                  <td {...stylex.props(styles.td)}>{v.sku}<div style={{ color: colors.muted, fontSize: '0.72rem' }}>{v.family}</div></td>
                  {BANDS.map((b) => (
                    <td key={b} {...stylex.props(styles.td)}>
                      <input {...stylex.props(styles.qty)} data-testid={`h6q-qty-${v.sku}-${b}`} value={grid[`${v.id}|${b}`] ?? ''} onChange={(e) => setCell(v.id, b, e.target.value)} placeholder="0" />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          <div {...stylex.props(styles.row)} style={{ marginTop: '0.9rem' }}>
            <button {...stylex.props(styles.button)} data-testid="h6q-submit" onClick={submit}>Submit my forecast</button>
            {status && <span {...stylex.props(styles.chip, styles.ok)} data-testid="h6q-cap-status">{status}</span>}
          </div>
        </>
      )}
    </div>
  );
}

// The rolled-up coverage board: the same atomic estimates aggregated by branch (org axis) or by agent
// (ownership axis) — the two must reconcile. Layer-aware: a volume-only viewer sees units, no money.
function Board({ token }: { token: string }) {
  const [scenario, setScenario] = useState<string | null>(null);
  const [groupBy, setGroupBy] = useState<'branch' | 'agent'>('branch');
  const [rows, setRows] = useState<any[]>([]);
  const [ties, setTies] = useState<boolean | null>(null);
  const [alerts, setAlerts] = useState<any[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = async (group: 'branch' | 'agent') => {
    setError(null);
    let sc = scenario;
    if (!sc) {
      const s = await getScenarios(token);
      const p50 = (s.json ?? []).find((x: any) => x.type === 'P50' && !x.toggle_basis);
      sc = p50?.id ?? null; setScenario(sc);
    }
    if (!sc) { setError('no scenario'); return; }
    const cov = await getCoverage(token, H6Q_MARKET, PERIOD, sc, group);
    if (cov.status !== 200) { setError(`Coverage failed (${cov.status})`); return; }
    setRows(cov.json ?? []);
    const rec = await getReconcile(token, H6Q_MARKET, PERIOD, sc);
    setTies(rec.json?.ties ?? null);
    const notes = await getNotifications(token);
    if (notes.status === 200) setAlerts(notes.json ?? []);
  };

  const total = rows.reduce((acc, r) => acc + (r.forecast_qty ?? 0), 0);
  const label = (r: any) => groupBy === 'agent' ? (r.agent_user_id ?? '—').slice(0, 8) : (r.branch_company_id ?? '—').slice(0, 8);

  return (
    <div {...stylex.props(styles.card)}>
      <div {...stylex.props(styles.row)}>
        <button {...stylex.props(styles.ghost, groupBy === 'branch' && styles.subtabActive)} data-testid="h6q-by-branch" onClick={() => { setGroupBy('branch'); load('branch'); }}>By branch</button>
        <button {...stylex.props(styles.ghost, groupBy === 'agent' && styles.subtabActive)} data-testid="h6q-by-agent" onClick={() => { setGroupBy('agent'); load('agent'); }}>By agent</button>
        <button {...stylex.props(styles.button)} data-testid="h6q-board-load" onClick={() => load(groupBy)}>Load board</button>
        {ties !== null && <span {...stylex.props(styles.chip, ties ? styles.ok : styles.warn)} data-testid="h6q-reconcile">{ties ? 'branch ≡ agent ✓' : 'reconcile mismatch'}</span>}
        {error && <span data-testid="h6q-board-error" style={{ color: colors.warn }}>{error}</span>}
      </div>
      <div {...stylex.props(styles.row)}>
        <span {...stylex.props(styles.section)}>{groupBy === 'agent' ? 'By sales agent' : 'By branch'} · {PERIOD} · P50</span>
        <span {...stylex.props(styles.total)} data-testid="h6q-total">{total} units</span>
      </div>
      <table {...stylex.props(styles.table)}>
        <thead>
          <tr>
            <th {...stylex.props(styles.th)}>{groupBy === 'agent' ? 'Agent' : 'Branch'}</th>
            <th {...stylex.props(styles.th)}>Forecast</th>
            <th {...stylex.props(styles.th)}>Shipped</th>
            <th {...stylex.props(styles.th)}>Coverage</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i} data-testid="h6q-board-row">
              <td {...stylex.props(styles.td)}>{label(r)}</td>
              <td {...stylex.props(styles.td)}>{r.forecast_qty}</td>
              <td {...stylex.props(styles.td)}>{r.shipped_qty}</td>
              <td {...stylex.props(styles.td, styles.cov)}>{r.coverage_pct == null ? '—' : `${Math.round(parseFloat(r.coverage_pct) * 100)}%`}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <div {...stylex.props(styles.section)} style={{ marginTop: '1.25rem' }}>Forward-visibility alerts — who was told H6Q shifted</div>
      <table {...stylex.props(styles.table)} data-testid="h6q-alerts">
        <thead>
          <tr>
            <th {...stylex.props(styles.th)}>Recipient</th>
            <th {...stylex.props(styles.th)}>Channel</th>
            <th {...stylex.props(styles.th)}>Message</th>
            <th {...stylex.props(styles.th)}>Status</th>
          </tr>
        </thead>
        <tbody>
          {alerts.slice(0, 8).map((a, i) => (
            <tr key={i} data-testid="h6q-alert-row">
              <td {...stylex.props(styles.td)}>{a.subscription}</td>
              <td {...stylex.props(styles.td)}>{a.channel}</td>
              <td {...stylex.props(styles.td)}>{a.body}</td>
              <td {...stylex.props(styles.td)}><span {...stylex.props(styles.chip, a.status === 'sent' ? styles.ok : styles.muted)}>{a.status}</span></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
