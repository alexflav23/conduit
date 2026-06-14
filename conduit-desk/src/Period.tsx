import React, { useState } from 'react';
import { investigatePeriod, lockGroupPeriod, getLineage } from './api';
import { asArray } from './state';
import { PageHead, Card, Chip, LoadBar } from './kit/kit';
import { I } from './kit/icons';

// The period investigation view (M-Period / doc 32 §2): a finance/auditor front door to one accounting
// period. Enter a group period key (e.g. 2026-Q2) and see the close status across every operating entity,
// the netted journals, the business events that drove them, the controls that ran, the reconciliations, the
// documents issued, and one-click lineage entry-points (invoice → CM PO via the Journal Atlas). The group
// roll-up lock (doc 32 §1 / ASC 810) refuses while any operating entity is still open, naming the laggards.
// Ported to the desk kit (PageHead / Card / Chip / .tbl), testids preserved.

export function Period({ token }: { token: string }) {
  const [key, setKey] = useState('2026-Q2');
  const [data, setData] = useState<any | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [lineage, setLineage] = useState<any | null>(null);

  const investigate = async () => {
    const r = await investigatePeriod(token, key);
    setData(r.status === 200 ? r.json : null);
    setStatus(r.status === 200 ? null : r.status === 403 ? 'requires view:accounting_period' : `not found: ${key}`);
    setLineage(null);
  };
  const doLock = async () => {
    const r = await lockGroupPeriod(token, key);
    const msg = r.status === 200 ? `group period ${key} locked` : `lock blocked: ${r.json?.message ?? r.status}`;
    await investigate(); // refresh the board first (it resets status), then surface the lock outcome
    setStatus(msg);
  };
  const trace = async (no: string) => { const r = await getLineage(token, no); setLineage(r.json); };

  const periods = asArray(data?.entity_periods);
  const journalLines = asArray(data?.journals?.lines);
  const events = asArray(data?.events);
  const controls = asArray(data?.controls);
  const recs = asArray(data?.reconciliations);
  const docs = asArray(data?.documents);
  const lineagePoints = asArray(data?.lineage);
  const allLocked = periods.length > 0 && periods.every((p: any) => p.status === 'locked');

  return (
    <>
      <PageHead
        title="Period"
        sub="Investigate one accounting period — close status, journals, controls, reconciliations, lineage"
        right={
          <LoadBar>
            <span className="dim">Group period</span>
            <input className="fld" style={{ width: 130 }} data-testid="per-key" value={key} onChange={(e) => setKey(e.target.value)} />
            <button className="btn primary" data-testid="per-investigate" onClick={investigate}>{I.search({ size: 14 })} Investigate</button>
            <button className="btn" data-testid="per-lock" onClick={doLock}>{I.shield({ size: 14 })} Lock group period</button>
            {status && <span className="dim" data-testid="per-status">{status}</span>}
            {data && <Chip s={data.group_status === 'locked' ? 'locked' : 'open'}><span data-testid="per-group-status">group: {data.group_status}</span></Chip>}
          </LoadBar>
        }
      />

      {data && (
        <Card title={`${data.period_key} — ${data.from} → ${data.to}`} icon={I.clock}
          aux={<Chip s={allLocked ? 'locked' : 'open'}>{allLocked ? 'all entities locked' : 'entities still open (lock gated)'}</Chip>} />
      )}

      {data && (
        <>
          <Card title="Entity close status (ASC 810 coterminous group close)" icon={I.layers}>
            <div className="tablewrap">
              <table className="tbl" data-testid="per-entities">
                <thead><tr><th>Entity</th><th>Status</th><th>Closed at</th></tr></thead>
                <tbody>
                  {periods.map((p: any, i: number) => (
                    <tr key={i} data-testid="per-entity-row">
                      <td><b>{p.entity}</b></td>
                      <td><Chip s={p.status === 'locked' ? 'locked' : 'open'}>{p.status}</Chip></td>
                      <td className="dim">{p.closed_at ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>

          <div className="grid" style={{ gridTemplateColumns: 'repeat(2, 1fr)' }}>
            <Card title={`Journals — ${data.journals?.leg_count ?? 0} posted legs`} icon={I.scale}>
              <div className="tablewrap">
                <table className="tbl" data-testid="per-journals">
                  <thead><tr><th>Account</th><th>Side</th><th className="num">Amount</th></tr></thead>
                  <tbody>
                    {journalLines.map((l: any, i: number) => (
                      <tr key={i}><td>{l.account}</td><td>{l.side}</td><td className="num">{l.amount}</td></tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>

            <Card title="Business events" icon={I.pulse}>
              <div className="tablewrap">
                <table className="tbl" data-testid="per-events">
                  <thead><tr><th>Event</th><th className="num">Count</th></tr></thead>
                  <tbody>
                    {events.map((e: any, i: number) => (
                      <tr key={i}><td>{e.event_type}</td><td className="num">{e.count}</td></tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>

            <Card title="Controls run in period" icon={I.check}>
              <div className="tablewrap">
                <table className="tbl" data-testid="per-controls">
                  <thead><tr><th>Code</th><th>Result</th><th className="num">Violations</th></tr></thead>
                  <tbody>
                    {controls.map((c: any, i: number) => (
                      <tr key={i}>
                        <td className="mono">{c.code}</td>
                        <td><Chip s={c.result === 'pass' ? 'pass' : 'warn'}>{c.result}</Chip></td>
                        <td className="num">{c.violations}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>

            <Card title="Reconciliations" icon={I.scale}>
              <div className="tablewrap">
                <table className="tbl" data-testid="per-recs">
                  <thead><tr><th>Type</th><th>Status</th><th>Signed off</th></tr></thead>
                  <tbody>
                    {recs.map((r: any, i: number) => (
                      <tr key={i}>
                        <td>{r.type}</td>
                        <td><Chip s={r.status === 'matched' ? 'matched' : 'warn'}>{r.status}</Chip></td>
                        <td>{r.signed_off ? '✓' : '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          </div>

          <Card title="Documents issued · lineage entry-points" icon={I.list}
            aux={<span className="dim" style={{ fontSize: 12 }}>click an invoice to trace to its CM PO</span>}>
            <div className="row g8" style={{ flexWrap: 'wrap', marginBottom: 10 }}>
              {docs.map((d: any, i: number) => (
                <Chip key={i} s="neutral"><span data-testid="per-doc">{d.kind} {d.number}</span></Chip>
              ))}
            </div>
            <div className="row g8" style={{ flexWrap: 'wrap' }}>
              {lineagePoints.map((l: any, i: number) => (
                <button key={i} className="btn sm" data-testid="per-lineage-link" onClick={() => trace(l.invoice_no)}>{l.invoice_no}</button>
              ))}
            </div>
            {lineage && (
              <div className="lineage" data-testid="per-lineage" style={{ marginTop: 12 }}>
                invoice {lineage.invoice_no} — total £{lineage.total_inc_vat}{'\n'}
                ledger transfers: {asArray(lineage.ledger_transfers).length}{'\n'}
                {asArray<string>(lineage.ledger_transfers).map((t) => '  • ' + t).join('\n')}{'\n'}
                document: {lineage.document?.formatted_number ?? '(not generated)'}
              </div>
            )}
          </Card>
        </>
      )}
    </>
  );
}
