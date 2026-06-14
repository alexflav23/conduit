import React, { useState } from 'react';
import { useApi, request } from './lib/query';
import { ApiError } from './lib/client';
import {
  PageHead, Card, Chip, Drawer, Money, AuditRef, LayerNote,
  EmptyRow, SkeletonRow, Skeleton, num,
} from './kit/kit';
import { I } from './kit/icons';

// Auditability Center (doc 20 D15–D18, doc 14 §6 / spec/ui/10-auditability.md). Four screens behind one tab:
// the period CLOSE board (open → closed → locked, lock gated on clean reconciliations + SoD), automated
// RECONCILIATIONS, the re-performable SOX CONTROL register (pass/fail HISTORY, not just last run), and the
// LINEAGE explorer (figure → invoice → ledger transfers → events → document). Every figure is layer-aware:
// a withheld layer collapses (never £0.00). Auto-loads on mount / ctx change — no manual load buttons.
//
// Real endpoints (api AuditRoutes):
//   GET  /api/v1/finance/periods[?entity=<uuid>]                   -> [{ id, period_key, scope, status, closed_by }]
//   GET  /api/v1/finance/periods/{id}/reconciliations             -> [{ id, type, expected, actual, variance, status }]
//   POST /api/v1/finance/periods/{id}/close                       -> { id, status:"closed" } | 422 {message}
//   POST /api/v1/finance/periods/{id}/lock                        -> { id, status:"locked" } | 422 {message}
//   GET  /api/v1/finance/controls                                 -> [{ code, name, framework, owner, frequency, last_result, ... }]
//   POST /api/v1/finance/controls/{code}/run                      -> { code, result, violations }
//   GET  /api/v1/finance/lineage?invoice_no=<no>                  -> { invoice_no, total_inc_vat, ledger_transfers[], events[], document } | { error }
// periods/lineage gate on view:accounting_period; reconciliations on view:reconciliation; controls on view:control.

type Tab = 'close' | 'recon' | 'controls' | 'lineage';
const TABS: [Tab, string][] = [
  ['close', 'Close board'],
  ['recon', 'Reconciliations'],
  ['controls', 'Control register'],
  ['lineage', 'Lineage'],
];

interface Props { role: any; ctx: any; toast: (m: string, k?: string) => void }

