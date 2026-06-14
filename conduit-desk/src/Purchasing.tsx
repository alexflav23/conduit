import React, { useState, useEffect, useCallback } from 'react';
import { apiFetch } from './api';
import { asArray } from './state';
import { PageHead, Card, Chip, Drawer, Money, LayerNote, AuditRef, EmptyRow, Skeleton, SkeletonRow } from './kit/kit';
import { I } from './kit/icons';

// 19 — Purchasing / receiving / stock ops (spec/ui/19-purchasing.md, doc 07 M9). The supply-in side: purchase
// orders to the contract manufacturers (Volex / Luxshare), receiving against them (GRN → stock lands at the
// rolled-forward batch landed cost), and governed STOCK OPERATIONS (cycle-count / transfer / write-off) under
// two-person MAKER-CHECKER — every adjustment immutably logged and ledger-posted. The hero is the governance:
// a write-off is money leaving the books, so self-approval is hard-blocked (SoD) and an approved op is never
// edited (corrections are new ops). The subtle accuracy story is the inbound-tranche landed-cost roll-forward
// (freight + duty conserve into each unit's cost, not averaged).
//
// Auto-loads on mount + when ctx.entity changes (no Load button). Four states everywhere: loading (skeleton) /
// empty (EmptyRow) / 403 (LayerNote — requires view:purchasing) / error. PO value + landed cost are
// commercial/profitability layers and COLLAPSE (never £0) for a volume-only viewer; PO qty/dates are volume.

type AnyRole = { name?: string; layers?: string[] };

interface PurchasingProps {
  role: AnyRole;
  ctx: { entity?: string; market?: string; period?: string; scenario?: string };
  toast: (m: string, k?: string) => void;
}

type Res = { status: number; json: any } | null;
function viewState(res: Res, rows: unknown): 'loading' | 'forbidden' | 'error' | 'empty' | 'ready' {
  if (res === null) return 'loading';
  if (res.status === 401 || res.status === 403) return 'forbidden';
  if (res.status >= 400) return 'error';
  return asArray(rows).length === 0 ? 'empty' : 'ready';
}

const SUBTABS: [string, string][] = [['pos', 'Purchase orders'], ['ops', 'Stock operations']];

export function Purchasing({ role, ctx, toast }: PurchasingProps) {
  const [sub, setSub] = useState<'pos' | 'ops'>('pos');

  return (
    <>
      <PageHead
        crumb="Supply · supply-in (doc 07 M9) · Volex / Luxshare"
        title="Purchasing"
        sub="Purchase orders to the contract manufacturers, receiving against them at rolled-forward landed cost, and governed stock operations under two-person maker-checker — every adjustment immutably logged and ledger-posted."
        right={
          <div className="seg" data-testid="pur-subtabs">
            {SUBTABS.map(([k, l]) => (
              <button key={k} className={sub === k ? 'on' : ''} data-testid={`pur-tab-${k}`} onClick={() => setSub(k as 'pos' | 'ops')}>{l}</button>
            ))}
          </div>
        }
      />
      {sub === 'pos' ? <PurchaseOrders role={role} ctx={ctx} toast={toast} /> : <StockOps role={role} ctx={ctx} toast={toast} />}
    </>
  );
}

// ---------------------------------------------------------------------------------------------------------------
// Purchase orders → Drawer (lines, expected vs received, the inbound-tranche landed-cost ladder, GRN receiving)
// ---------------------------------------------------------------------------------------------------------------

