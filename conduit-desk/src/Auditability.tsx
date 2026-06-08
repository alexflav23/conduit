import React, { useState } from 'react';
import * as stylex from '@stylexjs/stylex';
import { colors } from './styles/tokens.stylex';
import { getPeriods, getPeriodReconciliations, closePeriod, lockPeriod, getControls, runControl, getLineage } from './api';

// The Auditability Center (M13b / doc 14 §6): the period close board (close → lock, gated on clean
// reconciliations), the SOX control register with on-demand runs, and the lineage explorer
// (figure → order_invoice → ledger transfers → events → document).
const styles = stylex.create({
  card: { backgroundColor: colors.surface, border: `1px solid ${colors.border}`, borderRadius: '14px', padding: '1.25rem', marginBottom: '1.25rem', maxWidth: '900px' },
  section: { fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.06em', color: colors.muted, marginBottom: '0.6rem' },
  row: { display: 'flex', gap: '0.6rem', alignItems: 'center', marginBottom: '0.6rem', flexWrap: 'wrap' },
  button: { backgroundColor: colors.accent, color: '#fff', border: 'none', borderRadius: '10px', padding: '0.45rem 0.95rem', fontSize: '0.9rem', fontWeight: 600, cursor: 'pointer' },
  ghost: { backgroundColor: 'transparent', color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.35rem 0.8rem', fontWeight: 600, cursor: 'pointer' },
  input: { backgroundColor: colors.bg, color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.45rem 0.6rem', fontSize: '0.9rem' },
  label: { color: colors.muted, fontSize: '0.8rem' },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '0.88rem', fontVariantNumeric: 'tabular-nums' },
  th: { textAlign: 'left', color: colors.muted, fontSize: '0.7rem', textTransform: 'uppercase', letterSpacing: '0.05em', padding: '0.4rem 0.65rem', borderBottom: `1px solid ${colors.border}` },
  td: { padding: '0.4rem 0.65rem', borderBottom: `1px solid ${colors.border}` },
  chip: { padding: '0.15rem 0.55rem', borderRadius: '999px', fontWeight: 700, fontSize: '0.74rem' },
  pass: { backgroundColor: colors.ok, color: '#06210f' },
  fail: { backgroundColor: colors.warn, color: '#3a2400' },
  muted: { backgroundColor: colors.border, color: colors.text },
  pre: { fontFamily: 'monospace', fontSize: '0.78rem', whiteSpace: 'pre-wrap', color: colors.text, backgroundColor: colors.bg, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.7rem', maxWidth: '880px', overflowX: 'auto' },
});

export function Auditability({ token }: { token: string }) {
  const [periods, setPeriods] = useState<any[]>([]);
  const [recs, setRecs] = useState<Record<string, any[]>>({});
  const [pStatus, setPStatus] = useState<string | null>(null);
  const [controls, setControls] = useState<any[]>([]);
  const [invoiceNo, setInvoiceNo] = useState('INV-FLOW');
  const [lineage, setLineage] = useState<any | null>(null);

  const loadPeriods = async () => { const r = await getPeriods(token); setPeriods(r.json ?? []); };
  const loadRecs = async (id: string) => { const r = await getPeriodReconciliations(token, id); setRecs((m) => ({ ...m, [id]: r.json ?? [] })); };
  const doClose = async (id: string) => { const r = await closePeriod(token, id); setPStatus(r.status === 200 ? 'closed' : `close failed: ${r.json?.message ?? r.status}`); await loadPeriods(); };
  const doLock = async (id: string) => { const r = await lockPeriod(token, id); setPStatus(r.status === 200 ? 'locked' : `lock blocked: ${r.json?.message ?? r.status}`); await loadPeriods(); };
  const loadControls = async () => { const r = await getControls(token); setControls(r.json ?? []); };
  const doRun = async (code: string) => { await runControl(token, code); await loadControls(); };
  const loadLineage = async () => { const r = await getLineage(token, invoiceNo); setLineage(r.json); };

  const resultChip = (r: string | null) =>
    r === 'pass' ? styles.pass : r === 'fail' ? styles.fail : styles.muted;

  return (
    <div>
      {/* Close board */}
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.row)}>
          <button {...stylex.props(styles.button)} data-testid="aud-load-periods" onClick={loadPeriods}>Load close board</button>
          {pStatus && <span {...stylex.props(styles.label)} data-testid="aud-period-status">{pStatus}</span>}
        </div>
        <div {...stylex.props(styles.section)}>Period close board — lock only over clean, signed-off reconciliations</div>
        <table {...stylex.props(styles.table)} data-testid="aud-periods">
          <thead><tr>
            <th {...stylex.props(styles.th)}>Period</th><th {...stylex.props(styles.th)}>Scope</th>
            <th {...stylex.props(styles.th)}>Status</th><th {...stylex.props(styles.th)}>Reconciliations</th><th {...stylex.props(styles.th)}>Actions</th>
          </tr></thead>
          <tbody>
            {periods.map((p) => (
              <tr key={p.id} data-testid="aud-period-row">
                <td {...stylex.props(styles.td)}>{p.period_key}</td>
                <td {...stylex.props(styles.td)}>{p.scope}</td>
                <td {...stylex.props(styles.td)}><span {...stylex.props(styles.chip, p.status === 'locked' ? styles.pass : styles.muted)}>{p.status}</span></td>
                <td {...stylex.props(styles.td)}>
                  <button {...stylex.props(styles.ghost)} data-testid="aud-load-recs" onClick={() => loadRecs(p.id)}>show</button>
                  {(recs[p.id] ?? []).map((r, i) => (
                    <span key={i} {...stylex.props(styles.chip, r.status === 'matched' ? styles.pass : styles.fail)} style={{ marginLeft: '0.35rem' }}>{r.type}: {r.status}</span>
                  ))}
                </td>
                <td {...stylex.props(styles.td)}>
                  <button {...stylex.props(styles.ghost)} data-testid="aud-close" onClick={() => doClose(p.id)}>Close</button>{' '}
                  <button {...stylex.props(styles.ghost)} data-testid="aud-lock" onClick={() => doLock(p.id)}>Lock</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Control register */}
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.row)}>
          <button {...stylex.props(styles.button)} data-testid="aud-load-controls" onClick={loadControls}>Load controls</button>
          <span {...stylex.props(styles.label)}>re-performable evidence — run to refresh the result</span>
        </div>
        <div {...stylex.props(styles.section)}>SOX control register</div>
        <table {...stylex.props(styles.table)} data-testid="aud-controls">
          <thead><tr>
            <th {...stylex.props(styles.th)}>Code</th><th {...stylex.props(styles.th)}>Control</th>
            <th {...stylex.props(styles.th)}>Last result</th><th {...stylex.props(styles.th)}>Run</th>
          </tr></thead>
          <tbody>
            {controls.map((c) => (
              <tr key={c.code} data-testid="aud-control-row">
                <td {...stylex.props(styles.td)}>{c.code}</td>
                <td {...stylex.props(styles.td)}>{c.name}</td>
                <td {...stylex.props(styles.td)}><span {...stylex.props(styles.chip, resultChip(c.last_result))} data-testid={`aud-result-${c.code}`}>{c.last_result ?? 'not run'}</span></td>
                <td {...stylex.props(styles.td)}><button {...stylex.props(styles.ghost)} data-testid={`aud-run-${c.code}`} onClick={() => doRun(c.code)}>Run</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Lineage explorer */}
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.section)}>Lineage explorer — figure → invoice → ledger transfers → events → document</div>
        <div {...stylex.props(styles.row)}>
          <span {...stylex.props(styles.label)}>Invoice no</span>
          <input {...stylex.props(styles.input)} data-testid="aud-invoice" value={invoiceNo} onChange={(e) => setInvoiceNo(e.target.value)} style={{ width: '160px' }} />
          <button {...stylex.props(styles.button)} data-testid="aud-load-lineage" onClick={loadLineage}>Trace</button>
        </div>
        {lineage && (
          <div {...stylex.props(styles.pre)} data-testid="aud-lineage">
            invoice {lineage.invoice_no} — total £{lineage.total_inc_vat}{'\n'}
            ledger transfers: {(lineage.ledger_transfers ?? []).length}{'\n'}
            {(lineage.ledger_transfers ?? []).map((t: string) => '  • ' + t).join('\n')}{'\n'}
            events: {(lineage.events ?? []).map((e: any) => e.type).join(', ') || '(none)'}{'\n'}
            document: {lineage.document?.formatted_number ?? '(not generated)'}
          </div>
        )}
      </div>
    </div>
  );
}
