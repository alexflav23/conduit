import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from './api';
import {
  PageHead, Card, Chip, Money, LayerNote, AuditRef, EmptyRow, SkeletonRow, Skeleton, useToast,
} from './kit/kit';
import { I } from './kit/icons';
import { asArray } from './state';

// Batch / landed-cost / serial genealogy (spec/ui/20-batch.md): the traceability spine (doc 07 M7).
// The hero is BIDIRECTIONAL traceability — type a serial, see its whole life and its EXACT landed cost
// (specific-identification, never a weighted average); type a batch, see every unit it became (the recall list).
// Serial/batch identity + status are the `volume` layer; landed_unit_cost + the cost breakdown are
// `profitability` and COLLAPSE for a viewer without it (never £0.00). Auto-loads on mount + when ctx changes —
// no manual Load/Refresh buttons.

type Ctx = { entity: string; market: string; period: string; scenario: string };
type Role = { token?: string; name?: string; title?: string; layers?: string[] };

const SSTAT: Record<string, string> = {
  in_stock: 'ok', allocated: 'warn', dispatched: 'accent', delivered: 'neutral', activated: 'ok', returned: 'danger',
};

const gbpn = (v: any) =>
  v == null ? '—' : `£${Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
const human = (s: string) => (s || '').replace(/_/g, ' ');

export function BatchGenealogy({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const r = (role || {}) as Role;
  const c = (ctx || {}) as Ctx;
  const hasProfit = (r.layers || []).indexOf('profitability') >= 0;
  const [mode, setMode] = useState<'serial' | 'batch'>('serial');

  const [toastNode, fire] = useToast();
  const fireToast = useCallback((m: string, k?: string) => { fire(m, (k as any) || 'ok'); toast(m, k); }, [fire, toast]);

  return (
    <div className="page" style={{ maxWidth: 1320 }}>
      {toastNode}
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
        ? <SerialGenealogy ctxKey={c.entity} role={r} hasProfit={hasProfit} toast={fireToast} />
        : <BatchRoster ctxKey={c.entity} role={r} hasProfit={hasProfit} />}
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
function SerialGenealogy({ ctxKey, role, hasProfit, toast }: {
  ctxKey?: string; role: any; hasProfit: boolean; toast: (m: string, k?: string) => void;
}) {
  const [q, setQ] = useState('');
  const [res, setRes] = useState<{ status: number; json: any } | null>(null);

  const lookup = useCallback((serial: string) => {
    if (!serial.trim()) return;
    setRes(null);
    apiFetch(`/api/v1/inventory/genealogy?serial=${encodeURIComponent(serial.trim())}`).then((r) => {
      setRes(r);
      if (r.status === 404) toast('No serial matches that number', 'warn');
      else if (r.status >= 400 && r.status !== 401 && r.status !== 403) toast(`Lookup failed (${r.status})`, 'err');
    });
  }, [toast]);

  // Auto-load on mount + when the entity context changes: seed with the first known serial so the page is
  // never an empty form. No manual load button — Enter / Trace re-runs the lookup for a typed serial.
  useEffect(() => {
    setRes(null);
    setQ('');
    apiFetch(`/api/v1/inventory/serials?limit=1`).then((list) => {
      const first = asArray<any>(list.json && list.json.rows ? list.json.rows : list.json)[0];
      if (first && (first.sn || first.serial)) {
        const sn = first.sn || first.serial;
        setQ(sn);
        lookup(sn);
      } else {
        // nothing seeded — show the four-state shell against the seed result so empty/forbidden surface
        setRes(list.status >= 400 ? list : { status: 200, json: null });
      }
    });
  }, [ctxKey, lookup]);

  const loading = res === null;
  const forbidden = !!res && (res.status === 401 || res.status === 403);
  const error = !!res && res.status >= 400 && !forbidden && res.status !== 404;
  const d = res && res.status === 200 ? res.json : null;

  return (
    <div>
      <div className="loadbar">
        <span className="fldlabel">Serial</span>
        <input
          className="cellinput" style={{ width: 280, textAlign: 'left' }} value={q} data-testid="batch-serial-input"
          onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && lookup(q)}
          placeholder="Scan or type a serial number…"
        />
        <button className="btn primary" data-testid="batch-trace" onClick={() => lookup(q)}>{I.search({ size: 13 })}Trace</button>
      </div>

      {loading && (
        <div className="grid" style={{ gridTemplateColumns: '1.1fr 1fr', alignItems: 'start' }}>
          <Card title="Genealogy" icon={I.map}><Skeleton lines={6} /></Card>
          <Card title="Unit lifecycle" icon={I.clock}><Skeleton lines={5} /></Card>
        </div>
      )}

      {forbidden && (
        <Card title="Genealogy" icon={I.map}>
          <LayerNote>Serial genealogy is withheld — requires the volume layer.</LayerNote>
        </Card>
      )}

      {error && (
        <Card title="Genealogy" icon={I.map}>
          <EmptyRowless>Could not trace this serial — try again shortly.</EmptyRowless>
        </Card>
      )}

      {res && !loading && !forbidden && !error && !d && (
        <Card title="Genealogy" icon={I.map}>
          <EmptyRowless>No serial found. Scan or type a serial number to trace it.</EmptyRowless>
        </Card>
      )}

      {d && (
        <div className="grid" style={{ gridTemplateColumns: '1.1fr 1fr', alignItems: 'start' }}>
          <Card title={'Genealogy · ' + (d.serial?.sn || q)} icon={I.map}
            aux={<span className="dim" style={{ fontSize: 11.5 }}>serial → batch → PO → order → customer → activation</span>}>
            <ChainNode
              label="Serial" tone="var(--accent-bright)"
              title={<span className="mono">{d.serial?.sn}</span>}
              sub={<>{[d.serial?.sku_label || d.serial?.skuLabel, d.serial?.location].filter(Boolean).join(' · ')} {d.serial?.status && <Chip s={SSTAT[d.serial.status] || 'neutral'}>{human(d.serial.status)}</Chip>}</>}
            />
            <ChainNode
              label="Batch / lot" tone="var(--plum)"
              title={<span className="mono"><AuditRef id={d.batch?.id} /></span>}
              sub={['received ' + (d.batch?.received || '—'), d.batch?.cm].filter(Boolean).join(' · ')}
              money={d.batch && <Money value={d.batch.landed_unit_cost} role={role} layer="profitability" />}
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
                        <div style={{ fontFamily: 'var(--font-disp)', fontSize: 30, fontWeight: 600 }} className="num">{gbpn(d.batch.landed_unit_cost)}</div>
                      </div>
                      <div className="dim" style={{ fontSize: 11.5, textAlign: 'right' }}>from lot {d.batch.id}<br />not a weighted average</div>
                    </div>
                    <div className="kv" style={{ fontSize: 12 }}>
                      <span className="k">Factory unit</span><span className="v num">{gbpn(d.batch.unit_cost)}</span>
                      <span className="k">+ Freight / unit</span><span className="v num">{gbpn(d.batch.freight_per_unit)}</span>
                      <span className="k">+ Duty / unit</span><span className="v num">{gbpn(d.batch.duty_per_unit)}</span>
                      <span className="k" style={{ fontWeight: 600, color: 'var(--text)' }}>= Landed</span>
                      <span className="v num" style={{ fontWeight: 600 }}>{gbpn(d.batch.landed_unit_cost)}</span>
                    </div>
                  </>
                ) : (
                  <div className="dim" style={{ padding: '8px 2px', fontSize: 12.5 }}>No landed cost recorded for this lot yet.</div>
                )}
              </Card>
            ) : (
              <Card title="Specific-identification cost" icon={I.layers} style={{ marginBottom: 14 }}>
                <LayerNote>Landed unit cost is hidden — requires the profitability layer. The figure is absent from your projection, never shown as £0.</LayerNote>
              </Card>
            )}

            <Card title="Unit lifecycle" icon={I.clock}
              aux={<span className="dim" style={{ fontSize: 11.5 }}>received → … → current state</span>}>
              <div className="tl">
                {asArray<any>(d.timeline).length === 0 && <div className="dim" style={{ padding: '6px 2px', fontSize: 12.5 }}>No lifecycle events recorded.</div>}
                {asArray<any>(d.timeline).map((e, i) => (
                  <div className="ev" key={i}>
                    <span className="when" style={{ minWidth: 96 }}>{e.at}</span>
                    <span className="etype">{human(e.event)}</span>
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

// a card-body empty/error notice (EmptyRow is <tr>-only, so this is the non-table sibling)
function EmptyRowless({ children }: { children: React.ReactNode }) {
  return <div className="dim" style={{ padding: '22px 8px', textAlign: 'center', fontSize: 12.5 }}>{children}</div>;
}

// ============================================================ BATCH → ROSTER
function BatchRoster({ ctxKey, role, hasProfit }: { ctxKey?: string; role: any; hasProfit: boolean }) {
  const [listRes, setListRes] = useState<{ status: number; json: any } | null>(null);
  const [sel, setSel] = useState<string | null>(null);
  const [rosterRes, setRosterRes] = useState<{ status: number; json: any } | null>(null);

  const openBatch = useCallback((id: string) => {
    setSel(id);
    setRosterRes(null);
    apiFetch(`/api/v1/inventory/batches/${encodeURIComponent(id)}/roster`).then(setRosterRes);
  }, []);

  useEffect(() => {
    setListRes(null);
    setSel(null);
    setRosterRes(null);
    apiFetch(`/api/v1/inventory/batches`).then((r) => {
      setListRes(r);
      const first = asArray<any>(r.json && r.json.rows ? r.json.rows : r.json)[0];
      if (first && first.id) openBatch(first.id);
    });
  }, [ctxKey, openBatch]);

  const listLoading = listRes === null;
  const listForbidden = !!listRes && (listRes.status === 401 || listRes.status === 403);
  const listError = !!listRes && listRes.status >= 400 && !listForbidden;
  const batches = asArray<any>(listRes && listRes.status < 400 ? (listRes.json && listRes.json.rows ? listRes.json.rows : listRes.json) : []);

  const rLoading = sel !== null && rosterRes === null;
  const d = rosterRes && rosterRes.status === 200 ? rosterRes.json : null;
  const serials = asArray<any>(d && (d.serials || d.rows));
  const byStatus = (d && d.by_status) || (d && d.byStatus) || {};

  return (
    <div className="grid" style={{ gridTemplateColumns: '300px 1fr', alignItems: 'start' }}>
      <Card title="Batches" icon={I.layers} style={{ padding: 0 }} className="tablewrap">
        <div style={{ maxHeight: 560, overflowY: 'auto' }}>
          <table className="tbl">
            <tbody>
              {listLoading && <><SkeletonRow cols={2} /><SkeletonRow cols={2} /><SkeletonRow cols={2} /></>}
              {listForbidden && <tr><td colSpan={2} style={{ padding: 0 }}><LayerNote>Batches are withheld — requires the volume layer.</LayerNote></td></tr>}
              {listError && <EmptyRow cols={2}>Could not load batches — try again shortly.</EmptyRow>}
              {listRes && !listLoading && !listForbidden && !listError && batches.length === 0 && <EmptyRow cols={2}>No lots received yet.</EmptyRow>}
              {batches.map((b) => (
                <tr key={b.id} data-testid="batch-list-row" className={sel === b.id ? 'sel' : ''} onClick={() => openBatch(b.id)}>
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
        {rLoading && (
          <>
            <Card style={{ marginBottom: 14 }}><Skeleton lines={3} /></Card>
            <Card title="Serial roster" icon={I.list}><Skeleton lines={5} /></Card>
          </>
        )}

        {rosterRes && (rosterRes.status === 401 || rosterRes.status === 403) && (
          <Card title="Serial roster" icon={I.list}><LayerNote>This roster is withheld — requires the volume layer.</LayerNote></Card>
        )}

        {rosterRes && rosterRes.status >= 400 && rosterRes.status !== 401 && rosterRes.status !== 403 && (
          <Card title="Serial roster" icon={I.list}><EmptyRowless>Could not load this lot — try again shortly.</EmptyRowless></Card>
        )}

        {d && (
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
                    <div style={{ fontFamily: 'var(--font-disp)', fontSize: 24, fontWeight: 600 }} className="num">{gbpn(d.batch.landed_unit_cost)}</div>
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
                  <span className="v num">{gbpn(d.batch.unit_cost)} + {gbpn(d.batch.freight_per_unit)} + {gbpn(d.batch.duty_per_unit)}</span>
                  <span className="k" style={{ fontWeight: 600, color: 'var(--text)' }}>Batch landed value</span>
                  <span className="v num" style={{ fontWeight: 600 }}>
                    {gbpn(d.batch.landed_value != null ? d.batch.landed_value : Number(d.batch.landed_unit_cost) * (Number(d.batch.qty) || serials.length))}
                  </span>
                </div>
              )}
              {!hasProfit && (
                <LayerNote>Landed cost for this lot is hidden — requires the profitability layer. Identity and the recall roster remain fully visible.</LayerNote>
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
                        <td><Chip s={SSTAT[s.status] || 'neutral'}>{human(s.status)}</Chip></td>
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