function PurchaseOrders({ role, ctx, toast }: PurchasingProps) {
  const layers = asArray<string>(role?.layers);
  const hasCommercial = layers.length === 0 || layers.indexOf('commercial') >= 0;
  const hasProfit = layers.length === 0 || layers.indexOf('profitability') >= 0;

  const [res, setRes] = useState<Res>(null);
  const [sel, setSel] = useState<any | null>(null);
  const [detail, setDetail] = useState<Res>(null);
  const [grnQty, setGrnQty] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);

  const entity = ctx?.entity || '';

  const load = useCallback(async () => {
    setRes(null);
    const q = entity ? `?entity=${encodeURIComponent(entity)}` : '';
    setRes(await apiFetch(`/api/v1/purchasing/orders${q}`));
  }, [entity]);

  useEffect(() => { load(); }, [load]);

  const openPo = useCallback(async (po: any) => {
    setSel(po);
    setDetail(null);
    setGrnQty({});
    setDetail(await apiFetch(`/api/v1/purchasing/orders/${encodeURIComponent(po.id)}`));
  }, []);

  const rows = asArray<any>((res && res.status === 200) ? (res.json?.rows ?? res.json) : null);
  const state = viewState(res, rows);

  const po = detail && detail.status === 200 ? (detail.json?.po ?? detail.json) : null;
  const lines = asArray<any>(po?.lines);
  const tranches = asArray<any>(po?.tranches);
  const canReceive = !!(res?.json?.can_receive ?? po?.can_receive ?? detail?.json?.can_receive);

  const receive = async (lineId: string) => {
    if (!sel) return;
    const qty = parseInt(grnQty[lineId], 10);
    if (!qty || qty <= 0) { toast('Enter a quantity to receive', 'warn'); return; }
    setBusy(true);
    const r = await apiFetch(`/api/v1/purchasing/orders/${encodeURIComponent(sel.id)}/receipts`, {
      method: 'POST',
      body: JSON.stringify({ line_id: lineId, qty }),
    });
    setBusy(false);
    if (r.status === 200 || r.status === 201) {
      const variance = r.json?.variance;
      toast(`GRN booked — ${r.json?.received ?? qty} units into stock${variance ? ` · variance ${variance > 0 ? '+' : ''}${variance}` : ''}`, variance ? 'warn' : 'ok');
      setGrnQty((g) => ({ ...g, [lineId]: '' }));
      openPo(sel);
      load();
      return;
    }
    if (r.status === 403) { toast('Receiving requires procurement / finance rights', 'err'); return; }
    toast(`GRN failed: ${r.json?.message ?? r.status}`, 'err');
  };

  return (
    <>
      {state === 'loading' && (
        <Card title="Loading purchase orders…" icon={I.list} className="tablewrap" style={{ padding: 0 }}>
          <table className="tbl"><tbody>{[0, 1, 2, 3].map((i) => <SkeletonRow key={i} cols={8} />)}</tbody></table>
        </Card>
      )}

      {state === 'forbidden' && (
        <Card title="Purchasing is withheld" icon={I.shield} style={{ maxWidth: 620 }}>
          <LayerNote>hidden — requires <b>view:purchasing</b>. Your role's server-side projection doesn't carry the supply-in view; the data never reaches the browser.</LayerNote>
        </Card>
      )}

      {state === 'error' && (
        <Card title="Could not load purchase orders" icon={I.alert} style={{ maxWidth: 620 }}>
          <div className="banner danger" data-testid="pur-error">{I.alert()}<div>The purchasing request failed ({res?.status}). The PO book is unreachable right now — try again shortly.</div></div>
        </Card>
      )}

      {state === 'empty' && (
        <Card title="No open purchase orders" icon={I.list} style={{ maxWidth: 620 }}>
          <div className="dim" data-testid="pur-empty" style={{ fontSize: 13.5, lineHeight: 1.55 }}>
            No purchase orders to the contract manufacturers{entity ? <> for <span className="mono">{entity}</span></> : ''} yet. A PO is raised against an approved supply commitment.
          </div>
        </Card>
      )}

      {state === 'ready' && (
        <Card title="Purchase orders" icon={I.list} aux="to the contract manufacturers · expected vs received" className="tablewrap" style={{ padding: 0 }}>
          <table className="tbl" data-testid="pur-po-table">
            <thead><tr><th>PO</th><th>CM</th><th>Location</th><th>Status</th><th className="num">Expected</th><th className="num">Received</th><th className="num">Value</th><th /></tr></thead>
            <tbody>
              {rows.map((p) => {
                const exp = Number(p.total_expected ?? p.totalExpected ?? 0);
                const rec = Number(p.total_received ?? p.totalReceived ?? 0);
                const pct = exp ? Math.min(100, (rec / exp) * 100) : 0;
                const variance = rec > exp;
                return (
                  <tr key={p.id} data-testid="pur-po-row" tabIndex={0} style={{ cursor: 'pointer' }} onClick={() => openPo(p)} onKeyDown={(e) => e.key === 'Enter' && openPo(p)}>
                    <td><b className="mono" style={{ fontSize: 11.5 }}>{p.id}</b></td>
                    <td>{p.cm ?? p.supplier}</td>
                    <td><span className="chip neutral"><span className="d" />{p.location ?? '—'}</span></td>
                    <td><Chip s={p.status === 'received' ? 'approved' : p.status === 'open' ? 'open' : p.status}>{String(p.status || '').replace(/_/g, ' ')}</Chip></td>
                    <td className="num">{exp.toLocaleString('en-GB')}</td>
                    <td className="num">
                      {rec.toLocaleString('en-GB')}
                      <div style={{ marginTop: 3, height: 4, borderRadius: 3, background: 'var(--surface3)', overflow: 'hidden' }}>
                        <div style={{ width: pct + '%', height: '100%', background: variance ? 'var(--warn)' : 'var(--ok)' }} />
                      </div>
                    </td>
                    <td className="num">
                      {hasCommercial ? <Money value={p.value} layer="commercial" role={role as any} /> : <span className="dim">— layer</span>}
                    </td>
                    <td>{I.chevR({ size: 15, style: { color: 'var(--faint)' } } as any)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </Card>
      )}

      <Drawer
        open={!!sel}
        onClose={() => { setSel(null); setDetail(null); }}
        width={620}
        chip={sel && (
          <div className="row g8">
            <span className="chip neutral"><span className="d" />{sel.cm ?? sel.supplier}</span>
            <Chip s={sel.status === 'received' ? 'approved' : 'open'}>{String(sel.status || '').replace(/_/g, ' ')}</Chip>
          </div>
        )}
        title={sel ? sel.id : ''}
        sub={sel ? `raised ${sel.raised ?? sel.raised_at ?? '—'} · ${sel.location ?? ''}` : ''}
      >
        {sel && detail === null && <Skeleton lines={5} />}
        {sel && detail && detail.status === 403 && <LayerNote>hidden — requires <b>view:purchasing</b>.</LayerNote>}
        {sel && detail && detail.status >= 400 && detail.status !== 403 && (
          <div className="banner danger">{I.alert()}<div>Could not load this PO ({detail.status}).</div></div>
        )}
        {sel && po && (
          <>
            {!canReceive && (
              <div className="banner warn" style={{ marginBottom: 16 }} data-testid="pur-no-receive">
                {I.shield()}<div>Receiving needs procurement / finance rights — you can review this PO but not book a GRN.</div>
              </div>
            )}

            <div className="dim" style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 8 }}>Lines · expected vs received</div>
            <div className="tablewrap" style={{ border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden', marginBottom: 18 }}>
              <table className="tbl" data-testid="pur-lines">
                <thead><tr><th>Variant</th><th className="num">Exp.</th><th className="num">Rec.</th><th className="num">Unit cost</th><th>Receive (GRN)</th></tr></thead>
                <tbody>
                  {lines.length === 0 && <EmptyRow cols={5}>No lines on this PO.</EmptyRow>}
                  {lines.map((l) => {
                    const exp = Number(l.expected ?? 0);
                    const rec = Number(l.received ?? 0);
                    const remaining = exp - rec;
                    const over = rec > exp;
                    return (
                      <tr key={l.id} className={over ? 'sel' : ''} style={{ cursor: 'default' }}>
                        <td className="mono" style={{ fontSize: 11 }}>{l.sku ?? l.variant}</td>
                        <td className="num">{exp}</td>
                        <td className="num">
                          {rec}
                          {remaining > 0 && <span className="dim"> /{remaining} left</span>}
                          {over && <span style={{ color: 'var(--warn)' }}> · +{rec - exp} over</span>}
                        </td>
                        <td className="num">{hasProfit ? <Money value={l.unit_cost} layer="profitability" role={role as any} /> : <span className="dim">— layer</span>}</td>
                        <td>
                          {remaining > 0 ? (
                            <div className="row g6">
                              <input
                                className="fld" style={{ width: 60 }} placeholder="0" inputMode="numeric"
                                data-testid="pur-grn-qty"
                                value={grnQty[l.id] || ''}
                                disabled={!canReceive}
                                onChange={(e) => setGrnQty((g) => ({ ...g, [l.id]: e.target.value }))}
                                onKeyDown={(e) => e.key === 'Enter' && canReceive && receive(l.id)}
                              />
                              <button className="btn sm primary" data-testid="pur-grn-book" disabled={!canReceive || busy} onClick={() => receive(l.id)}>Book</button>
                            </div>
                          ) : (
                            <span className="row g6" style={{ color: 'var(--ok)' }}>{I.check({ size: 13 })} complete</span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            <div className="dim" style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 8 }}>Inbound tranches · freight + duty roll into landed cost</div>
            <div className="tablewrap" style={{ border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden' }}>
              <table className="tbl" data-testid="pur-tranches">
                <thead><tr><th>Tranche</th><th className="num">Qty</th><th className="num">Freight</th><th className="num">Duty</th><th className="num">+/unit</th><th>ETA</th><th>Status</th></tr></thead>
                <tbody>
                  {tranches.length === 0 && <EmptyRow cols={7}>No inbound tranches scheduled.</EmptyRow>}
                  {tranches.map((t) => (
                    <tr key={t.id} style={{ cursor: 'default' }}>
                      <td className="mono" style={{ fontSize: 11 }}>{t.id}</td>
                      <td className="num">{t.qty}</td>
                      <td className="num">{hasProfit ? <Money value={t.freight} layer="profitability" role={role as any} /> : <span className="dim">—</span>}</td>
                      <td className="num">{hasProfit ? <Money value={t.duty} layer="profitability" role={role as any} /> : <span className="dim">—</span>}</td>
                      <td className="num">{hasProfit ? <b><Money value={t.addon_per_unit} layer="profitability" role={role as any} /></b> : <span className="dim">— layer</span>}</td>
                      <td className="dim">{t.eta ?? '—'}</td>
                      <td><Chip s={t.status === 'received' ? 'approved' : t.status === 'in_transit' ? 'monitoring' : 'open'}>{String(t.status || '').replace(/_/g, ' ')}</Chip></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {hasProfit ? (
              <div className="layer-note" data-testid="pur-landed-note">{I.layers()}Freight and duty don't expense separately — they conserve into each unit's landed cost (specific-identification), so margin is exact, not averaged.</div>
            ) : (
              <LayerNote>Landed-cost detail hidden — requires the <b>profitability</b> layer.</LayerNote>
            )}

            {po.commitment_ref && (
              <div className="row g8" style={{ marginTop: 14 }}>
                <span className="dim" style={{ fontSize: 12 }}>Commitment ladder</span>
                <AuditRef id={po.commitment_ref} />
              </div>
            )}
          </>
        )}
      </Drawer>
    </>
  );
}

// ---------------------------------------------------------------------------------------------------------------
// Stock operations — the maker-checker queue (cycle-count / transfer / write-off). Self-approval is hard-blocked.
// ---------------------------------------------------------------------------------------------------------------

const OP_LABEL: Record<string, string> = { write_off: 'Write-off', cycle_count: 'Cycle count', transfer: 'Transfer' };
const OP_CHIP: Record<string, string> = { write_off: 'danger', transfer: 'accent', cycle_count: 'neutral' };

function StockOps({ role, ctx, toast }: PurchasingProps) {
  const layers = asArray<string>(role?.layers);
  const hasProfit = layers.length === 0 || layers.indexOf('profitability') >= 0;

  const [res, setRes] = useState<Res>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const entity = ctx?.entity || '';

  const load = useCallback(async () => {
    setRes(null);
    const q = entity ? `?entity=${encodeURIComponent(entity)}` : '';
    setRes(await apiFetch(`/api/v1/purchasing/stock-ops${q}`));
  }, [entity]);

  useEffect(() => { load(); }, [load]);

  const rows = asArray<any>((res && res.status === 200) ? (res.json?.rows ?? res.json) : null);
  const state = viewState(res, rows);
  const canApprove = !!(res?.json?.can_approve);
  const me = role?.name || '';
  const pending = rows.filter((o) => o.status === 'proposed').length;

  const decide = async (op: any, decision: 'approve' | 'reject') => {
    setBusy(op.id);
    const r = await apiFetch(`/api/v1/purchasing/stock-ops/${encodeURIComponent(op.id)}/decision`, {
      method: 'POST',
      body: JSON.stringify({ decision }),
    });
    setBusy(null);
    if (r.status === 200) {
      const st = r.json?.status ?? (decision === 'approve' ? 'approved' : 'rejected');
      toast(`Stock op ${st}${r.json?.ledger_ref ? ` · posted ${r.json.ledger_ref}` : ''}`, st === 'approved' ? 'ok' : 'warn');
      load();
      return;
    }
    if (r.status === 403 || r.status === 409) { toast(r.json?.message || 'Self-approval blocked — a checker must differ from the proposer (SoD)', 'err'); return; }
    toast(`Decision failed: ${r.json?.message ?? r.status}`, 'err');
  };

  return (
    <Card title="Stock operations" icon={I.shield} aux="cycle-count · transfer · write-off · proposed → approved (maker ≠ checker)" className="tablewrap" style={{ padding: 0 }}>
      <div className="banner info" style={{ margin: 14 }} data-testid="pur-mc-banner">
        {I.shield()}
        <div>
          <b>Maker-checker.</b> A write-off is money leaving the books — it must be proposed by one person and approved by another.
          Self-approval is blocked, and an approved op is immutably logged, never edited (corrections are new ops).
          {pending > 0 && ` ${pending} awaiting a checker.`}
        </div>
      </div>

      {state === 'loading' && (
        <table className="tbl"><tbody>{[0, 1, 2].map((i) => <SkeletonRow key={i} cols={9} />)}</tbody></table>
      )}

      {state === 'forbidden' && (
        <div style={{ padding: 16 }}>
          <LayerNote>hidden — requires <b>view:purchasing</b> (with <b>edit:stock_op</b> to act). The queue never reaches the browser.</LayerNote>
        </div>
      )}

      {state === 'error' && (
        <div className="banner danger" style={{ margin: 14 }} data-testid="pur-ops-error">{I.alert()}<div>The stock-ops queue failed to load ({res?.status}). Try again shortly.</div></div>
      )}

      {(state === 'empty' || state === 'ready') && (
        <table className="tbl" data-testid="pur-ops-table">
          <thead><tr><th>Op</th><th>Kind</th><th>Variant</th><th>Location</th><th className="num">Qty</th><th className="num">Value</th><th>Reason / proposer</th><th>Status</th><th>Decision</th></tr></thead>
          <tbody>
            {state === 'empty' && <EmptyRow cols={9}>No stock operations proposed. A cycle-count, transfer or write-off appears here for a second person to approve.</EmptyRow>}
            {rows.map((o) => {
              const isProposer = !!me && (o.proposer === me || o.proposed_by === me);
              const blockedSelf = o.status === 'proposed' && isProposer;
              const disabled = !canApprove || blockedSelf || busy === o.id;
              const tip = blockedSelf ? 'you proposed this — SoD blocks self-approval' : !canApprove ? 'requires edit:stock_op (a checker different from the proposer)' : '';
              const qty = Number(o.qty ?? 0);
              return (
                <tr key={o.id} data-testid="pur-op-row" style={{ cursor: 'default' }}>
                  <td><b className="mono" style={{ fontSize: 11 }}>{o.id}</b></td>
                  <td><span className={'chip ' + (OP_CHIP[o.kind] || 'neutral')}><span className="d" />{OP_LABEL[o.kind] ?? o.kind}</span></td>
                  <td className="mono dim" style={{ fontSize: 11 }}>{o.sku ?? o.variant}</td>
                  <td className="dim">{o.location ?? '—'}</td>
                  <td className="num">{qty > 0 ? '+' : ''}{qty}</td>
                  <td className="num">{hasProfit ? <Money value={o.value} layer="profitability" role={role as any} /> : <span className="dim">— layer</span>}</td>
                  <td>
                    <div style={{ fontSize: 12, maxWidth: 260 }}>{o.reason}</div>
                    <div className="dim" style={{ fontSize: 10.5 }}>
                      by {o.proposer ?? o.proposed_by ?? '—'} · {o.at ?? o.proposed_at ?? ''}
                      {(o.approver || o.approved_by) && <> · {I.check({ size: 10 })} {o.approver ?? o.approved_by}</>}
                      {o.ledger_ref && <> · {o.ledger_ref}</>}
                    </div>
                  </td>
                  <td>
                    {o.status === 'approved' && o.ledger_ref
                      ? <span data-testid="pur-op-audit"><AuditRef id={o.ledger_ref} /></span>
                      : <Chip s={o.status === 'approved' ? 'approved' : o.status === 'rejected' ? 'rejected' : 'proposed'}>{o.status}</Chip>}
                  </td>
                  <td>
                    {o.status === 'proposed' && (
                      <div className="row g6">
                        <button className="btn sm primary" data-testid="pur-op-approve" disabled={disabled} title={tip} onClick={() => decide(o, 'approve')}>Approve</button>
                        <button className="btn sm" data-testid="pur-op-reject" disabled={!canApprove || busy === o.id} onClick={() => decide(o, 'reject')}>Reject</button>
                      </div>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}

      {state === 'ready' && !canApprove && (
        <div className="layer-note" style={{ padding: '10px 16px' }} data-testid="pur-ops-readonly">
          {I.shield()}You can view the queue, but approving a stock op requires <b>edit:stock_op</b> — and a checker different from the proposer.
        </div>
      )}
    </Card>
  );
}
