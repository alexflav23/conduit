import React, { useState } from 'react';
import * as stylex from '@stylexjs/stylex';
import { colors } from './styles/tokens.stylex';
import { getSyncState } from './api';
import { asArray } from './state';

// The shadow dual-run sync-health board (M-Ingest / doc 33 §7): per-source cursor, lag, and last status for
// every ingest stream feeding the parallel run — the surface finance/auditors watch to confirm Conduit is
// tracking each source system. A stale lag or a rising failure count is the early warning before a
// reconciliation exception. Read-only, gated view:sync_state.
const styles = stylex.create({
  card: { backgroundColor: colors.surface, border: `1px solid ${colors.border}`, borderRadius: '14px', padding: '1.25rem', marginBottom: '1.25rem', maxWidth: '1000px' },
  section: { fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.06em', color: colors.muted, marginBottom: '0.6rem' },
  row: { display: 'flex', gap: '0.6rem', alignItems: 'center', marginBottom: '0.6rem', flexWrap: 'wrap' },
  button: { backgroundColor: colors.accent, color: '#fff', border: 'none', borderRadius: '10px', padding: '0.45rem 0.95rem', fontSize: '0.9rem', fontWeight: 600, cursor: 'pointer' },
  label: { color: colors.muted, fontSize: '0.8rem' },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '0.88rem', fontVariantNumeric: 'tabular-nums' },
  th: { textAlign: 'left', color: colors.muted, fontSize: '0.7rem', textTransform: 'uppercase', letterSpacing: '0.05em', padding: '0.4rem 0.65rem', borderBottom: `1px solid ${colors.border}` },
  td: { padding: '0.4rem 0.65rem', borderBottom: `1px solid ${colors.border}` },
  num: { textAlign: 'right', fontVariantNumeric: 'tabular-nums' },
  mono: { fontFamily: 'monospace', fontSize: '0.78rem', color: colors.muted },
  chip: { padding: '0.15rem 0.55rem', borderRadius: '999px', fontWeight: 700, fontSize: '0.74rem' },
  ok: { backgroundColor: colors.ok, color: '#06210f' },
  warn: { backgroundColor: colors.warn, color: '#3a2400' },
  muted: { backgroundColor: colors.border, color: colors.text },
});

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

  // a source is healthy when its last run was ok and it isn't badly stale (>1h) with no failures piling up.
  const lagChip = (st: string | null, lag: number | null, fails: number) =>
    st === 'ok' && fails === 0 && (lag == null || lag < 3600) ? styles.ok : (st === 'error' || fails > 0) ? styles.warn : styles.muted;

  return (
    <div>
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.row)}>
          <button {...stylex.props(styles.button)} data-testid="sync-load" onClick={load}>Load sync health</button>
          {status && <span {...stylex.props(styles.label)} data-testid="sync-status">{status}</span>}
          <span {...stylex.props(styles.label)}>the shadow dual-run feeds — cursor, lag, and last status per source</span>
        </div>
        <div {...stylex.props(styles.section)}>Ingest sync health</div>
        <table {...stylex.props(styles.table)} data-testid="sync-board">
          <thead><tr>
            <th {...stylex.props(styles.th)}>Source</th>
            <th {...stylex.props(styles.th)}>Dataset</th>
            <th {...stylex.props(styles.th)}>Status</th>
            <th {...stylex.props(styles.th)}>Last run</th>
            <th {...stylex.props(styles.th, styles.num)}>Written</th>
            <th {...stylex.props(styles.th, styles.num)}>Fails</th>
            <th {...stylex.props(styles.th)}>Cursor</th>
            <th {...stylex.props(styles.th)}>Last error</th>
          </tr></thead>
          <tbody>
            {rows.map((r: any, i: number) => (
              <tr key={i} data-testid="sync-row">
                <td {...stylex.props(styles.td)}>{r.source}</td>
                <td {...stylex.props(styles.td)}>{r.dataset}</td>
                <td {...stylex.props(styles.td)}>
                  <span {...stylex.props(styles.chip, lagChip(r.last_status, r.lag_seconds, r.consecutive_failures ?? 0))}>
                    {r.last_status ?? 'pending'}
                  </span>
                </td>
                <td {...stylex.props(styles.td)}>{fmtLag(r.lag_seconds)}</td>
                <td {...stylex.props(styles.td, styles.num)}>{r.records_written ?? 0}</td>
                <td {...stylex.props(styles.td, styles.num)}>{r.consecutive_failures ?? 0}</td>
                <td {...stylex.props(styles.td, styles.mono)}>{r.cursor ?? '—'}</td>
                <td {...stylex.props(styles.td, styles.mono)}>{r.last_error ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {loaded && rows.length === 0 && !status && (
          <div {...stylex.props(styles.label)} data-testid="sync-empty">No sync streams yet — connectors register a row on first run.</div>
        )}
      </div>
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.section)}>Dual-run reconciliations</div>
        <div {...stylex.props(styles.label)}>
          The penny-level proof that Conduit's books match each source runs as <code>dualrun_*</code> reconciliations —
          view them on the <strong>Audit</strong> tab's reconciliation board (a sustained all-matched window is the cutover green light).
        </div>
      </div>
    </div>
  );
}
