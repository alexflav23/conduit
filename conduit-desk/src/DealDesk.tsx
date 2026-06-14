import React, { useState } from 'react';
import { listExceptions, getException, submitNarrative, decide } from './api';
import { PageHead, Card, Chip } from './kit/kit';
import { I } from './kit/icons';

// Deal Desk (M4/M-Pricing / spec/ui/03-dealdesk.md): the maker-checker for governed price-tier requests.
// An out-of-band line lands here as pending_ceo; the agent writes the proposal narrative, the CEO (single
// approver) decides — approval mints the tier and releases the held order. Ported to the desk kit; the
// price-band strip becomes four metric cells. testids preserved.

export function DealDesk({ token }: { token: string }) {
  const [exc, setExc] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  const [justification, setJustification] = useState('');
  const [volume, setVolume] = useState('500');
  const [denomination, setDenomination] = useState('P50');
  const [strategic, setStrategic] = useState('');
  const [notes, setNotes] = useState('');
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

  const cell = (label: string, value: React.ReactNode, testid: string) => (
    <div className="metric" style={{ background: 'var(--bg-2)', border: '1px solid var(--border)', borderRadius: 10, padding: '10px 12px' }}>
      <div className="ml">{label}</div>
      <div className="mv" style={{ fontSize: 20, marginTop: 4 }} data-testid={testid}>{value}</div>
    </div>
  );

  return (
    <>
      <PageHead title="Deal Desk" sub="Governed price-tier requests — maker-checker; CEO is the single approver" />
      <Card style={{ maxWidth: 640 }}>
        <div className="row g8">
          <button className="btn primary" data-testid="load-pending" onClick={loadPending}>{I.flag({ size: 14 })} Load deal-desk queue</button>
          {error && <span className="dim" data-testid="dd-error" style={{ color: 'var(--danger)' }}>{error}</span>}
        </div>
      </Card>

      {exc && (
        <Card title="Price deviation" icon={I.flag} style={{ maxWidth: 640 }}
          aux={<Chip s={exc.status === 'approved' ? 'approved' : exc.status === 'rejected' ? 'rejected' : 'pending_ceo'}><span data-testid="exc-status">{exc.status}</span></Chip>}>
          <div data-testid="exception">
            <div className="grid" style={{ gridTemplateColumns: 'repeat(4, 1fr)', marginBottom: 14 }}>
              {cell('List (ex-VAT)', exc.list_price ?? '—', 'exc-list-price')}
              {cell('ADLP band', (exc.max_discount_pct ?? '—') + '%', 'exc-band')}
              {cell('Requested', exc.requested_price ?? '—', 'exc-requested')}
              {cell('Deviation', deviation + '%', 'exc-deviation')}
            </div>
            <div style={{ marginBottom: 16 }}><Chip s="exception"><span data-testid="exc-chip">Out of band — CEO approval required</span></Chip></div>

            <div className="dim" style={{ fontSize: 12, textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 10 }}>Agent proposal</div>
            <div className="kv" style={{ gridTemplateColumns: '150px 1fr', rowGap: 10, marginBottom: 10 }}>
              <span className="k">Volume expectation</span>
              <div className="row g8">
                <input className="fld" style={{ flexGrow: 1 }} data-testid="narr-volume" value={volume} onChange={(e) => setVolume(e.target.value)} />
                <select className="fld sel" data-testid="narr-denomination" value={denomination} onChange={(e) => setDenomination(e.target.value)}>
                  <option>P20</option><option>P50</option><option>P80</option>
                </select>
              </div>
              <span className="k">Strategic importance</span>
              <input className="fld" data-testid="narr-strategic" value={strategic} onChange={(e) => setStrategic(e.target.value)} />
            </div>
            <textarea className="fld" style={{ width: '100%', minHeight: 64, marginBottom: 8 }} data-testid="narr-justification" placeholder="Narrative — the value you see in this deal" value={justification} onChange={(e) => setJustification(e.target.value)} />
            <textarea className="fld" style={{ width: '100%', minHeight: 64, marginBottom: 12 }} data-testid="narr-notes" placeholder="Notes" value={notes} onChange={(e) => setNotes(e.target.value)} />
            <button className="btn primary" data-testid="submit-narrative" onClick={onSubmit}>Submit proposal</button>

            <div className="dim" style={{ fontSize: 12, textTransform: 'uppercase', letterSpacing: '0.06em', margin: '20px 0 10px' }}>CEO decision (single approver)</div>
            <div className="kv" style={{ gridTemplateColumns: '150px 1fr', rowGap: 10, marginBottom: 12 }}>
              <span className="k">Approval memo</span>
              <input className="fld" data-testid="dec-memo" value={memo} onChange={(e) => setMemo(e.target.value)} />
              <span className="k">Valid until</span>
              <input className="fld" data-testid="dec-valid-to" value={validTo} onChange={(e) => setValidTo(e.target.value)} />
              <span className="k">Min volume</span>
              <input className="fld" data-testid="dec-volume-min" value={volumeMin} onChange={(e) => setVolumeMin(e.target.value)} />
            </div>
            <div className="row g8">
              <button className="btn primary" data-testid="approve-btn" onClick={() => onDecision('approve')}>Approve</button>
              <button className="btn danger" data-testid="reject-btn" onClick={() => onDecision('reject')}>Reject</button>
            </div>
          </div>
        </Card>
      )}
    </>
  );
}
