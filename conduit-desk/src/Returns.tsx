import React, { useState, useEffect, useCallback } from 'react';
import { apiFetch, authToken } from './api';
import { PageHead, Card, Chip, Money, Drawer, LayerNote, AuditRef, EmptyRow, SkeletonRow, useToast } from './kit/kit';
import { tableState, asArray, type ApiResult } from './state';
import { I } from './kit/icons';

// Returns / RMA (spec/ui/16-returns.md, doc 09): the full RMA lifecycle — raise → assess → approve (maker ≠
// checker) → receive → disposition → refund — per return type, each with its own money, stock and commission
// consequence. Money reverses at the unit's specific batch landed cost; serials never silently re-enter
// sellable stock. The hero is the LIFECYCLE TIMELINE (who did what), with maker-checker visible and the
// money/stock consequences explicit at each step.
//
// Backend (per spec): GET /returns · GET /returns/{id} · POST /orders/{id}/returns ·
//   POST /returns/{id}/{assess,approve,receive,disposition,refund}.
// Layers: refund_amount → commercial · unit_landed_cost → profitability · commission claw → commission.

interface RmaLine {
  serial?: string;
  grade?: string | null;
  disposition?: string | null;
  activated?: boolean;
  landed_cost?: number | string | null;
}
interface RmaEvent {
  stage: string;
  actor?: string;
  note?: string;
  at?: string;
}
interface Rma {
  id: string;
  order?: string;
  customer?: string;
  type?: string;
  status?: string;
  reason?: string;
  refund_amount?: number | string | null;
  unit_landed_cost?: number | string | null;
  commission_claw?: number | string | null;
  credit_note?: string | null;
  replacement_order?: string | null;
  raised_by?: string;
  approved_by?: string | null;
  age_days?: number;
  timeline?: RmaEvent[];
  lines?: RmaLine[];
}

const STATUSES = ['all', 'raised', 'assessed', 'approved', 'received', 'dispositioned', 'refunded'];
const STATUS_LABEL: Record<string, string> = {
  all: 'All', raised: 'Raised', assessed: 'Assessed', approved: 'Approved',
  received: 'Received', dispositioned: 'Dispositioned', refunded: 'Refunded',
};
const STATUS_CHIP: Record<string, string> = {
  raised: 'warn', assessed: 'neutral', approved: 'accent', received: 'neutral', dispositioned: 'accent', refunded: 'ok',
};
const TYPE_LABEL: Record<string, string> = {
  full_unit: 'Full unit', part_only: 'Part only', DOA: 'DOA',
  warranty_replacement: 'Warranty repl.', goodwill: 'Goodwill',
};
const TYPE_CHIP: Record<string, string> = {
  DOA: 'danger', warranty_replacement: 'accent', goodwill: 'plum', full_unit: 'neutral', part_only: 'neutral',
};
// Disposition rules (doc 09): restock allowed only for A-grade, non-activated units; otherwise refurbish/scrap.
const DISPO_OPTS = ['restock', 'refurbish', 'scrap'];
const dispoAllowed = (grade?: string | null, activated?: boolean) =>
  (d: string) => d === 'restock' ? grade === 'A' && !activated : true;

