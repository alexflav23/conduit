import React, { useState } from 'react';
import { getPeriods, getPeriodReconciliations, closePeriod, lockPeriod, getControls, runControl, getLineage } from './api';
import { PageHead, Card, Chip, LoadBar } from './kit/kit';
import { I } from './kit/icons';

// The Auditability Center (M13b / doc 14 §6 / spec/ui/12-auditability.md): the period close board (close →
// lock, gated on clean reconciliations), the SOX control register with on-demand runs, and the lineage
// explorer (figure → order_invoice → ledger transfers → events → document). Ported to the desk kit, testids
// preserved.

export function Auditability({ token }: { token: string }) {
  const [periods, setPeriods] = useState<any[]>([]);
  const [recs, setRecs] = useState<Record<string, any[]>>({});
  const [pStatus, setPStatus] = useState<string | null>(null);
  const [controls, setControls] = useState<any[]>([]);
  const [invoiceNo, setInvoiceNo] = useState('INV-FLOW');
  const [lineage, setLineage] = useState<any | null>(null);

  const loadPeriods = async () => { const r = await getPeriods(token); setPeriods(Array.isArray(r.json) ? r.json : []); };
  const loadRecs = async (id: string) => { const r = await getPeriodReconciliations(token, id); setRecs((m) => ({ ...m, [id]: Array.isArray(r.json) ? r.json : [] })); };
  const doClose = async (id: string) => { const r = await closePeriod(token, id); setPStatus(r.status === 200 ? 'closed' : `close failed: ${r.json?.message ?? r.status}`); await loadPeriods(); };
  const doLock = async (id: string) => { const r = await lockPeriod(token, id); setPStatus(r.status === 200 ? 'locked' : `lock blocked: ${r.json?.message ?? r.status}`); await loadPeriods(); };
  const loadControls = async () => { const r = await getControls(token); setControls(Array.isArray(r.json) ? r.json : []); };
  const doRun = async (code: string) => { await runControl(token, code); await loadControls(); };
  const loadLineage = async () => { const r = await getLineage(token, invoiceNo); setLineage(r.json); };

  const resultChip = (r: string | null) => (r === 'pass' ? 'pass' : r === 'fail' ? 'fail' : 'neutral');

  return (
    <>
      <PageHead title="Auditability Center" sub="Period close board, the SOX control register, and the figure-to-source lineage explorer" />

      <Card title="Period close board" icon={I.clock} style={{ maxWidth: 900 }}
        aux={<LoadBar><button className="btn primary sm" data-testid="aud-load-periods" onClick={loadPeriods}>{I.refresh({ size: 13 })} Load close board</button>{pStatus && <span className="dim" data-testid="aud-period-status">{pStatus}</span>}</LoadBar>}>
        <div className="dim" style={{ fontSize: 12.5, marginBottom: 10 }}>lock only over clean, signed-off reconciliations</div>
        <div className="tablewrap">
          <table className="tbl" data-testid="aud-periods">
            <thead><tr><th>Period</th><th>Scope</th><th>Status</th><th>Reconciliations</th><th>Actions</th></tr></thead>
            <tbody>
              {periods.map((p) => (
                <tr key={p.id} data-testid="aud-period-row">
                  <td>{p.period_key}</td>
                  <td>{p.scope}</td>
                  <td><Chip s={p.status === 'locked' ? 'locked' : 'open'}>{p.status}</Chip></td>
                  <td>
                    <div className="row g6" style={{ flexWrap: 'wrap' }}>
                      <button className="btn sm" data-testid="aud-load-recs" onClick={() => loadRecs(p.id)}>show</button>
                      {(recs[p.id] ?? []).map((r, i) => (
                        <Chip key={i} s={r.status === 'matched' ? 'matched' : 'fail'}>{r.type}: {r.status}</Chip>
                      ))}
                    </div>
                  </td>
                  <td>
                    <div className="row g6">
                      <button className="btn sm" data-testid="aud-close" onClick={() => doClose(p.id)}>Close</button>
                      <button className="btn sm" data-testid="aud-lock" onClick={() => doLock(p.id)}>Lock</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <Card title="SOX control register" icon={I.shield} style={{ maxWidth: 900 }}
        aux={<LoadBar><button className="btn primary sm" data-testid="aud-load-controls" onClick={loadControls}>{I.refresh({ size: 13 })} Load controls</button><span className="dim" style={{ fontSize: 12 }}>re-performable — run to refresh</span></LoadBar>}>
        <div className="tablewrap">
          <table className="tbl" data-testid="aud-controls">
            <thead><tr><th>Code</th><th>Control</th><th>Last result</th><th>Run</th></tr></thead>
            <tbody>
              {controls.map((c) => (
                <tr key={c.code} data-testid="aud-control-row">
                  <td className="mono">{c.code}</td>
                  <td>{c.name}</td>
                  <td><Chip s={resultChip(c.last_result)}><span data-testid={`aud-result-${c.code}`}>{c.last_result ?? 'not run'}</span></Chip></td>
                  <td><button className="btn sm" data-testid={`aud-run-${c.code}`} onClick={() => doRun(c.code)}>Run</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <Card title="Lineage explorer" icon={I.scale} style={{ maxWidth: 900 }} aux={<span className="dim" style={{ fontSize: 12 }}>figure → invoice → ledger transfers → events → document</span>}>
        <LoadBar>
          <span className="dim">Invoice no</span>
          <input className="fld" style={{ width: 160 }} data-testid="aud-invoice" value={invoiceNo} onChange={(e) => setInvoiceNo(e.target.value)} />
          <button className="btn primary" data-testid="aud-load-lineage" onClick={loadLineage}>Trace</button>
        </LoadBar>
        {lineage && (
          <div className="lineage" data-testid="aud-lineage" style={{ marginTop: 12 }}>
            invoice {lineage.invoice_no} — total £{lineage.total_inc_vat}{'\n'}
            ledger transfers: {(lineage.ledger_transfers ?? []).length}{'\n'}
            {(lineage.ledger_transfers ?? []).map((t: string) => '  • ' + t).join('\n')}{'\n'}
            events: {(lineage.events ?? []).map((e: any) => e.type).join(', ') || '(none)'}{'\n'}
            document: {lineage.document?.formatted_number ?? '(not generated)'}
          </div>
        )}
      </Card>
    </>
  );
}
