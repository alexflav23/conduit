import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from './api';
import {
  PageHead, Card, Chip, Drawer, Money, LayerNote, EmptyRow, SkeletonRow, useToast, num,
} from './kit/kit';
import { I } from './kit/icons';
import { asArray } from './state';

// Inventory (spec/ui/18-inventory.md): the operational stock + fulfilment surface (doc 07 M6). Three views —
//  · ATP board    — on-hand − allocated = available, per variant × location; the promiseable number is the HERO.
//  · Serial view  — serial-level register by status (in_stock → allocated → dispatched → delivered → returned).
//  · Dispatch     — orders ready to ship → allocate serials → ship (carrier/tracking) → deliver.
// The invariant surfaced everywhere: a serialised line CANNOT ship without its serials.
// Data-layer wall: on-hand/allocated/ATP are `volume`; unit_landed_cost is `profitability` — it COLLAPSES (the
// Money widget renders nothing, never £0). Stock is scope-filtered by entity/market/location server-side.
// Maker-checker: allocate/dispatch/deliver need edit:inventory — disabled + explained when the role can't.
// Auto-loads on mount + when ctx.entity / ctx.market change. No manual Load/Refresh buttons.

type Ctx = { entity: string; market: string; period: string; scenario: string };
type Role = { token?: string; name?: string; title?: string; layers?: string[] };
type Res = { status: number; json: any } | null;

const SUBTABS: [string, string][] = [
  ['atp', 'Available-to-promise'],
  ['serials', 'Serial view'],
  ['dispatch', 'Dispatch worklist'],
];
const SERIAL_STATUS = ['all', 'in_stock', 'allocated', 'dispatched', 'delivered', 'returned'];
const SSTATUS_CHIP: Record<string, string> = {
  in_stock: 'ok', allocated: 'warn', dispatched: 'accent', delivered: 'neutral', returned: 'danger',
};
const STEP: Record<string, number> = { ready: 0, allocated: 1, dispatched: 2, delivered: 3 };
const CARRIERS = ['DPD', 'DHL', 'UPS', 'Royal Mail', 'Parcelforce'];

const qs = (o: Record<string, string | undefined>) =>
  Object.entries(o)
    .filter(([, v]) => v != null && v !== '')
    .map(([k, v]) => `${k}=${encodeURIComponent(v as string)}`)
    .join('&');

