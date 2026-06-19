import React, { useState } from 'react';
import { useApi } from './lib/query';
import { request, ApiError } from './lib/client';
import { PageHead, Card, Chip, LayerNote, EmptyRow, SkeletonRow, num } from './kit/kit';
import { I } from './kit/icons';

// Inbox — the durable inbound cockpit (S1/S3.3, spec 20 §9b · D23). The never-lost guarantee made visible: every
// source's landed → published → processed → failed counts, and the QUARANTINE — rows that failed to map, with
// their raw payload + error retained — that an operator can requeue once the mapping is fixed. Read + requeue,
// gated to the dual-run owners (view:ingest_record; requeue needs edit:reconciliation).
//
// Backing routes (InboxRoutes): GET /inbox/health · GET /inbox/quarantine · POST /inbox/requeue.

interface HealthRow { source: string; status: string; count: number }
interface QRow {
  source: string; dataset: string; source_id: string; attempts: number;
  last_error: string | null; payload: unknown; first_seen: string; last_seen: string;
}
const STATUSES = ['received', 'published', 'processed', 'failed'] as const;

export function Inbox({ ctx }: { role?: any; ctx?: any; toast?: (m: string, k?: string) => void }) {
  const health = useApi<{ rows?: HealthRow[] }>(['inbox-health', ctx?.entity], '/api/v1/inbox/health');
  const quar = useApi<{ rows?: QRow[] }>(['inbox-quarantine', ctx?.entity], '/api/v1/inbox/quarantine?limit=200');
  const [open, setOpen] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const refetch = () => { health.refetch(); quar.refetch(); };
  React.useEffect(() => { const t = setInterval(refetch, 30000); return () => clearInterval(t); }, []);

  const err = (health.error || quar.error) as ApiError | null;
  const forbidden = !!err?.forbidden;

  const rows = health.data?.rows ?? [];
  const sources = Array.from(new Set(rows.map((r) => r.source))).sort();
  const cell = (s: string, st: string) => rows.find((r) => r.source === s && r.status === st)?.count ?? 0;
  const totalFailed = rows.filter((r) => r.status === 'failed').reduce((a, r) => a + r.count, 0);
  const totalLanded = rows.reduce((a, r) => a + r.count, 0);
  const qrows = quar.data?.rows ?? [];

  const requeue = async (r: QRow) => {
    const key = `${r.source}/${r.dataset}/${r.source_id}`;
    setBusy(key);
    try {
      await request(
        `/api/v1/inbox/requeue?source=${encodeURIComponent(r.source)}&dataset=${encodeURIComponent(r.dataset)}&source_id=${encodeURIComponent(r.source_id)}`,
        { method: 'POST' },
      );
      refetch();
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="page">
      <PageHead
        crumb="Shadow Ops · inbound durability (doc 33 / 36)"
        title="Inbox"
        sub="Inbound is never lost: every source's landed → published → processed → failed, and the quarantine of rows that failed to map (raw payload retained) — requeue once fixed."
        right={<span className="dim" style={{ fontSize: 12 }}>{health.isFetching ? 'Polling…' : 'auto-refreshes every 30s'}</span>}
      />

      {forbidden && <LayerNote>hidden — requires view:ingest_record</LayerNote>}

      {!forbidden && (
        <>
          <div className="grid" style={{ gridTemplateColumns: '1.4fr 1fr 1fr', gap: 14, marginBottom: 14 }}>
            <Card style={{ padding: '18px 20px', background: totalFailed === 0 ? 'var(--ok-bg)' : 'var(--warn-bg)' }}>
              <div className="row g10" style={{ alignItems: 'center' }}>
                <span style={{ width: 42, height: 42, borderRadius: 12, display: 'grid', placeItems: 'center', background: totalFailed === 0 ? 'var(--ok)' : 'var(--warn)', color: '#fff', flex: '0 0 42px' }}>
                  {totalFailed === 0 ? I.check({ size: 22 }) : I.alert({ size: 22 })}
                </span>
                <div>
                  <div style={{ fontFamily: 'var(--font-disp)', fontSize: 20, fontWeight: 600 }}>{totalFailed === 0 ? 'Nothing lost' : `${num(totalFailed)} quarantined`}</div>
                  <div className="dim" style={{ fontSize: 12.5 }}>{health.isLoading ? 'Loading…' : totalFailed === 0 ? 'every landed record mapped or in flight' : 'rows failed to map — raw payload retained, requeue below'}</div>
                </div>
              </div>
            </Card>
            <Card style={{ padding: '15px 18px' }}>
              <div className="dim" style={{ fontSize: 11.5 }}>Records landed</div>
              <div style={{ fontFamily: 'var(--font-disp)', fontSize: 28, fontWeight: 600, marginTop: 4 }}>{health.isLoading ? '—' : num(totalLanded)}</div>
            </Card>
            <Card style={{ padding: '15px 18px' }}>
              <div className="dim" style={{ fontSize: 11.5 }}>Quarantined</div>
              <div style={{ fontFamily: 'var(--font-disp)', fontSize: 28, fontWeight: 600, marginTop: 4, color: totalFailed > 0 ? 'var(--danger)' : 'var(--ok)' }}>{health.isLoading ? '—' : num(totalFailed)}</div>
            </Card>
          </div>

          <Card title="Inbox health" icon={I.sync} aux="per source · received → published → processed → failed" style={{ marginBottom: 14, padding: 0 }} className="tablewrap">
            <table className="tbl">
              <thead><tr><th>Source</th>{STATUSES.map((s) => <th key={s} className="num">{s}</th>)}</tr></thead>
              <tbody>
                {health.isLoading && <><SkeletonRow cols={5} /><SkeletonRow cols={5} /></>}
                {!health.isLoading && sources.length === 0 && <EmptyRow cols={5}>No inbound yet — connectors land here once their credentials are set.</EmptyRow>}
                {sources.map((s) => (
                  <tr key={s}>
                    <td><b>{s}</b></td>
                    {STATUSES.map((st) => {
                      const v = cell(s, st);
                      return <td key={st} className="num" style={st === 'failed' && v > 0 ? { color: 'var(--danger)', fontWeight: 600 } : undefined}>{v > 0 ? num(v) : '·'}</td>;
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>

          <Card title={`Quarantine${qrows.length ? ` (${qrows.length})` : ''}`} icon={I.alert} aux="rows that failed to map — raw payload + error retained, never dropped" style={{ padding: 0 }} className="tablewrap">
            <table className="tbl">
              <thead><tr><th>Source</th><th>Dataset</th><th>Source id</th><th className="num">Attempts</th><th>Last error</th><th /></tr></thead>
              <tbody>
                {quar.isLoading && <><SkeletonRow cols={6} /><SkeletonRow cols={6} /></>}
                {!quar.isLoading && qrows.length === 0 && <EmptyRow cols={6}>Quarantine empty — nothing failed to map.</EmptyRow>}
                {qrows.map((r) => {
                  const key = `${r.source}/${r.dataset}/${r.source_id}`;
                  return (
                    <React.Fragment key={key}>
                      <tr style={{ cursor: 'pointer' }} onClick={() => setOpen(open === key ? null : key)}>
                        <td><b>{r.source}</b></td>
                        <td className="mono dim" style={{ fontSize: 11.5 }}>{r.dataset}</td>
                        <td className="mono" style={{ fontSize: 11 }}>{r.source_id}</td>
                        <td className="num">{r.attempts}</td>
                        <td className="dim" style={{ fontSize: 11.5, color: 'var(--danger)', maxWidth: 320 }}>{r.last_error ?? '—'}</td>
                        <td style={{ textAlign: 'right' }}>
                          <button className="btn ghost sm" disabled={busy === key} onClick={(e) => { e.stopPropagation(); requeue(r); }}>{busy === key ? '…' : 'Requeue'}</button>
                        </td>
                      </tr>
                      {open === key && (
                        <tr><td colSpan={6} style={{ background: 'var(--bg-2)' }}>
                          <pre style={{ margin: 0, padding: 12, fontSize: 11, overflowX: 'auto', maxHeight: 280 }}>{JSON.stringify(r.payload, null, 2)}</pre>
                        </td></tr>
                      )}
                    </React.Fragment>
                  );
                })}
              </tbody>
            </table>
          </Card>
        </>
      )}
    </div>
  );
}