export function Returns({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const [toastNode, kitToast] = useToast();
  const fire = useCallback((m: string, k?: string) => { toast(m, k); kitToast(m, (k as any) || 'ok'); }, [toast, kitToast]);

  const [filter, setFilter] = useState('all');
  const [res, setRes] = useState<ApiResult | null>(null);
  const [rows, setRows] = useState<Rma[]>([]);
  const [selId, setSelId] = useState<string | null>(null);
  const [sel, setSel] = useState<Rma | null>(null);
  const [memo, setMemo] = useState('');
  const [busy, setBusy] = useState(false);

  const tok = () => role?.token || authToken();

  // AUTO-LOAD: on mount + whenever the status filter, entity, market or period context changes. No Load button.
  const load = useCallback(() => {
    setRes(null);
    const q = filter === 'all' ? '' : `?status=${encodeURIComponent(filter)}`;
    apiFetch(`/api/v1/returns${q}`).then((r) => {
      setRes(r);
      setRows(asArray<Rma>(r.json));
    });
  }, [filter]);

  useEffect(load, [load, ctx?.entity, ctx?.market, ctx?.period]);

  // Reopen the selected RMA from the server (after a transition) so the timeline + lines reflect the new state.
  const openRma = useCallback((id: string) => {
    setSelId(id);
    apiFetch(`/api/v1/returns/${encodeURIComponent(id)}`).then((r) => {
      if (r.status === 200) setSel(r.json as Rma);
      else if (r.status === 403) setSel({ id, status: 'forbidden' } as any);
      else fire(r.json?.message ?? `Couldn't open ${id} (${r.status})`, 'err');
    });
  }, [fire]);

  const closeDrawer = () => { setSelId(null); setSel(null); setMemo(''); };

  // A stage transition command. The consumer effects disposition/refund — the UI shows requested → done.
  const act = (stage: string, okMsg: string, body?: unknown) => {
    if (!selId) return;
    setBusy(true);
    apiFetch(`/api/v1/returns/${encodeURIComponent(selId)}/${stage}`, {
      method: 'POST',
      body: body === undefined ? undefined : JSON.stringify(body),
    }).then((r) => {
      setBusy(false);
      if (r.status === 200 || r.status === 202) {
        fire(okMsg, 'ok');
        openRma(selId);
        load();
      } else if (r.status === 422) {
        // The restock-rejection is guidance, not a failure (doc 09): "non-A-grade → refurbish or scrap".
        fire(r.json?.message ?? 'Not allowed in this state — see guidance', 'warn');
      } else if (r.status === 409) {
        fire(r.json?.message ?? 'Out-of-order transition rejected', 'warn');
      } else if (r.status === 403) {
        fire(r.json?.message ?? 'Maker-checker: a different approver is required', 'warn');
      } else {
        fire(r.json?.message ?? `Action failed (${r.status})`, 'err');
      }
    });
  };

  const st = tableState(res, rows);
  const periodFrozen = ctx?.period && /(lock|clos)/i.test(String(ctx.period));

  return (
    <div className="page" style={{ maxWidth: 1320 }}>
      {toastNode}
      <PageHead
        crumb="RMA lifecycle · doc 09 — money reverses at the unit's specific batch cost"
        title="Returns"
        sub="The full return lifecycle — raise, assess, approve, receive, disposition, refund — per type, each with its own money, stock and commission consequence. Serials never silently re-enter sellable stock."
      />

      <Card
        title="Returns worklist"
        icon={I.list}
        aux={
          <div className="row g8">
            <div className="seg" data-testid="rma-filter">
              {STATUSES.map((s) => (
                <button key={s} className={filter === s ? 'on' : ''} data-testid={`rma-filter-${s}`} onClick={() => setFilter(s)}>
                  {STATUS_LABEL[s]}
                </button>
              ))}
            </div>
            <span className="dim" style={{ fontSize: 12 }}>{rows.length} RMAs</span>
          </div>
        }
        className="tablewrap"
      >
        <table className="tbl ord" data-testid="rma-table">
          <thead>
            <tr>
              <th>RMA</th>
              <th>Order</th>
              <th>Customer</th>
              <th>Type</th>
              <th>Status</th>
              <th className="num">Refund</th>
              <th>Raised by</th>
              <th className="num">Age</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {st === 'loading' && <SkeletonRow cols={9} />}

            {st === 'forbidden' && (
              <tr><td colSpan={9}><LayerNote>Returns is withheld for your view — requires <b>returns</b> access.</LayerNote></td></tr>
            )}

            {st === 'error' && (
              <EmptyRow cols={9}>Couldn't load returns{(res?.json as any)?.message ? ` — ${(res!.json as any).message}` : ` (${res?.status})`}.</EmptyRow>
            )}

            {st === 'empty' && (
              <EmptyRow cols={9}>No returns in {filter === 'all' ? 'any state' : `the ${filter} state`} yet.</EmptyRow>
            )}

            {st === 'ready' && rows.map((r, i) => (
              <tr key={r.id ?? i} tabIndex={0} data-testid="rma-row"
                onClick={() => openRma(r.id)}
                onKeyDown={(e) => e.key === 'Enter' && openRma(r.id)}>
                <td><b className="mono" style={{ fontSize: 11.5 }}>{r.id}</b></td>
                <td className="mono dim" style={{ fontSize: 11 }}>{r.order ?? '—'}</td>
                <td>{r.customer ?? '—'}</td>
                <td><span className={'chip ' + (TYPE_CHIP[r.type ?? ''] || 'neutral')}>{TYPE_LABEL[r.type ?? ''] ?? r.type ?? '—'}</span></td>
                <td><Chip s={STATUS_CHIP[r.status ?? ''] || r.status || 'neutral'}>{r.status}</Chip></td>
                <td className="num">
                  {/* refund_amount is the commercial layer — collapses (renders nothing) when withheld */}
                  <Money value={r.refund_amount ?? null} layer="commercial" role={role} />
                </td>
                <td className="dim">{r.raised_by ?? '—'}</td>
                <td className="num dim">{r.age_days != null ? `${r.age_days}d` : '—'}</td>
                <td>{I.chevR({ size: 15 })}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      <Drawer
        open={!!selId}
        onClose={closeDrawer}
        width={600}
        title={sel ? sel.id : selId ?? ''}
        sub={sel ? [sel.order, sel.customer].filter(Boolean).join(' · ') : 'Loading…'}
        chip={sel && sel.status !== 'forbidden' && (
          <div className="row g8">
            <span className={'chip ' + (TYPE_CHIP[sel.type ?? ''] || 'neutral')}>{TYPE_LABEL[sel.type ?? ''] ?? sel.type}</span>
            <Chip s={STATUS_CHIP[sel.status ?? ''] || sel.status || 'neutral'}>{sel.status}</Chip>
          </div>
        )}
        footer={sel && sel.status !== 'forbidden' && (
          <RmaActions sel={sel} role={role} busy={busy} memo={memo} setMemo={setMemo} act={act} frozen={!!periodFrozen} />
        )}
      >
        {!sel && <div style={{ display: 'grid', gap: 10 }}><SkeletonNote /></div>}

        {sel && sel.status === 'forbidden' && (
          <LayerNote>This return is withheld for your view — requires <b>returns</b> access.</LayerNote>
        )}

        {sel && sel.status !== 'forbidden' && (
          <>
            {sel.reason && (
              <div className="banner info" style={{ marginBottom: 16 }}>{I.alert({ size: 15 })}<div>{sel.reason}</div></div>
            )}

            {/* HERO — the lifecycle timeline: who did what (maker-checker visible). */}
            <div className="mini" style={{ marginBottom: 10 }}>Lifecycle · who did what</div>
            <div className="tl" style={{ marginBottom: 18 }}>
              {asArray<RmaEvent>(sel.timeline).map((e, i) => (
                <div className="ev" key={i}>
                  <span className="seq">{i + 1}</span>
                  <span className="etype">return.{e.stage}</span>
                  {e.actor && <span className="dim" style={{ fontSize: 11.5 }}>{e.actor}</span>}
                  {e.note && <span className="dim" style={{ fontSize: 11 }}>· {e.note}</span>}
                  {e.at && <span className="when" style={{ marginLeft: 'auto' }}>{e.at}</span>}
                </div>
              ))}
              {asArray<RmaEvent>(sel.timeline).length === 0 && (
                <div className="dim" style={{ fontSize: 12, padding: '8px 0' }}>No lifecycle events yet.</div>
              )}
            </div>

            {asArray<RmaLine>(sel.lines).length > 0 && (
              <>
                <div className="mini" style={{ marginBottom: 8 }}>Lines · serial · grade · disposition</div>
                <div className="tablewrap" style={{ border: '1px solid var(--border)', borderRadius: 10, overflow: 'hidden', marginBottom: 16 }}>
                  <table className="tbl">
                    <thead>
                      <tr>
                        <th>Serial</th>
                        <th>Grade</th>
                        <th>Disposition</th>
                        <th className="num">Landed</th>
                      </tr>
                    </thead>
                    <tbody>
                      {asArray<RmaLine>(sel.lines).map((l, i) => (
                        <tr key={i} style={{ cursor: 'default' }}>
                          <td className="mono" style={{ fontSize: 11 }}>
                            {l.serial ?? '—'}
                            {l.activated && <span className="chip danger" style={{ marginLeft: 6, padding: '0 6px', fontSize: 9 }}>activated</span>}
                          </td>
                          <td>{l.grade ? <span className={'chip ' + (l.grade === 'A' ? 'ok' : l.grade === 'B' ? 'warn' : 'danger')}>{l.grade}</span> : <span className="dim">—</span>}</td>
                          <td>
                            {l.disposition
                              ? <span className="row g6"><span className="chip neutral">{l.disposition}</span><span className="dim" style={{ fontSize: 10 }}>requested</span></span>
                              : sel.status === 'received' && l.grade
                                ? <DispoPicker line={l} idx={i} busy={busy} frozen={!!periodFrozen} act={act} />
                                : <span className="dim">—</span>}
                          </td>
                          <td className="num">
                            {/* unit_landed_cost is the profitability layer — collapses when withheld */}
                            <Money value={l.landed_cost ?? null} layer="profitability" role={role} />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {(role?.layers || []).indexOf('profitability') < 0 && (
                  <LayerNote>Per-unit landed cost is the <b>profitability</b> layer — hidden for your view.</LayerNote>
                )}
              </>
            )}

            <div className="mini" style={{ margin: '14px 0 8px' }}>Money &amp; stock consequence</div>
            <div className="kv" style={{ fontSize: 12 }}>
              {(role?.layers || []).indexOf('commercial') >= 0 && sel.refund_amount != null && (
                <><span className="k">Refund</span><span className="v num"><Money value={sel.refund_amount} layer="commercial" role={role} /></span></>
              )}
              {(role?.layers || []).indexOf('commission') >= 0 && sel.commission_claw != null && (
                <><span className="k">Commission claw-back</span><span className="v num"><Money value={sel.commission_claw} layer="commission" role={role} /></span></>
              )}
              {sel.credit_note && (
                <><span className="k">Credit note</span><span className="v mono"><AuditRef id={sel.credit_note} /></span></>
              )}
              {sel.replacement_order && (
                <><span className="k">Replacement order</span><span className="v mono">{sel.replacement_order}</span></>
              )}
              {sel.raised_by && (<><span className="k">Raised by</span><span className="v">{sel.raised_by}</span></>)}
              {sel.approved_by && (<><span className="k">Approved by</span><span className="v">{sel.approved_by}</span></>)}
            </div>
          </>
        )}
      </Drawer>
    </div>
  );
}

function SkeletonNote() {
  return (
    <>
      <div className="skel skel-line" style={{ width: '90%' }} />
      <div className="skel skel-line" style={{ width: '70%' }} />
      <div className="skel skel-line" style={{ width: '80%' }} />
    </>
  );
}

function DispoPicker({ line, idx, busy, frozen, act }: {
  line: RmaLine; idx: number; busy: boolean; frozen: boolean;
  act: (stage: string, okMsg: string, body?: unknown) => void;
}) {
  const allowed = dispoAllowed(line.grade, line.activated);
  return (
    <select
      className="fld sel"
      style={{ padding: '3px 8px', fontSize: 11.5 }}
      data-testid={`rma-dispo-${idx}`}
      defaultValue=""
      disabled={busy || frozen}
      onChange={(e) => e.target.value && act('disposition', `Disposition requested: ${e.target.value}`, { lineIndex: idx, disposition: e.target.value })}
    >
      <option value="" disabled>disposition…</option>
      {DISPO_OPTS.map((d) => (
        <option key={d} value={d} disabled={!allowed(d)}>
          {d}{!allowed(d) ? ' — non-A-grade/activated: refurbish or scrap' : ''}
        </option>
      ))}
    </select>
  );
}

function RmaActions({ sel, role, busy, memo, setMemo, act, frozen }: {
  sel: Rma; role: any; busy: boolean; memo: string; setMemo: (v: string) => void;
  act: (stage: string, okMsg: string, body?: unknown) => void; frozen: boolean;
}) {
  const s = sel.status;
  // Maker-checker (SoD): the raiser can never approve their own RMA. Server enforces 403; UI mirrors it.
  const isRaiser = sel.raised_by && role?.name && sel.raised_by === role.name;

  if (frozen && (s === 'approved' || s === 'dispositioned')) {
    return <span className="dim row g6" style={{ fontSize: 12 }}>{I.clock({ size: 13 })} Period is locked/closed — refund cannot post.</span>;
  }

  if (s === 'raised') {
    return (
      <button className="btn primary" data-testid="rma-assess" disabled={busy} onClick={() => act('assess', 'Assessed — units graded')}>
        {I.check({ size: 14 })} Assess &amp; grade
      </button>
    );
  }
  if (s === 'assessed') {
    return (
      <div className="row g8" style={{ flex: 1 }}>
        <input className="fld" style={{ flex: 1 }} data-testid="rma-memo" placeholder="approval memo…" value={memo} onChange={(e) => setMemo(e.target.value)} />
        <span title={isRaiser ? 'Maker-checker: you raised this RMA — a different approver must approve it' : undefined}>
          <button
            className="btn primary"
            data-testid="rma-approve"
            disabled={busy || !!isRaiser}
            onClick={() => act('approve', 'Approved', { memo })}
          >
            {I.shield({ size: 14 })} Approve
          </button>
        </span>
      </div>
    );
  }
  if (s === 'approved') {
    return (
      <button className="btn primary" data-testid="rma-receive" disabled={busy} onClick={() => act('receive', 'Received into the returns bay')}>
        {I.check({ size: 14 })} Receive
      </button>
    );
  }
  if (s === 'received') {
    return <span className="dim" style={{ fontSize: 12, flex: 1 }}>Disposition each line above (restock / refurbish / scrap), then refund.</span>;
  }
  if (s === 'dispositioned') {
    return (
      <button className="btn primary" data-testid="rma-refund" disabled={busy} onClick={() => act('refund', 'Refund requested — credit note minting', { method: 'credit_memo' })}>
        {I.check({ size: 14 })} Issue refund / credit note
      </button>
    );
  }
  if (s === 'refunded') {
    return <span className="row g6" style={{ color: 'var(--ok)' }}>{I.check({ size: 15 })} Closed{sel.credit_note ? ` · ${sel.credit_note}` : ''}</span>;
  }
  return null;
}
