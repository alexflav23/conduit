import React, { useState } from 'react';
import { useApi, request } from './lib/query';
import { ApiError } from './lib/client';
import { marketId } from './api';
import { PageHead, Card, Chip, Money, LayerNote, AuditRef, EmptyRow, SkeletonRow, Skeleton } from './kit/kit';
import { I } from './kit/icons';

// Conduit Desk — Procurement entity (doc 28): the SG principal / LRD topology, the central catalogue the group
// buys through, and flash-title matched journals. Title flashes through the principal at dispatch, the markup is
// booked, and unwinds to EXACTLY zero on a void/return — that conservation is the proof the structure is sound.
// The whole surface is inter_entity-walled (absent without the layer); catalogue governance is maker-checker
// (self-activation blocked).
//
// Backing routes:
//   GET  /api/v1/group/structure                                org chart (view:entity_structure; the
//                                                                inter_entity layer decides whether the
//                                                                procurement entity + procurement_parent edge exist)
//   GET  /api/v1/procurement/price-lists?market_id=<uuid>        central catalogue (view:transfer_price_list)
//   POST /api/v1/procurement/price-lists/{id}/activate           activate a draft (approve:transfer_price_list)
//   GET  /api/v1/procurement/matches?order_id=                   flash-title match ledger (view:ic_match)

const WALL = 'inter_entity';

type Props = { role: any; ctx: any; toast: (m: string, k?: string) => void };

interface EntityRow {
  id?: string;
  name?: string;
  jurisdiction?: string;
  functional_currency?: string;
  entity_type?: string;
  status?: string;
  group_parent_id?: string | null;
  procurement_parent_id?: string | null;
}
interface StructureRes { entities?: EntityRow[] }

interface PriceListLine { product_variant_id?: string; unit_price?: string | number }
interface PriceListRow {
  id?: string;
  procurement_entity_id?: string;
  market_id?: string;
  currency?: string;
  status?: string;
  version?: string | number;
  effective_from?: string;
  effective_to?: string | null;
  proposed_by?: string;
  proposer?: string;
  maker?: string;
  lines?: PriceListLine[];
}

interface MatchRow {
  id?: string;
  dispatch_id?: string;
  order_id?: string;
  operating_entity_id?: string;
  procurement_entity_id?: string;
  price_list_id?: string;
  currency?: string;
  landed_total?: string | number;
  transfer_total?: string | number;
  uplift_total?: string | number;
  elimination_group_id?: string | null;
  created_at?: string;
}

const asArray = <T,>(x: unknown): T[] => (Array.isArray(x) ? (x as T[]) : []);
const short = (s?: string) => (s ? String(s).slice(0, 8) : '—');

// The honest unbacked-environment panel (a 404 from the route — the endpoint isn't wired here).
function NotBacked({ what }: { what: string }) {
  return (
    <div style={{ display: 'grid', placeItems: 'center', gap: 10, padding: '30px 24px', textAlign: 'center' }}>
      <span style={{ width: 44, height: 44, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'var(--panel-2)' }}>{I.layers({ size: 22 })}</span>
      <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>Not available in this environment yet</div>
      <div className="dim" style={{ fontSize: 12.5, maxWidth: 460 }}>{what}</div>
    </div>
  );
}

