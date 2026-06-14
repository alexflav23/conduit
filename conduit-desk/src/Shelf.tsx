import React, { useEffect, useMemo, useState } from 'react';
import { getShelfBoard } from './api';
import { tableState, asArray } from './state';
import type { ApiResult } from './state';
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

export function Shelf({ role, ctx, toast }: { role: any; ctx: Ctx; toast: (m: string, k?: string) => void }) {
  const token: string = role?.token ?? '';
  const [res, setRes] = useState<ApiResult | null>(null);
  const [sel, setSel] = useState<ShelfRow | null>(null);

  useEffect(() => {
    let live = true;
    setRes(null);
    getShelfBoard(token)
      .then((r) => { if (live) setRes(r); })
      .catch(() => { if (live) setRes({ status: 0, json: null }); });
    return () => { live = false; };
  }, [token, ctx.entity, ctx.market, ctx.scenario]);

  const rows = useMemo(() => {
    const arr = asArray<ShelfRow>(res?.json);
    return arr.slice().sort((a, b) => runwayRank(a) - runwayRank(b));
  }, [res]);

  const state = tableState(res, res?.json);
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
              <tr><td colSpan={8}><LayerNote>hidden — requires <b>volume</b></LayerNote></td></tr>
            )}

            {state === 'error' && (
              <EmptyRow cols={8}>
                <span style={{ color: 'var(--danger)' }}>Couldn't load the shelf board{res?.status ? ` (${res.status})` : ''}.</span>
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

// Per-account drill: shipped/activated/on-shelf headline + sell-in vs sell-through. For a consignment
// branch the SALE is what's been DRAWN, not what was PLACED — so placed != drawn is shown honestly.
function ShelfDrill({ row }: { row: ShelfRow }) {
  const placed = row.placed ?? row.shipped ?? 0;
  const drawn = row.drawn ?? row.activated ?? 0;
  const sellThrough = placed ? (drawn / placed) * 100 : 0;

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
        <div className="banner danger">
          {I.alert()}
          <div>
            <span className="bb">{acctName(row)} is at or below its reorder point.</span> On-shelf {num(row.on_shelf)} ≤ reorder {num(row.reorder_point)} — chase replenishment before it runs dry.
          </div>
        </div>
      )}
    </>
  );
}
