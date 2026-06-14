import React, { useState } from 'react';
import { getSyncState } from './api';
import { asArray } from './state';
import { PageHead, Card, Chip, LoadBar } from './kit/kit';
import { I } from './kit/icons';

// The shadow dual-run sync-health board (M-Ingest / doc 33 §7 → spec/ui/13-sync.md). Per-source cursor, lag,
// and last status for every ingest stream feeding the parallel run — the surface finance/auditors watch to
// confirm Conduit is tracking each source. Ported to the desk kit (PageHead / Card / Chip / .tbl). Read-only,
// gated view:sync_state.

function fmtLag(s: number | null): string {
  if (s == null) return 'never';
  if (s < 90) return `${s}s ago`;
  if (s < 5400) return `${Math.round(s / 60)}m ago`;
  if (s < 172800) return `${Math.round(s / 3600)}h ago`;
  return `${Math.round(s / 86400)}d ago`;
}

export function Sync({ token }: { token: string }) {
  const [rows, setRows] = useState<any[]>([]);
  const [status, setStatus] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);

  const load = async () => {
    const r = await getSyncState(token);
    setLoaded(true);
    if (r.status === 200) { setRows(asArray(r.json)); setStatus(null); }
    else { setRows([]); setStatus(r.status === 403 ? 'requires view:sync_state' : `error: ${r.status}`); }
  };

  // healthy = last run ok, no failures piling up, not badly stale (>1h).
  const chipState = (st: string | null, lag: number | null, fails: number) =>
    st === 'ok' && fails === 0 && (lag == null || lag < 3600) ? 'ok' : st === 'error' || fails > 0 ? 'exception' : 'open';

  return (
    <>
      <PageHead
        title="Sync"
        sub="Shadow dual-run feeds — cursor, lag, and last status per source"
        right={<LoadBar><button className="btn primary" data-testid="sync-load" onClick={load}>{I.refresh({ size: 14 })} Load sync health</button>{status && <span className="dim" data-testid="sync-status">{status}</span>}</LoadBar>}
      />
      <Card title="Ingest sync health" icon={I.sync}>
        <div className="tablewrap">
          <table className="tbl" data-testid="sync-board">
            <thead><tr>
              <th>Source</th><th>Dataset</th><th>Status</th><th>Last run</th>
              <th className="num">Written</th><th className="num">Fails</th><th>Cursor</th><th>Last error</th>
            </tr></thead>
            <tbody>
              {rows.map((r: any, i: number) => (
                <tr key={i} data-testid="sync-row">
                  <td><b>{r.source}</b></td>
                  <td>{r.dataset}</td>
                  <td><Chip s={chipState(r.last_status, r.lag_seconds, r.consecutive_failures ?? 0)}>{r.last_status ?? 'pending'}</Chip></td>
                  <td>{fmtLag(r.lag_seconds)}</td>
                  <td className="num">{r.records_written ?? 0}</td>
                  <td className="num">{r.consecutive_failures ?? 0}</td>
                  <td className="mono">{r.cursor ?? '—'}</td>
                  <td className="mono">{r.last_error ?? ''}</td>
                </tr>
              ))}
              {loaded && rows.length === 0 && !status && (
                <tr><td className="dim" colSpan={8} style={{ padding: '18px 12px', textAlign: 'center' }} data-testid="sync-empty">No sync streams yet — connectors register a row on first run.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </Card>
      <Card title="Dual-run reconciliations" icon={I.scale}>
        <div className="dim" style={{ padding: '4px 2px', fontSize: 13 }}>
          The penny-level proof that Conduit's books match each source runs as <code>dualrun_*</code> reconciliations —
          view them on the <b>Auditability</b> tab's reconciliation board (a sustained all-matched window is the cutover green light).
        </div>
      </Card>
    </>
  );
}
