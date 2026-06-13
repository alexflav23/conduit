import React, { useState } from 'react';
import * as stylex from '@stylexjs/stylex';
import { colors } from './styles/tokens.stylex';
import { investigatePeriod, lockGroupPeriod, getLineage } from './api';
import { asArray } from './state';

// The period investigation view (M-Period / doc 32 §2): a finance/auditor front door to one accounting
// period. Enter a group period key (e.g. 2026-Q2) and see the close status across every operating entity,
// the netted journals, the business events that drove them, the controls that ran, the reconciliations, the
// documents issued, and one-click lineage entry-points (invoice → CM PO via the Journal Atlas). The group
// roll-up lock (doc 32 §1 / ASC 810) refuses while any operating entity is still open, naming the laggards.
const styles = stylex.create({
  card: { backgroundColor: colors.surface, border: `1px solid ${colors.border}`, borderRadius: '14px', padding: '1.25rem', marginBottom: '1.25rem', maxWidth: '940px' },
  section: { fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.06em', color: colors.muted, marginBottom: '0.6rem' },
  row: { display: 'flex', gap: '0.6rem', alignItems: 'center', marginBottom: '0.6rem', flexWrap: 'wrap' },
  button: { backgroundColor: colors.accent, color: '#fff', border: 'none', borderRadius: '10px', padding: '0.45rem 0.95rem', fontSize: '0.9rem', fontWeight: 600, cursor: 'pointer' },
  ghost: { backgroundColor: 'transparent', color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.35rem 0.8rem', fontWeight: 600, cursor: 'pointer' },
  input: { backgroundColor: colors.bg, color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.45rem 0.6rem', fontSize: '0.9rem', width: '160px' },
  label: { color: colors.muted, fontSize: '0.8rem' },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '0.88rem', fontVariantNumeric: 'tabular-nums' },
  th: { textAlign: 'left', color: colors.muted, fontSize: '0.7rem', textTransform: 'uppercase', letterSpacing: '0.05em', padding: '0.4rem 0.65rem', borderBottom: `1px solid ${colors.border}` },
  td: { padding: '0.4rem 0.65rem', borderBottom: `1px solid ${colors.border}` },
  num: { textAlign: 'right', fontVariantNumeric: 'tabular-nums' },
  chip: { padding: '0.15rem 0.55rem', borderRadius: '999px', fontWeight: 700, fontSize: '0.74rem' },
  ok: { backgroundColor: colors.ok, color: '#06210f' },
  warn: { backgroundColor: colors.warn, color: '#3a2400' },
  muted: { backgroundColor: colors.border, color: colors.text },
  link: { color: colors.accent, cursor: 'pointer', textDecoration: 'underline', background: 'none', border: 'none', font: 'inherit', padding: 0 },
  pre: { fontFamily: 'monospace', fontSize: '0.78rem', whiteSpace: 'pre-wrap', color: colors.text, backgroundColor: colors.bg, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.7rem', overflowX: 'auto' },
  grid: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' },
});

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
    <div>
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.row)}>
          <span {...stylex.props(styles.label)}>Group period</span>
          <input {...stylex.props(styles.input)} data-testid="per-key" value={key} onChange={(e) => setKey(e.target.value)} />
          <button {...stylex.props(styles.button)} data-testid="per-investigate" onClick={investigate}>Investigate</button>
          <button {...stylex.props(styles.ghost)} data-testid="per-lock" onClick={doLock}>Lock group period</button>
          {status && <span {...stylex.props(styles.label)} data-testid="per-status">{status}</span>}
          {data && <span {...stylex.props(styles.chip, data.group_status === 'locked' ? styles.ok : styles.muted)} data-testid="per-group-status">group: {data.group_status}</span>}
        </div>
        {data && (
          <div {...stylex.props(styles.section)}>
            {data.period_key} — {data.from} → {data.to}{' '}
            {allLocked ? '· all operating entities locked' : '· some entities still open (lock gated)'}
          </div>
        )}
      </div>

      {data && (
        <>
          {/* Close status across operating entities — the roll-up board */}
          <div {...stylex.props(styles.card)}>
            <div {...stylex.props(styles.section)}>Entity close status (ASC 810 coterminous group close)</div>
            <table {...stylex.props(styles.table)} data-testid="per-entities">
              <thead><tr>
                <th {...stylex.props(styles.th)}>Entity</th><th {...stylex.props(styles.th)}>Status</th><th {...stylex.props(styles.th)}>Closed at</th>
              </tr></thead>
              <tbody>
                {periods.map((p: any, i: number) => (
                  <tr key={i} data-testid="per-entity-row">
                    <td {...stylex.props(styles.td)}>{p.entity}</td>
                    <td {...stylex.props(styles.td)}><span {...stylex.props(styles.chip, p.status === 'locked' ? styles.ok : styles.muted)}>{p.status}</span></td>
                    <td {...stylex.props(styles.td)}>{p.closed_at ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div {...stylex.props(styles.grid)}>
            {/* Journals */}
            <div {...stylex.props(styles.card)}>
              <div {...stylex.props(styles.section)}>Journals — {data.journals?.leg_count ?? 0} posted legs</div>
              <table {...stylex.props(styles.table)} data-testid="per-journals">
                <thead><tr>
                  <th {...stylex.props(styles.th)}>Account</th><th {...stylex.props(styles.th)}>Side</th><th {...stylex.props(styles.th, styles.num)}>Amount</th>
                </tr></thead>
                <tbody>
                  {journalLines.map((l: any, i: number) => (
                    <tr key={i}>
                      <td {...stylex.props(styles.td)}>{l.account}</td>
                      <td {...stylex.props(styles.td)}>{l.side}</td>
                      <td {...stylex.props(styles.td, styles.num)}>{l.amount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Events */}
            <div {...stylex.props(styles.card)}>
              <div {...stylex.props(styles.section)}>Business events</div>
              <table {...stylex.props(styles.table)} data-testid="per-events">
                <thead><tr><th {...stylex.props(styles.th)}>Event</th><th {...stylex.props(styles.th, styles.num)}>Count</th></tr></thead>
                <tbody>
                  {events.map((e: any, i: number) => (
                    <tr key={i}><td {...stylex.props(styles.td)}>{e.event_type}</td><td {...stylex.props(styles.td, styles.num)}>{e.count}</td></tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Controls */}
            <div {...stylex.props(styles.card)}>
              <div {...stylex.props(styles.section)}>Controls run in period</div>
              <table {...stylex.props(styles.table)} data-testid="per-controls">
                <thead><tr><th {...stylex.props(styles.th)}>Code</th><th {...stylex.props(styles.th)}>Result</th><th {...stylex.props(styles.th, styles.num)}>Violations</th></tr></thead>
                <tbody>
                  {controls.map((c: any, i: number) => (
                    <tr key={i}>
                      <td {...stylex.props(styles.td)}>{c.code}</td>
                      <td {...stylex.props(styles.td)}><span {...stylex.props(styles.chip, c.result === 'pass' ? styles.ok : styles.warn)}>{c.result}</span></td>
                      <td {...stylex.props(styles.td, styles.num)}>{c.violations}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Reconciliations */}
            <div {...stylex.props(styles.card)}>
              <div {...stylex.props(styles.section)}>Reconciliations</div>
              <table {...stylex.props(styles.table)} data-testid="per-recs">
                <thead><tr><th {...stylex.props(styles.th)}>Type</th><th {...stylex.props(styles.th)}>Status</th><th {...stylex.props(styles.th)}>Signed off</th></tr></thead>
                <tbody>
                  {recs.map((r: any, i: number) => (
                    <tr key={i}>
                      <td {...stylex.props(styles.td)}>{r.type}</td>
                      <td {...stylex.props(styles.td)}><span {...stylex.props(styles.chip, r.status === 'matched' ? styles.ok : styles.warn)}>{r.status}</span></td>
                      <td {...stylex.props(styles.td)}>{r.signed_off ? '✓' : '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          {/* Documents + lineage entry points */}
          <div {...stylex.props(styles.card)}>
            <div {...stylex.props(styles.section)}>Documents issued · lineage entry-points (click an invoice to trace to its CM PO)</div>
            <div {...stylex.props(styles.row)}>
              {docs.map((d: any, i: number) => (
                <span key={i} {...stylex.props(styles.chip, styles.muted)} data-testid="per-doc">{d.kind} {d.number}</span>
              ))}
            </div>
            <div {...stylex.props(styles.row)}>
              {lineagePoints.map((l: any, i: number) => (
                <button key={i} {...stylex.props(styles.link)} data-testid="per-lineage-link" onClick={() => trace(l.invoice_no)}>{l.invoice_no}</button>
              ))}
            </div>
            {lineage && (
              <div {...stylex.props(styles.pre)} data-testid="per-lineage">
                invoice {lineage.invoice_no} — total £{lineage.total_inc_vat}{'\n'}
                ledger transfers: {asArray(lineage.ledger_transfers).length}{'\n'}
                {asArray<string>(lineage.ledger_transfers).map((t) => '  • ' + t).join('\n')}{'\n'}
                document: {lineage.document?.formatted_number ?? '(not generated)'}
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}
