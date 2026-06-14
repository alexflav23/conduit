import React, { useState } from 'react';
import { useApi } from './lib/query';
import { ApiError } from './lib/client';
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
// Data layer: there is NO purchasing/receiving/stock-ops backend in this environment yet (the only procurement
// routes — ProcurementRoutes — serve the inter-entity transfer price-list + ic_match catalogue, NOT POs / GRN /
// stock-ops). So the lists fetch via useApi against the would-be paths and the 404 surfaces honestly as the
// "Not available in this environment yet" panel — no guessed payloads, no stuck skeletons. The four other
// states (loading skeleton / forbidden LayerNote / empty / inline error) are kept so the screen lights up the
// instant the route is wired. Money stays layer-aware (collapse, never £0).

type AnyRole = { name?: string; layers?: string[] };

interface PurchasingProps {
  role: AnyRole;
  ctx: { entity?: string; market?: string; period?: string; scenario?: string };
  toast: (m: string, k?: string) => void;
}

const layersOf = (role: AnyRole): string[] => (Array.isArray(role?.layers) ? role.layers : []);
const hasLayer = (role: AnyRole, l: string): boolean => {
  const ls = layersOf(role);
  return ls.length === 0 || ls.indexOf(l) >= 0;
};

const SUBTABS: [string, string][] = [['pos', 'Purchase orders'], ['ops', 'Stock operations']];

function Unbacked({ what, testid }: { what: string; testid: string }) {
  return (
    <Card style={{ padding: '34px 28px', textAlign: 'center' }} data-testid={testid}>
      <div style={{ display: 'grid', placeItems: 'center', gap: 10 }}>
        <span style={{ width: 44, height: 44, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'var(--panel-2)' }}>{I.flag({ size: 22 })}</span>
        <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>Not available in this environment yet</div>
        <div className="dim" style={{ fontSize: 12.5, maxWidth: 480 }}>{what}</div>
      </div>
    </Card>
  );
}

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

interface PoLine { id: string; sku?: string; variant?: string; expected?: number; received?: number; unit_cost?: string | number }
interface PoTranche { id: string; qty?: number; freight?: string | number; duty?: string | number; addon_per_unit?: string | number; eta?: string; status?: string }
interface PoRow {
  id: string;
  cm?: string;
  supplier?: string;
  location?: string;
  status?: string;
  raised?: string;
  raised_at?: string;
  total_expected?: number;
  total_received?: number;
  value?: string | number;
}
interface PoDetail extends PoRow { lines?: PoLine[]; tranches?: PoTranche[]; can_receive?: boolean; commitment_ref?: string }
type PoList = { rows?: PoRow[]; can_receive?: boolean } | PoRow[] | null;

const poRows = (d: PoList): PoRow[] => (Array.isArray(d) ? d : Array.isArray(d?.rows) ? d!.rows! : []);