export function Inventory({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const r = (role || {}) as Role;
  const c = (ctx || {}) as Ctx;
  const layers = r.layers || [];
  const hasProfit = layers.indexOf('profitability') >= 0;
  const canEdit = layers.indexOf('inter_entity') >= 0 || (r.title || '').toLowerCase().includes('admin')
    || (r.title || '').toLowerCase().includes('fulfil');

  const [sub, setSub] = useState('atp');
  const [toastNode, fire] = useToast();
  const fireToast = useCallback((m: string, k?: string) => { fire(m, (k as any) || 'ok'); toast(m, k); }, [fire, toast]);

  return (
    <div className="page" style={{ maxWidth: 1320 }}>
      {toastNode}
      <PageHead
        crumb={'Operational stock & fulfilment · doc 07 M6'}
        title="Inventory"
        sub="Available-to-promise per variant and location, serial-level stock, and dispatch — allocate serials, ship, deliver. A serialised line cannot ship without its serials." />

      <div className="seg" style={{ marginBottom: 18 }}>
        {SUBTABS.map(([k, l]) => (
          <button key={k} data-testid={'inv-tab-' + k} className={sub === k ? 'on' : ''} onClick={() => setSub(k)}>{l}</button>
        ))}
      </div>

      {sub === 'atp' && <AtpBoard ctx={c} role={r} hasProfit={hasProfit} />}
      {sub === 'serials' && <SerialView ctx={c} role={r} hasProfit={hasProfit} />}
      {sub === 'dispatch' && <DispatchWorklist ctx={c} canEdit={canEdit} fireToast={fireToast} />}
    </div>
  );
}

// ---------------- ATP board (the hero) ----------------
function AtpBoard({ ctx, role, hasProfit }: { ctx: Ctx; role: Role; hasProfit: boolean }) {
  const viewer = { layers: role.layers || [] };
  const [res, setRes] = useState<Res>(null);
  const [loc, setLoc] = useState('all');

  const load = useCallback((l: string) => {
    setRes(null);
    apiFetch(`/api/v1/inventory/atp?${qs({ entity: ctx.entity, market: ctx.market, location: l === 'all' ? '' : l })}`).then(setRes);
  }, [ctx.entity, ctx.market]);

  useEffect(() => { setLoc('all'); load('all'); }, [load]);

  const loading = res === null;
  const forbidden = !!res && (res.status === 401 || res.status === 403);
  const error = !!res && res.status >= 400 && !forbidden;
  const body = res && res.status < 400 ? res.json : null;
  const rows = asArray<any>(body && (body.rows ?? body));
  const locations = asArray<any>(body && body.locations);

  const totAtp = rows.reduce((a, x) => a + (Number(x.available) || 0), 0);
  const totOnHand = rows.reduce((a, x) => a + (Number(x.on_hand) || 0), 0);
  const totAlloc = rows.reduce((a, x) => a + (Number(x.allocated) || 0), 0);

  return (
    <div>
      <div className="grid" style={{ gridTemplateColumns: 'repeat(3,1fr)', marginBottom: 14 }}>
        <Card style={{ padding: '16px 18px', background: 'var(--ok-bg)', borderColor: 'rgba(87,224,160,0.3)' }}>
          <div className="fldlabel">Available to promise</div>
          <div style={{ fontFamily: 'var(--font-disp)', fontSize: 30, fontWeight: 600, color: 'var(--ok)', marginTop: 3 }}>
            {loading ? '—' : num(totAtp)}
          </div>
          <div className="dim" style={{ fontSize: 11.5 }}>units promiseable now</div>
        </Card>
        <Card style={{ padding: '16px 18px' }}>
          <div className="fldlabel">On hand</div>
          <div style={{ fontFamily: 'var(--font-disp)', fontSize: 30, fontWeight: 600, marginTop: 3 }}>{loading ? '—' : num(totOnHand)}</div>
          <div className="dim" style={{ fontSize: 11.5 }}>physically present</div>
        </Card>
        <Card style={{ padding: '16px 18px' }}>
          <div className="fldlabel">Allocated</div>
          <div style={{ fontFamily: 'var(--font-disp)', fontSize: 30, fontWeight: 600, marginTop: 3, color: 'var(--warn)' }}>{loading ? '—' : num(totAlloc)}</div>
          <div className="dim" style={{ fontSize: 11.5 }}>committed, not yet shipped</div>
        </Card>
      </div>

      <Card title="ATP board" icon={I.grid} aux="on-hand − allocated = available" style={{ padding: 0 }} className="tablewrap">
        <div className="loadbar" style={{ padding: '11px 16px', margin: 0, borderBottom: '1px solid var(--border)' }}>
          <span className="fldlabel">Location</span>
          <select className="fld sel" value={loc} onChange={(e) => { setLoc(e.target.value); load(e.target.value); }}>
            <option value="all">All locations</option>
            {locations.map((l) => <option key={l.code} value={l.code}>{l.name} ({l.code})</option>)}
          </select>
        </div>
        <table className="tbl" data-testid="inv-atp">
          <thead>
            <tr>
              <th>Variant</th><th>Location</th>
              <th className="num">On hand</th><th className="num">Allocated</th><th className="num">Available (ATP)</th>
              <th className="num">Landed unit cost</th>
            </tr>
          </thead>
          <tbody>
            {loading && <><SkeletonRow cols={6} /><SkeletonRow cols={6} /><SkeletonRow cols={6} /></>}
            {forbidden && <tr><td colSpan={6} style={{ padding: 0 }}><LayerNote>Stock is hidden — requires the <b>volume</b> layer.</LayerNote></td></tr>}
            {error && <EmptyRow cols={6}>Could not load the ATP board — try again shortly.</EmptyRow>}
            {!!res && !forbidden && !error && rows.length === 0 && <EmptyRow cols={6}>No stock at this location.</EmptyRow>}
            {!forbidden && !error && rows.map((x, i) => (
              <tr key={x.variant_id || x.sku || i} data-testid="inv-atp-row" style={{ cursor: 'default' }}>
                <td>
                  <b>{x.variant_label || x.skuLabel || x.sku}</b>
                  <div className="dim mono" style={{ fontFamily: 'var(--font-mono)', fontSize: 10.5 }}>{x.sku}</div>
                </td>
                <td><span className="chip neutral"><span className="d" />{x.location}</span></td>
                <td className="num">{num(x.on_hand)}</td>
                <td className="num">{Number(x.allocated) > 0 ? <span style={{ color: 'var(--warn)' }}>{num(x.allocated)}</span> : '0'}</td>
                <td className="num"><b style={{ color: Number(x.available) > 0 ? 'var(--ok)' : 'var(--faint)' }}>{num(x.available)}</b></td>
                <td className="num">
                  {hasProfit
                    ? <Money value={x.unit_landed_cost ?? x.landed} ccy={x.currency} role={viewer} layer="profitability" />
                    : <span className="dim" style={{ fontStyle: 'italic' }}>hidden</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {!!res && !forbidden && !error && !hasProfit && (
          <LayerNote>Landed unit cost sits behind the <b>profitability</b> layer — absent for your role, never shown as £0.</LayerNote>
        )}
      </Card>
    </div>
  );
}

// ---------------- Serial register ----------------
function SerialView({ ctx, role, hasProfit }: { ctx: Ctx; role: Role; hasProfit: boolean }) {
  const viewer = { layers: role.layers || [] };
  const [res, setRes] = useState<Res>(null);
  const [status, setStatus] = useState('all');
  const [q, setQ] = useState('');

  const load = useCallback((st: string, query: string) => {
    setRes(null);
    apiFetch(`/api/v1/inventory/serials?${qs({ entity: ctx.entity, market: ctx.market, status: st === 'all' ? '' : st, q: query, limit: '80' })}`).then(setRes);
  }, [ctx.entity, ctx.market]);

  useEffect(() => { setStatus('all'); setQ(''); load('all', ''); }, [load]);

  const loading = res === null;
  const forbidden = !!res && (res.status === 401 || res.status === 403);
  const error = !!res && res.status >= 400 && !forbidden;
  const body = res && res.status < 400 ? res.json : null;
  const rows = asArray<any>(body && (body.rows ?? body));
  const total = body && body.total != null ? Number(body.total) : rows.length;

  return (
    <Card title="Serial register" icon={I.list} aux={!loading && !forbidden && !error ? total + ' serials match' : ''} style={{ padding: 0 }} className="tablewrap">
      <div className="loadbar" style={{ padding: '11px 16px', margin: 0, borderBottom: '1px solid var(--border)' }}>
        <div className="seg">
          {SERIAL_STATUS.map((s) => (
            <button key={s} className={status === s ? 'on' : ''} onClick={() => { setStatus(s); load(s, q); }}>
              {s === 'all' ? 'All' : s.replace('_', ' ')}
            </button>
          ))}
        </div>
        <div className="sp" />
        <div className="search" style={{ width: 240, height: 38 }}>
          {I.search()}
          <input placeholder="Search serial or batch…" value={q}
            onChange={(e) => { setQ(e.target.value); load(status, e.target.value); }} />
        </div>
      </div>
      <table className="tbl" data-testid="inv-serials">
        <thead>
          <tr><th>Serial</th><th>Variant</th><th>Batch</th><th>Location</th><th>Status</th><th className="num">Landed cost</th></tr>
        </thead>
        <tbody>
          {loading && <><SkeletonRow cols={6} /><SkeletonRow cols={6} /><SkeletonRow cols={6} /></>}
          {forbidden && <tr><td colSpan={6} style={{ padding: 0 }}><LayerNote>Serial stock is hidden — requires the <b>volume</b> layer.</LayerNote></td></tr>}
          {error && <EmptyRow cols={6}>Could not load the serial register — try again shortly.</EmptyRow>}
          {!!res && !forbidden && !error && rows.length === 0 && <EmptyRow cols={6}>No serials match.</EmptyRow>}
          {!forbidden && !error && rows.map((s, i) => (
            <tr key={s.serial_no || s.sn || i} data-testid="inv-serial-row" style={{ cursor: 'default' }}>
              <td className="mono" style={{ fontFamily: 'var(--font-mono)', fontSize: 11.5 }}>{s.serial_no || s.sn}</td>
              <td className="dim">{s.variant_label || s.skuLabel || s.sku}</td>
              <td className="mono dim" style={{ fontFamily: 'var(--font-mono)', fontSize: 11 }}>{s.batch || s.batch_code}</td>
              <td><span className="chip neutral"><span className="d" />{s.location}</span></td>
              <td><Chip s={SSTATUS_CHIP[s.status] || 'neutral'}>{String(s.status || '').replace('_', ' ')}</Chip></td>
              <td className="num">
                {hasProfit
                  ? <Money value={s.unit_landed_cost ?? s.landed} ccy={s.currency} role={viewer} layer="profitability" />
                  : <span className="dim" style={{ fontStyle: 'italic' }}>hidden</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {!!res && !forbidden && !error && total > rows.length && (
        <div className="dim" style={{ padding: '10px 16px', fontSize: 11.5 }}>Showing first {rows.length} of {total} — narrow with status or search.</div>
      )}
      {!!res && !forbidden && !error && !hasProfit && rows.length > 0 && (
        <LayerNote>Landed cost is hidden — requires the <b>profitability</b> layer.</LayerNote>
      )}
    </Card>
  );
}

// ---------------- Dispatch worklist (maker-checker mutations) ----------------
function DispatchWorklist({ ctx, canEdit, fireToast }: { ctx: Ctx; canEdit: boolean; fireToast: (m: string, k?: string) => void }) {
  const [res, setRes] = useState<Res>(null);
  const [sel, setSel] = useState<any | null>(null);
  const [carrier, setCarrier] = useState(CARRIERS[0]);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(() => {
    setRes(null);
    apiFetch(`/api/v1/inventory/dispatch?${qs({ entity: ctx.entity, market: ctx.market })}`).then(setRes);
  }, [ctx.entity, ctx.market]);

  useEffect(load, [load]);

  const loading = res === null;
  const forbidden = !!res && (res.status === 401 || res.status === 403);
  const error = !!res && res.status >= 400 && !forbidden;
  const orders = asArray<any>(res && res.status < 400 ? (res.json && (res.json.rows ?? res.json)) : []);

  const refreshSel = (updated: any) => { load(); if (updated) setSel(updated); };

  const mutate = (id: string, kind: 'allocate' | 'ship' | 'deliver') => {
    setBusy(id + ':' + kind);
    const path = `/api/v1/inventory/dispatch/${encodeURIComponent(id)}/${kind}`;
    const body = kind === 'ship' ? JSON.stringify({ carrier }) : undefined;
    apiFetch(path, { method: 'POST', body }).then((rr) => {
      setBusy(null);
      if (rr.status === 200 || rr.status === 202) {
        const next = rr.json || {};
        refreshSel(next);
        if (kind === 'allocate') fireToast('Allocated ' + asArray<any>(next.serials).length + ' serials', 'ok');
        else if (kind === 'ship') fireToast('Dispatched via ' + (next.carrier || carrier), 'ok');
        else fireToast('Marked delivered', 'ok');
      } else if (rr.status === 403) {
        fireToast('Forbidden — dispatch needs edit:inventory', 'err');
      } else if (rr.status === 409) {
        fireToast((rr.json && rr.json.message) || 'Cannot dispatch — serials not allocated (the invariant)', 'err');
        load();
      } else {
        fireToast((rr.json && rr.json.message) || `Action failed (${rr.status})`, 'err');
        load();
      }
    });
  };

  return (
    <Card title="Dispatch worklist" icon={I.download}
      aux="allocate serials → ship → deliver · a serialised line can’t ship without serials"
      style={{ padding: 0 }} className="tablewrap">
      {!canEdit && (
        <div className="banner warn" style={{ margin: 14 }}>
          {I.shield()}
          <div><span className="bb">Read-only.</span> Allocation &amp; dispatch need <span className="mono" style={{ fontFamily: 'var(--font-mono)' }}>edit:inventory</span>.</div>
        </div>
      )}
      <table className="tbl" data-testid="inv-dispatch">
        <thead>
          <tr><th>Order</th><th>Customer</th><th>Variant</th><th className="num">Qty</th><th>Location</th><th>Progress</th><th>Action</th></tr>
        </thead>
        <tbody>
          {loading && <><SkeletonRow cols={7} /><SkeletonRow cols={7} /><SkeletonRow cols={7} /></>}
          {forbidden && <tr><td colSpan={7} style={{ padding: 0 }}><LayerNote>The dispatch worklist is hidden — requires the <b>volume</b> layer.</LayerNote></td></tr>}
          {error && <EmptyRow cols={7}>Could not load the dispatch worklist — try again shortly.</EmptyRow>}
          {!!res && !forbidden && !error && orders.length === 0 && <EmptyRow cols={7}>Nothing ready to dispatch.</EmptyRow>}
          {!forbidden && !error && orders.map((o) => {
            const serials = asArray<any>(o.serials);
            const noSerials = serials.length === 0;
            return (
              <tr key={o.id || o.order_id} data-testid="inv-dispatch-row" onClick={() => setSel(o)} tabIndex={0} style={{ cursor: 'pointer' }}>
                <td><b className="mono" style={{ fontFamily: 'var(--font-mono)', fontSize: 11.5 }}>{o.id || o.order_id}</b></td>
                <td>{o.customer}<div className="dim" style={{ fontSize: 10.5 }}>{o.branch}</div></td>
                <td className="dim">{o.variant_label || o.sku}</td>
                <td className="num">{num(o.qty)}</td>
                <td><span className="chip neutral"><span className="d" />{o.location}</span></td>
                <td>
                  <div className="row g6" style={{ alignItems: 'center' }}>
                    {['Alloc', 'Ship', 'Deliver'].map((_, i) => (
                      <span key={i} style={{ width: 8, height: 8, borderRadius: 4, background: (STEP[o.status] ?? -1) > i ? 'var(--ok)' : 'var(--surface3)' }} />
                    ))}
                    <span className="dim" style={{ fontSize: 11, marginLeft: 4 }}>{o.status}</span>
                  </div>
                </td>
                <td onClick={(e) => e.stopPropagation()}>
                  {o.status === 'ready' && (
                    <button className="btn sm primary" disabled={!canEdit || busy != null} onClick={() => mutate(o.id || o.order_id, 'allocate')}>
                      {busy === (o.id || o.order_id) + ':allocate' ? 'Allocating…' : 'Allocate'}
                    </button>
                  )}
                  {o.status === 'allocated' && (
                    <button className="btn sm primary" disabled={!canEdit || noSerials || busy != null}
                      title={noSerials ? 'Cannot dispatch — no serials allocated (the invariant)' : ''}
                      onClick={() => mutate(o.id || o.order_id, 'ship')}>
                      {I.download({ size: 12 })}{busy === (o.id || o.order_id) + ':ship' ? 'Dispatching…' : 'Dispatch'}
                    </button>
                  )}
                  {o.status === 'dispatched' && (
                    <button className="btn sm" disabled={!canEdit || busy != null} onClick={() => mutate(o.id || o.order_id, 'deliver')}>
                      {busy === (o.id || o.order_id) + ':deliver' ? 'Delivering…' : 'Deliver'}
                    </button>
                  )}
                  {o.status === 'delivered' && (
                    <span className="row g6" style={{ color: 'var(--ok)' }}>{I.check({ size: 14 })}done</span>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {!forbidden && !error && (
        <div className="loadbar" style={{ padding: '11px 16px', margin: 0, borderTop: '1px solid var(--border)' }}>
          <span className="fldlabel">Carrier</span>
          <select className="fld sel" value={carrier} onChange={(e) => setCarrier(e.target.value)}>
            {CARRIERS.map((cr) => <option key={cr}>{cr}</option>)}
          </select>
          <span className="dim" style={{ fontSize: 11.5 }}>applied on dispatch</span>
        </div>
      )}

      <Drawer open={!!sel} onClose={() => setSel(null)} width={520}
        chip={sel && <Chip s={SSTATUS_CHIP[sel.status] || 'neutral'}>{sel.status}</Chip>}
        title={sel ? (sel.id || sel.order_id) : ''}
        sub={sel ? [sel.customer, sel.branch].filter(Boolean).join(' · ') : ''}>
        {sel && (() => {
          const serials = asArray<any>(sel.serials);
          return (
            <>
              <div className="kv" style={{ marginBottom: 16 }}>
                <span className="k">Variant</span><span className="v">{sel.variant_label || sel.sku}</span>
                <span className="k">Quantity</span><span className="v num">{num(sel.qty)} units</span>
                <span className="k">Location</span><span className="v">{sel.location}</span>
                {sel.carrier && <><span className="k">Carrier</span><span className="v">{sel.carrier}</span></>}
                {sel.tracking && <><span className="k">Tracking</span><span className="v mono" style={{ fontFamily: 'var(--font-mono)' }}>{sel.tracking}</span></>}
              </div>
              <div className="mini" style={{ marginBottom: 8 }}>Allocated serials {serials.length ? '· ' + serials.length : ''}</div>
              {serials.length === 0 ? (
                <div className="banner info">
                  {I.alert()}
                  <div>No serials allocated yet. The invariant: this serialised line <span className="bb">cannot dispatch</span> until specific serials are picked from stock.</div>
                </div>
              ) : (
                <div className="tablewrap" style={{ border: '1px solid var(--border)', borderRadius: 10, maxHeight: 280, overflowY: 'auto' }}>
                  <table className="tbl"><tbody>
                    {serials.map((sn, i) => (
                      <tr key={i} style={{ cursor: 'default' }}>
                        <td className="mono" style={{ fontFamily: 'var(--font-mono)', fontSize: 11 }}>{typeof sn === 'string' ? sn : (sn.serial_no || sn.sn)}</td>
                      </tr>
                    ))}
                  </tbody></table>
                </div>
              )}
            </>
          );
        })()}
      </Drawer>
    </Card>
  );
}
