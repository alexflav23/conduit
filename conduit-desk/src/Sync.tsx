import React from 'react';
import { useApi } from './lib/query';
import { ApiError } from './lib/client';
import { PageHead, Card, Chip, LayerNote, EmptyRow, SkeletonRow, num } from './kit/kit';
import { I } from './kit/icons';

// Sync — shadow dual-run health (M-Ingest / doc 33 §7 → spec/ui/13-sync.md). The control room for the
// months-long shadow parallel run: is Conduit tracking every source system (Xero, HubSpot, MRPeasy, Athena,
// Stripe) to the penny? Per-source sync health (cursor, lag, status, drift). A monitoring dashboard — calm
// when green, loud when not. Read-only, gated on view:sync_state.
//
// Backing route: GET /api/v1/finance/sync-state (AuditRoutes) → a flat array of sync_state rows
// { source, dataset, cursor, last_status, lag_seconds, records_seen, records_written,
//   consecutive_failures, last_error }. Gated server-side (403 -> requires sync_state layer).
// React Query polls every 30s so the board stays live; ctx.entity scopes the watched run.

interface SyncStream {
  source: string;
  dataset: string;
  cursor: string | null;
  last_status: string | null;
  lag_seconds: number | null;
  records_seen: number;
  records_written: number;
  consecutive_failures: number;
  last_error: string | null;
}

function fmtLag(s: number | null | undefined): string {
  if (s == null) return 'never';
  if (s < 90) return `${Math.round(s)}s ago`;
  if (s < 5400) return `${Math.round(s / 60)}m ago`;
  if (s < 172800) return `${Math.round(s / 3600)}h ago`;
  return `${Math.round(s / 86400)}d ago`;
}

// A stream is healthy only when its last run was ok, no fails are piling up, and it isn't badly stale (>1h).
function streamHealth(st: string | null | undefined, lag: number | null | undefined, fails: number): 'ok' | 'stale' | 'error' {
  const stale = lag != null && lag > 3600;
  if (st === 'error' || fails > 0) return 'error';
  if (st === 'stale' || stale) return 'stale';
  return st === 'ok' ? 'ok' : 'stale';
}
const healthChip = (h: 'ok' | 'stale' | 'error') => (h === 'ok' ? 'ok' : h === 'stale' ? 'warn' : 'danger');

