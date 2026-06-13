import React, { useState } from 'react';
import * as stylex from '@stylexjs/stylex';
import { colors } from './styles/tokens.stylex';
import {
  getProofLaws,
  runProofControl,
  getProofTrialBalance,
  getProofJournal,
  proofTamper,
  proofTamperRestore,
} from './api';

// The Proof Center (spec doc 31 §2) — the interactive formal proof. Four pages: the live Law register
// (controls re-run on click, green earned per click), the Journal Walk (an invoice's DR/CR legs with the
// conservation strip recomputed in THIS browser), Reconcile (the trial balance ties), and the non-prod
// Tamper Sandbox (corrupt → the control names it → restore → green). Everything reads live data.
const styles = stylex.create({
  subnav: { display: 'flex', gap: '0.4rem', marginBottom: '1rem' },
  subtab: { backgroundColor: 'transparent', color: colors.muted, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.35rem 0.85rem', fontWeight: 600, cursor: 'pointer', fontSize: '0.85rem' },
  subtabActive: { backgroundColor: colors.surface, color: colors.text, borderColor: colors.accent },
  card: { backgroundColor: colors.surface, border: `1px solid ${colors.border}`, borderRadius: '14px', padding: '1.25rem', marginBottom: '1.25rem', maxWidth: '960px' },
  section: { fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.06em', color: colors.muted, marginBottom: '0.7rem' },
  row: { display: 'flex', gap: '0.6rem', alignItems: 'center', marginBottom: '0.8rem', flexWrap: 'wrap' },
  button: { backgroundColor: colors.accent, color: '#fff', border: 'none', borderRadius: '10px', padding: '0.45rem 0.95rem', fontSize: '0.9rem', fontWeight: 600, cursor: 'pointer' },
  ghost: { backgroundColor: 'transparent', color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.3rem 0.7rem', fontWeight: 600, cursor: 'pointer', fontSize: '0.82rem' },
  danger: { backgroundColor: 'transparent', color: colors.warn, border: `1px solid ${colors.warn}`, borderRadius: '8px', padding: '0.3rem 0.7rem', fontWeight: 600, cursor: 'pointer', fontSize: '0.82rem' },
  input: { backgroundColor: colors.bg, color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.45rem 0.6rem', fontSize: '0.9rem', width: '180px' },
  label: { color: colors.muted, fontSize: '0.8rem' },
  law: { borderBottom: `1px solid ${colors.border}`, padding: '0.7rem 0' },
  lawHead: { display: 'flex', gap: '0.6rem', alignItems: 'baseline', marginBottom: '0.25rem' },
  lawId: { color: colors.accent, fontWeight: 700, fontSize: '0.95rem' },
  lawTitle: { fontWeight: 600 },
  lawText: { color: colors.muted, fontSize: '0.84rem', lineHeight: 1.5 },
  pins: { display: 'flex', gap: '0.4rem', flexWrap: 'wrap', marginTop: '0.45rem' },
  pin: { display: 'flex', gap: '0.35rem', alignItems: 'center', border: `1px solid ${colors.border}`, borderRadius: '999px', padding: '0.12rem 0.5rem', fontSize: '0.74rem' },
  chip: { padding: '0.12rem 0.5rem', borderRadius: '999px', fontWeight: 700, fontSize: '0.72rem' },
  pass: { backgroundColor: colors.ok, color: '#06210f' },
  fail: { backgroundColor: colors.warn, color: '#3a2400' },
  muted: { backgroundColor: colors.border, color: colors.text },
  legGrid: { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0.6rem' },
  leg: { border: `1px solid ${colors.border}`, borderRadius: '10px', padding: '0.6rem 0.75rem', fontSize: '0.84rem' },
  legSide: { fontWeight: 700, fontSize: '0.72rem', textTransform: 'uppercase', letterSpacing: '0.05em' },
  dr: { color: '#7fb4ff' },
  cr: { color: '#ffb47f' },
  mono: { fontFamily: 'monospace', fontSize: '0.78rem', color: colors.muted },
  strip: { display: 'flex', gap: '1rem', flexWrap: 'wrap', marginBottom: '0.8rem' },
  stripItem: { border: `1px solid ${colors.border}`, borderRadius: '10px', padding: '0.5rem 0.8rem', fontSize: '0.85rem' },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '0.86rem', fontVariantNumeric: 'tabular-nums' },
  th: { textAlign: 'left', color: colors.muted, fontSize: '0.7rem', textTransform: 'uppercase', letterSpacing: '0.05em', padding: '0.35rem 0.6rem', borderBottom: `1px solid ${colors.border}` },
  thNum: { textAlign: 'right', color: colors.muted, fontSize: '0.7rem', textTransform: 'uppercase', letterSpacing: '0.05em', padding: '0.35rem 0.6rem', borderBottom: `1px solid ${colors.border}` },
  td: { padding: '0.32rem 0.6rem', borderBottom: `1px solid ${colors.border}` },
  tdNum: { padding: '0.32rem 0.6rem', borderBottom: `1px solid ${colors.border}`, textAlign: 'right' },
  empty: { color: colors.muted, fontSize: '0.85rem', fontStyle: 'italic' },
});

const arr = (x: any) => (Array.isArray(x) ? x : []);

function Laws({ token }: { token: string }) {
  const [laws, setLaws] = useState<any[]>([]);
  const [busy, setBusy] = useState<string | null>(null);
  const load = async () => setLaws(arr((await getProofLaws(token)).json?.laws));
  const reRun = async (code: string) => {
    setBusy(code);
    await runProofControl(token, code);
    await load();
    setBusy(null);
  };
  const chip = (r: string | null, v: number | null) =>
    r === 'pass' || (r === null && v === 0) ? styles.pass : r === 'fail' ? styles.fail : styles.muted;
  return (
    <div {...stylex.props(styles.card)}>
      <div {...stylex.props(styles.row)}>
        <button {...stylex.props(styles.button)} data-testid="proof-load-laws" onClick={load}>Load the law register</button>
        <span {...stylex.props(styles.label)}>each control pin re-runs on click — green is earned, never cached</span>
      </div>
      <div {...stylex.props(styles.section)}>The engineering formalism (doc 30) — statement · mechanism · the artifact that pins it</div>
      {laws.length === 0 && <div {...stylex.props(styles.empty)}>Load to see the fourteen laws.</div>}
      {laws.map((l) => (
        <div key={l.id} {...stylex.props(styles.law)} data-testid="proof-law-row">
          <div {...stylex.props(styles.lawHead)}>
            <span {...stylex.props(styles.lawId)}>{l.id}</span>
            <span {...stylex.props(styles.lawTitle)}>{l.title}</span>
          </div>
          <div {...stylex.props(styles.lawText)}>{l.statement}</div>
          <div {...stylex.props(styles.pins)}>
            {arr(l.pins).map((p: any, i: number) => (
              <span key={i} {...stylex.props(styles.pin)}>
                <span {...stylex.props(styles.mono)}>{p.ref}</span>
                {p.re_performable ? (
                  <>
                    <span {...stylex.props(styles.chip, chip(p.last_result, p.last_violations))} data-testid={`proof-pin-${p.ref}`}>
                      {p.last_result ?? 'not run'}{p.last_violations != null ? ` (${p.last_violations})` : ''}
                    </span>
                    <button {...stylex.props(styles.ghost)} data-testid={`proof-run-${p.ref}`} disabled={busy === p.ref} onClick={() => reRun(p.ref)}>
                      {busy === p.ref ? '…' : 'run'}
                    </button>
                  </>
                ) : (
                  <span {...stylex.props(styles.chip, styles.muted)}>{p.kind}</span>
                )}
              </span>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

function JournalWalk({ token }: { token: string }) {
  const [invoiceNo, setInvoiceNo] = useState('');
  const [walk, setWalk] = useState<any | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const load = async () => {
    setErr(null);
    const r = await getProofJournal(token, invoiceNo.trim());
    if (r.status !== 200) { setErr(`could not load (${r.status})`); setWalk(null); return; }
    setWalk(r.json);
  };
  const legs = arr(walk?.legs);
  const cons = arr(walk?.conservation);
  return (
    <div {...stylex.props(styles.card)}>
      <div {...stylex.props(styles.section)}>Journal walk — an invoice's complete double-entry, conservation recomputed in your browser</div>
      <div {...stylex.props(styles.row)}>
        <span {...stylex.props(styles.label)}>Invoice no</span>
        <input {...stylex.props(styles.input)} data-testid="proof-invoice" value={invoiceNo} onChange={(e) => setInvoiceNo(e.target.value)} placeholder="e.g. INV-000123" />
        <button {...stylex.props(styles.button)} data-testid="proof-walk" onClick={load}>Walk the journals</button>
        {err && <span {...stylex.props(styles.label)} data-testid="proof-walk-err">{err}</span>}
      </div>
      {walk && legs.length === 0 && <div {...stylex.props(styles.empty)} data-testid="proof-walk-empty">No posted legs for that invoice.</div>}
      {legs.length > 0 && (
        <>
          <div {...stylex.props(styles.strip)} data-testid="proof-conservation">
            {cons.map((c: any, i: number) => (
              <div key={i} {...stylex.props(styles.stripItem)}>
                <span {...stylex.props(styles.mono)}>{c.currency}</span>{' '}
                Σ DR {c.debits} = Σ CR {c.credits}{' '}
                <span {...stylex.props(styles.chip, c.balanced ? styles.pass : styles.fail)}>{c.balanced ? 'balanced' : 'OUT'}</span>
              </div>
            ))}
          </div>
          <div {...stylex.props(styles.legGrid)}>
            {legs.map((g: any, i: number) => (
              <div key={i} {...stylex.props(styles.leg)} data-testid="proof-leg">
                <div {...stylex.props(styles.legSide, g.side === 'debit' ? styles.dr : styles.cr)}>
                  {g.side === 'debit' ? 'DR' : 'CR'} · {g.amount} {g.currency}
                </div>
                <div>{g.account_key}</div>
                <div {...stylex.props(styles.mono)}>tb {String(g.tb_transfer_id).slice(0, 12)}… · {g.phase}{g.posted ? '' : ' · pending'}</div>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

function Reconcile({ token }: { token: string }) {
  const [entityId, setEntityId] = useState('');
  const [tb, setTb] = useState<any | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const load = async () => {
    setErr(null);
    const r = await getProofTrialBalance(token, entityId.trim());
    if (r.status !== 200) { setErr(`could not load (${r.status})`); setTb(null); return; }
    setTb(r.json);
  };
  const accounts = arr(tb?.accounts);
  return (
    <div {...stylex.props(styles.card)}>
      <div {...stylex.props(styles.section)}>Reconcile — the trial balance ties, debits to credits, from the gl_entry mirror</div>
      <div {...stylex.props(styles.row)}>
        <span {...stylex.props(styles.label)}>Entity id</span>
        <input {...stylex.props(styles.input)} data-testid="proof-entity" value={entityId} onChange={(e) => setEntityId(e.target.value)} placeholder="operating entity uuid" />
        <button {...stylex.props(styles.button)} data-testid="proof-tb" onClick={load}>Load trial balance</button>
        {err && <span {...stylex.props(styles.label)}>{err}</span>}
      </div>
      {tb && (
        <>
          <div {...stylex.props(styles.strip)}>
            <div {...stylex.props(styles.stripItem)} data-testid="proof-tb-balanced">
              Σ DR {tb.total_debits} = Σ CR {tb.total_credits}{' '}
              <span {...stylex.props(styles.chip, tb.balanced ? styles.pass : styles.fail)}>{tb.balanced ? 'balanced' : 'OUT'}</span>
            </div>
          </div>
          <table {...stylex.props(styles.table)}>
            <thead><tr>
              <th {...stylex.props(styles.th)}>Account</th><th {...stylex.props(styles.th)}>Ccy</th>
              <th {...stylex.props(styles.thNum)}>Debits</th><th {...stylex.props(styles.thNum)}>Credits</th><th {...stylex.props(styles.thNum)}>Balance</th>
            </tr></thead>
            <tbody>
              {accounts.map((a: any, i: number) => (
                <tr key={i} data-testid="proof-tb-row">
                  <td {...stylex.props(styles.td)}>{a.account}</td>
                  <td {...stylex.props(styles.td)}>{a.currency}</td>
                  <td {...stylex.props(styles.tdNum)}>{a.debits}</td>
                  <td {...stylex.props(styles.tdNum)}>{a.credits}</td>
                  <td {...stylex.props(styles.tdNum)}>{a.balance}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}

// The Tamper Sandbox (doc 31 §2.5) — non-prod only; in prod the endpoints 404 and the buttons surface that.
function Tamper({ token }: { token: string }) {
  const [log, setLog] = useState<string[]>([]);
  const [control, setControl] = useState<any | null>(null);
  const append = (s: string) => setLog((l) => [s, ...l].slice(0, 8));
  const tamper = async (kind: string) => {
    const r = await proofTamper(token, kind);
    if (r.status === 404) { append(`${kind}: not available (prod has no tamper surface)`); return; }
    if (r.status !== 200) { append(`${kind}: ${r.json?.message ?? r.status}`); return; }
    append(`tampered: ${kind}`);
    const c = await runProofControl(token, 'CTRL-LINEAGE-CLOSURE');
    setControl(c.json);
  };
  const restore = async () => {
    await proofTamperRestore(token);
    const c = await runProofControl(token, 'CTRL-LINEAGE-CLOSURE');
    setControl(c.json);
    append('restored');
  };
  return (
    <div {...stylex.props(styles.card)}>
      <div {...stylex.props(styles.section)}>Tamper sandbox (non-prod) — break the books on purpose; watch the control name it</div>
      <div {...stylex.props(styles.row)}>
        <button {...stylex.props(styles.danger)} data-testid="proof-tamper-delete_leg" onClick={() => tamper('delete_leg')}>Delete a journal leg</button>
        <button {...stylex.props(styles.danger)} data-testid="proof-tamper-orphan_transfer" onClick={() => tamper('orphan_transfer')}>Orphan a transfer</button>
        <button {...stylex.props(styles.danger)} data-testid="proof-tamper-strip_reversal" onClick={() => tamper('strip_reversal')}>Strip a reversal leg</button>
        <button {...stylex.props(styles.button)} data-testid="proof-tamper-restore" onClick={restore}>Restore</button>
      </div>
      {control && (
        <div {...stylex.props(styles.row)} data-testid="proof-tamper-control">
          CTRL-LINEAGE-CLOSURE:{' '}
          <span {...stylex.props(styles.chip, control.result === 'pass' ? styles.pass : styles.fail)}>{control.result}</span>
          <span {...stylex.props(styles.label)}>{control.violations} violation(s)</span>
        </div>
      )}
      {log.map((l, i) => <div key={i} {...stylex.props(styles.mono)}>{l}</div>)}
    </div>
  );
}

export function Proof({ token }: { token: string }) {
  const [page, setPage] = useState<'laws' | 'walk' | 'reconcile' | 'tamper'>('laws');
  const tab = (id: typeof page, label: string) => (
    <button {...stylex.props(styles.subtab, page === id && styles.subtabActive)} data-testid={`proof-nav-${id}`} onClick={() => setPage(id)}>{label}</button>
  );
  return (
    <div>
      <div {...stylex.props(styles.subnav)}>
        {tab('laws', 'The Laws')}
        {tab('walk', 'Journal Walk')}
        {tab('reconcile', 'Reconcile')}
        {tab('tamper', 'Tamper Sandbox')}
      </div>
      {page === 'laws' ? <Laws token={token} />
        : page === 'walk' ? <JournalWalk token={token} />
        : page === 'reconcile' ? <Reconcile token={token} />
        : <Tamper token={token} />}
    </div>
  );
}
