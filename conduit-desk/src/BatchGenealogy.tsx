import React, { useState } from 'react';
import { useApi } from './lib/query';
import { ApiError } from './lib/client';
import { marketId } from './api';
import {
  PageHead, Card, Chip, Money, LayerNote, AuditRef, EmptyRow, SkeletonRow, Skeleton, gbp,
} from './kit/kit';
import { I } from './kit/icons';

// Batch / landed-cost / serial genealogy (spec/ui/20-batch.md): the traceability spine (doc 07 M7).
// The hero is BIDIRECTIONAL traceability — type a serial, see its whole life and its EXACT landed cost
// (specific-identification, never a weighted average); type a batch, see every unit it became (the recall list).
// Serial/batch identity + status are the `volume` layer; landed_unit_cost + the cost breakdown are
// `profitability` and COLLAPSE for a viewer without it (never £0.00).
// M7 is a Phase-2 milestone: there is no inventory/serial/batch/genealogy route in this environment yet, so
// every surface is wired through useApi and honestly renders "Not available in this environment yet" off the
// 404 (notImplemented) — no stuck skeletons, no guessed numbers. The four-state shell (loading/forbidden/
// notImplemented/error) is in place so the moment the backend lands the page just lights up.

type Ctx = { entity: string; market: string; period: string; scenario: string };
type Role = { token?: string; name?: string; title?: string; layers?: string[] };
type Surface = 'loading' | 'forbidden' | 'notImplemented' | 'error' | 'empty' | 'ready';

const SSTAT: Record<string, string> = {
  in_stock: 'ok', allocated: 'warn', dispatched: 'accent', delivered: 'neutral', activated: 'ok', returned: 'danger',
};
const human = (s: string) => (s || '').replace(/_/g, ' ');