export function Procurement({ role, ctx, toast }: Props) {
  const layers: string[] = (role?.layers as string[]) || [];
  const hasLayer = layers.indexOf(WALL) >= 0;
  const viewer: string = role?.name || role?.token || '';
  const mkt = ctx?.market ? marketId(ctx.market) : undefined;

  const [busy, setBusy] = useState<string | null>(null);

  const structure = useApi<StructureRes>(
    ['proc-structure', ctx?.entity],
    '/api/v1/group/structure',
    { enabled: hasLayer },
  );
  const lists = useApi<PriceListRow[]>(
    ['proc-price-lists', ctx?.entity, ctx?.market],
    `/api/v1/procurement/price-lists${mkt ? `?market_id=${encodeURIComponent(mkt)}` : ''}`,
    { enabled: hasLayer },
  );
  const matches = useApi<MatchRow[]>(
    ['proc-matches', ctx?.entity, ctx?.market],
    '/api/v1/procurement/matches',
    { enabled: hasLayer },
  );

  const entities = asArray<EntityRow>(structure.data?.entities);
  const principal = entities.find((e) => e.entity_type === 'procurement');
  const operating = entities.filter((e) => e.procurement_parent_id != null);
  const listRows = asArray<PriceListRow>(lists.data);
  const matchRows = asArray<MatchRow>(matches.data);

  const proposedByViewer = (row: PriceListRow) =>
    !!viewer && (row.proposed_by === viewer || row.proposer === viewer || row.maker === viewer);

  const activate = async (row: PriceListRow) => {
    if (!row.id) return;
    setBusy(row.id);
    try {
      await request(`/api/v1/procurement/price-lists/${encodeURIComponent(row.id)}/activate`, { method: 'POST' });
      toast(`Catalogue ${short(row.id)} v${String(row.version ?? '')} activated`, 'ok');
      await lists.refetch();
    } catch (e) {
      const ae = e as ApiError;
      if (ae?.status === 422) toast('Self-activation blocked — a second checker must activate', 'warn');
      else toast(`Activate failed (${ae?.status ?? '—'}: ${ae?.message ?? ''})`, 'err');
    } finally {
      setBusy(null);
    }
  };

  // The whole surface is inter_entity-walled — without the layer the desk collapses to a single LayerNote
  // rather than render empty frames, and the server withholds these routes (403) too.
  if (!hasLayer) {
    return (
      <>
        <PageHead
          crumb="Procurement-entity structure (doc 28) · how the group actually procures"
          title="Procurement"
          sub="The SG principal / LRD topology, the central catalogue, and flash-title matched journals."
        />
        <Card>
          <LayerNote>hidden — requires <b>inter_entity</b>. The principal/LRD structure, transfer terms and flash-title uplift are walled for this view.</LayerNote>
        </Card>
      </>
    );
  }

  return (
    <>
      <PageHead
        crumb="Procurement-entity structure (doc 28) · how the group actually procures"
        title="Procurement"
        sub="The SG principal / LRD topology, the central catalogue the group buys through, and flash-title matched journals — title flashes through the principal at dispatch, the markup is booked, and unwinds to exactly zero on a void or return."
      />

      {/* ── Entity structure: principal ↔ operating (LRD) graph ── */}
      <Card title="Entity structure" icon={I.layers} aux="principal ↔ operating (LRD) · functional currencies" style={{ marginBottom: 14 }}>
        {structure.isLoading ? (
          <Skeleton lines={3} />
        ) : structure.error?.forbidden ? (
          <LayerNote>hidden — requires <b>inter_entity</b>. The principal/LRD structure is walled for this view.</LayerNote>
        ) : structure.error?.notImplemented ? (
          <NotBacked what="The group entity structure appears once the intercompany service is wired in this environment." />
        ) : structure.error ? (
          <div className="banner danger">{I.alert()}Couldn't load the entity structure ({structure.error.status}).</div>
        ) : !principal && operating.length === 0 ? (
          <div className="dim" style={{ padding: 6 }} data-testid="proc-structure-empty">No procurement entity structure configured.</div>
        ) : (
          <>
            <div className="row g12" style={{ alignItems: 'center', flexWrap: 'wrap' }} data-testid="proc-structure">
              {principal && (
                <div className="card" style={{ padding: '14px 18px', background: 'var(--accent-subtle)', borderColor: 'var(--accent-line)', minWidth: 220 }}>
                  <div className="dim" style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em' }}>Principal</div>
                  <div style={{ fontFamily: 'var(--font-disp)', fontSize: 16, fontWeight: 600 }}>{principal.name}</div>
                  <div className="dim" style={{ fontSize: 11.5 }}>{principal.jurisdiction}</div>
                  <div className="row g6" style={{ marginTop: 8 }}>
                    <span className="chip neutral"><span className="d" />{principal.functional_currency}</span>
                    {principal.entity_type && <span className="chip accent"><span className="d" />{principal.entity_type}</span>}
                  </div>
                </div>
              )}
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4, color: 'var(--faint)', alignItems: 'center' }}>
                {I.arrowR({ size: 20 })}<span style={{ fontSize: 10 }}>buy-sell</span>
              </div>
              <div className="row g10" style={{ flexWrap: 'wrap' }}>
                {operating.map((o) => (
                  <div key={o.id} className="card" style={{ padding: '14px 18px', minWidth: 200 }}>
                    <div className="dim" style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em' }}>Operating · LRD</div>
                    <div style={{ fontFamily: 'var(--font-disp)', fontSize: 16, fontWeight: 600 }}>{o.name}</div>
                    <div className="dim" style={{ fontSize: 11.5 }}>{o.jurisdiction}</div>
                    <div className="row g6" style={{ marginTop: 8 }}><span className="chip neutral"><span className="d" />{o.functional_currency}</span></div>
                  </div>
                ))}
              </div>
            </div>
            <LayerNote>
              The contract manufacturers sell to the principal; the principal on-sells to each LRD at the catalogue uplift. Risk and title sit with the principal for an instant.
            </LayerNote>
          </>
        )}
      </Card>

      {/* ── Central catalogue: maker proposes → checker activates → v2 supersedes ── */}
      <Card title="Central catalogue" icon={I.list} aux="maker proposes → checker activates → v2 supersedes · per-variant transfer prices (inter_entity)" style={{ padding: 0, marginBottom: 14 }} className="tablewrap">
        {lists.error?.notImplemented ? (
          <NotBacked what="The central price catalogue appears once the procurement service is wired in this environment." />
        ) : (
          <table className="tbl" data-testid="proc-catalogue">
            <thead>
              <tr>
                <th>List</th><th>Entity</th><th>Market</th>
                <th className="num">Lines</th><th>Currency</th>
                <th>From</th><th>Version</th><th>Status</th><th></th>
              </tr>
            </thead>
            <tbody>
              {lists.isLoading && <SkeletonRow cols={9} />}
              {!lists.isLoading && lists.error?.forbidden && (
                <EmptyRow cols={9}><LayerNote>Catalogue hidden — requires <b>inter_entity</b>.</LayerNote></EmptyRow>
              )}
              {!lists.isLoading && lists.error && !lists.error.forbidden && !lists.error.notImplemented && (
                <EmptyRow cols={9}>Couldn't load the catalogue ({lists.error.status}).</EmptyRow>
              )}
              {!lists.isLoading && !lists.error && listRows.length === 0 && (
                <EmptyRow cols={9}>No catalogue price lists{mkt ? ' for this market' : ''}.</EmptyRow>
              )}
              {!lists.isLoading && !lists.error && listRows.map((c, i) => {
                const status = c.status ?? 'draft';
                const isDraft = status === 'draft' || status === 'proposed';
                const selfBlocked = isDraft && proposedByViewer(c);
                return (
                  <tr key={c.id ?? i} data-testid="proc-cat-row">
                    <td className="mono dim" style={{ fontSize: 11 }}>{short(c.id)}</td>
                    <td className="mono dim" style={{ fontSize: 11 }}>{short(c.procurement_entity_id)}</td>
                    <td className="mono dim" style={{ fontSize: 11 }}>{short(c.market_id)}</td>
                    <td className="num"><b>{asArray<PriceListLine>(c.lines).length}</b></td>
                    <td><span className="chip neutral"><span className="d" />{c.currency ?? '—'}</span></td>
                    <td className="mono">{c.effective_from ?? '—'}</td>
                    <td><span className="chip neutral"><span className="d" />{c.version ?? '—'}</span></td>
                    <td><Chip s={status}>{status}</Chip></td>
                    <td>
                      {isDraft && (
                        <button
                          className="btn sm primary"
                          data-testid="proc-activate"
                          disabled={selfBlocked || busy === c.id}
                          title={selfBlocked ? 'You proposed this — a second checker must activate (maker-checker)' : 'Activate'}
                          onClick={() => activate(c)}
                        >
                          {busy === c.id ? 'Activating…' : 'Activate'}
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </Card>

      {/* ── Flash-title ledger: matched principal/operating legs · the unwind nets to exactly zero ── */}
      <Card title="Flash-title ledger" icon={I.shield} aux="matched principal/operating legs · landed → transfer → uplift" style={{ padding: 0 }} className="tablewrap">
        {matches.error?.notImplemented ? (
          <NotBacked what="The flash-title match ledger appears once the intercompany service is wired in this environment." />
        ) : matches.isLoading ? (
          <div style={{ padding: 16 }}><Skeleton lines={4} /></div>
        ) : matches.error?.forbidden ? (
          <div style={{ padding: 16 }}><LayerNote>Flash-title ledger hidden — requires <b>inter_entity</b>.</LayerNote></div>
        ) : matches.error ? (
          <div className="banner danger" style={{ margin: 16 }}>{I.alert()}Couldn't load the flash-title ledger ({matches.error.status}).</div>
        ) : matchRows.length === 0 ? (
          <div className="dim" style={{ padding: 16 }} data-testid="proc-flash-empty">No flash-title dispatches in this context.</div>
        ) : (
          <>
            {matchRows.map((m, i) => {
              const ccy = m.currency ?? 'GBP';
              const uplift = Number(m.uplift_total) || 0;
              const unwound = uplift === 0;
              return (
                <div key={m.id ?? i} style={{ borderBottom: i < matchRows.length - 1 ? '1px solid var(--border)' : 'none', padding: '14px 16px' }} data-testid="proc-flash-row">
                  <div className="row between" style={{ marginBottom: 10 }}>
                    <div className="row g8">
                      <b className="mono" style={{ fontSize: 12 }}>{short(m.dispatch_id)}</b>
                      {m.order_id && <span className="dim" style={{ fontSize: 11.5 }}>order {short(m.order_id)}</span>}
                      <Chip s={unwound ? 'neutral' : 'matched'}>{unwound ? 'unwound' : 'matched'}</Chip>
                    </div>
                    {unwound ? (
                      <span className="row g6" style={{ fontSize: 12 }}>
                        unwinds to <b className="num" style={{ color: 'var(--ok)' }}>£0.00</b>
                        {I.check({ size: 14, style: { color: 'var(--ok)' } } as any)}
                      </span>
                    ) : (
                      <span className="row g6" style={{ fontSize: 12 }}>
                        uplift booked <b className="num" style={{ color: 'var(--ok)' }}><Money value={m.uplift_total} ccy={ccy} layer={WALL} role={role} /></b>
                      </span>
                    )}
                  </div>
                  <div style={{ display: 'grid', gap: 4 }}>
                    <div className="row between" style={{ padding: '6px 11px', borderRadius: 8, background: 'var(--bg-2)', fontSize: 12 }}>
                      <span>
                        <span className="mono dim" style={{ fontSize: 11 }}>{short(m.procurement_entity_id)} → {short(m.operating_entity_id)}</span>
                        <span className="dim"> · transfer</span>
                        {m.elimination_group_id && <> <AuditRef id={short(m.elimination_group_id)} /></>}
                      </span>
                      <span className="num"><Money value={m.transfer_total} ccy={ccy} layer={WALL} role={role} /></span>
                    </div>
                    <div className="row between" style={{ padding: '6px 11px', borderRadius: 8, background: 'var(--bg-2)', fontSize: 12 }}>
                      <span><span className="mono dim" style={{ fontSize: 11 }}>landed cost basis</span></span>
                      <span className="num"><Money value={m.landed_total} ccy={ccy} layer={WALL} role={role} /></span>
                    </div>
                  </div>
                </div>
              );
            })}
            <div className="layer-note" style={{ padding: '10px 16px' }}>
              {I.shield()}The conservation proof: a fully-voided dispatch's legs sum to <b>exactly zero</b> — the structure leaves no residue on the books.
            </div>
          </>
        )}
      </Card>
    </>
  );
}