export function Sync({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const q = useApi<SyncStream[]>(
    ['sync-state', ctx?.entity],
    '/api/v1/finance/sync-state',
  );

  const refetch = q.refetch;
  React.useEffect(() => {
    const t = setInterval(() => refetch(), 30000);
    return () => clearInterval(t);
  }, [refetch]);

  const err = q.error as ApiError | null;
  const forbidden = !!err?.forbidden;
  const notImplemented = !!err?.notImplemented;
  const otherError = !!err && !forbidden && !notImplemented;

  const streams: SyncStream[] = Array.isArray(q.data) ? q.data : [];
  const ready = !q.isLoading && !err;
  const empty = ready && streams.length === 0;

  const badStreams = streams.filter((s) => streamHealth(s.last_status, s.lag_seconds, s.consecutive_failures ?? 0) !== 'ok');
  const okStreams = streams.length - badStreams.length;
  const allGreen = ready && badStreams.length === 0 && streams.length > 0;

  return (
    <>
      <PageHead
        crumb="Cutover assurance · shadow parallel run (doc 33)"
        title="Sync"
        sub="Is Conduit tracking every source system to the penny? Per-source sync health — cursor, lag, last status and rising failures — across Xero, HubSpot, MRPeasy, Athena and Stripe. A sustained all-fresh window is the cutover green light."
        right={<span className="dim" style={{ fontSize: 12 }} data-testid="sync-poll">{q.isFetching ? 'Polling…' : 'Polled just now'} · auto-refreshes every 30s</span>}
      />

      {forbidden && <LayerNote>hidden — requires view:sync_state</LayerNote>}

      {notImplemented && (
        <Card style={{ padding: '34px 28px', textAlign: 'center' }} data-testid="sync-unbacked">
          <div style={{ display: 'grid', placeItems: 'center', gap: 10 }}>
            <span style={{ width: 44, height: 44, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'var(--panel-2)' }}>{I.sync({ size: 22 })}</span>
            <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>Not available in this environment yet</div>
            <div className="dim" style={{ fontSize: 12.5, maxWidth: 460 }}>The sync-health board appears once the shadow dual-run is wired and connectors have registered their first run.</div>
          </div>
        </Card>
      )}

      {otherError && (
        <div className="banner danger" style={{ marginBottom: 14 }} data-testid="sync-error">{I.alert()}<div>Sync board failed to load (HTTP {err?.status}). Retrying on the next poll.</div></div>
      )}

      {!forbidden && !notImplemented && (
        <>
          <div className="grid" style={{ gridTemplateColumns: '1.4fr 1fr 1fr', marginBottom: 14 }}>
            <Card
              style={{
                padding: '18px 20px',
                background: allGreen ? 'var(--ok-bg)' : ready && badStreams.length ? 'var(--warn-bg)' : undefined,
              }}
            >
              <div className="row g10" style={{ alignItems: 'center' }} data-testid="sync-hero">
                <span style={{ width: 42, height: 42, borderRadius: 12, display: 'grid', placeItems: 'center', background: allGreen ? 'var(--ok)' : 'var(--warn)', color: '#fff', flex: '0 0 42px' }}>
                  {allGreen ? I.check({ size: 22 }) : I.alert({ size: 22 })}
                </span>
                <div>
                  <div style={{ fontFamily: 'var(--font-disp)', fontSize: 20, fontWeight: 600 }}>{allGreen ? 'In step with reality' : ready && badStreams.length ? 'Drift detected' : 'Sync board'}</div>
                  <div className="dim" style={{ fontSize: 12.5 }}>
                    {q.isLoading
                      ? 'Loading streams…'
                      : allGreen
                        ? 'All streams fresh and tracking'
                        : badStreams.length
                          ? `${badStreams.length} stream(s) need attention`
                          : 'No streams registered yet'}
                  </div>
                </div>
              </div>
            </Card>
            <Card style={{ padding: '15px 18px' }}>
              <div className="dim" style={{ fontSize: 11.5 }}>Streams healthy</div>
              <div style={{ fontFamily: 'var(--font-disp)', fontSize: 28, fontWeight: 600, marginTop: 4, color: okStreams === streams.length && streams.length > 0 ? 'var(--ok)' : 'var(--warn)' }}>
                {q.isLoading ? '—' : okStreams}<span className="dim" style={{ fontSize: 15, fontWeight: 400 }}> / {q.isLoading ? '—' : streams.length}</span>
              </div>
            </Card>
            <Card style={{ padding: '15px 18px' }}>
              <div className="dim" style={{ fontSize: 11.5 }}>Records written</div>
              <div style={{ fontFamily: 'var(--font-disp)', fontSize: 28, fontWeight: 600, marginTop: 4 }}>
                {q.isLoading ? '—' : num(streams.reduce((a, s) => a + (s.records_written ?? 0), 0))}
              </div>
            </Card>
          </div>

          <Card title="Sync-health board" icon={I.sync} aux="one row per (source, dataset) · lag & rising fails are the early warning" style={{ marginBottom: 14, padding: 0 }} className="tablewrap">
            <table className="tbl" data-testid="sync-board">
              <thead><tr>
                <th>Source</th><th>Dataset</th><th>Status</th><th>Last run</th>
                <th className="num">Written</th><th className="num">Fails</th><th>Cursor</th><th>Last error</th>
              </tr></thead>
              <tbody>
                {q.isLoading && <><SkeletonRow cols={8} /><SkeletonRow cols={8} /><SkeletonRow cols={8} /></>}
                {empty && <EmptyRow cols={8}>No sync streams yet — connectors register on first run.</EmptyRow>}
                {ready && streams.map((s, i) => {
                  const h = streamHealth(s.last_status, s.lag_seconds, s.consecutive_failures ?? 0);
                  const fails = s.consecutive_failures ?? 0;
                  return (
                    <tr key={i} className={h !== 'ok' ? 'sel' : ''} data-testid="sync-row">
                      <td><b>{s.source}</b></td>
                      <td className="mono dim" style={{ fontSize: 11.5 }}>{s.dataset}</td>
                      <td><Chip s={healthChip(h)}>{h === 'ok' ? 'ok · fresh' : h}</Chip></td>
                      <td className={h === 'stale' ? '' : 'dim'} style={h === 'stale' ? { color: 'var(--warn)', fontWeight: 600 } : undefined}>
                        {fmtLag(s.lag_seconds)}
                      </td>
                      <td className="num">{num(s.records_written ?? 0)}</td>
                      <td className="num">{fails > 0 ? <b style={{ color: 'var(--danger)' }}>{fails}</b> : '0'}</td>
                      <td className="mono dim" style={{ fontSize: 10.5 }}>{s.cursor ?? '—'}</td>
                      <td className="dim" style={{ fontSize: 11.5, color: s.last_error ? 'var(--danger)' : undefined, maxWidth: 240 }}>{s.last_error ?? '—'}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </Card>

          {ready && badStreams.length > 0 && (
            <div className="banner danger" style={{ marginBottom: 14 }} data-testid="sync-alert">
              {I.alert()}
              <div>
                <b>{badStreams.map((s) => `${s.source}/${s.dataset}`).join(', ')}</b> {badStreams.length > 1 ? 'are' : 'is'} stale or failing. Stale streams precede reconciliation exceptions — check the last error and follow the <span className="mono">dlq-replay</span> / <span className="mono">projection-rebuild</span> runbook.
              </div>
            </div>
          )}
        </>
      )}
    </>
  );
}