const qs = (o: Record<string, string | undefined>) =>
  Object.entries(o)
    .filter(([, v]) => v != null && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(v as string)}`)
    .join('&');

const surfaceOf = (loading: boolean, err: ApiError | null, empty: boolean): Surface =>
  loading
    ? 'loading'
    : err?.forbidden
      ? 'forbidden'
      : err?.notImplemented
        ? 'notImplemented'
        : err
          ? 'error'
          : empty
            ? 'empty'
            : 'ready';

// the styled "endpoint isn't built yet" panel — a clean card body, never a stuck skeleton
function UnbackedPanel({ icon, line }: { icon: React.ReactNode; line: string }) {
  return (
    <div data-testid="batch-unbacked" style={{ display: 'grid', placeItems: 'center', gap: 10, padding: '40px 24px', textAlign: 'center' }}>
      <span style={{ width: 44, height: 44, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'var(--panel-2)' }}>{icon}</span>
      <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>Not available in this environment yet</div>
      <div className="dim" style={{ fontSize: 12.5, maxWidth: 480 }}>{line}</div>
    </div>
  );
}

// a card-body empty/error notice (EmptyRow is <tr>-only, so this is the non-table sibling)
function EmptyRowless({ children }: { children: React.ReactNode }) {
  return <div className="dim" style={{ padding: '22px 8px', textAlign: 'center', fontSize: 12.5 }}>{children}</div>;
}

export function BatchGenealogy({ role, ctx }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const r = (role || {}) as Role;
  const c = (ctx || {}) as Ctx;
  const hasProfit = (r.layers || []).indexOf('profitability') >= 0;
  const [mode, setMode] = useState<'serial' | 'batch'>('serial');

  return (
    <div className="page" style={{ maxWidth: 1320 }}>
      <PageHead
        crumb={'Traceability spine · doc 07 M7 · specific-identification'}
        title="Batch & genealogy"
        sub={
          <span style={{ display: 'block', maxWidth: 820 }}>
            Every serial traces to its lot, its exact landed cost, the order it shipped on and the customer — and
            backward, a batch to all its serials. The recall, warranty and cost-of-a-specific-unit answer.
          </span>
        }
        right={<span className="stale"><span className="pulse" />entity {(c.entity || '—').slice(0, 8)}</span>}
      />

      <div className="seg" style={{ marginBottom: 18 }}>
        <button className={mode === 'serial' ? 'on' : ''} onClick={() => setMode('serial')}>Serial → genealogy</button>
        <button className={mode === 'batch' ? 'on' : ''} onClick={() => setMode('batch')}>Batch → roster</button>
      </div>

      {mode === 'serial'
        ? <SerialGenealogy ctx={c} role={r} hasProfit={hasProfit} />
        : <BatchRoster ctx={c} role={r} hasProfit={hasProfit} />}
    </div>
  );
}

// ---------------- the vertical genealogy chain node ----------------
function ChainNode({ label, title, sub, money, tone, last }: {
  label: string; title: React.ReactNode; sub?: React.ReactNode; money?: React.ReactNode; tone: string; last?: boolean;
}) {
  return (
    <div className="row g12" style={{ alignItems: 'flex-start', padding: '0 0 14px', position: 'relative' }}>
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', flex: '0 0 14px', paddingTop: 4 }}>
        <span style={{ width: 12, height: 12, borderRadius: 6, background: tone, flex: '0 0 12px' }} />
        {!last && <span style={{ width: 2, flex: 1, minHeight: 22, background: 'var(--border)', marginTop: 4 }} />}
      </div>
      <div style={{ flex: 1, paddingBottom: 4 }}>
        <div className="fldlabel" style={{ marginBottom: 2 }}>{label}</div>
        <div className="row between">
          <b style={{ fontSize: 13.5 }}>{title}</b>
          {money != null && money !== false && money}
        </div>
        {sub && <div className="dim" style={{ fontSize: 12 }}>{sub}</div>}
      </div>
    </div>
  );
}

// ============================================================ SERIAL → GENEALOGY
interface SerialNode { sn?: string; serial?: string; sku_label?: string; skuLabel?: string; location?: string; status?: string; order?: string; customer?: string; dispatched_at?: string; dispatchedAt?: string }
interface BatchNode { id?: string; received?: string; cm?: string; po?: string; location_name?: string; locationName?: string; landed_unit_cost?: number | string; unit_cost?: number | string; freight_per_unit?: number | string; duty_per_unit?: number | string; currency?: string }
interface Activation { activated_at?: string; installer?: string }
interface TimelineEvent { at?: string; event?: string; origin?: string }
interface Genealogy { serial?: SerialNode; batch?: BatchNode; activation?: Activation | null; timeline?: TimelineEvent[] }

function SerialGenealogy({ ctx, role, hasProfit }: { ctx: Ctx; role: Role; hasProfit: boolean }) {
  const viewer = { layers: role.layers || [] };
  const [q, setQ] = useState('');
  const [serial, setSerial] = useState('');
  const mid = marketId(ctx.market);

  const path = `/api/v1/inventory/genealogy?${qs({ entity: ctx.entity, market: mid, serial })}`;
  const query = useApi<Genealogy>(['batch-genealogy', ctx.entity, mid, serial], path, { enabled: serial.trim().length > 0 });
  const err = query.error as ApiError | null;
  const d = (query.data ?? null) as Genealogy | null;

  // Before a serial is typed the panel is idle (not loading); once a lookup runs it's wired through React Query.
  const idle = serial.trim().length === 0;
  const state: Surface = idle ? 'empty' : surfaceOf(query.isLoading, err, !d);

  const run = () => setSerial(q.trim());

  return (
    <div>
      <div className="loadbar">
        <span className="fldlabel">Serial</span>
        <input
          className="cellinput" style={{ width: 280, textAlign: 'left' }} value={q} data-testid="batch-serial-input"
          onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && run()}
          placeholder="Scan or type a serial number…"
        />
        <button className="btn primary" data-testid="batch-trace" onClick={run}>{I.search({ size: 13 })}Trace</button>
      </div>

      {state === 'loading' && (
        <div className="grid" style={{ gridTemplateColumns: '1.1fr 1fr', alignItems: 'start' }}>
          <Card title="Genealogy" icon={I.map}><Skeleton lines={6} /></Card>
          <Card title="Unit lifecycle" icon={I.clock}><Skeleton lines={5} /></Card>
        </div>
      )}

      {state === 'forbidden' && (
        <Card title="Genealogy" icon={I.map}>
          <LayerNote>Serial genealogy is withheld — requires the <b>volume</b> layer.</LayerNote>
        </Card>
      )}

      {state === 'notImplemented' && (
        <Card title="Genealogy" icon={I.map}>
          <UnbackedPanel icon={I.map({ size: 22 })}
            line="Serial genealogy lands with M7 traceability — type a serial and walk it to its lot, contract-manufacturing PO, sales order, customer and activation, with the exact specific-identification landed cost." />
        </Card>
      )}

      {state === 'error' && (
        <Card title="Genealogy" icon={I.map}>
          <EmptyRowless><span style={{ color: 'var(--danger)' }}>Could not trace this serial{err?.status ? ` (${err.status})` : ''} — try again shortly.</span></EmptyRowless>
        </Card>
      )}

      {state === 'empty' && (
        <Card title="Genealogy" icon={I.map}>
          <EmptyRowless>{idle ? 'Scan or type a serial number to trace it.' : 'No serial matches that number.'}</EmptyRowless>
        </Card>
      )}

      {state === 'ready' && d && (
        <div className="grid" style={{ gridTemplateColumns: '1.1fr 1fr', alignItems: 'start' }}>
          <Card title={'Genealogy · ' + (d.serial?.sn || serial)} icon={I.map}
            aux={<span className="dim" style={{ fontSize: 11.5 }}>serial → batch → PO → order → customer → activation</span>}>
            <ChainNode
              label="Serial" tone="var(--accent-bright)"
              title={<span className="mono">{d.serial?.sn || d.serial?.serial}</span>}
              sub={<>{[d.serial?.sku_label || d.serial?.skuLabel, d.serial?.location].filter(Boolean).join(' · ')} {d.serial?.status && <Chip s={SSTAT[d.serial.status] || 'neutral'}>{human(d.serial.status)}</Chip>}</>}
            />
            <ChainNode
              label="Batch / lot" tone="var(--plum)"
              title={<span className="mono"><AuditRef id={d.batch?.id} /></span>}
              sub={['received ' + (d.batch?.received || '—'), d.batch?.cm].filter(Boolean).join(' · ')}
              money={d.batch && <Money value={d.batch.landed_unit_cost} ccy={d.batch.currency} role={viewer} layer="profitability" />}
            />
            <ChainNode
              label="Contract-mfg PO" tone="var(--warn)"
              title={<span className="mono"><AuditRef id={d.batch?.po} /></span>}
              sub={[d.batch?.cm, d.batch?.location_name || d.batch?.locationName].filter(Boolean).join(' → ')}
            />
            {d.serial?.order ? (
              <ChainNode
                label="Sales order → customer" tone="var(--info)"
                title={<span className="mono"><AuditRef id={d.serial.order} /></span>}
                sub={[d.serial.customer, d.serial.dispatched_at || d.serial.dispatchedAt ? 'dispatched ' + (d.serial.dispatched_at || d.serial.dispatchedAt) : null].filter(Boolean).join(' · ')}
              />
            ) : (
              <ChainNode label="Sales order" title="Still in stock" sub="not yet allocated to an order" tone="var(--ok)" />
            )}
            {d.activation ? (
              <ChainNode
                label="Activation" tone="var(--ok)" last
                title={'Live · ' + (d.activation.activated_at || '—')}
                sub={['installer ' + (d.activation.installer || '—'), 'warranty clock started here'].join(' · ')}
              />
            ) : (
              <ChainNode label="Activation" tone="var(--faint)" last
                title="Not activated" sub="dispatched (sell-in) but no sell-through signal yet" />
            )}
          </Card>

          <div>
            {hasProfit ? (
              <Card title="Specific-identification cost" icon={I.layers} style={{ marginBottom: 14 }}>
                {d.batch?.landed_unit_cost != null ? (
                  <>
                    <div className="row between" style={{ alignItems: 'flex-end', marginBottom: 12 }}>
                      <div>
                        <div className="fldlabel">This exact unit cost</div>
                        <div style={{ fontFamily: 'var(--font-disp)', fontSize: 30, fontWeight: 600 }} className="num">{gbp(d.batch.landed_unit_cost, d.batch.currency)}</div>
                      </div>
                      <div className="dim" style={{ fontSize: 11.5, textAlign: 'right' }}>from lot {d.batch.id}<br />not a weighted average</div>
                    </div>
                    <div className="kv" style={{ fontSize: 12 }}>
                      <span className="k">Factory unit</span><span className="v num">{gbp(d.batch.unit_cost, d.batch.currency)}</span>
                      <span className="k">+ Freight / unit</span><span className="v num">{gbp(d.batch.freight_per_unit, d.batch.currency)}</span>
                      <span className="k">+ Duty / unit</span><span className="v num">{gbp(d.batch.duty_per_unit, d.batch.currency)}</span>
                      <span className="k" style={{ fontWeight: 600, color: 'var(--text)' }}>= Landed</span>
                      <span className="v num" style={{ fontWeight: 600 }}>{gbp(d.batch.landed_unit_cost, d.batch.currency)}</span>
                    </div>
                  </>
                ) : (
                  <div className="dim" style={{ padding: '8px 2px', fontSize: 12.5 }}>No landed cost recorded for this lot yet.</div>
                )}
              </Card>
            ) : (
              <Card title="Specific-identification cost" icon={I.layers} style={{ marginBottom: 14 }}>
                <LayerNote>Landed unit cost is hidden — requires the <b>profitability</b> layer. The figure is absent from your projection, never shown as £0.</LayerNote>
              </Card>
            )}

            <Card title="Unit lifecycle" icon={I.clock}
              aux={<span className="dim" style={{ fontSize: 11.5 }}>received → … → current state</span>}>
              <div className="tl">
                {(d.timeline ?? []).length === 0 && <div className="dim" style={{ padding: '6px 2px', fontSize: 12.5 }}>No lifecycle events recorded.</div>}
                {(d.timeline ?? []).map((e, i) => (
                  <div className="ev" key={i}>
                    <span className="when" style={{ minWidth: 96 }}>{e.at}</span>
                    <span className="etype">{human(e.event || '')}</span>
                    {e.origin && <span className="org">{e.origin}</span>}
                  </div>
                ))}
              </div>
            </Card>
          </div>
        </div>
      )}
    </div>
  );
}

// ============================================================ BATCH → ROSTER
interface BatchRow { id?: string; sku_label?: string; skuLabel?: string; cm?: string; qty?: number | string }
interface BatchListBody { rows?: BatchRow[] }
interface RosterSerial { sn?: string; serial?: string; status?: string; order?: string; customer?: string; dispatched_at?: string; dispatchedAt?: string }
interface RosterBatch extends BatchNode { sku_label?: string; skuLabel?: string; qty?: number | string; landed_value?: number | string }
interface RosterBody { batch?: RosterBatch; serials?: RosterSerial[]; rows?: RosterSerial[]; by_status?: Record<string, number>; byStatus?: Record<string, number> }

function BatchRoster({ ctx, role, hasProfit }: { ctx: Ctx; role: Role; hasProfit: boolean }) {
  const viewer = { layers: role.layers || [] };
  const [sel, setSel] = useState<string | null>(null);
  const mid = marketId(ctx.market);

  const listPath = `/api/v1/inventory/batches?${qs({ entity: ctx.entity, market: mid })}`;
  const listQuery = useApi<BatchListBody | BatchRow[]>(['batch-list', ctx.entity, mid], listPath);
  const listErr = listQuery.error as ApiError | null;
  const listData = (listQuery.data ?? null) as BatchListBody | BatchRow[] | null;
  const batches: BatchRow[] = Array.isArray(listData) ? listData : (listData?.rows ?? []);
  const listState = surfaceOf(listQuery.isLoading, listErr, batches.length === 0);

  const activeId = sel ?? batches[0]?.id ?? null;

  const rosterPath = `/api/v1/inventory/batches/${encodeURIComponent(activeId || '')}/roster?${qs({ entity: ctx.entity, market: mid })}`;
  const rosterQuery = useApi<RosterBody>(['batch-roster', ctx.entity, mid, activeId ?? ''], rosterPath, { enabled: !!activeId });
  const rosterErr = rosterQuery.error as ApiError | null;
  const d = (rosterQuery.data ?? null) as RosterBody | null;
  const serials: RosterSerial[] = d ? (d.serials ?? d.rows ?? []) : [];
  const byStatus = (d && (d.by_status || d.byStatus)) || {};
  const rosterState: Surface = !activeId ? 'empty' : surfaceOf(rosterQuery.isLoading, rosterErr, !d);

  return (
    <div className="grid" style={{ gridTemplateColumns: '300px 1fr', alignItems: 'start' }}>
      <Card title="Batches" icon={I.layers} style={{ padding: 0 }} className="tablewrap">
        <div style={{ maxHeight: 560, overflowY: 'auto' }}>
          <table className="tbl">
            <tbody>
              {listState === 'loading' && <><SkeletonRow cols={2} /><SkeletonRow cols={2} /><SkeletonRow cols={2} /></>}
              {listState === 'forbidden' && <tr><td colSpan={2} style={{ padding: 0 }}><LayerNote>Batches are withheld — requires the <b>volume</b> layer.</LayerNote></td></tr>}
              {listState === 'notImplemented' && (
                <tr><td colSpan={2} style={{ padding: 0 }}>
                  <UnbackedPanel icon={I.layers({ size: 22 })} line="The batch register lands with M7 — every lot received, the recall unit for traceability." />
                </td></tr>
              )}
              {listState === 'error' && <EmptyRow cols={2}><span style={{ color: 'var(--danger)' }}>Could not load batches{listErr?.status ? ` (${listErr.status})` : ''}.</span></EmptyRow>}
              {listState === 'empty' && <EmptyRow cols={2}>No lots received yet.</EmptyRow>}
              {listState === 'ready' && batches.map((b) => (
                <tr key={b.id} data-testid="batch-list-row" className={activeId === b.id ? 'sel' : ''} onClick={() => setSel(b.id ?? null)}>
                  <td>
                    <b className="mono" style={{ fontFamily: 'var(--font-mono)', fontSize: 11 }}>{b.id}</b>
                    <div className="dim" style={{ fontSize: 11 }}>{[b.sku_label || b.skuLabel, b.cm].filter(Boolean).join(' · ')}</div>
                  </td>
                  <td className="num">{b.qty}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <div>
        {rosterState === 'loading' && (
          <>
            <Card style={{ marginBottom: 14 }}><Skeleton lines={3} /></Card>
            <Card title="Serial roster" icon={I.list}><Skeleton lines={5} /></Card>
          </>
        )}

        {rosterState === 'forbidden' && (
          <Card title="Serial roster" icon={I.list}><LayerNote>This roster is withheld — requires the <b>volume</b> layer.</LayerNote></Card>
        )}

        {rosterState === 'notImplemented' && (
          <Card title="Serial roster" icon={I.list}>
            <UnbackedPanel icon={I.list({ size: 22 })}
              line="The serial roster — every unit a lot became, the recall list — comes online with M7 traceability, with this lot's specific-identification landed cost." />
          </Card>
        )}

        {rosterState === 'error' && (
          <Card title="Serial roster" icon={I.list}><EmptyRowless><span style={{ color: 'var(--danger)' }}>Could not load this lot{rosterErr?.status ? ` (${rosterErr.status})` : ''} — try again shortly.</span></EmptyRowless></Card>
        )}

        {rosterState === 'empty' && (
          <Card title="Serial roster" icon={I.list}><EmptyRowless>Select a batch to see its serial roster.</EmptyRowless></Card>
        )}

        {rosterState === 'ready' && d && (
          <>
            <Card style={{ marginBottom: 14 }}>
              <div className="row between" style={{ alignItems: 'flex-start' }}>
                <div>
                  <div className="mini" style={{ marginBottom: 6 }}>Lot · the recall unit</div>
                  <div style={{ fontFamily: 'var(--font-disp)', fontSize: 22, fontWeight: 600 }} className="mono">{d.batch?.id}</div>
                  <div className="dim" style={{ fontSize: 12.5 }}>
                    {[d.batch?.sku_label || d.batch?.skuLabel,
                      [d.batch?.cm, d.batch?.location_name || d.batch?.locationName].filter(Boolean).join(' → '),
                      d.batch?.received ? 'received ' + d.batch.received : null,
                      d.batch?.po ? 'PO ' + d.batch.po : null,
                    ].filter(Boolean).join(' · ')}
                  </div>
                </div>
                {hasProfit && d.batch?.landed_unit_cost != null && (
                  <div style={{ textAlign: 'right' }}>
                    <div className="fldlabel">Landed / unit</div>
                    <div style={{ fontFamily: 'var(--font-disp)', fontSize: 24, fontWeight: 600 }} className="num">{gbp(d.batch.landed_unit_cost, d.batch.currency)}</div>
                  </div>
                )}
              </div>

              <div className="row g6 wrap" style={{ marginTop: 14 }}>
                {Object.keys(byStatus).map((s) => (
                  <Chip key={s} s={SSTAT[s] || 'neutral'}>{byStatus[s]} {human(s)}</Chip>
                ))}
              </div>

              {hasProfit && d.batch?.landed_unit_cost != null && (
                <div className="kv" style={{ marginTop: 16, fontSize: 12 }}>
                  <span className="k">Factory + freight + duty</span>
                  <span className="v num">{gbp(d.batch.unit_cost, d.batch.currency)} + {gbp(d.batch.freight_per_unit, d.batch.currency)} + {gbp(d.batch.duty_per_unit, d.batch.currency)}</span>
                  <span className="k" style={{ fontWeight: 600, color: 'var(--text)' }}>Batch landed value</span>
                  <span className="v num" style={{ fontWeight: 600 }}>
                    {gbp(d.batch.landed_value != null ? d.batch.landed_value : Number(d.batch.landed_unit_cost) * (Number(d.batch.qty) || serials.length), d.batch.currency)}
                  </span>
                </div>
              )}
              {!hasProfit && (
                <LayerNote>Landed cost for this lot is hidden — requires the <b>profitability</b> layer. Identity and the recall roster remain fully visible.</LayerNote>
              )}
            </Card>

            <Card title={'Serial roster · ' + serials.length + ' units'} icon={I.list}
              aux={<span className="dim" style={{ fontSize: 11.5 }}>the recall list — every unit this lot became</span>}
              style={{ padding: 0 }} className="tablewrap">
              <div style={{ maxHeight: 420, overflowY: 'auto' }}>
                <table className="tbl">
                  <thead><tr><th>Serial</th><th>Status</th><th>Order</th><th>Customer</th><th>Dispatched</th></tr></thead>
                  <tbody>
                    {serials.length === 0 && <EmptyRow cols={5}>This lot has no serial units recorded.</EmptyRow>}
                    {serials.slice(0, 120).map((s, i) => (
                      <tr key={i} data-testid="batch-roster-row" style={{ cursor: 'default' }}>
                        <td className="mono" style={{ fontFamily: 'var(--font-mono)', fontSize: 11 }}>{s.sn || s.serial}</td>
                        <td><Chip s={SSTAT[s.status || ''] || 'neutral'}>{human(s.status || '')}</Chip></td>
                        <td className="mono dim" style={{ fontFamily: 'var(--font-mono)', fontSize: 10.5 }}>{s.order || '—'}</td>
                        <td className="dim">{s.customer || '—'}</td>
                        <td className="dim">{s.dispatched_at || s.dispatchedAt || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {serials.length > 120 && <div className="dim" style={{ padding: '10px 16px', fontSize: 11.5 }}>Showing first 120 of {serials.length}.</div>}
            </Card>
          </>
        )}
      </div>
    </div>
  );
}