function PurchaseOrders({ role, ctx }: PurchasingProps) {
  const hasCommercial = hasLayer(role, 'commercial');
  const hasProfit = hasLayer(role, 'profitability');

  const entity = ctx?.entity || '';
  const q = entity ? `?entity=${encodeURIComponent(entity)}` : '';

  const list = useApi<PoList>(['purchasing', 'orders', entity], `/api/v1/purchasing/orders${q}`);
  const rows = poRows(list.data ?? null);

  const [sel, setSel] = useState<PoRow | null>(null);

  const detail = useApi<PoDetail>(
    ['purchasing', 'order', sel?.id],
    `/api/v1/purchasing/orders/${encodeURIComponent(sel?.id ?? '')}`,
    { enabled: !!sel },
  );

  const po = detail.data ?? null;
  const lines = Array.isArray(po?.lines) ? po!.lines! : [];
  const tranches = Array.isArray(po?.tranches) ? po!.tranches! : [];
  const canReceive = !!po?.can_receive;

  if (list.isError && list.error.notImplemented) {
    return <Unbacked testid="pur-po-unbacked" what="The PO book appears once purchasing & receiving (POs to the contract manufacturers, GRN receiving at rolled-forward landed cost) is wired in this environment." />;
  }

  return (
    <>
      {list.isLoading && (
        <Card title="Loading purchase orders…" icon={I.list} className="tablewrap" style={{ padding: 0 }}>
          <table className="tbl"><tbody>{[0, 1, 2, 3].map((i) => <SkeletonRow key={i} cols={8} />)}</tbody></table>
        </Card>
      )}

      {list.isError && list.error.forbidden && (
        <Card title="Purchasing is withheld" icon={I.shield} style={{ maxWidth: 620 }}>
          <LayerNote>hidden — requires <b>view:purchasing</b>. Your role's server-side projection doesn't carry the supply-in view; the data never reaches the browser.</LayerNote>
        </Card>
      )}

      {list.isError && !list.error.forbidden && !list.error.notImplemented && (
        <Card title="Could not load purchase orders" icon={I.alert} style={{ maxWidth: 620 }}>
          <div className="banner danger" data-testid="pur-error">{I.alert()}<div>The purchasing request failed ({list.error.status}). The PO book is unreachable right now — try again shortly.</div></div>
        </Card>
      )}

      {list.isSuccess && rows.length === 0 && (
        <Card title="No open purchase orders" icon={I.list} style={{ maxWidth: 620 }}>
          <div className="dim" data-testid="pur-empty" style={{ fontSize: 13.5, lineHeight: 1.55 }}>
            No purchase orders to the contract manufacturers{entity ? <> for <span className="mono">{entity}</span></> : ''} yet. A PO is raised against an approved supply commitment.
          </div>
        </Card>
      )}

      {list.isSuccess && rows.length > 0 && (
        <Card title="Purchase orders" icon={I.list} aux="to the contract manufacturers · expected vs received" className="tablewrap" style={{ padding: 0 }}>
          <table className="tbl" data-testid="pur-po-table">
            <thead><tr><th>PO</th><th>CM</th><th>Location</th><th>Status</th><th className="num">Expected</th><th className="num">Received</th><th className="num">Value</th><th /></tr></thead>
            <tbody>
              {rows.map((p) => {
                const exp = Number(p.total_expected ?? 0);
                const rec = Number(p.total_received ?? 0);
                const pct = exp ? Math.min(100, (rec / exp) * 100) : 0;
                const variance = rec > exp;
                return (
                  <tr key={p.id} data-testid="pur-po-row" tabIndex={0} style={{ cursor: 'pointer' }} onClick={() => setSel(p)} onKeyDown={(e) => e.key === 'Enter' && setSel(p)}>
                    <td><b className="mono" style={{ fontSize: 11.5 }}>{p.id}</b></td>
                    <td>{p.cm ?? p.supplier}</td>
                    <td><span className="chip neutral"><span className="d" />{p.location ?? '—'}</span></td>
                    <td><Chip s={p.status === 'received' ? 'approved' : p.status === 'open' ? 'open' : (p.status ?? '')}>{String(p.status || '').replace(/_/g, ' ')}</Chip></td>
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
        onClose={() => setSel(null)}
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
        {sel && detail.isLoading && <Skeleton lines={5} />}
        {sel && detail.isError && detail.error.notImplemented && (
          <Unbacked testid="pur-po-detail-unbacked" what="PO detail (lines, expected vs received, the inbound-tranche landed-cost ladder, GRN receiving) appears once purchasing is wired in this environment." />
        )}
        {sel && detail.isError && detail.error.forbidden && <LayerNote>hidden — requires <b>view:purchasing</b>.</LayerNote>}
        {sel && detail.isError && !detail.error.forbidden && !detail.error.notImplemented && (
          <div className="banner danger">{I.alert()}<div>Could not load this PO ({detail.error.status}).</div></div>
        )}
        {sel && detail.isSuccess && po && (
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
                            <span className="dim" style={{ fontSize: 11.5 }}>receiving unavailable</span>
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

interface StockOp {
  id: string;
  kind: string;
  sku?: string;
  variant?: string;
  location?: string;
  qty?: number;
  value?: string | number;
  reason?: string;
  status?: string;
  proposer?: string;
  proposed_by?: string;
  at?: string;
  proposed_at?: string;
  approver?: string;
  approved_by?: string;
  ledger_ref?: string;
}
type OpsList = { rows?: StockOp[] } | StockOp[] | null;
const opRows = (d: OpsList): StockOp[] => (Array.isArray(d) ? d : Array.isArray(d?.rows) ? d!.rows! : []);

function StockOps({ role, ctx }: PurchasingProps) {
  const hasProfit = hasLayer(role, 'profitability');

  const entity = ctx?.entity || '';
  const q = entity ? `?entity=${encodeURIComponent(entity)}` : '';

  const ops = useApi<OpsList>(['purchasing', 'stock-ops', entity], `/api/v1/purchasing/stock-ops${q}`);
  const rows = opRows(ops.data ?? null);
  const pending = rows.filter((o) => o.status === 'proposed').length;

  if (ops.isError && ops.error.notImplemented) {
    return <Unbacked testid="pur-ops-unbacked" what="The stock-operations queue (cycle-count / transfer / write-off under two-person maker-checker) appears once governed stock ops are wired in this environment." />;
  }

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

      {ops.isLoading && (
        <table className="tbl"><tbody>{[0, 1, 2].map((i) => <SkeletonRow key={i} cols={9} />)}</tbody></table>
      )}

      {ops.isError && ops.error.forbidden && (
        <div style={{ padding: 16 }}>
          <LayerNote>hidden — requires <b>view:purchasing</b> (with <b>edit:stock_op</b> to act). The queue never reaches the browser.</LayerNote>
        </div>
      )}

      {ops.isError && !ops.error.forbidden && !ops.error.notImplemented && (
        <div className="banner danger" style={{ margin: 14 }} data-testid="pur-ops-error">{I.alert()}<div>The stock-ops queue failed to load ({ops.error.status}). Try again shortly.</div></div>
      )}

      {ops.isSuccess && (
        <table className="tbl" data-testid="pur-ops-table">
          <thead><tr><th>Op</th><th>Kind</th><th>Variant</th><th>Location</th><th className="num">Qty</th><th className="num">Value</th><th>Reason / proposer</th><th>Status</th></tr></thead>
          <tbody>
            {rows.length === 0 && <EmptyRow cols={8}>No stock operations proposed. A cycle-count, transfer or write-off appears here for a second person to approve.</EmptyRow>}
            {rows.map((o) => {
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
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </Card>
  );
}
