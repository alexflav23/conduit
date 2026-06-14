import React, { useState } from 'react';
import { useApi, request } from './lib/query';
import { ApiError } from './lib/client';
import { PageHead, Card, Chip, Money, Drawer, LayerNote, AuditRef, EmptyRow, SkeletonRow, Skeleton } from './kit/kit';
import { I } from './kit/icons';

// Returns / RMA (spec/ui/16-returns.md, doc 09): the full RMA lifecycle — raise → assess → approve (maker ≠
// checker) → receive → disposition → refund — per return type, each with its own money, stock and commission
// consequence. Money reverses at the unit's specific batch landed cost; serials never silently re-enter
// sellable stock. The hero is the LIFECYCLE TIMELINE (who did what), with maker-checker visible and the
// money/stock consequences explicit at each step.
//
// Backing routes (ReturnRoutes):
//   GET  /api/v1/returns?status=<s>&order_id=<o>&type=<t>   — worklist (view:rma, scope-filtered + layer-projected)
//   GET  /api/v1/returns/{id}                               — one RMA (lines / dispositions / credit_note / lifecycle)
//   POST /api/v1/orders/{id}/returns                        — raise (create:rma)
//   POST /api/v1/returns/{id}/assess                        — grade (edit:rma) — body { lines:[{rma_line_id,condition_grade}] }
//   POST /api/v1/returns/{id}/approve                       — approve (approve:rma, SoD) — body { approval_memo_ref }
//   POST /api/v1/returns/{id}/receive                       — receive (edit:rma)
//   POST /api/v1/returns/{id}/disposition                  — body { rma_line_id, disposition, location_id? } (202)
//   POST /api/v1/returns/{id}/refund                       — body { refund_method } (202, create:credit_note)
// Layers: refund_amount → commercial · unit_landed_cost → profitability · commission claw → commission.

