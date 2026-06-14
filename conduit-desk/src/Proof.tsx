import React, { useState } from 'react';
import { asArray } from './state';
import { PageHead, Card, Chip, LoadBar } from './kit/kit';
import { I } from './kit/icons';
import {
  getProofLaws,
  runProofControl,
  getProofTrialBalance,
  getProofJournal,
  getProofAsc606,
  proofTamper,
  proofTamperRestore,
} from './api';

// The Proof Center (spec doc 31 §2) — the interactive formal proof. Five pages: the live Law register
// (controls re-run on click, green earned per click), the Journal Walk (an invoice's DR/CR legs with the
// conservation strip recomputed in THIS browser), Reconcile (the trial balance ties), the ASC-606
// walkthrough, and the non-prod Tamper Sandbox (corrupt → the control names it → restore → green).
// Everything reads live data. Ported to the desk kit (PageHead / Card / Chip / .seg / .tbl), testids preserved.

const arr = <T,>(x: unknown): T[] => asArray<T>(x); // the shared crash-class guard (state.ts), unit-tested

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
    r === 'pass' || (r === null && v === 0) ? 'pass' : r === 'fail' ? 'fail' : 'neutral';
  return (
    <Card title="The engineering formalism (doc 30)" icon={I.shield}
      aux={<button className="btn primary sm" data-testid="proof-load-laws" onClick={load}>{I.refresh({ size: 13 })} Load the law register</button>}>
      <div className="dim" style={{ fontSize: 12.5, marginBottom: 12 }}>statement · mechanism · the artifact that pins it — each control pin re-runs on click; green is earned, never cached</div>
      {laws.length === 0 && <div className="dim" style={{ fontStyle: 'italic' }}>Load to see the fourteen laws.</div>}
      {laws.map((l) => (
        <div key={l.id} className="leg" data-testid="proof-law-row" style={{ borderBottom: '1px solid var(--border)', padding: '11px 0', borderRadius: 0 }}>
          <div style={{ display: 'flex', gap: 10, alignItems: 'baseline', marginBottom: 4 }}>
            <span style={{ color: 'var(--accent)', fontWeight: 700 }}>{l.id}</span>
            <span style={{ fontWeight: 600 }}>{l.title}</span>
          </div>
          <div className="dim" style={{ fontSize: 13, lineHeight: 1.5 }}>{l.statement}</div>
          <div className="row g8" style={{ flexWrap: 'wrap', marginTop: 8 }}>
            {arr(l.pins).map((p: any, i: number) => (
              <span key={i} className="aref" style={{ gap: 7 }}>
                <span className="mono">{p.ref}</span>
                {p.re_performable ? (
                  <>
                    <Chip s={chip(p.last_result, p.last_violations)}><span data-testid={`proof-pin-${p.ref}`}>{p.last_result ?? 'not run'}{p.last_violations != null ? ` (${p.last_violations})` : ''}</span></Chip>
                    <button className="btn sm" data-testid={`proof-run-${p.ref}`} disabled={busy === p.ref} onClick={() => reRun(p.ref)}>
                      {busy === p.ref ? '…' : 'run'}
                    </button>
                  </>
                ) : (
                  <Chip s="neutral">{p.kind}</Chip>
                )}
              </span>
            ))}
          </div>
        </div>
      ))}
    </Card>
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
    <Card title="Journal walk" icon={I.scale}
      aux={<span className="dim" style={{ fontSize: 12 }}>conservation recomputed in your browser</span>}>
      <LoadBar>
        <span className="dim">Invoice no</span>
        <input className="fld" style={{ width: 180 }} data-testid="proof-invoice" value={invoiceNo} onChange={(e) => setInvoiceNo(e.target.value)} placeholder="e.g. INV-000123" />
        <button className="btn primary" data-testid="proof-walk" onClick={load}>Walk the journals</button>
        {err && <span className="dim" data-testid="proof-walk-err">{err}</span>}
      </LoadBar>
      {walk && legs.length === 0 && <div className="dim" style={{ fontStyle: 'italic', marginTop: 10 }} data-testid="proof-walk-empty">No posted legs for that invoice.</div>}
      {legs.length > 0 && (
        <>
          <div className="row g12" style={{ flexWrap: 'wrap', margin: '12px 0' }} data-testid="proof-conservation">
            {cons.map((c: any, i: number) => (
              <div key={i} className="aref" style={{ fontSize: 12.5 }}>
                <span className="mono">{c.currency}</span>{' '}Σ DR {c.debits} = Σ CR {c.credits}{' '}
                <Chip s={c.balanced ? 'pass' : 'fail'}>{c.balanced ? 'balanced' : 'OUT'}</Chip>
              </div>
            ))}
          </div>
          <div className="grid" style={{ gridTemplateColumns: 'repeat(2, 1fr)' }}>
            {legs.map((g: any, i: number) => (
              <div key={i} className="leg" data-testid="proof-leg">
                <div style={{ fontWeight: 700, fontSize: 12, color: g.side === 'debit' ? '#7fb4ff' : '#ffb47f' }}>
                  {g.side === 'debit' ? 'DR' : 'CR'} · {g.amount} {g.currency}
                </div>
                <div>{g.account_key}</div>
                <div className="mono dim">tb {String(g.tb_transfer_id).slice(0, 12)}… · {g.phase}{g.posted ? '' : ' · pending'}</div>
              </div>
            ))}
          </div>
        </>
      )}
    </Card>
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
    <Card title="Reconcile — the trial balance ties" icon={I.scale}
      aux={<span className="dim" style={{ fontSize: 12 }}>from the gl_entry mirror</span>}>
      <LoadBar>
        <span className="dim">Entity id</span>
        <input className="fld" style={{ width: 220 }} data-testid="proof-entity" value={entityId} onChange={(e) => setEntityId(e.target.value)} placeholder="operating entity uuid" />
        <button className="btn primary" data-testid="proof-tb" onClick={load}>Load trial balance</button>
        {err && <span className="dim">{err}</span>}
      </LoadBar>
      {tb && (
        <>
          <div className="row g12" style={{ margin: '12px 0' }}>
            <div className="aref" style={{ fontSize: 12.5 }} data-testid="proof-tb-balanced">
              Σ DR {tb.total_debits} = Σ CR {tb.total_credits}{' '}
              <Chip s={tb.balanced ? 'pass' : 'fail'}>{tb.balanced ? 'balanced' : 'OUT'}</Chip>
            </div>
          </div>
          <div className="tablewrap">
            <table className="tbl">
              <thead><tr>
                <th>Account</th><th>Ccy</th><th className="num">Debits</th><th className="num">Credits</th><th className="num">Balance</th>
              </tr></thead>
              <tbody>
                {accounts.map((a: any, i: number) => (
                  <tr key={i} data-testid="proof-tb-row">
                    <td>{a.account}</td><td>{a.currency}</td>
                    <td className="num">{a.debits}</td><td className="num">{a.credits}</td><td className="num">{a.balance}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </Card>
  );
}

// ASC 606, step by step (doc 31 §2.3 — the surface of doc 29 A3). The five steps for ONE real order, live,
// each citing the laws/controls that pin it; the principal/LRD flash overlay appears only for inter_entity
// holders (the wall is absence). The same row source as the spec matrix, so page and spec cannot drift.
function Asc606({ token }: { token: string }) {
  const [orderId, setOrderId] = useState('');
  const [b, setB] = useState<any | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const load = async () => {
    setErr(null);
    const r = await getProofAsc606(token, orderId.trim());
    if (r.status !== 200) { setErr(r.json?.message ?? `could not load (${r.status})`); setB(null); return; }
    setB(r.json);
  };
  const pins = (p: any) => (
    <div className="row g8" style={{ flexWrap: 'wrap', marginTop: 6 }}>
      {arr<string>(p).map((x, i) => <span key={i} className="aref mono" style={{ fontSize: 10.5 }}>{x}</span>)}
    </div>
  );
  const step = (no: string, key: string, title: string, body: React.ReactNode) => {
    const s = b?.[key];
    if (!s) return null;
    return (
      <div className="step" data-testid={`asc606-${key}`} style={{ borderLeft: '3px solid var(--accent)', paddingLeft: 14, marginBottom: 16 }}>
        <div style={{ display: 'flex', gap: 8, alignItems: 'baseline', marginBottom: 4 }}><span style={{ color: 'var(--accent)', fontWeight: 700, fontSize: 12 }}>STEP {no}</span><span style={{ fontWeight: 600 }}>{title}</span></div>
        <div className="dim" style={{ fontSize: 13, lineHeight: 1.5, marginBottom: 5 }}>{s.explain}</div>
        {body}
        {pins(s.pins)}
      </div>
    );
  };
  const ags = arr(b?.step1_identify_contract?.price_agreements);
  const lines = arr(b?.step2_performance_obligations?.lines);
  const reb = b?.step3_transaction_price?.rebate;
  const alloc = b?.step4_allocation?.line_total_check;
  const recogs = arr(b?.step5_recognition?.recognitions);
  const reversals = arr(b?.step5_recognition?.reversals);
  const flash = b?.step5_recognition_flash;
  return (
    <Card title="ASC 606 — the five steps for one real order" icon={I.layers}
      aux={<span className="dim" style={{ fontSize: 12 }}>each pinned to its law and control</span>}>
      <LoadBar>
        <span className="dim">Order id</span>
        <input className="fld" style={{ width: 260 }} data-testid="asc606-order" value={orderId} onChange={(e) => setOrderId(e.target.value)} placeholder="order uuid" />
        <button className="btn primary" data-testid="asc606-load" onClick={load}>Walk the contract</button>
        {err && <span className="dim" data-testid="asc606-err">{err}</span>}
      </LoadBar>
      {b && (
        <div style={{ marginTop: 14 }}>
          <div style={{ fontSize: 13, marginBottom: 14 }} data-testid="asc606-order-head">
            <strong>{b.order?.order_no}</strong> · {b.order?.customer} · {b.order?.currency} {b.order?.total_inc_vat} inc VAT
          </div>
          {step('1', 'step1_identify_contract', 'Identify the contract',
            <div style={{ fontSize: 13 }}>
              customer PO: {b.step1_identify_contract?.customer_po_number ?? '—'}
              {ags.map((a: any, i: number) => (
                <div key={i}>· agreement <strong>{a.name}</strong> ({a.volume_basis}, {a.status}) — {arr(a.bands).length} band(s)</div>
              ))}
            </div>)}
          {step('2', 'step2_performance_obligations', 'Performance obligations',
            <div style={{ fontSize: 13 }}>
              {lines.map((l: any, i: number) => <div key={i}>· {l.qty}× {l.sku} @ {l.unit_price_ex_vat} — {l.status}{l.tranches > 0 ? ` (${l.tranches} tranche(s))` : ''}</div>)}
            </div>)}
          {step('3', 'step3_transaction_price', 'Transaction price (variable consideration)',
            <div style={{ fontSize: 13 }} data-testid="asc606-rebate">
              {reb && reb !== null
                ? <>retrospective rebate — accrual outstanding {reb.accrual_outstanding}; {arr(reb.settlements).length} settlement(s)</>
                : <span className="dim">no variable consideration on this order</span>}
            </div>)}
          {step('4', 'step4_allocation', 'Allocation',
            <div style={{ fontSize: 13 }}>Σ lines {alloc?.sum_of_lines} = subtotal {alloc?.subtotal_ex_vat} (conserving allocate)</div>)}
          {step('5', 'step5_recognition', 'Recognition at control transfer',
            <div style={{ fontSize: 13 }}>
              {recogs.map((r: any, i: number) => <div key={i}>· recognized {r.invoice_no}: rev {r.revenue_ex_vat} / VAT {r.vat} / COGS {r.cogs} → margin {r.gross_margin}</div>)}
              {reversals.map((r: any, i: number) => <div key={i} style={{ color: 'var(--accent)', fontStyle: 'italic' }}>· reversed ({r.kind}): {r.reason}</div>)}
            </div>)}
          {/* the principal/LRD overlay — present ONLY for inter_entity holders (absence is the wall) */}
          {flash ? (
            <div className="step" data-testid="asc606-flash" style={{ borderLeft: '3px solid var(--accent)', paddingLeft: 14, marginBottom: 16 }}>
              <div style={{ display: 'flex', gap: 8, alignItems: 'baseline', marginBottom: 4 }}><span style={{ color: 'var(--accent)', fontWeight: 700, fontSize: 12 }}>OVERLAY</span><span style={{ fontWeight: 600 }}>Principal / LRD (inter-entity)</span></div>
              <div className="dim" style={{ fontSize: 13, lineHeight: 1.5, marginBottom: 5 }}>{flash.explain}</div>
              {arr(flash.matches).map((m: any, i: number) => (
                <div key={i} style={{ fontSize: 13 }}>· landed {m.landed_total} → transfer {m.transfer_total} (uplift {m.uplift_total}{m.reversed ? ', reversed' : ''})</div>
              ))}
              {pins(flash.pins)}
            </div>
          ) : (
            <div style={{ color: 'var(--accent)', fontStyle: 'italic', fontSize: 13 }} data-testid="asc606-no-flash">Principal/LRD decomposition is not visible at your data layer.</div>
          )}
        </div>
      )}
    </Card>
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
    <Card title="Tamper sandbox (non-prod)" icon={I.shield}
      aux={<span className="dim" style={{ fontSize: 12 }}>break the books on purpose; watch the control name it</span>}>
      <div className="row g8" style={{ flexWrap: 'wrap' }}>
        <button className="btn danger" data-testid="proof-tamper-delete_leg" onClick={() => tamper('delete_leg')}>Delete a journal leg</button>
        <button className="btn danger" data-testid="proof-tamper-orphan_transfer" onClick={() => tamper('orphan_transfer')}>Orphan a transfer</button>
        <button className="btn danger" data-testid="proof-tamper-strip_reversal" onClick={() => tamper('strip_reversal')}>Strip a reversal leg</button>
        <button className="btn primary" data-testid="proof-tamper-restore" onClick={restore}>Restore</button>
      </div>
      {control && (
        <div className="row g8" style={{ marginTop: 12 }} data-testid="proof-tamper-control">
          CTRL-LINEAGE-CLOSURE:{' '}
          <Chip s={control.result === 'pass' ? 'pass' : 'fail'}>{control.result}</Chip>
          <span className="dim">{control.violations} violation(s)</span>
        </div>
      )}
      {log.length > 0 && <div style={{ marginTop: 10 }}>{log.map((l, i) => <div key={i} className="mono dim" style={{ fontSize: 12 }}>{l}</div>)}</div>}
    </Card>
  );
}

export function Proof({ token }: { token: string }) {
  const [page, setPage] = useState<'laws' | 'walk' | 'asc606' | 'reconcile' | 'tamper'>('laws');
  const tab = (id: typeof page, label: string) => (
    <button className={page === id ? 'on' : ''} data-testid={`proof-nav-${id}`} onClick={() => setPage(id)}>{label}</button>
  );
  return (
    <>
      <PageHead
        title="Proof Center"
        sub="The interactive formal proof — laws re-run, journals walk, the trial balance ties, ASC 606 step by step"
        right={
          <div className="seg">
            {tab('laws', 'The Laws')}
            {tab('walk', 'Journal Walk')}
            {tab('asc606', 'ASC 606')}
            {tab('reconcile', 'Reconcile')}
            {tab('tamper', 'Tamper Sandbox')}
          </div>
        }
      />
      {page === 'laws' ? <Laws token={token} />
        : page === 'walk' ? <JournalWalk token={token} />
        : page === 'asc606' ? <Asc606 token={token} />
        : page === 'reconcile' ? <Reconcile token={token} />
        : <Tamper token={token} />}
    </>
  );
}
