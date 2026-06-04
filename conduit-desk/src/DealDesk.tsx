import React, { useState } from 'react';
import * as stylex from '@stylexjs/stylex';
import { colors } from './styles/tokens.stylex';
import { listExceptions, getException, submitNarrative, decide } from './api';

const styles = stylex.create({
  card: { backgroundColor: colors.surface, border: `1px solid ${colors.border}`, borderRadius: '14px', padding: '1.25rem', marginBottom: '1.25rem', maxWidth: '620px' },
  section: { fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.06em', color: colors.muted, marginBottom: '0.6rem' },
  row: { display: 'flex', gap: '0.75rem', alignItems: 'center', marginBottom: '0.6rem', flexWrap: 'wrap' },
  label: { color: colors.muted, fontSize: '0.8rem', width: '150px' },
  input: { backgroundColor: colors.bg, color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.5rem 0.7rem', fontSize: '0.95rem', flexGrow: 1 },
  textarea: { backgroundColor: colors.bg, color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.5rem 0.7rem', fontSize: '0.95rem', width: '100%', minHeight: '64px' },
  button: { backgroundColor: colors.accent, color: '#fff', border: 'none', borderRadius: '10px', padding: '0.6rem 1.1rem', fontSize: '0.95rem', fontWeight: 600, cursor: 'pointer', marginRight: '0.75rem' },
  ghost: { backgroundColor: 'transparent', color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '10px', padding: '0.6rem 1.1rem', fontWeight: 600, cursor: 'pointer', marginRight: '0.75rem' },
  // Price banding — the visual heart of the desk
  band: { display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '0.5rem', marginBottom: '0.75rem' },
  bandCell: { backgroundColor: colors.bg, border: `1px solid ${colors.border}`, borderRadius: '10px', padding: '0.6rem' },
  bandLabel: { color: colors.muted, fontSize: '0.7rem' },
  bandValue: { fontSize: '1.15rem', fontWeight: 700 },
  exceptionChip: { backgroundColor: colors.warn, color: '#3a2400', padding: '0.25rem 0.7rem', borderRadius: '999px', fontWeight: 700, fontSize: '0.85rem' },
  approvedChip: { backgroundColor: colors.ok, color: '#06210f', padding: '0.25rem 0.7rem', borderRadius: '999px', fontWeight: 700, fontSize: '0.85rem' },
  pendingChip: { backgroundColor: colors.border, color: colors.text, padding: '0.25rem 0.7rem', borderRadius: '999px', fontWeight: 700, fontSize: '0.85rem' },
});

export function DealDesk({ token }: { token: string }) {
  const [exc, setExc] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  // agent narrative
  const [justification, setJustification] = useState('');
  const [volume, setVolume] = useState('500');
  const [denomination, setDenomination] = useState('P50');
  const [strategic, setStrategic] = useState('');
  const [notes, setNotes] = useState('');
  // ceo decision
  const [memo, setMemo] = useState('');
  const [validTo, setValidTo] = useState('2026-09-01T00:00:00Z');
  const [volumeMin, setVolumeMin] = useState('400');

  const loadPending = async () => {
    setError(null);
    const { status, json } = await listExceptions(token, 'pending_ceo');
    if (status === 200 && Array.isArray(json) && json.length > 0) setExc(json[0]);
    else if (status === 200) { const all = await listExceptions(token, 'approved'); setExc(Array.isArray(all.json) && all.json.length ? all.json[0] : null); }
    else setError(`Load failed (${status})`);
  };
  const reload = async (id: string) => { const { json } = await getException(token, id); setExc(json); };

  const onSubmit = async () => {
    setError(null);
    const { status, json } = await submitNarrative(token, exc.id, { justification, volumeExpectation: parseInt(volume, 10), volumeDenomination: denomination, strategicImportance: strategic, notes });
    if (status === 200) await reload(exc.id); else setError(`Submit failed (${status}): ${json?.message ?? ''}`);
  };
  const onDecision = async (decision: 'approve' | 'reject') => {
    setError(null);
    const { status, json } = await decide(token, exc.id, { decision, memo, validFrom: '2026-06-01T00:00:00Z', validTo, volumeMin: parseInt(volumeMin, 10) });
    if (status === 200) await reload(exc.id); else setError(`Decision failed (${status}): ${json?.message ?? ''}`);
  };

  const deviation = exc?.list_price && exc?.requested_price
    ? (((parseFloat(exc.list_price) - parseFloat(exc.requested_price)) / parseFloat(exc.list_price)) * 100).toFixed(2)
    : '—';

  return (
    <div>
      <div {...stylex.props(styles.card)}>
        <button {...stylex.props(styles.button)} data-testid="load-pending" onClick={loadPending}>Load deal-desk queue</button>
        {error && <span data-testid="dd-error" style={{ color: colors.warn }}>{error}</span>}
      </div>

      {exc && (
        <div {...stylex.props(styles.card)} data-testid="exception">
          <div {...stylex.props(styles.row)}>
            <span {...stylex.props(styles.section)}>Price deviation</span>
            <span {...stylex.props(exc.status === 'approved' ? styles.approvedChip : exc.status === 'rejected' ? styles.exceptionChip : styles.pendingChip)} data-testid="exc-status">{exc.status}</span>
          </div>
          <div {...stylex.props(styles.band)}>
            <div {...stylex.props(styles.bandCell)}><div {...stylex.props(styles.bandLabel)}>List (ex-VAT)</div><div {...stylex.props(styles.bandValue)} data-testid="exc-list-price">{exc.list_price ?? '—'}</div></div>
            <div {...stylex.props(styles.bandCell)}><div {...stylex.props(styles.bandLabel)}>ADLP band</div><div {...stylex.props(styles.bandValue)} data-testid="exc-band">{exc.max_discount_pct ?? '—'}%</div></div>
            <div {...stylex.props(styles.bandCell)}><div {...stylex.props(styles.bandLabel)}>Requested</div><div {...stylex.props(styles.bandValue)} data-testid="exc-requested">{exc.requested_price ?? '—'}</div></div>
            <div {...stylex.props(styles.bandCell)}><div {...stylex.props(styles.bandLabel)}>Deviation</div><div {...stylex.props(styles.bandValue)} data-testid="exc-deviation">{deviation}%</div></div>
          </div>
          <div {...stylex.props(styles.row)}><span {...stylex.props(styles.exceptionChip)} data-testid="exc-chip">Out of band — CEO approval required</span></div>

          <div {...stylex.props(styles.section)} style={{ marginTop: '1rem' }}>Agent proposal</div>
          <div {...stylex.props(styles.row)}><span {...stylex.props(styles.label)}>Volume expectation</span>
            <input {...stylex.props(styles.input)} data-testid="narr-volume" value={volume} onChange={(e) => setVolume(e.target.value)} />
            <select {...stylex.props(styles.input)} data-testid="narr-denomination" value={denomination} onChange={(e) => setDenomination(e.target.value)}>
              <option>P20</option><option>P50</option><option>P80</option>
            </select>
          </div>
          <div {...stylex.props(styles.row)}><span {...stylex.props(styles.label)}>Strategic importance</span><input {...stylex.props(styles.input)} data-testid="narr-strategic" value={strategic} onChange={(e) => setStrategic(e.target.value)} /></div>
          <textarea {...stylex.props(styles.textarea)} data-testid="narr-justification" placeholder="Narrative — the value you see in this deal" value={justification} onChange={(e) => setJustification(e.target.value)} />
          <textarea {...stylex.props(styles.textarea)} data-testid="narr-notes" placeholder="Notes" value={notes} onChange={(e) => setNotes(e.target.value)} />
          <button {...stylex.props(styles.button)} data-testid="submit-narrative" onClick={onSubmit}>Submit proposal</button>

          <div {...stylex.props(styles.section)} style={{ marginTop: '1.25rem' }}>CEO decision (single approver)</div>
          <div {...stylex.props(styles.row)}><span {...stylex.props(styles.label)}>Approval memo</span><input {...stylex.props(styles.input)} data-testid="dec-memo" value={memo} onChange={(e) => setMemo(e.target.value)} /></div>
          <div {...stylex.props(styles.row)}><span {...stylex.props(styles.label)}>Valid until</span><input {...stylex.props(styles.input)} data-testid="dec-valid-to" value={validTo} onChange={(e) => setValidTo(e.target.value)} /></div>
          <div {...stylex.props(styles.row)}><span {...stylex.props(styles.label)}>Min volume</span><input {...stylex.props(styles.input)} data-testid="dec-volume-min" value={volumeMin} onChange={(e) => setVolumeMin(e.target.value)} /></div>
          <button {...stylex.props(styles.button)} data-testid="approve-btn" onClick={() => onDecision('approve')}>Approve</button>
          <button {...stylex.props(styles.ghost)} data-testid="reject-btn" onClick={() => onDecision('reject')}>Reject</button>
        </div>
      )}
    </div>
  );
}