interface RmaLine {
  id?: string;
  serial_unit?: string | null;
  component_ref?: string | null;
  qty?: number;
  condition_grade?: string | null;
  unit_landed_cost?: number | string | null;
  disposition?: string | null;
  status?: string;
}
interface RmaLifecycle {
  event: string;
  at?: string;
}
interface RmaCreditNote {
  credit_note_no?: string;
  total_inc_vat?: string;
  refund_method?: string;
}
interface Rma {
  id: string;
  rma_no?: string;
  order_id?: string;
  type?: string;
  scope?: string;
  status?: string;
  currency?: string;
  reason_code?: string | null;
  refund_amount?: number | string | null;
  commission_claw?: number | string | null;
  credit_note_id?: string | null;
  credit_note?: RmaCreditNote | null;
  replacement_order_id?: string | null;
  lifecycle?: RmaLifecycle[];
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
const dispoAllowed = (grade?: string | null) =>
  (d: string) => d === 'restock' ? grade === 'A' : true;

const asArray = <T,>(x: unknown): T[] => (Array.isArray(x) ? (x as T[]) : []);

export function Returns({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const [filter, setFilter] = useState('all');
  const [selId, setSelId] = useState<string | null>(null);
  const [memo, setMemo] = useState('');
  const [busy, setBusy] = useState(false);

  // AUTO-LOAD: keyed on the status filter + the ctx fields the scope-filtered worklist depends on, so a
  // context switch (entity/market/period) refetches. No Load button.
  const listPath = `/api/v1/returns${filter === 'all' ? '' : `?status=${encodeURIComponent(filter)}`}`;
  const list = useApi<Rma[]>(
    ['returns', filter, ctx?.entity, ctx?.market, ctx?.period],
    listPath,
  );

  const listErr = list.error as ApiError | null;
  const forbidden = !!listErr?.forbidden;
  const notImplemented = !!listErr?.notImplemented;
  const otherError = !!listErr && !forbidden && !notImplemented;
  const rows: Rma[] = Array.isArray(list.data) ? list.data : [];
  const ready = !list.isLoading && !listErr;
  const empty = ready && rows.length === 0;

  // The selected RMA's full detail (lines + lifecycle + credit note) — refetched after each transition.
  const detail = useApi<Rma>(
    ['return', selId],
    selId ? `/api/v1/returns/${encodeURIComponent(selId)}` : '',
    { enabled: !!selId },
  );
  const detailErr = detail.error as ApiError | null;
  const sel: Rma | null = selId
    ? ({ ...(rows.find((r) => r.id === selId) || {}), ...(detail.data || {}) } as Rma)
    : null;

  const closeDrawer = () => { setSelId(null); setMemo(''); };

  // A stage transition command. The consumer effects disposition/refund — the UI shows requested → done.
  const act = async (stage: string, okMsg: string, body?: unknown) => {
    if (!selId) return;
    setBusy(true);
    try {
      await request(`/api/v1/returns/${encodeURIComponent(selId)}/${stage}`, {
        method: 'POST',
        body: JSON.stringify(body ?? {}),
      });
      toast(okMsg, 'ok');
      await Promise.all([detail.refetch(), list.refetch()]);
    } catch (e) {
      const ae = e as ApiError;
      // The restock-rejection (422) is guidance, not a failure (doc 09); SoD (403) and out-of-order (409) too.
      if (ae?.status === 422) toast(ae.message ?? 'Not allowed in this state — see guidance', 'warn');
      else if (ae?.status === 409) toast(ae.message ?? 'Out-of-order transition rejected', 'warn');
      else if (ae?.forbidden) toast(ae.message ?? 'Maker-checker: a different approver is required', 'warn');
      else toast(`Action failed (${ae?.status ?? '—'})${ae?.message ? ': ' + ae.message : ''}`, 'err');
    } finally {
      setBusy(false);
    }
  };

  const periodFrozen = ctx?.period && /(lock|clos)/i.test(String(ctx.period));

  return (
    <div className="page" style={{ maxWidth: 1320 }}>
      <PageHead
        crumb="RMA lifecycle · doc 09 — money reverses at the unit's specific batch cost"
        title="Returns"
        sub="The full return lifecycle — raise, assess, approve, receive, disposition, refund — per type, each with its own money, stock and commission consequence. Serials never silently re-enter sellable stock."
      />

      {notImplemented ? (
        <Card style={{ padding: '34px 28px', textAlign: 'center' }} className="">
          <div style={{ display: 'grid', placeItems: 'center', gap: 10 }} data-testid="rma-unbacked">
            <span style={{ width: 44, height: 44, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'var(--panel-2)' }}>{I.list({ size: 22 })}</span>
            <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>Not available in this environment yet</div>
            <div className="dim" style={{ fontSize: 12.5, maxWidth: 460 }}>The returns worklist appears once the RMA lifecycle service is wired in this environment.</div>
          </div>
        </Card>
      ) : (
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
              <span className="dim" style={{ fontSize: 12 }}>{ready ? `${rows.length} RMAs` : ''}</span>
            </div>
          }
          className="tablewrap"
        >
          <table className="tbl ord" data-testid="rma-table">
            <thead>
              <tr>
                <th>RMA</th>
                <th>Order</th>
                <th>Reason</th>
                <th>Type</th>
                <th>Status</th>
                <th className="num">Refund</th>
                <th>Scope</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {list.isLoading && (<><SkeletonRow cols={8} /><SkeletonRow cols={8} /><SkeletonRow cols={8} /></>)}

              {forbidden && (
                <tr><td colSpan={8}><LayerNote>hidden — requires the <b>rma</b> view layer.</LayerNote></td></tr>
              )}

              {otherError && (
                <EmptyRow cols={8}>Couldn't load returns (HTTP {listErr?.status}){listErr?.message ? ` — ${listErr.message}` : ''}. It retries on the next context change.</EmptyRow>
              )}

              {empty && (
                <EmptyRow cols={8}>No returns in {filter === 'all' ? 'any state' : `the ${filter} state`} yet.</EmptyRow>
              )}

              {ready && rows.map((r, i) => (
                <tr key={r.id ?? i} tabIndex={0} data-testid="rma-row"
                  className={selId === r.id ? 'sel' : ''}
                  onClick={() => setSelId(r.id)}
                  onKeyDown={(e) => e.key === 'Enter' && setSelId(r.id)}>
                  <td><b className="mono" style={{ fontSize: 11.5 }}>{r.rma_no ?? r.id}</b></td>
                  <td className="mono dim" style={{ fontSize: 11 }}>{r.order_id ?? '—'}</td>
                  <td className="dim">{r.reason_code ?? '—'}</td>
                  <td><span className={'chip ' + (TYPE_CHIP[r.type ?? ''] || 'neutral')}>{TYPE_LABEL[r.type ?? ''] ?? r.type ?? '—'}</span></td>
                  <td><Chip s={STATUS_CHIP[r.status ?? ''] || r.status || 'neutral'}>{r.status}</Chip></td>
                  <td className="num">
                    {/* refund_amount is the commercial layer — collapses (renders nothing) when withheld */}
                    <Money value={r.refund_amount ?? null} ccy={r.currency || ctx?.currency || 'GBP'} layer="commercial" role={role} />
                  </td>
                  <td className="dim">{r.scope ?? '—'}</td>
                  <td>{I.chevR({ size: 15 })}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
      )}

      <Drawer
        open={!!selId}
        onClose={closeDrawer}
        width={600}
        title={sel?.rma_no ?? sel?.id ?? selId ?? ''}
        sub={sel ? [sel.order_id, sel.scope].filter(Boolean).join(' · ') : 'Loading…'}
        chip={sel && !detailErr && (
          <div className="row g8">
            <span className={'chip ' + (TYPE_CHIP[sel.type ?? ''] || 'neutral')}>{TYPE_LABEL[sel.type ?? ''] ?? sel.type}</span>
            <Chip s={STATUS_CHIP[sel.status ?? ''] || sel.status || 'neutral'}>{sel.status}</Chip>
          </div>
        )}
        footer={sel && !detail.isLoading && !detailErr && (
          <RmaActions sel={sel} role={role} busy={busy} memo={memo} setMemo={setMemo} act={act} frozen={!!periodFrozen} />
        )}
      >
        {detail.isLoading ? (
          <div data-testid="rma-loading"><Skeleton lines={6} /></div>
        ) : detailErr?.forbidden ? (
          <LayerNote>This return is withheld for your view — requires <b>rma</b> access.</LayerNote>
        ) : detailErr?.notImplemented ? (
          <div className="dim" style={{ fontSize: 12.5 }} data-testid="rma-detail-unbacked">This return is not available in this environment.</div>
        ) : detailErr ? (
          <div className="banner danger" data-testid="rma-detail-error">{I.alert({ size: 14 })} Couldn't open this return (HTTP {detailErr.status}).</div>
        ) : sel ? (
          <>
            {sel.reason_code && (
              <div className="banner info" style={{ marginBottom: 16 }}>{I.alert({ size: 15 })}<div>{sel.reason_code}</div></div>
            )}

            {/* HERO — the lifecycle timeline: who did what (maker-checker visible). */}
            <div className="mini" style={{ marginBottom: 10 }}>Lifecycle · who did what</div>
            <div className="tl" style={{ marginBottom: 18 }}>
              {asArray<RmaLifecycle>(sel.lifecycle).map((e, i) => (
                <div className="ev" key={i}>
                  <span className="seq">{i + 1}</span>
                  <span className="etype">{e.event}</span>
                  {e.at && <span className="when" style={{ marginLeft: 'auto' }}>{e.at}</span>}
                </div>
              ))}
              {asArray<RmaLifecycle>(sel.lifecycle).length === 0 && (
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
                        <tr key={l.id ?? i} style={{ cursor: 'default' }}>
                          <td className="mono" style={{ fontSize: 11 }}>
                            {l.serial_unit ?? l.component_ref ?? '—'}
                          </td>
                          <td>{l.condition_grade ? <span className={'chip ' + (l.condition_grade === 'A' ? 'ok' : l.condition_grade === 'B' ? 'warn' : 'danger')}>{l.condition_grade}</span> : <span className="dim">—</span>}</td>
                          <td>
                            {l.disposition
                              ? <span className="row g6"><span className="chip neutral">{l.disposition}</span><span className="dim" style={{ fontSize: 10 }}>requested</span></span>
                              : sel.status === 'received' && l.condition_grade && l.id
                                ? <DispoPicker line={l} idx={i} busy={busy} frozen={!!periodFrozen} act={act} />
                                : <span className="dim">—</span>}
                          </td>
                          <td className="num">
                            {/* unit_landed_cost is the profitability layer — collapses when withheld */}
                            <Money value={l.unit_landed_cost ?? null} ccy={sel.currency || ctx?.currency || 'GBP'} layer="profitability" role={role} />
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
                <><span className="k">Refund</span><span className="v num"><Money value={sel.refund_amount} ccy={sel.currency || ctx?.currency || 'GBP'} layer="commercial" role={role} /></span></>
              )}
              {(role?.layers || []).indexOf('commission') >= 0 && sel.commission_claw != null && (
                <><span className="k">Commission claw-back</span><span className="v num"><Money value={sel.commission_claw} ccy={sel.currency || ctx?.currency || 'GBP'} layer="commission" role={role} /></span></>
              )}
              {sel.credit_note?.credit_note_no && (
                <><span className="k">Credit note</span><span className="v mono"><AuditRef id={sel.credit_note.credit_note_no} /></span></>
              )}
              {!sel.credit_note?.credit_note_no && sel.credit_note_id && (
                <><span className="k">Credit note</span><span className="v mono"><AuditRef id={sel.credit_note_id} /></span></>
              )}
              {sel.replacement_order_id && (
                <><span className="k">Replacement order</span><span className="v mono">{sel.replacement_order_id}</span></>
              )}
            </div>
          </>
        ) : null}
      </Drawer>
    </div>
  );
}

function DispoPicker({ line, idx, busy, frozen, act }: {
  line: RmaLine; idx: number; busy: boolean; frozen: boolean;
  act: (stage: string, okMsg: string, body?: unknown) => void;
}) {
  const allowed = dispoAllowed(line.condition_grade);
  return (
    <select
      className="fld sel"
      style={{ padding: '3px 8px', fontSize: 11.5 }}
      data-testid={`rma-dispo-${idx}`}
      defaultValue=""
      disabled={busy || frozen}
      onChange={(e) => e.target.value && act('disposition', `Disposition requested: ${e.target.value}`, { rma_line_id: line.id, disposition: e.target.value })}
    >
      <option value="" disabled>disposition…</option>
      {DISPO_OPTS.map((d) => (
        <option key={d} value={d} disabled={!allowed(d)}>
          {d}{!allowed(d) ? ' — non-A-grade: refurbish or scrap' : ''}
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

  if (frozen && (s === 'approved' || s === 'dispositioned')) {
    return <span className="dim row g6" style={{ fontSize: 12 }}>{I.clock({ size: 13 })} Period is locked/closed — refund cannot post.</span>;
  }

  if (s === 'raised') {
    return (
      <button className="btn primary" data-testid="rma-assess" disabled={busy} onClick={() => act('assess', 'Assessed — units graded', { lines: [] })}>
        {I.check({ size: 14 })} Assess &amp; grade
      </button>
    );
  }
  if (s === 'assessed') {
    return (
      <div className="row g8" style={{ flex: 1 }}>
        <input className="fld" style={{ flex: 1 }} data-testid="rma-memo" placeholder="approval memo ref…" value={memo} onChange={(e) => setMemo(e.target.value)} />
        <span>
          <button
            className="btn primary"
            data-testid="rma-approve"
            disabled={busy}
            onClick={() => act('approve', 'Approved', { approval_memo_ref: memo || null })}
          >
            {I.shield({ size: 14 })} Approve
          </button>
        </span>
      </div>
    );
  }
  if (s === 'approved') {
    return (
      <button className="btn primary" data-testid="rma-receive" disabled={busy} onClick={() => act('receive', 'Received into the returns bay', {})}>
        {I.check({ size: 14 })} Receive
      </button>
    );
  }
  if (s === 'received') {
    return <span className="dim" style={{ fontSize: 12, flex: 1 }}>Disposition each line above (restock / refurbish / scrap), then refund.</span>;
  }
  if (s === 'dispositioned') {
    return (
      <button className="btn primary" data-testid="rma-refund" disabled={busy} onClick={() => act('refund', 'Refund requested — credit note minting', { refund_method: 'credit_memo' })}>
        {I.check({ size: 14 })} Issue refund / credit note
      </button>
    );
  }
  if (s === 'refunded') {
    return <span className="row g6" style={{ color: 'var(--ok)' }}>{I.check({ size: 15 })} Closed{sel.credit_note?.credit_note_no ? ` · ${sel.credit_note.credit_note_no}` : ''}</span>;
  }
  return null;
}
