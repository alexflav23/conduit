import React, { useState } from 'react';
import { getForecastRuns, getForecastRunReport, getForecastRunDiff } from './api';
import { asArray } from './state';
import { PageHead, Card, Chip, LoadBar } from './kit/kit';
import { I } from './kit/icons';

// Forecast-run tracking (doc 26 §7 / spec/ui): the timeline of every forecast origin (the tournament's
// idempotent, immutable record), a comprehensive per-run report (stats + by-segment outturn + the model runs
// and their scored error — the BASIS the champion was chosen on), and a human-readable diff between any two
// runs so a human can see HOW the forecast evolved and WHY. Read-only, gated view:pipeline_coverage.

function errChip(pct: number | string | null | undefined): string {
  const n = pct == null ? 0 : Number(pct);
  return n <= 15 ? 'ok' : n <= 40 ? 'warn' : 'exception';
}

export function ForecastRuns({ token }: { token: string }) {
  const [runs, setRuns] = useState<any[]>([]);
  const [status, setStatus] = useState<string | null>(null);
  const [report, setReport] = useState<any | null>(null);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [diff, setDiff] = useState<any | null>(null);

  const load = async () => {
    const r = await getForecastRuns(token);
    if (r.status === 200) {
      const rows = asArray(r.json);
      setRuns(rows);
      setStatus(null);
      // default the diff selectors to the two most recent runs (rows are origin-desc)
      if (rows.length >= 2) { setTo((rows[0] as any).origin); setFrom((rows[1] as any).origin); }
      else if (rows.length === 1) { setTo((rows[0] as any).origin); setFrom((rows[0] as any).origin); }
    } else {
      setRuns([]);
      setStatus(r.status === 403 ? 'requires view:pipeline_coverage' : `error: ${r.status}`);
    }
  };

  const openReport = async (origin: string) => {
    const r = await getForecastRunReport(token, origin);
    setReport(r.status === 200 ? r.json : null);
  };

  const compare = async () => {
    if (!from || !to) return;
    const r = await getForecastRunDiff(token, from, to);
    setDiff(r.status === 200 ? r.json : null);
  };

  const rep = report;
  const stat = (label: string, value: React.ReactNode, testid: string, accent?: boolean) => (
    <div className="metric" style={{ minWidth: 130 }}>
      <div className="ml">{label}</div>
      <div className={'mv' + (accent ? ' accent' : '')} style={{ fontSize: 22 }} data-testid={testid}>{value}</div>
    </div>
  );

  return (
    <>
      <PageHead
        title="Forecast runs"
        sub="The tournament's run history — per-run report (the basis) and a human-readable diff of how it evolved"
        right={<LoadBar><button className="btn primary" data-testid="fr-load" onClick={load}>{I.refresh({ size: 14 })} Load runs</button>{status && <span className="dim" data-testid="fr-status">{status}</span>}</LoadBar>}
      />

      <Card title="Run timeline" icon={I.clock} aux={<span className="dim" style={{ fontSize: 12 }}>each origin is an immutable, reproducible record</span>}>
        <div className="tablewrap">
          <table className="tbl" data-testid="fr-runs">
            <thead><tr>
              <th>Origin</th><th className="num">Accounts</th><th className="num">Forecast</th><th className="num">Actual</th>
              <th>Total-level error</th><th className="num">Model runs</th><th>Last scored</th><th></th>
            </tr></thead>
            <tbody>
              {runs.map((r: any, i: number) => (
                <tr key={i} data-testid="fr-run-row">
                  <td><b>{r.origin}</b></td>
                  <td className="num">{r.accounts}</td>
                  <td className="num">{Number(r.forecast_units).toLocaleString('en-GB')}</td>
                  <td className="num">{Number(r.actual_units).toLocaleString('en-GB')}</td>
                  <td><Chip s={errChip(r.total_level_error_pct)}>{r.total_level_error_pct}%</Chip></td>
                  <td className="num">{r.model_runs}</td>
                  <td className="dim mono" style={{ fontSize: 11.5 }}>{(r.last_selected_at ?? '').slice(0, 10)}</td>
                  <td><button className="btn sm" data-testid="fr-open" onClick={() => openReport(r.origin)}>Report</button></td>
                </tr>
              ))}
              {runs.length === 0 && !status && <tr><td className="dim" colSpan={8} style={{ padding: '16px 12px', textAlign: 'center' }} data-testid="fr-empty">No forecast runs yet — the backtest loop writes one per origin.</td></tr>}
            </tbody>
          </table>
        </div>
      </Card>

      {rep && (
        <Card title={`Run report · ${rep.origin}`} icon={I.pulse} aux={<span className="dim" style={{ fontSize: 12 }}>the basis the champions were chosen on</span>}>
          <div data-testid="fr-report">
            <div className="row" style={{ gap: 26, flexWrap: 'wrap', marginBottom: 14 }}>
              {stat('Accounts', rep.stats?.accounts, 'fr-stat-accounts')}
              {stat('Forecast units', Number(rep.stats?.forecast_units).toLocaleString('en-GB'), 'fr-stat-forecast')}
              {stat('Actual units', Number(rep.stats?.actual_units).toLocaleString('en-GB'), 'fr-stat-actual')}
              {stat('Total-level error', `${rep.stats?.total_level_error_pct}%`, 'fr-stat-error', true)}
              {stat('Structural champ', `${Math.round(Number(rep.stats?.structural_share) * 100)}%`, 'fr-stat-structural')}
            </div>

            <div className="grid" style={{ gridTemplateColumns: 'repeat(2, 1fr)' }}>
              <div>
                <div className="dim" style={{ fontSize: 12, textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 8 }}>Outturn by segment</div>
                <div className="tablewrap"><table className="tbl" data-testid="fr-segments">
                  <thead><tr><th>Segment</th><th className="num">Accts</th><th className="num">Forecast</th><th className="num">Actual</th><th>Error</th></tr></thead>
                  <tbody>
                    {asArray(rep.segments).map((s: any, i: number) => (
                      <tr key={i}><td>{s.segment}</td><td className="num">{s.accounts}</td><td className="num">{Number(s.forecast_units).toLocaleString('en-GB')}</td><td className="num">{Number(s.actual_units).toLocaleString('en-GB')}</td><td><Chip s={errChip(s.total_level_error_pct)}>{s.total_level_error_pct}%</Chip></td></tr>
                    ))}
                  </tbody>
                </table></div>
              </div>
              <div>
                <div className="dim" style={{ fontSize: 12, textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 8 }}>Champion model mix</div>
                <div className="tablewrap"><table className="tbl" data-testid="fr-policy-mix">
                  <thead><tr><th>Policy</th><th className="num">Accounts</th></tr></thead>
                  <tbody>
                    {asArray(rep.policy_mix).map((m: any, i: number) => (
                      <tr key={i}><td className="mono">{m.policy_key}</td><td className="num">{m.accounts}</td></tr>
                    ))}
                  </tbody>
                </table></div>
              </div>
            </div>

            <div className="dim" style={{ fontSize: 12, textTransform: 'uppercase', letterSpacing: '0.06em', margin: '14px 0 8px' }}>Model bake-off (lowest mean error wins the account)</div>
            <div className="tablewrap"><table className="tbl" data-testid="fr-accuracy">
              <thead><tr><th>Model</th><th>Kind</th><th className="num">Scored</th><th className="num">Mean abs error</th><th className="num">Total abs error</th></tr></thead>
              <tbody>
                {asArray(rep.model_accuracy).map((m: any, i: number) => (
                  <tr key={i}><td className="mono">{m.model_key}</td><td><Chip s={m.structural ? 'accent' : 'neutral'}>{m.structural ? 'structural' : 'statistical'}</Chip></td><td className="num">{m.scored}</td><td className="num">{m.mean_abs_error}</td><td className="num">{m.total_abs_error}</td></tr>
                ))}
              </tbody>
            </table></div>

            <div className="dim" style={{ fontSize: 12, textTransform: 'uppercase', letterSpacing: '0.06em', margin: '14px 0 8px' }}>Run provenance (the pinning data SHA + params)</div>
            <div className="tablewrap"><table className="tbl" data-testid="fr-models">
              <thead><tr><th>Model</th><th className="num">Ver</th><th>Purpose</th><th>Data SHA</th><th>Params</th><th>Ran at</th></tr></thead>
              <tbody>
                {asArray(rep.model_runs).map((m: any, i: number) => (
                  <tr key={i}><td className="mono">{m.model_key}</td><td className="num">{m.model_version}</td><td>{m.purpose}</td><td className="mono dim">{(m.data_sha ?? '—')?.toString().slice(0, 10)}</td><td className="mono dim">{(m.params_hash ?? '—')?.toString().slice(0, 10)}</td><td className="dim mono" style={{ fontSize: 11.5 }}>{(m.created_at ?? '').slice(0, 16)}</td></tr>
                ))}
              </tbody>
            </table></div>
          </div>
        </Card>
      )}

      <Card title="Compare two runs" icon={I.scale} aux={<span className="dim" style={{ fontSize: 12 }}>how the forecast evolved — and the basis for it</span>}>
        <LoadBar>
          <span className="dim">From</span>
          <select className="fld sel" data-testid="fr-from" value={from} onChange={(e) => setFrom(e.target.value)}>
            <option value="">—</option>{runs.map((r: any) => <option key={r.origin} value={r.origin}>{r.origin}</option>)}
          </select>
          <span className="dim">To</span>
          <select className="fld sel" data-testid="fr-to" value={to} onChange={(e) => setTo(e.target.value)}>
            <option value="">—</option>{runs.map((r: any) => <option key={r.origin} value={r.origin}>{r.origin}</option>)}
          </select>
          <button className="btn primary" data-testid="fr-compare" onClick={compare}>Compare</button>
        </LoadBar>

        {diff && (
          <div data-testid="fr-diff" style={{ marginTop: 14 }}>
            <div className="lineage" data-testid="fr-narrative" style={{ marginBottom: 14 }}>
              {asArray<string>(diff.narrative).map((n) => `• ${n}`).join('\n')}
            </div>
            <div className="row" style={{ gap: 26, flexWrap: 'wrap', marginBottom: 14 }}>
              {stat('Error Δ', `${diff.error_delta_pct > 0 ? '+' : ''}${diff.error_delta_pct} pts`, 'fr-diff-error', true)}
              {stat('Accounts added', diff.accounts_added, 'fr-diff-added')}
              {stat('Accounts dropped', diff.accounts_dropped, 'fr-diff-dropped')}
              {stat('Champion changes', asArray(diff.champion_changes).length, 'fr-diff-changes')}
            </div>
            <div className="dim" style={{ fontSize: 12, textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 8 }}>Champion changes (account → new policy)</div>
            <div className="tablewrap"><table className="tbl" data-testid="fr-champion-changes">
              <thead><tr><th>Account</th><th>From</th><th></th><th>To</th></tr></thead>
              <tbody>
                {asArray(diff.champion_changes).map((c: any, i: number) => (
                  <tr key={i}><td className="mono">{(c.company_id ?? '').slice(0, 8)}</td><td className="mono">{c.from}</td><td className="dim">→</td><td className="mono">{c.to}</td></tr>
                ))}
                {asArray(diff.champion_changes).length === 0 && <tr><td className="dim" colSpan={4} style={{ padding: '12px' }}>No champion changed between these runs.</td></tr>}
              </tbody>
            </table></div>
          </div>
        )}
      </Card>
    </>
  );
}
