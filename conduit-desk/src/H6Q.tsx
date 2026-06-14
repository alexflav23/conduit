import React, { useState } from 'react';
import {
  getMyForecasts, getScenarios, getVariants, submitForecast,
  getCoverage, getCoverageMatrix, getReconcile, H6Q_MARKET, ForecastLine,
} from './api';
import { PageHead, Card, Chip } from './kit/kit';
import { I } from './kit/icons';

// H6Q demand planning (M11 / spec/ui/05-h6q.md): the agent captures their portion (per account × SKU ×
// demand band) bottom-up; the Board shows the whole demand matrix (every SKU × every month) and the
// branch≡agent reconciliation. Ported to the desk kit (.seg / .tbl / .cellinput), testids preserved.

const PERIOD = '2026-09';
const BANDS = ['P20', 'P50', 'P80'] as const;
const fmt = (n: number) => n.toLocaleString('en-GB');

export function H6Q({ token }: { token: string }) {
  const [view, setView] = useState<'board' | 'capture'>('board');
  return (
    <>
      <PageHead
        title="H6Q demand"
        sub="Six-quarter demand horizon — bottom-up agent capture, the full matrix, branch ≡ agent reconciliation"
        right={
          <div className="seg">
            <button className={view === 'board' ? 'on' : ''} data-testid="h6q-tab-board" onClick={() => setView('board')}>Demand (H6Q)</button>
            <button className={view === 'capture' ? 'on' : ''} data-testid="h6q-tab-capture" onClick={() => setView('capture')}>My forecast</button>
          </div>
        }
      />
      {view === 'board' ? <Board token={token} /> : <Capture token={token} />}
    </>
  );
}

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
    const v = await getVariants(token); setVariants(Array.isArray(v.json) ? v.json : []);
    const sc = await getScenarios(token);
    const map: Record<string, string> = {};
    (Array.isArray(sc.json) ? sc.json : []).forEach((s: any) => { if (!s.toggle_basis) map[s.type] = s.id; });
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
    <Card title="My forecast" icon={I.layers}
      aux={<button className="btn primary sm" data-testid="h6q-load-mine" onClick={load}>{I.refresh({ size: 13 })} Load my accounts</button>}>
      <div className="row g8" style={{ marginBottom: 10 }}>
        {cycle && <Chip s="neutral"><span data-testid="h6q-cycle">cycle open</span></Chip>}
        {error && <span className="dim" data-testid="h6q-error" style={{ color: 'var(--danger)' }}>{error}</span>}
      </div>

      {account && (
        <>
          <div className="dim" style={{ fontSize: 12, textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 8 }}>Account</div>
          <div style={{ marginBottom: 14 }}>
            <select className="fld sel" style={{ minWidth: 280 }} data-testid="h6q-account" value={account} onChange={(e) => setAccount(e.target.value)}>
              {accounts.map((a) => <option key={a.company_id} value={a.company_id}>{a.name} — {a.status}</option>)}
            </select>
          </div>
          <div className="dim" style={{ fontSize: 12, textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 8 }}>Your portion — units by SKU × demand band ({PERIOD})</div>
          <div className="tablewrap">
            <table className="tbl">
              <thead><tr><th>SKU</th>{BANDS.map((b) => <th key={b} className="num mono">{b}</th>)}</tr></thead>
              <tbody>
                {variants.map((v) => (
                  <tr key={v.id}>
                    <td><b>{v.sku}</b><div className="dim" style={{ fontSize: 11.5 }}>{v.family}</div></td>
                    {BANDS.map((b) => (
                      <td key={b} className="num">
                        <input className="cellinput" data-testid={`h6q-qty-${v.sku}-${b}`} value={grid[`${v.id}|${b}`] ?? ''} onChange={(e) => setCell(v.id, b, e.target.value)} placeholder="0" />
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="row g8" style={{ marginTop: 14 }}>
            <button className="btn primary" data-testid="h6q-submit" onClick={submit}>Submit my forecast</button>
            {status && <Chip s="ok"><span data-testid="h6q-cap-status">{status}</span></Chip>}
          </div>
        </>
      )}
    </Card>
  );
}

function Board({ token }: { token: string }) {
  const [mode, setMode] = useState<'matrix' | 'reconcile'>('matrix');
  const [scenario, setScenario] = useState<string | null>(null);
  const [matrix, setMatrix] = useState<any[]>([]);
  const [groupBy, setGroupBy] = useState<'branch' | 'agent'>('branch');
  const [rows, setRows] = useState<any[]>([]);
  const [ties, setTies] = useState<boolean | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);

  const scenarioId = async (): Promise<string | null> => {
    if (scenario) return scenario;
    const s = await getScenarios(token);
    const p50 = (Array.isArray(s.json) ? s.json : []).find((x: any) => x.type === 'P50' && !x.toggle_basis);
    const id = p50?.id ?? null; setScenario(id); return id;
  };

  const loadMatrix = async () => {
    setError(null);
    const sc = await scenarioId();
    if (!sc) { setError('no scenario'); return; }
    const m = await getCoverageMatrix(token, H6Q_MARKET, sc);
    if (m.status !== 200) { setError(`Matrix failed (${m.status})`); return; }
    setMatrix(Array.isArray(m.json) ? m.json : []); setLoaded(true);
  };

  const loadReconcile = async (group: 'branch' | 'agent') => {
    setError(null);
    const sc = await scenarioId();
    if (!sc) { setError('no scenario'); return; }
    const cov = await getCoverage(token, H6Q_MARKET, PERIOD, sc, group);
    if (cov.status !== 200) { setError(`Coverage failed (${cov.status})`); return; }
    setRows(Array.isArray(cov.json) ? cov.json : []); setLoaded(true);
    const rec = await getReconcile(token, H6Q_MARKET, PERIOD, sc);
    setTies(rec.json?.ties ?? null);
  };

  React.useEffect(() => { loadMatrix(); /* auto-load the matrix on open */ }, []);

  const months = Array.from(new Set(matrix.map((r) => r.month))).sort();
  const skus = Array.from(new Set(matrix.map((r) => r.sku))).sort();
  const cellMap: Record<string, number> = {};
  const famOf: Record<string, string> = {};
  matrix.forEach((r) => { cellMap[`${r.sku}|${r.month}`] = r.forecast; if (r.family) famOf[r.sku] = r.family; });
  const colTotal = (m: string) => skus.reduce((a, s) => a + (cellMap[`${s}|${m}`] ?? 0), 0);
  const rowTotal = (s: string) => months.reduce((a, m) => a + (cellMap[`${s}|${m}`] ?? 0), 0);
  const grand = months.reduce((a, m) => a + colTotal(m), 0);

  return (
    <Card title="Demand board" icon={I.trend}
      aux={
        <div className="seg">
          <button className={mode === 'matrix' ? 'on' : ''} data-testid="h6q-mode-matrix" onClick={() => { setMode('matrix'); loadMatrix(); }}>Demand matrix</button>
          <button className={mode === 'reconcile' ? 'on' : ''} data-testid="h6q-mode-reconcile" onClick={() => { setMode('reconcile'); loadReconcile(groupBy); }}>Reconcile</button>
        </div>
      }>
      {error && <div className="dim" data-testid="h6q-board-error" style={{ color: 'var(--danger)', marginBottom: 8 }}>{error}</div>}

      {mode === 'matrix' ? (
        <>
          <div className="row between" style={{ marginBottom: 10 }}>
            <span className="dim" style={{ fontSize: 12 }}>Forecast units · all SKUs × all months · P50 · market total</span>
            <span style={{ fontFamily: 'var(--font-disp)', fontWeight: 800, fontSize: 18 }} data-testid="h6q-grand-total">{fmt(grand)} units</span>
          </div>
          <div className="tablewrap">
            <table className="tbl">
              <thead><tr>
                <th style={{ position: 'sticky', left: 0, background: 'var(--surface)' }}>SKU</th>
                {months.map((m) => <th key={m} className="num">{m}</th>)}
                <th className="num">Total</th>
              </tr></thead>
              <tbody>
                {skus.map((s) => (
                  <tr key={s} data-testid="h6q-matrix-row">
                    <td style={{ position: 'sticky', left: 0, background: 'var(--surface)' }}><b>{s}</b><div className="dim" style={{ fontSize: 11.5 }}>{famOf[s] ?? ''}</div></td>
                    {months.map((m) => <td key={m} className="num">{fmt(cellMap[`${s}|${m}`] ?? 0)}</td>)}
                    <td className="num" style={{ fontWeight: 700 }}>{fmt(rowTotal(s))}</td>
                  </tr>
                ))}
                <tr style={{ fontWeight: 800 }}>
                  <td style={{ position: 'sticky', left: 0, background: 'var(--surface)', fontWeight: 800 }}>Total</td>
                  {months.map((m) => <td key={m} className="num" style={{ fontWeight: 800 }} data-testid={`h6q-coltotal-${m}`}>{fmt(colTotal(m))}</td>)}
                  <td className="num" style={{ fontWeight: 800 }}>{fmt(grand)}</td>
                </tr>
              </tbody>
            </table>
          </div>
          {loaded && skus.length === 0 && (
            <p className="dim">No forecast yet. Import H6Q (<code>./local/run-local.sh --import</code>) or submit a forecast on the “My forecast” tab.</p>
          )}
        </>
      ) : (
        <>
          <div className="row g8" style={{ marginBottom: 10, flexWrap: 'wrap' }}>
            <div className="seg">
              <button className={groupBy === 'branch' ? 'on' : ''} data-testid="h6q-by-branch" onClick={() => { setGroupBy('branch'); loadReconcile('branch'); }}>By branch</button>
              <button className={groupBy === 'agent' ? 'on' : ''} data-testid="h6q-by-agent" onClick={() => { setGroupBy('agent'); loadReconcile('agent'); }}>By agent</button>
            </div>
            {ties !== null && <Chip s={ties ? 'ok' : 'warn'}><span data-testid="h6q-reconcile">{ties ? 'branch ≡ agent ✓' : 'reconcile mismatch'}</span></Chip>}
            <span className="dim" style={{ fontSize: 12 }}>{groupBy === 'agent' ? 'By sales agent' : 'By branch'} · {PERIOD} · P50</span>
          </div>
          <div className="tablewrap">
            <table className="tbl">
              <thead><tr>
                <th>{groupBy === 'agent' ? 'Agent' : 'Branch'}</th>
                <th className="num">Forecast</th><th className="num">Shipped</th><th className="num">Activated</th><th className="num">Coverage</th>
              </tr></thead>
              <tbody>
                {rows.map((r, i) => (
                  <tr key={i} data-testid="h6q-board-row">
                    <td className="mono">{groupBy === 'agent' ? (r.agent_user_id ?? '—').slice(0, 8) : (r.branch_company_id ?? '—').slice(0, 8)}</td>
                    <td className="num">{r.forecast_qty}</td>
                    <td className="num">{r.shipped_qty}</td>
                    <td className="num">{r.activated_qty}</td>
                    <td className="num" style={{ fontWeight: 700 }}>{r.coverage_pct == null ? '—' : `${Math.round(parseFloat(r.coverage_pct) * 100)}%`}</td>
                  </tr>
                ))}
                {loaded && rows.length === 0 && (
                  <tr><td className="dim" colSpan={5} style={{ padding: '14px 12px' }}>
                    No branch/agent rows at {PERIOD} — this view needs bottom-up agent submissions. Imported/market forecasts show on the Demand matrix.
                  </td></tr>
                )}
              </tbody>
            </table>
          </div>
        </>
      )}
    </Card>
  );
}
