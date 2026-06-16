import React, { useState } from 'react';
import { useApi } from './lib/query';
import { request, ApiError } from './lib/client';
import { PageHead, Card, Chip, LayerNote, EmptyRow, SkeletonRow, num } from './kit/kit';
import { I } from './kit/icons';

// Shadow validation — the cutover gate (doc 33 §5). Before Conduit becomes system of record, a battery of checks
// compares its computed reality against source-stated figures + integrity invariants, surfacing every discrepancy
// into a triage queue worked to zero. This is the control room: severity mix, the queue (filterable), and triage
// (investigating / accepted / resolved). Run on demand; the consumer also re-runs every 6h.
//   GET /shadow/summary · GET /shadow/findings · POST /shadow/validate · POST /shadow/findings/{id}/triage

interface Summary { shadow_mode: boolean; summary: { by_check: Record<string, number>; by_severity: Record<string, number>; by_status: Record<string, number> } }
interface Finding {
  id: string; check_code: string; severity: string; scope_type: string; scope_id: string;
  expected: number | null; actual: number | null; variance: number | null; currency: string | null;
  detail: any; status: string; note: string | null; detected_at: string;
}

const SEV_CHIP: Record<string, string> = { critical: 'danger', high: 'danger', medium: 'warn', low: 'neutral', info: 'neutral' };
const STATUS_CHIP: Record<string, string> = { open: 'warn', investigating: 'accent', accepted: 'plum', resolved: 'ok' };
const money = (v: number | null) => (v == null ? '—' : (v < 0 ? '-£' : '£') + num(Math.abs(v)));

export function Shadow(_props: any) {
  const [severity, setSeverity] = useState('high');
  const [status, setStatus] = useState('open');
  const [busy, setBusy] = useState(false);

  const sum = useApi<Summary>(['shadow-summary'], '/api/v1/shadow/summary');
  const find = useApi<Finding[]>(['shadow-findings', severity, status], `/api/v1/shadow/findings?severity=${severity}&status=${status}&limit=100`);

  const err = sum.error as ApiError | null;
  const forbidden = !!err?.forbidden;
  const bySev = sum.data?.summary?.by_severity ?? {};
  const byCheck = sum.data?.summary?.by_check ?? {};
  const byStatus = sum.data?.summary?.by_status ?? {};
  const findings = Array.isArray(find.data) ? find.data : [];

  const run = async () => {
    setBusy(true);
    try { await request('/api/v1/shadow/validate', { method: 'POST' }); await Promise.all([sum.refetch(), find.refetch()]); }
    finally { setBusy(false); }
  };
  const triage = async (id: string, st: string) => {
    await request(`/api/v1/shadow/findings/${id}/triage`, { method: 'POST', body: JSON.stringify({ status: st }) });
    await Promise.all([sum.refetch(), find.refetch()]);
  };

  return (
    <>
      <PageHead
        crumb="Cutover assurance · shadow validation (doc 33 §5)"
        title="Shadow validation"
        sub="The cutover gate: discrepancies between Conduit's computed reality and the source — worked to zero before go-live. Run the battery, triage the queue."
        right={<button className="btn" disabled={busy} onClick={run} data-testid="shadow-run">{busy ? 'Running…' : 'Run validation'}</button>}
      />

      {forbidden && <LayerNote>hidden — requires view:shadow_validation</LayerNote>}

      {!forbidden && (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(130px,1fr))', gap: 12, marginBottom: 16 }}>
            {sum.isLoading && <Card style={{ padding: 16 }}><SkeletonRow cols={1} /></Card>}
            {['critical', 'high', 'medium', 'low'].filter((sv) => bySev[sv]).map((sv) => (
              <Card key={sv} style={{ padding: '14px 16px' }}>
                <div className="dim" style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: 0.4 }}>{sv}</div>
                <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600, marginTop: 4 }}>{num(bySev[sv])}</div>
                <Chip s={SEV_CHIP[sv]}>{sv === 'low' ? 'review/accept' : 'open findings'}</Chip>
              </Card>
            ))}
            <Card style={{ padding: '14px 16px' }}>
              <div className="dim" style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: 0.4 }}>by status</div>
              <div style={{ fontSize: 13, marginTop: 6, display: 'grid', gap: 3 }}>
                {Object.entries(byStatus).map(([k, v]) => <div key={k}><Chip s={STATUS_CHIP[k] ?? 'neutral'}>{k}</Chip> {num(v)}</div>)}
              </div>
            </Card>
          </div>

          <Card title="By check" icon={I.list}>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              {Object.entries(byCheck).sort((a, b) => b[1] - a[1]).map(([code, n]) => (
                <Chip key={code} s="neutral">{code} · {num(n)}</Chip>
              ))}
              {Object.keys(byCheck).length === 0 && <span className="dim">No open findings — gate is green.</span>}
            </div>
          </Card>

          <Card title="Triage queue" icon={I.shield} aux={
            <span style={{ display: 'flex', gap: 6 }}>
              {['critical', 'high', 'medium', 'low'].map((sv) => <button key={sv} className={'chipbtn' + (severity === sv ? ' on' : '')} onClick={() => setSeverity(sv)}>{sv}</button>)}
              <span style={{ width: 8 }} />
              {['open', 'investigating', 'accepted', 'resolved'].map((st) => <button key={st} className={'chipbtn' + (status === st ? ' on' : '')} onClick={() => setStatus(st)}>{st}</button>)}
            </span>
          }>
            <table className="tbl">
              <thead><tr><th>Check</th><th>Scope</th><th style={{ textAlign: 'right' }}>Variance</th><th>Detail</th><th>Triage</th></tr></thead>
              <tbody>
                {find.isLoading && <SkeletonRow cols={5} />}
                {!find.isLoading && findings.length === 0 && <EmptyRow cols={5}>No {status} {severity} findings.</EmptyRow>}
                {findings.map((f) => (
                  <tr key={f.id}>
                    <td><Chip s={SEV_CHIP[f.severity]}>{f.check_code}</Chip></td>
                    <td className="dim" style={{ fontFamily: 'var(--font-mono)', fontSize: 11.5 }}>{f.scope_type}:{String(f.scope_id).slice(0, 8)}</td>
                    <td style={{ textAlign: 'right' }}>{money(f.variance)}</td>
                    <td className="dim" style={{ fontSize: 12, maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      {f.detail?.order_no || f.detail?.classification || f.detail?.order_id || JSON.stringify(f.detail).slice(0, 60)}
                    </td>
                    <td style={{ display: 'flex', gap: 4 }}>
                      {f.status !== 'investigating' && <button className="chipbtn" onClick={() => triage(f.id, 'investigating')}>investigate</button>}
                      {f.status !== 'accepted' && <button className="chipbtn" onClick={() => triage(f.id, 'accepted')}>accept</button>}
                      {f.status !== 'resolved' && <button className="chipbtn" onClick={() => triage(f.id, 'resolved')}>resolve</button>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Card>
        </>
      )}
    </>
  );
}
