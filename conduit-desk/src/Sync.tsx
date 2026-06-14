import React, { useState, useEffect } from 'react';
import { getSyncState } from './api';
import { asArray, tableState } from './state';
import { PageHead, Card, Chip, Money, AuditRef, LayerNote, EmptyRow, SkeletonRow, num } from './kit/kit';
import { I } from './kit/icons';

// Sync — shadow dual-run health (M-Ingest / doc 33 §7 → spec/ui/13-sync.md). The control room for the
// months-long shadow parallel run: is Conduit tracking every source system (Xero, HubSpot, MRPeasy, Athena,
// Stripe) to the penny? Per-source sync health (cursor, lag, status, drift) + the dual-run reconciliations that
// prove the books tie. A monitoring dashboard — calm when green, loud when not. Read-only, gated view:sync_state.
//
// Auto-loads on mount (no Load/Refresh button) and polls every 30s so the board stays live. Sync-health is the
// volume layer (operational); the dual-run reconciliation figures are commercial/profitability layered and
// COLLAPSE (Money returns null, never £0.00) when the viewer lacks the layer.

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
  const [res, setRes] = useState<{ status: number; json: any } | null>(null);

  useEffect(() => {
    let live = true;
    const load = () => getSyncState(role.token).then((r) => { if (live) setRes(r); });
    load();
    const t = setInterval(load, 30000);
    return () => { live = false; clearInterval(t); };
    // ctx.entity scopes which entity's shadow run we watch; remount/route already re-keys, deps cover ctx change.
  }, [role.token, ctx?.entity]);

  const data = res && res.status === 200 ? res.json : null;
  const streams = asArray<any>(data?.streams ?? data);
  const dual = asArray<any>(data?.dualrun ?? data?.reconciliations);
  const layers: string[] = (role?.layers as string[]) || [];

  const boardState = tableState(res, streams);
  const dualState = tableState(res, dual);

  const badStreams = streams.filter((s) => streamHealth(s.status, s.lag_seconds, s.consecutive_failures ?? s.fails ?? 0) !== 'ok');
  const okStreams = streams.length - badStreams.length;
  const excRecs = dual.filter((d) => (d.status ?? '').toLowerCase() === 'exception' || (Number(d.variance) || 0) !== 0);
  const matchedRecs = dual.length - excRecs.length;
  const allGreen = res?.status === 200 && badStreams.length === 0 && excRecs.length === 0 && streams.length > 0;
  const shadowOn = !!data?.shadow;

  return (
    <>
      <PageHead
        crumb="Cutover assurance · shadow parallel run (doc 33)"
        title="Sync"
        sub="Is Conduit tracking every source system to the penny? Per-source sync health and the dual-run reconciliations that prove the books match — a sustained all-matched window is the cutover green light."
        right={<span className="dim" style={{ fontSize: 12 }} data-testid="sync-poll">{res ? 'Polled just now' : 'Polling…'} · auto-refreshes every 30s</span>}
      />

      {shadowOn && (
        <div className="banner info" style={{ marginBottom: 14 }} data-testid="sync-shadow">
          {I.layers()}
          <div><b>Shadow mode is ON.</b> Conduit runs in parallel with the live stack; outbound effects (emails, source posts) are computed and suppressed, not sent. Cutover flips this off.</div>
        </div>
      )}

      {boardState === 'forbidden' && <LayerNote>requires view:sync_state</LayerNote>}
      {boardState === 'error' && (
        <div className="banner danger" style={{ marginBottom: 14 }} data-testid="sync-error">{I.alert()}<div>Sync board failed to load (HTTP {res?.status}). Retrying on the next poll.</div></div>
      )}

      {boardState !== 'forbidden' && boardState !== 'error' && (
        <>
          <div className="grid" style={{ gridTemplateColumns: '1.4fr 1fr 1fr', marginBottom: 14 }}>
            <Card
              style={{
                padding: '18px 20px',
                background: allGreen ? 'var(--ok-bg)' : badStreams.length || excRecs.length ? 'var(--warn-bg)' : undefined,
              }}
            >
              <div className="row g10" style={{ alignItems: 'center' }} data-testid="sync-hero">
                <span style={{ width: 42, height: 42, borderRadius: 12, display: 'grid', placeItems: 'center', background: allGreen ? 'var(--ok)' : 'var(--warn)', color: '#fff', flex: '0 0 42px' }}>
                  {allGreen ? I.check({ size: 22 }) : I.alert({ size: 22 })}
                </span>
                <div>
                  <div style={{ fontFamily: 'var(--font-disp)', fontSize: 20, fontWeight: 600 }}>{allGreen ? 'In step with reality' : 'Drift detected'}</div>
                  <div className="dim" style={{ fontSize: 12.5 }}>
                    {allGreen
                      ? 'All streams fresh, all reconciliations matched'
                      : `${badStreams.length} stream(s) need attention · ${excRecs.length} reconciliation exception(s)`}
                  </div>
                </div>
              </div>
            </Card>
            <Card style={{ padding: '15px 18px' }}>
              <div className="dim" style={{ fontSize: 11.5 }}>Streams healthy</div>
              <div style={{ fontFamily: 'var(--font-disp)', fontSize: 28, fontWeight: 600, marginTop: 4, color: okStreams === streams.length && streams.length > 0 ? 'var(--ok)' : 'var(--warn)' }}>
                {okStreams}<span className="dim" style={{ fontSize: 15, fontWeight: 400 }}> / {streams.length}</span>
              </div>
            </Card>
            <Card style={{ padding: '15px 18px' }}>
              <div className="dim" style={{ fontSize: 11.5 }}>Matched, consecutive</div>
              <div style={{ fontFamily: 'var(--font-disp)', fontSize: 28, fontWeight: 600, marginTop: 4 }}>
                {data?.matched_days ?? '—'}<span className="dim" style={{ fontSize: 15, fontWeight: 400 }}> days</span>
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
                {boardState === 'loading' && <><SkeletonRow cols={8} /><SkeletonRow cols={8} /><SkeletonRow cols={8} /></>}
                {boardState === 'empty' && <EmptyRow cols={8}>No sync streams yet — connectors register on first run.</EmptyRow>}
                {boardState === 'ready' && streams.map((s, i) => {
                  const h = streamHealth(s.status, s.lag_seconds, s.consecutive_failures ?? s.fails ?? 0);
                  const fails = s.consecutive_failures ?? s.fails ?? 0;
                  return (
                    <tr key={i} className={h !== 'ok' ? 'sel' : ''} data-testid="sync-row">
                      <td><b>{s.source}</b></td>
                      <td className="mono dim" style={{ fontSize: 11.5 }}>{s.dataset}</td>
                      <td><Chip s={healthChip(h)}>{h === 'ok' ? 'ok · fresh' : h}</Chip></td>
                      <td className={h === 'stale' ? '' : 'dim'} style={h === 'stale' ? { color: 'var(--warn)', fontWeight: 600 } : undefined}>
                        {fmtLag(s.lag_seconds)}
                      </td>
                      <td className="num">{num(s.records_written ?? s.written ?? 0)}</td>
                      <td className="num">{fails > 0 ? <b style={{ color: 'var(--danger)' }}>{fails}</b> : '0'}</td>
                      <td className="mono dim" style={{ fontSize: 10.5 }}>{s.cursor ?? '—'}</td>
                      <td className="dim" style={{ fontSize: 11.5, color: s.last_error || s.error ? 'var(--danger)' : undefined, maxWidth: 240 }}>{s.last_error ?? s.error ?? '—'}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </Card>

          {boardState === 'ready' && badStreams.length > 0 && (
            <div className="banner danger" style={{ marginBottom: 14 }} data-testid="sync-alert">
              {I.alert()}
              <div>
                <b>{badStreams.map((s) => `${s.source}/${s.dataset}`).join(', ')}</b> {badStreams.length > 1 ? 'are' : 'is'} stale or failing. Stale streams precede reconciliation exceptions — check the last error and follow the <span className="mono">dlq-replay</span> / <span className="mono">projection-rebuild</span> runbook.
              </div>
            </div>
          )}

          <Card title="Dual-run reconciliations" icon={I.scale} aux="expected[source] vs actual[Conduit] · tolerance 0" style={{ padding: 0 }} className="tablewrap">
            <table className="tbl" data-testid="sync-dual">
              <thead><tr>
                <th>Domain</th><th>Source</th>
                <th className="num">Expected (source)</th><th className="num">Actual (Conduit)</th><th className="num">Variance</th>
                <th>Status</th><th>Proof</th>
              </tr></thead>
              <tbody>
                {dualState === 'loading' && <><SkeletonRow cols={7} /><SkeletonRow cols={7} /></>}
                {dualState === 'empty' && <EmptyRow cols={7}>No dual-run reconciliations yet — they appear once the shadow run has a matched window.</EmptyRow>}
                {dualState === 'ready' && dual.map((d, i) => {
                  const isCount = d.unit === 'count';
                  const exc = (d.status ?? '').toLowerCase() === 'exception' || (Number(d.variance) || 0) !== 0;
                  const layer = (d.layer as string) || 'commercial';
                  const moneyHidden = !isCount && layers.length > 0 && layers.indexOf(layer) < 0;
                  return (
                    <tr key={i} className={exc ? 'sel' : ''} data-testid="sync-dual-row">
                      <td><b>{d.domain}</b></td>
                      <td className="dim">{d.source}</td>
                      <td className="num">{isCount ? num(d.expected) : moneyHidden ? <span className="dim">— layer</span> : <Money value={d.expected} ccy={d.currency} />}</td>
                      <td className="num">{isCount ? num(d.actual) : moneyHidden ? <span className="dim">— layer</span> : <Money value={d.actual} ccy={d.currency} />}</td>
                      <td className="num">
                        {(Number(d.variance) || 0) === 0
                          ? <span className="dim">{isCount ? '0' : '0.00'}</span>
                          : isCount
                            ? <b style={{ color: 'var(--danger)' }}>{num(d.variance)}</b>
                            : moneyHidden ? <span className="dim">— layer</span> : <b style={{ color: 'var(--danger)' }}><Money value={d.variance} ccy={d.currency} /></b>}
                      </td>
                      <td><Chip s={exc ? 'exception' : 'matched'}>{exc ? 'exception' : 'matched'}</Chip></td>
                      <td>{d.recon_id ? <AuditRef id={d.recon_id} /> : <span className="dim">—</span>}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {dualState === 'ready' && dual.length > 0 && (
              <div className="layer-note" style={{ padding: '10px 16px' }} data-testid="sync-dual-note">
                {I.shield()}{matchedRecs} of {dual.length} domains tie to the penny. A sustained zero-variance window across every domain is the signal to cut over. Open the Auditability board for the full <span className="mono">dualrun_*</span> reconciliation detail.
              </div>
            )}
          </Card>
        </>
      )}
    </>
  );
}
