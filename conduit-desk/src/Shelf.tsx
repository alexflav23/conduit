import React, { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useApi } from './lib/query';
import { ApiError } from './lib/client';
import { PageHead, Card, Chip, Coverage, EmptyRow, LayerNote, SkeletonRow, num } from './kit/kit';
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

export function Shelf({ ctx }: { role: any; ctx: Ctx; toast: (m: string, k?: string) => void }) {
  const navigate = useNavigate();

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
                  onClick={() => navigate('/account/' + acctId(r))}
                  onKeyDown={(e) => e.key === 'Enter' && navigate('/account/' + acctId(r))}
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
    </>
  );
}
