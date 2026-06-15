import React, { useMemo, useState } from 'react';
import { useApi } from './lib/query';
import { ApiError } from './lib/client';
import { PageHead, Card, Chip, Coverage, Drawer, EmptyRow, LayerNote, SkeletonRow, num } from './kit/kit';
import { I } from './kit/icons';

// Shelf — per-account stock (spec/ui/06-shelf.md, doc 20 D11). The real-time picture from the serial
// register: shipped -> activated -> on-shelf, with measured runway days and a reorder point. The hero is
// runway -> reorder: who crosses reorder next (the default sort). Quantities/runway are the `volume` layer,
// scope-filtered server-side by the viewer's market/channel/sector. Per-account drill = sell-in vs
// sell-through, consignment-aware (drawn, not placed, is the sale). Auto-loads; no Load button.

type Ctx = { entity: string; market: string; period: string; scenario: string };

interface ShelfRow {
  company_id?: string;
  name?: string;
  market?: string;
  channel?: string;
  shipped?: number;
  activated?: number;
  on_shelf?: number;
  runway_days?: number | null;
  reorder_point?: number | null;
  // consignment-aware sell-in vs sell-through detail (per-account drill)
  placed?: number;
  drawn?: number;
  consignment?: boolean;
  weekly_run_rate?: number | null;
}

const acctId = (r: ShelfRow) => r.company_id ?? '';
const acctName = (r: ShelfRow) => r.name ?? (r.company_id ?? '').slice(0, 8);

// at-risk = crossing reorder: on-shelf at/under reorder point, or runway short. Drives highlight + sort.
const crossesReorder = (r: ShelfRow) =>
  r.reorder_point != null && (r.on_shelf ?? 0) <= r.reorder_point;
const runwayState = (r: ShelfRow): 'danger' | 'warn' | 'ok' => {
  if (crossesReorder(r) || (r.runway_days != null && r.runway_days <= 14)) return 'danger';
  if (r.runway_days != null && r.runway_days <= 30) return 'warn';
  return 'ok';
};
const runwayLabel = (r: ShelfRow) =>
  r.runway_days == null ? 'n/a' : r.runway_days >= 365 ? '1y+' : num(r.runway_days) + 'd';
// rank by who crosses reorder next: shortest runway first (null runway sinks to the bottom).
const runwayRank = (r: ShelfRow) => (r.runway_days == null ? Number.POSITIVE_INFINITY : r.runway_days);

type Surface = 'loading' | 'forbidden' | 'notImplemented' | 'error' | 'empty' | 'ready';