const isUuid = (s: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-/i.test(s || '');
const entityParam = (ctx: any): string => {
  const e = ctx?.entity;
  if (typeof e === 'string' && isUuid(e)) return `?entity=${encodeURIComponent(e)}`;
  return '';
};
const forbidden = (e: unknown): e is ApiError => e instanceof ApiError && e.forbidden;
const notImpl = (e: unknown): e is ApiError => e instanceof ApiError && e.notImplemented;

function NotAvailable({ testid }: { testid?: string }) {
  return (
    <div className="card" data-testid={testid} style={{ textAlign: 'center', padding: '40px 24px' }}>
      <div style={{ marginBottom: 10, opacity: 0.5 }}>{I.alert({ size: 26 })}</div>
      <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>Not available in this environment yet</div>
      <div className="dim" style={{ fontSize: 12.5, marginTop: 6 }}>This surface has no backing endpoint here.</div>
    </div>
  );
}

export function Auditability({ role, ctx, toast }: Props) {
  const [tab, setTab] = useState<Tab>('close');
  return (
    <div className="page">
      <PageHead
        crumb="Auditability Center"
        title="Close, control &amp; lineage"
        sub="Period close that locks only over clean reconciliations, the re-performable SOX control register, and figure-to-document lineage — the prove-this-number tool."
      />
      <div className="seg" style={{ marginBottom: 18 }} data-testid="aud-subtabs">
        {TABS.map(([k, l]) => (
          <button key={k} className={tab === k ? 'on' : ''} data-testid={`aud-tab-${k}`} onClick={() => setTab(k)}>{l}</button>
        ))}
      </div>
      {tab === 'close' && <CloseBoard role={role} ctx={ctx} toast={toast} />}
      {tab === 'recon' && <Reconciliations role={role} ctx={ctx} />}
      {tab === 'controls' && <Controls role={role} toast={toast} />}
      {tab === 'lineage' && <Lineage role={role} ctx={ctx} />}
    </div>
  );
}

// ============================ CLOSE BOARD ============================

type Period = { id: string; period_key: string; scope: string; status: string; closed_by?: string | null };
type Recon = { id: string; type: string; expected?: number | string | null; actual?: number | string | null; variance?: number | string | null; status: string; signed_off_by?: string | null };

function CloseBoard({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const [sel, setSel] = useState<Period | null>(null);
  const myId = role?.token;

  const periodsQ = useApi<Period[]>(
    ['aud-periods', ctx?.entity, ctx?.period],
    `/api/v1/finance/periods${entityParam(ctx)}`,
  );
  const periods = Array.isArray(periodsQ.data) ? periodsQ.data : [];

  const ids = periods.map((p) => p.id);

  // Reconciliations are a per-period sub-resource: fetch each board period's set and bucket by id, so the close
  // board can gate the lock on cleanliness. Keyed on the id set; the per-period read goes through the same client.
  const [recsByPeriod, setRecsByPeriod] = useState<Record<string, Recon[]>>({});
  const [recsErr, setRecsErr] = useState<ApiError | null>(null);
  const idsKey = ids.join(',');
  React.useEffect(() => {
    setRecsErr(null);
    if (!ids.length) { setRecsByPeriod({}); return; }
    let live = true;
    Promise.all(
      ids.map((id) =>
        request<Recon[]>(`/api/v1/finance/periods/${id}/reconciliations`)
          .then((r) => [id, Array.isArray(r) ? r : []] as const)
          .catch((e) => { if (e instanceof ApiError) setRecsErr(e); return [id, [] as Recon[]] as const; }),
      ),
    ).then((pairs) => { if (live) setRecsByPeriod(Object.fromEntries(pairs)); });
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [idsKey]);

  const recsFor = (p: Period): Recon[] => recsByPeriod[p.id] ?? [];
  const isClean = (p: Period) => { const rs = recsFor(p); return rs.length > 0 && rs.every((r) => r.status === 'matched'); };
  const blockSelfLock = (p: Period) => !!p?.closed_by && !!myId && p.closed_by === myId;

  const err = periodsQ.error;
  const isForbidden = forbidden(err);
  const isNotImpl = notImpl(err);
  const isOtherError = err && !isForbidden && !isNotImpl;

  const close = async (p: Period) => {
    try {
      await request(`/api/v1/finance/periods/${p.id}/close`, { method: 'POST' });
      toast(`${p.period_key} closed`, 'ok');
      await periodsQ.refetch();
      setSel(null);
    } catch (e) {
      toast(e instanceof ApiError ? (e.message ?? 'Close failed') : 'Close failed', 'err');
    }
  };
  const lock = async (p: Period) => {
    try {
      await request(`/api/v1/finance/periods/${p.id}/lock`, { method: 'POST' });
      toast(`${p.period_key} locked over clean books`, 'ok');
      await periodsQ.refetch();
      setSel(null);
    } catch (e) {
      toast(e instanceof ApiError ? (e.message ?? 'Lock blocked') : 'Lock blocked', 'err');
    }
  };

  if (isNotImpl) {
    return (
      <>
        <CloseBoardIntro />
        <NotAvailable testid="aud-periods-na" />
      </>
    );
  }

  return (
    <>
      <CloseBoardIntro />
      <Card title="Period close board" icon={I.clock} aux="open → closed → locked" className="tablewrap">
        <table className="tbl" data-testid="aud-periods">
          <thead><tr><th>Period</th><th>Scope</th><th>Status</th><th>Reconciliations</th><th style={{ textAlign: 'right' }}>Actions</th></tr></thead>
          <tbody>
            {periodsQ.isLoading && <SkeletonRow cols={5} />}
            {!periodsQ.isLoading && isForbidden && <tr><td colSpan={5}><LayerNote>close board hidden — requires <b>accounting period</b></LayerNote></td></tr>}
            {!periodsQ.isLoading && isOtherError && <EmptyRow cols={5}>Could not load the close board.</EmptyRow>}
            {!periodsQ.isLoading && !err && periods.length === 0 && <EmptyRow cols={5}>No periods on the board.</EmptyRow>}
            {!periodsQ.isLoading && !err && periods.map((p) => {
              const clean = isClean(p);
              const recs = recsFor(p);
              const selfLock = blockSelfLock(p);
              return (
                <tr key={p.id} tabIndex={0} data-testid="aud-period-row"
                  onClick={() => setSel(p)} onKeyDown={(e) => e.key === 'Enter' && setSel(p)} style={{ cursor: 'pointer' }}>
                  <td><b>{p.period_key}</b></td>
                  <td className="dim">{p.scope}</td>
                  <td><Chip s={p.status}>{p.status}</Chip></td>
                  <td><div className="row g6 wrap">
                    {recsErr && forbidden(recsErr) && recs.length === 0 && <span className="dim" style={{ fontSize: 11.5 }}>hidden</span>}
                    {!recsErr && recs.length === 0 && <span className="dim" style={{ fontSize: 11.5 }}>—</span>}
                    {recs.map((r, i) => (
                      <span key={i} className={'chip ' + (r.status === 'matched' ? 'ok' : 'danger')} style={{ padding: '2px 8px' }}>{r.type}</span>
                    ))}
                  </div></td>
                  <td onClick={(e) => e.stopPropagation()}>
                    <div className="row g6" style={{ justifyContent: 'flex-end' }}>
                      <button className="btn sm" data-testid="aud-close" disabled={p.status !== 'open'} onClick={() => close(p)}>Close</button>
                      <button className="btn sm" data-testid="aud-lock"
                        disabled={p.status === 'locked' || p.status === 'open' || !clean || selfLock}
                        title={selfLock ? 'you closed this period — another approver must lock it (SoD)' : !clean ? 'open reconciliations block locking' : ''}
                        onClick={() => lock(p)}>Lock</button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Card>

      <Drawer open={!!sel} onClose={() => setSel(null)} width={520}
        chip={sel && <Chip s={sel.status}>{sel.status}</Chip>}
        title={sel ? `${sel.period_key} close` : ''} sub={sel ? `${sel.scope} period` : ''}
        footer={sel && (() => {
          const clean = isClean(sel);
          const selfLock = blockSelfLock(sel);
          return (
            <>
              <button className="btn" disabled={sel.status !== 'open'} onClick={() => close(sel)}>Close period</button>
              <button className="btn primary" data-testid="aud-drawer-lock"
                disabled={sel.status === 'locked' || sel.status === 'open' || !clean || selfLock}
                title={selfLock ? 'you closed this period — another approver must lock it (SoD)' : !clean ? 'open reconciliations block locking' : ''}
                onClick={() => lock(sel)}>{I.shield({ size: 14 })} Lock period</button>
            </>
          );
        })()}>
        {sel && <>
          <div className="mini">Reconciliations · all must match before lock</div>
          {recsFor(sel).length === 0 && <div className="dim" style={{ fontSize: 12.5, padding: '8px 0' }}>No reconciliations recorded for this period.</div>}
          {recsFor(sel).map((r, i) => (
            <div key={i} className="row between" style={{ padding: '13px 14px', border: '1px solid var(--border)', borderRadius: 12, marginBottom: 9, background: 'var(--bg-2)' }}>
              <div>
                <div style={{ fontWeight: 600, fontSize: 13 }}>{String(r.type).replace(/_/g, ' ')}</div>
                {r.expected != null && (
                  <div className="dim row g6" style={{ fontSize: 11.5, marginTop: 2 }}>
                    expected <Money value={r.expected} layer="commercial" role={role} /> · actual <Money value={r.actual} layer="commercial" role={role} />
                  </div>
                )}
              </div>
              <Chip s={r.status === 'matched' ? 'ok' : 'danger'}>{r.status}</Chip>
            </div>
          ))}
          {blockSelfLock(sel) && (
            <div className="banner warn" style={{ marginTop: 8 }} data-testid="aud-sod-gate">
              {I.shield({})}<div>You closed this period. Under segregation of duties a <b>different approver</b> must perform the lock.</div>
            </div>
          )}
          {!isClean(sel) && (
            <div className="banner danger" style={{ marginTop: 8 }} data-testid="aud-lock-gate">
              {I.alert({})}<div>Lock blocked — an unmatched reconciliation is open. Resolve and sign it off before the period can be locked.</div>
            </div>
          )}
          <div className="banner info" style={{ marginTop: 8 }}>
            {I.shield({})}<div>A period can only be <b>locked</b> once every reconciliation is matched. Locking makes it read-only — no further postings are accepted at the ledger boundary.</div>
          </div>
        </>}
      </Drawer>
    </>
  );
}

function CloseBoardIntro() {
  return (
    <div className="dim" style={{ fontSize: 12.5, marginBottom: 12 }}>
      Lock is two-step and final — posting to a locked period is rejected at the ledger boundary. Unmatched
      reconciliations block the lock; the closer cannot lock (segregation of duties).
    </div>
  );
}

// ============================ RECONCILIATIONS ============================

function Reconciliations({ role, ctx }: { role: any; ctx: any }) {
  const [pid, setPid] = useState<string>('');

  const periodsQ = useApi<Period[]>(
    ['aud-recon-periods', ctx?.entity, ctx?.period],
    `/api/v1/finance/periods${entityParam(ctx)}`,
  );
  const periods = Array.isArray(periodsQ.data) ? periodsQ.data : [];
  const activePid = pid || periods[0]?.id || '';

  const recsQ = useApi<Recon[]>(
    ['aud-recons', activePid],
    `/api/v1/finance/periods/${activePid}/reconciliations`,
    { enabled: !!activePid },
  );
  const recs = Array.isArray(recsQ.data) ? recsQ.data : [];

  const pErr = periodsQ.error;
  const pForbidden = forbidden(pErr);
  const pNotImpl = notImpl(pErr);
  const pOtherError = pErr && !pForbidden && !pNotImpl;

  const rErr = recsQ.error;
  const rForbidden = forbidden(rErr);
  const rOtherError = rErr && !rForbidden && !notImpl(rErr);

  if (pNotImpl) return <NotAvailable testid="aud-recs-na" />;

  return (
    <Card title="Automated reconciliations" icon={I.scale}
      aux={!periodsQ.isLoading && !pErr && periods.length > 0 && (
        <select className="fld sel" data-testid="aud-rec-period" value={activePid} onChange={(e) => setPid(e.target.value)} style={{ minWidth: 180 }}>
          {periods.map((p) => <option key={p.id} value={p.id}>{p.period_key} · {p.scope}</option>)}
        </select>
      )}
      className="tablewrap">
      <table className="tbl" data-testid="aud-recs">
        <thead><tr><th>Reconciliation</th><th>Status</th><th className="num">Expected</th><th className="num">Actual</th><th className="num">Variance</th></tr></thead>
        <tbody>
          {(periodsQ.isLoading || (!!activePid && recsQ.isLoading)) && <SkeletonRow cols={5} />}
          {!periodsQ.isLoading && pForbidden && <tr><td colSpan={5}><LayerNote>reconciliations hidden — requires <b>accounting period</b></LayerNote></td></tr>}
          {!periodsQ.isLoading && !pForbidden && rForbidden && <tr><td colSpan={5}><LayerNote>reconciliations hidden — requires <b>reconciliation</b></LayerNote></td></tr>}
          {!periodsQ.isLoading && (pOtherError || rOtherError) && <EmptyRow cols={5}>Could not load reconciliations.</EmptyRow>}
          {!periodsQ.isLoading && !pErr && periods.length === 0 && <EmptyRow cols={5}>No periods to reconcile.</EmptyRow>}
          {!periodsQ.isLoading && !pErr && !!activePid && !recsQ.isLoading && !rErr && recs.length === 0 && <EmptyRow cols={5}>No reconciliations for this period.</EmptyRow>}
          {!periodsQ.isLoading && !pErr && !recsQ.isLoading && !rErr && recs.map((r, i) => {
            const variance = r.expected != null && r.actual != null ? Number(r.actual) - Number(r.expected) : null;
            return (
              <tr key={i} data-testid="aud-rec-row">
                <td><b>{String(r.type).replace(/_/g, ' ')}</b>{r.signed_off_by && <span className="dim" style={{ fontSize: 11, marginLeft: 8 }}>signed off · {r.signed_off_by}</span>}</td>
                <td><Chip s={r.status === 'matched' ? 'ok' : r.status === 'signed_off' ? 'ok' : 'danger'}>{r.status === 'matched' ? 'matched' : r.status}</Chip></td>
                <td className="num"><Money value={r.expected} layer="commercial" role={role} /></td>
                <td className="num"><Money value={r.actual} layer="commercial" role={role} /></td>
                <td className="num">{variance === null ? <span className="dim">—</span> : <span className={Math.abs(variance) > 0.005 ? 'danger' : 'dim'}><Money value={variance} layer="commercial" role={role} /></span>}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </Card>
  );
}

// ============================ CONTROL REGISTER ============================

type Control = {
  code: string; name: string; framework?: string | null; owner?: string | null; frequency?: string | null;
  last_result?: string | null; automated?: boolean; assertion?: string | null; check?: string | null;
  expected?: number | string | null; actual?: number | string | null; unit?: string | null; history?: unknown[];
};

function Controls({ role, toast }: { role: any; toast: (m: string, k?: string) => void }) {
  const [sel, setSel] = useState<Control | null>(null);

  const controlsQ = useApi<Control[]>(['aud-controls'], '/api/v1/finance/controls');
  const controls = Array.isArray(controlsQ.data) ? controlsQ.data : [];

  const err = controlsQ.error;
  const isForbidden = forbidden(err);
  const isNotImpl = notImpl(err);
  const isOtherError = err && !isForbidden && !isNotImpl;
  const ready = !controlsQ.isLoading && !err;

  const passing = controls.filter((c) => c.last_result === 'pass').length;
  const failing = controls.filter((c) => c.last_result === 'fail').length;
  const notRun = controls.filter((c) => !c.last_result).length;

  const run = async (code: string) => {
    try {
      const r = await request<{ code?: string; result?: string; violations?: unknown }>(`/api/v1/finance/controls/${code}/run`, { method: 'POST' });
      const result = r?.result ?? 'pass';
      toast(`${code} re-performed — ${result}`, result === 'fail' ? 'err' : 'ok');
      await controlsQ.refetch();
      setSel((s) => (s && s.code === code ? { ...s, last_result: result } : s));
    } catch (e) {
      toast(e instanceof ApiError ? (e.message ?? 'Run failed') : 'Run failed', 'err');
    }
  };

  if (isNotImpl) return <NotAvailable testid="aud-controls-na" />;

  return (
    <>
      {ready && controls.length > 0 && (
        <div className="grid" style={{ gridTemplateColumns: 'repeat(3,1fr)', gap: 12, marginBottom: 14 }}>
          <Card style={{ padding: '15px 17px' }}><div className="fldlabel">Passing</div><div style={{ fontFamily: 'var(--font-disp)', fontSize: 28, fontWeight: 600, color: 'var(--ok)', marginTop: 5 }}>{passing}<span className="dim" style={{ fontSize: 15, fontWeight: 400 }}> / {controls.length}</span></div></Card>
          <Card style={{ padding: '15px 17px' }}><div className="fldlabel">Failing</div><div style={{ fontFamily: 'var(--font-disp)', fontSize: 28, fontWeight: 600, color: failing ? 'var(--danger)' : 'var(--text)', marginTop: 5 }}>{failing}</div></Card>
          <Card style={{ padding: '15px 17px' }}><div className="fldlabel">Not run this period</div><div style={{ fontFamily: 'var(--font-disp)', fontSize: 28, fontWeight: 600, color: notRun ? 'var(--warn)' : 'var(--text)', marginTop: 5 }}>{notRun}</div></Card>
        </div>
      )}
      {controlsQ.isLoading && <div style={{ marginBottom: 14 }}><Skeleton lines={1} h={70} /></div>}

      <Card title="SOX control register" icon={I.check} aux="re-performable — click a control for its evidence &amp; run history" className="tablewrap">
        <table className="tbl" data-testid="aud-controls">
          <thead><tr><th>Code</th><th>Control</th><th>Framework</th><th>Owner</th><th>Frequency</th><th>Last result</th><th style={{ textAlign: 'right' }}>Run</th></tr></thead>
          <tbody>
            {controlsQ.isLoading && <SkeletonRow cols={7} />}
            {!controlsQ.isLoading && isForbidden && <tr><td colSpan={7}><LayerNote>control register hidden — requires <b>control</b></LayerNote></td></tr>}
            {!controlsQ.isLoading && isOtherError && <EmptyRow cols={7}>Could not load the control register.</EmptyRow>}
            {ready && controls.length === 0 && <EmptyRow cols={7}>No controls registered.</EmptyRow>}
            {ready && controls.map((c) => (
              <tr key={c.code} tabIndex={0} data-testid="aud-control-row" onClick={() => setSel(c)} onKeyDown={(e) => e.key === 'Enter' && setSel(c)} style={{ cursor: 'pointer' }}>
                <td><b className="mono" style={{ fontSize: 11.5 }}>{c.code}</b></td>
                <td><b>{c.name}</b></td>
                <td className="dim">{c.framework ?? '—'}</td>
                <td className="dim">{c.owner ?? '—'}</td>
                <td>{c.frequency ? <span className="chip neutral">{c.frequency}</span> : <span className="dim">—</span>}</td>
                <td><span data-testid={`aud-result-${c.code}`}>{c.last_result ? <Chip s={c.last_result === 'pass' ? 'ok' : 'danger'}>{c.last_result}</Chip> : <span className="chip neutral">not run</span>}</span></td>
                <td onClick={(e) => e.stopPropagation()} style={{ textAlign: 'right' }}>
                  <button className="btn sm" data-testid={`aud-run-${c.code}`} onClick={() => run(c.code)}>{I.refresh({ size: 12 })} Run</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      <Drawer open={!!sel} onClose={() => setSel(null)} width={560}
        chip={sel && <div className="row g8">{sel.framework && <span className="chip accent">{sel.framework}</span>}{sel.automated && <span className="chip neutral">Automated</span>}{sel.last_result && <Chip s={sel.last_result === 'pass' ? 'ok' : 'danger'}>{sel.last_result}</Chip>}</div>}
        title={sel ? sel.code : ''} sub={sel ? sel.name : ''}
        footer={sel && <>
          <span className="dim" style={{ fontSize: 11.5, flex: 1 }}>{sel.owner ? `Owner · ${sel.owner}` : ''}{sel.frequency ? ` · runs ${String(sel.frequency).toLowerCase()}` : ''}</span>
          <button className="btn primary" data-testid="aud-drawer-run" onClick={() => run(sel.code)}>{I.refresh({ size: 14 })} Re-perform now</button>
        </>}>
        {sel && <>
          {sel.assertion && <><div className="mini">Control assertion</div><p style={{ fontSize: 13.5, lineHeight: 1.55, margin: '0 0 18px', color: 'var(--text)' }}>{sel.assertion}</p></>}
          {sel.check && <><div className="mini">Automated check</div><div className="lineage" style={{ marginBottom: 18, fontSize: 11.5 }}>{sel.check}</div></>}

          {(sel.expected != null || sel.actual != null) && (
            <>
              <div className="mini">Latest evidence</div>
              <div className="grid" style={{ gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 18 }}>
                <div className="card" style={{ padding: '12px 14px', background: 'var(--bg-2)' }}>
                  <div className="fldlabel">Expected</div>
                  <div style={{ fontSize: 16, fontWeight: 600, marginTop: 4 }}>{sel.unit ? num(sel.expected) : <Money value={sel.expected} layer="commercial" role={role} />}</div>
                  {sel.unit && <div className="dim" style={{ fontSize: 10.5 }}>{sel.unit}</div>}
                </div>
                <div className="card" style={{ padding: '12px 14px', background: 'var(--bg-2)', borderColor: sel.actual === sel.expected ? 'var(--ok)' : 'var(--danger)' }}>
                  <div className="fldlabel">Actual</div>
                  <div style={{ fontSize: 16, fontWeight: 600, marginTop: 4, color: sel.actual === sel.expected ? 'var(--ok)' : 'var(--danger)' }}>{sel.unit ? num(sel.actual) : <Money value={sel.actual} layer="commercial" role={role} />}</div>
                  {sel.unit && <div className="dim" style={{ fontSize: 10.5 }}>{sel.unit}</div>}
                </div>
              </div>
            </>
          )}

          <div className="mini">Run history</div>
          {asHistory(sel.history).length === 0
            ? <div className="dim" style={{ fontSize: 12.5, padding: '8px 0' }}>No prior runs recorded.</div>
            : <div className="tl" style={{ paddingLeft: 22 }}>
                {asHistory(sel.history).map((h, i) => {
                  const when = Array.isArray(h) ? h[0] : (h.at ?? h.ran_at);
                  const result = Array.isArray(h) ? h[1] : h.result;
                  return (
                    <div className="ev" key={i}>
                      <span className="when" style={{ minWidth: 140 }}>{String(when)}</span>
                      <Chip s={result === 'pass' ? 'ok' : 'danger'}>{String(result)}</Chip>
                    </div>
                  );
                })}
              </div>}
        </>}
      </Drawer>
    </>
  );
}

function asHistory(h: unknown): Array<any> {
  return Array.isArray(h) ? h : [];
}

// ============================ LINEAGE EXPLORER ============================

type Lineage = {
  invoice_no?: string;
  total_inc_vat?: number | string | null;
  ledger_transfers?: unknown[];
  events?: Array<{ type?: string }>;
  document?: { formatted_number?: string } | null;
  error?: string;
};

function Lineage({ role, ctx }: { role: any; ctx: any }) {
  const [invoiceNo, setInvoiceNo] = useState('INV-FLOW');
  const [query, setQuery] = useState('INV-FLOW');

  const q = useApi<Lineage>(
    ['aud-lineage', query, ctx?.entity],
    `/api/v1/finance/lineage?invoice_no=${encodeURIComponent(query)}`,
    { enabled: !!query },
  );

  const trace = () => setQuery(invoiceNo.trim());

  const err = q.error;
  const isForbidden = forbidden(err);
  const isNotImpl = notImpl(err);
  const isOtherError = err && !isForbidden && !isNotImpl;
  const lineage = q.data && !q.data.error ? q.data : null;
  const unknownInvoice = q.data && !!q.data.error;

  return (
    <Card title="Lineage explorer" icon={I.layers} aux="figure → invoice → ledger transfers → events → document">
      <div className="loadbar" style={{ marginBottom: 4 }}>
        <span className="fldlabel">Invoice no</span>
        <input className="fld" style={{ width: 180, textAlign: 'left' }} data-testid="aud-invoice"
          value={invoiceNo} onChange={(e) => setInvoiceNo(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && trace()} />
        <button className="btn primary" data-testid="aud-trace" onClick={trace}>{I.search({ size: 13 })} Trace lineage</button>
      </div>

      {q.isLoading && <div style={{ marginTop: 14 }}><Skeleton lines={5} /></div>}
      {!q.isLoading && isNotImpl && <div style={{ marginTop: 14 }}><NotAvailable testid="aud-lineage-na" /></div>}
      {!q.isLoading && isForbidden && <div style={{ marginTop: 14 }}><LayerNote>lineage figures hidden — requires <b>accounting period</b></LayerNote></div>}
      {!q.isLoading && isOtherError && <div className="banner danger" style={{ marginTop: 14 }}>{I.alert({})}<div>Could not trace <b>{query}</b>.</div></div>}
      {!q.isLoading && !err && unknownInvoice && <div className="banner info" style={{ marginTop: 14 }} data-testid="aud-lineage-empty">{I.search({})}<div>No lineage found for <b>{query}</b>.</div></div>}

      {!q.isLoading && !err && lineage && (
        <div className="lineage" data-testid="aud-lineage" style={{ marginTop: 14 }}>
          <div className="row g8" style={{ marginBottom: 4 }}>
            <span className="step">invoice</span>
            <b>{lineage.invoice_no}</b>
            <span className="dim">— total</span>
            <Money value={lineage.total_inc_vat} layer="commercial" role={role} />
            <AuditRef id={lineage.invoice_no} />
          </div>
          <div style={{ marginBottom: 4 }}><span className="step">transfers</span> {asArr(lineage.ledger_transfers).length} immutable ledger postings</div>
          {asArr(lineage.ledger_transfers).map((t, i) => (
            <div key={i} style={{ paddingLeft: 14 }}>↳ <span className="mono">{typeof t === 'string' ? t : (t as any)?.id ?? JSON.stringify(t)}</span></div>
          ))}
          <div style={{ margin: '4px 0' }}><span className="step">events</span> {asArr(lineage.events).map((e: any) => e.type).join('  →  ') || '(none)'}</div>
          <div><span className="step">document</span> <b>{lineage.document?.formatted_number ?? '(not generated)'}</b> {lineage.document && <span className="dim">(WORM)</span>}</div>
        </div>
      )}
    </Card>
  );
}

function asArr<T = unknown>(v: unknown): T[] {
  return Array.isArray(v) ? (v as T[]) : [];
}