export function Shelf({ role, ctx, toast }: { role: any; ctx: Ctx; toast: (m: string, k?: string) => void }) {
  const [sel, setSel] = useState<ShelfRow | null>(null);

  // The fleet shelf board is scope-filtered server-side by the viewer's market/channel/sector; key the query
  // on every ctx field so a context switch refetches. The `view:pipeline_coverage` gate -> 403 collapses to a
  // LayerNote (never zeros), and an unbacked env -> 404 renders the honest "not available" panel.
  const board = useApi<ShelfRow[]>(
    ['shelf-board', ctx.entity, ctx.market, ctx.period, ctx.scenario],
    '/api/v1/h6q/shelf',
  );
  const err = board.error as ApiError | null;

  const rows = useMemo(() => {
    const arr = Array.isArray(board.data) ? board.data : [];
    return arr.slice().sort((a, b) => runwayRank(a) - runwayRank(b));
  }, [board.data]);

  const state: Surface = board.isLoading
    ? 'loading'
    : err?.forbidden
      ? 'forbidden'
      : err?.notImplemented
        ? 'notImplemented'
        : err
          ? 'error'
          : rows.length === 0
            ? 'empty'
            : 'ready';

  const atRisk = rows.filter((r) => runwayState(r) === 'danger').length;

  return (
    <>
      <PageHead
        crumb="H6Q · Shelf"
        title="Shelf"
        sub="Per-account stock from the serial register — shipped − activated = on-shelf, with runway days and a measured reorder point. Sorted by who crosses reorder next."
        right={
          atRisk > 0 && state === 'ready'
            ? <Chip s="danger">{atRisk} crossing reorder</Chip>
            : undefined
        }
      />

      <Card
        title="Shelf board"
        icon={I.battery}
        aux={<span className="dim" style={{ fontSize: 12 }}>serial-attributed by Conduit at dispatch · runway → reorder</span>}
        className="tablewrap"
        style={{ padding: 0 }}
      >
        <table className="tbl" data-testid="shelf-board">
          <thead>
            <tr>
              <th>Account</th>
              <th className="num">Shipped</th>
              <th className="num">Activated</th>
              <th className="num">On-shelf</th>
              <th className="num">Runway</th>
              <th className="num">Reorder pt</th>
              <th style={{ width: 150 }}>Coverage</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {state === 'loading' && <SkeletonRow cols={8} />}

            {state === 'forbidden' && (
              <tr><td colSpan={8}><LayerNote>hidden — requires <b>view:pipeline_coverage</b></LayerNote></td></tr>
            )}

            {state === 'notImplemented' && (
              <tr>
                <td colSpan={8} style={{ padding: '34px 24px', textAlign: 'center' }} data-testid="shelf-unbacked">
                  <div style={{ display: 'grid', placeItems: 'center', gap: 10 }}>
                    <span style={{ width: 44, height: 44, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'var(--panel-2)' }}>{I.battery({ size: 22 })}</span>
                    <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>Not available in this environment yet</div>
                    <div className="dim" style={{ fontSize: 12.5, maxWidth: 460 }}>The shelf board appears once dispatched serials have been attributed to accounts in the serial register.</div>
                  </div>
                </td>
              </tr>
            )}

            {state === 'error' && (
              <EmptyRow cols={8}>
                <span style={{ color: 'var(--danger)' }}>Couldn't load the shelf board{err?.status ? ` (${err.status})` : ''}.</span>
              </EmptyRow>
            )}

            {state === 'empty' && <EmptyRow cols={8}>No shelf data.</EmptyRow>}

            {state === 'ready' && rows.map((r) => {
              const rs = runwayState(r);
              const reorder = crossesReorder(r);
              const cov = r.shipped ? ((r.activated ?? 0) / r.shipped) * 100 : 0;
              return (
                <tr
                  key={acctId(r) || acctName(r)}
                  data-testid="shelf-row"
                  tabIndex={0}
                  onClick={() => setSel(r)}
                  onKeyDown={(e) => e.key === 'Enter' && setSel(r)}
                  style={{ cursor: 'pointer', ...(reorder ? { background: 'var(--danger-bg)' } : {}) }}
                >
                  <td>
                    <b>{acctName(r)}</b>
                    <div className="dim" style={{ fontSize: 10.5 }}>
                      {r.channel ? r.channel : ''}{r.channel && r.market ? ' · ' : ''}{r.market ?? ''}{r.consignment ? ' · consignment' : ''}
                    </div>
                  </td>
                  <td className="num">{num(r.shipped)}</td>
                  <td className="num" style={{ color: 'var(--ok)' }}>{num(r.activated)}</td>
                  <td className="num"><b style={{ color: (r.on_shelf ?? 0) > 0 ? 'var(--accent-bright)' : 'var(--faint)' }}>{num(r.on_shelf)}</b></td>
                  <td className="num">
                    <span style={{ color: rs === 'danger' ? 'var(--danger)' : rs === 'warn' ? 'var(--warn)' : 'var(--muted)', fontWeight: rs === 'ok' ? 400 : 700 }}>
                      {runwayLabel(r)}
                    </span>
                  </td>
                  <td className="num dim">{r.reorder_point == null ? '—' : num(r.reorder_point)}</td>
                  <td><Coverage pct={cov} /></td>
                  <td>{reorder ? <Chip s="danger">reorder</Chip> : rs === 'warn' ? <Chip s="warn">low</Chip> : <span className="dim" style={{ fontSize: 11 }}>ok</span>}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Card>

      <Drawer
        open={!!sel}
        onClose={() => setSel(null)}
        width={520}
        title={sel ? acctName(sel) : ''}
        sub={sel ? [sel.channel, sel.market].filter(Boolean).join(' · ') : ''}
        chip={sel && (
          <div className="row g8">
            {sel.consignment && <Chip s="accent">consignment</Chip>}
            <Chip s={runwayState(sel)}>{runwayLabel(sel)} runway</Chip>
          </div>
        )}
        footer={sel && (
          <button
            className="btn primary"
            data-testid="shelf-chase"
            disabled={!crossesReorder(sel)}
            onClick={() => toast(crossesReorder(sel) ? `Replenishment flagged for ${acctName(sel)}` : 'Above reorder point', 'ok')}
          >
            {I.flag({ size: 14 })} Flag for replenishment
          </button>
        )}
      >
        {sel && <ShelfDrill row={sel} />}
      </Drawer>
    </>
  );
}

interface Delivery {
  dispatch_no?: string;
  date?: string | null;
  delivered_at?: string | null;
  status?: string;
  shipped?: number;
  activated?: number;
  depletion_pct?: number;
}
interface ActPoint { period?: string; activated?: number }
interface AcctDetail {
  deliveries?: Delivery[];
  activations?: { daily?: ActPoint[]; weekly?: ActPoint[]; monthly?: ActPoint[] };
  depletion?: { month?: string; shelf_stock?: number; velocity_3m?: number; runway_days?: number | null }[];
}

type Grain = 'daily' | 'weekly' | 'monthly';

// Activation rate over time — a bar per period at the chosen grain, newest on the right.
function ActivationBars({ points }: { points: ActPoint[] }) {
  if (!points.length) return <div className="dim" style={{ fontSize: 12, padding: '8px 2px' }}>No activations recorded yet.</div>;
  const W = 480;
  const H = 110;
  const max = Math.max(...points.map((p) => p.activated ?? 0), 1);
  const gap = 1.5;
  const bw = (W - gap * (points.length - 1)) / points.length;
  return (
    <svg viewBox={'0 0 ' + W + ' ' + H} style={{ width: '100%', height: 'auto', display: 'block' }} role="img" aria-label="activation rate over time">
      {points.map((p, i) => {
        const h = ((p.activated ?? 0) / max) * (H - 4);
        return (
          <rect
            key={p.period ?? i}
            x={i * (bw + gap)}
            y={H - h}
            width={Math.max(bw, 0.6)}
            height={h}
            rx={bw > 3 ? 1 : 0}
            fill="var(--accent)"
          >
            <title>{p.period}: {num(p.activated)} activated</title>
          </rect>
        );
      })}
    </svg>
  );
}

// Per-account drill (ghost-busters parity): headline + sell-through, the activation rate over time at three
// grains, and every MRPeasy delivery as a dated tranche scored by depletion %.
function ShelfDrill({ row }: { row: ShelfRow }) {
  const [grain, setGrain] = useState<Grain>('monthly');
  const placed = row.placed ?? row.shipped ?? 0;
  const drawn = row.drawn ?? row.activated ?? 0;
  const sellThrough = placed ? (drawn / placed) * 100 : 0;

  const detail = useApi<AcctDetail>(
    ['shelf-detail', row.company_id ?? ''],
    `/api/v1/h6q/shelf/${row.company_id}/detail`,
    { enabled: !!row.company_id },
  );
  const data = detail.data ?? {};
  const deliveries = Array.isArray(data.deliveries) ? data.deliveries : [];
  const series = (data.activations?.[grain] ?? []) as ActPoint[];

  return (
    <>
      <div className="grid" style={{ gridTemplateColumns: 'repeat(3,1fr)', gap: 10, marginBottom: 18 }}>
        <div className="card" style={{ padding: '12px 14px', background: 'var(--bg-2)' }}>
          <div className="fldlabel">Shipped</div>
          <div className="num" style={{ fontFamily: 'var(--font-disp)', fontSize: 20, fontWeight: 600, marginTop: 3 }}>{num(row.shipped)}</div>
        </div>
        <div className="card" style={{ padding: '12px 14px', background: 'var(--bg-2)' }}>
          <div className="fldlabel">Activated</div>
          <div className="num" style={{ fontFamily: 'var(--font-disp)', fontSize: 20, fontWeight: 600, marginTop: 3, color: 'var(--ok)' }}>{num(row.activated)}</div>
        </div>
        <div className="card" style={{ padding: '12px 14px', background: 'var(--bg-2)' }}>
          <div className="fldlabel">On-shelf</div>
          <div className="num" style={{ fontFamily: 'var(--font-disp)', fontSize: 20, fontWeight: 600, marginTop: 3, color: 'var(--accent-bright)' }}>{num(row.on_shelf)}</div>
        </div>
      </div>

      <div className="mini" style={{ marginBottom: 8 }}>Sell-in vs sell-through{row.consignment ? ' — consignment (drawn, not placed, is the sale)' : ''}</div>
      <div className="card" style={{ padding: 14, background: 'var(--bg-2)', marginBottom: 16 }}>
        <div className="row between" style={{ marginBottom: 11 }}>
          <span className="dim" style={{ fontSize: 12 }}>{row.consignment ? 'placed on shelf' : 'sold in'}</span>
          <span className="num" style={{ fontWeight: 600 }}>{num(placed)}</span>
        </div>
        <div className="covbar" style={{ marginBottom: 12 }}>
          <div className="track"><i style={{ width: Math.min(100, sellThrough) + '%', background: 'linear-gradient(90deg,var(--accent),var(--accent-bright))' }} /></div>
          <span className="pct">{sellThrough.toFixed(0)}%</span>
        </div>
        <div className="kv">
          <span className="k">{row.consignment ? 'Drawn (the sale)' : 'Activated'}</span>
          <span className="v num">{num(drawn)}</span>
          <span className="k">On-shelf now</span>
          <span className="v num">{num(row.on_shelf)}</span>
          <span className="k">Reorder point</span>
          <span className="v num">{row.reorder_point == null ? '—' : num(row.reorder_point)}</span>
          <span className="k">Runway</span>
          <span className="v">{runwayLabel(row)}{row.weekly_run_rate != null ? ` · ${num(row.weekly_run_rate)}/wk run-rate` : ''}</span>
        </div>
      </div>

      {crossesReorder(row) && (
        <div className="banner danger" style={{ marginBottom: 16 }}>
          {I.alert()}
          <div>
            <span className="bb">{acctName(row)} is at or below its reorder point.</span> On-shelf {num(row.on_shelf)} ≤ reorder {num(row.reorder_point)} — chase replenishment before it runs dry.
          </div>
        </div>
      )}

      <div className="row between" style={{ marginBottom: 8, alignItems: 'center' }}>
        <span className="mini">Activation rate over time</span>
        <div className="row g6">
          {(['daily', 'weekly', 'monthly'] as Grain[]).map((g) => (
            <button
              key={g}
              className={'btn sm' + (grain === g ? ' primary' : '')}
              data-testid={`act-grain-${g}`}
              onClick={() => setGrain(g)}
              style={{ textTransform: 'capitalize' }}
            >
              {g}
            </button>
          ))}
        </div>
      </div>
      <div className="card" style={{ padding: 14, background: 'var(--bg-2)', marginBottom: 16 }}>
        {detail.isLoading ? (
          <div className="dim" style={{ fontSize: 12, padding: '8px 2px' }}>Loading activation history…</div>
        ) : (
          <ActivationBars points={series} />
        )}
      </div>

      <div className="mini" style={{ marginBottom: 8 }}>Deliveries — each MRPeasy shipment as a dated tranche, scored by depletion</div>
      <div className="card tablewrap" style={{ padding: 0, background: 'var(--bg-2)', maxHeight: 320, overflow: 'auto' }}>
        {detail.isLoading ? (
          <div className="dim" style={{ fontSize: 12, padding: 14 }}>Loading deliveries…</div>
        ) : deliveries.length === 0 ? (
          <div className="dim" style={{ fontSize: 12, padding: 14 }}>No deliveries recorded for this account.</div>
        ) : (
          <table className="tbl" data-testid="acct-deliveries" style={{ fontSize: 12 }}>
            <thead>
              <tr>
                <th>Tranche</th>
                <th>Date</th>
                <th className="num">Units</th>
                <th className="num">Activated</th>
                <th style={{ width: 120 }}>Depletion</th>
              </tr>
            </thead>
            <tbody>
              {deliveries.map((d, i) => {
                const pct = d.depletion_pct ?? 0;
                return (
                  <tr key={d.dispatch_no ?? i} data-testid="acct-delivery-row">
                    <td style={{ fontFamily: 'var(--font-mono)' }}>{d.dispatch_no}</td>
                    <td className="dim">{d.date ? d.date.slice(0, 10) : '—'}</td>
                    <td className="num">{num(d.shipped)}</td>
                    <td className="num" style={{ color: 'var(--ok)' }}>{num(d.activated)}</td>
                    <td><Coverage pct={pct} /></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}
