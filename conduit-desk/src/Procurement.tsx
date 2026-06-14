import React, { useState, useEffect, useCallback } from 'react';
import { apiFetch } from './api';
import { tableState, asArray } from './state';
import { PageHead, Card, Chip, Money, LayerNote, AuditRef, EmptyRow, SkeletonRow, Skeleton, useToast } from './kit/kit';
import { I } from './kit/icons';

// Conduit Desk — Procurement entity (doc 28 / spec/ui/24): the SG principal / LRD topology, the central
// catalogue the group buys through, and flash-title matched journals. Title flashes through the principal at
// dispatch, the markup is booked, and unwinds to EXACTLY zero on a void/return — that conservation is the
// proof the structure is sound. The whole surface is inter_entity-walled (absent without the layer); catalogue
// governance is maker-checker (self-activation blocked). Backend: ProcurementCatalogue + IntercompanyService.

const WALL = 'inter_entity';

interface EntityNode { code: string; name: string; ccy: string; role?: string }
interface Graph { principal: EntityNode; operating: EntityNode[]; cms?: string[] }
interface CatRow {
  id: string; sku: string; label?: string; transferTerms?: string; transfer_terms?: string;
  baseCost?: string | number; base_cost?: string | number; baseCcy?: string;
  upliftPct?: number; uplift_pct?: number; version?: string | number; status?: string;
  proposedBy?: string; proposed_by?: string;
}
interface Leg { from: string; to: string; basis?: string; amount: number; ccy?: string; transferId?: string; transfer_id?: string }
interface Flash {
  dispatch: string; sku?: string; status?: string; uplift?: number; ccy?: string; legs: Leg[];
}

function hasLayer(role: any): boolean {
  return Array.isArray(role?.layers) && role.layers.indexOf(WALL) >= 0;
}

function pick<T>(...vals: (T | undefined)[]): T | undefined {
  return vals.find((v) => v !== undefined);
}

export function Procurement({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const [graphRes, setGraphRes] = useState<{ status: number; json: any } | null>(null);
  const [catRes, setCatRes] = useState<{ status: number; json: any } | null>(null);
  const [flashRes, setFlashRes] = useState<{ status: number; json: any } | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [toastNode, fire] = useToast();

  const notify = useCallback((m: string, k?: string) => { fire(m, (k as any) || 'ok'); toast(m, k); }, [fire, toast]);

  const entity = ctx?.entity ?? '';

  const load = useCallback(() => {
    setGraphRes(null); setCatRes(null); setFlashRes(null);
    const q = entity ? `?entity=${encodeURIComponent(entity)}` : '';
    apiFetch(`/api/v1/procurement/structure${q}`).then(setGraphRes);
    apiFetch(`/api/v1/procurement/catalogue${q}`).then(setCatRes);
    apiFetch(`/api/v1/procurement/flash-title${q}`).then(setFlashRes);
  }, [entity]);

  useEffect(load, [load]);

  // The whole surface is inter_entity-walled. The server withholds it (401/403) without the layer; the
  // viewer's role mirrors that so the desk can collapse to a single LayerNote rather than render empty frames.
  const forbidden =
    !hasLayer(role) ||
    [graphRes, catRes, flashRes].some((r) => r && (r.status === 401 || r.status === 403));

  if (forbidden) {
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

  const graph: Graph | null = graphRes && graphRes.status < 400 ? (graphRes.json as Graph) : null;
  const catState = tableState(catRes, catRes?.json);
  const cat = asArray<CatRow>(catRes?.json);
  const flashState = tableState(flashRes, flashRes?.json);
  const flash = asArray<Flash>(flashRes?.json);

  const me = pick<string>(role?.name, role?.token) ?? '';

  const activate = (row: CatRow) => {
    setBusy(row.id);
    apiFetch(`/api/v1/procurement/catalogue/${encodeURIComponent(row.id)}/activate`, { method: 'POST' })
      .then((r) => {
        if (r.status >= 200 && r.status < 300) notify(`Catalogue ${row.sku} ${pick(row.version, '') as any} activated`, 'ok');
        else if (r.status === 409) notify('Self-activation blocked — a second checker must activate', 'warn');
        else notify(`Activate failed (${r.status})`, 'err');
        load();
      })
      .finally(() => setBusy(null));
  };

  return (
    <>
      {toastNode}
      <PageHead
        crumb="Procurement-entity structure (doc 28) · how the group actually procures"
        title="Procurement"
        sub="The SG principal / LRD topology, the central catalogue the group buys through, and flash-title matched journals — title flashes through the principal at dispatch, the markup is booked, and unwinds to exactly zero on a void or return."
      />

      {/* ── Entity structure: principal ↔ operating (LRD) graph ── */}
      <Card title="Entity structure" icon={I.layers} aux="principal ↔ operating (LRD) · functional currencies" style={{ marginBottom: 14 }}>
        {!graphRes ? (
          <Skeleton lines={3} />
        ) : graphRes.status >= 400 ? (
          <div className="banner danger">{I.alert()}Couldn't load the entity structure ({graphRes.status}).</div>
        ) : !graph ? (
          <div className="dim" style={{ padding: 6 }}>No entity structure configured.</div>
        ) : (
          <>
            <div className="row g12" style={{ alignItems: 'center', flexWrap: 'wrap' }}>
              <div className="card" style={{ padding: '14px 18px', background: 'var(--accent-subtle)', borderColor: 'var(--accent-line)', minWidth: 220 }}>
                <div className="dim" style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em' }}>Principal</div>
                <div style={{ fontFamily: 'var(--font-disp)', fontSize: 16, fontWeight: 600 }}>{graph.principal.code}</div>
                <div className="dim" style={{ fontSize: 11.5 }}>{graph.principal.name}</div>
                <div className="row g6" style={{ marginTop: 8 }}>
                  <span className="chip neutral"><span className="d" />{graph.principal.ccy}</span>
                  {graph.principal.role && <span className="chip accent"><span className="d" />{graph.principal.role}</span>}
                </div>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4, color: 'var(--faint)', alignItems: 'center' }}>
                {I.arrowR({ size: 20 })}<span style={{ fontSize: 10 }}>buy-sell</span>
              </div>
              <div className="row g10" style={{ flexWrap: 'wrap' }}>
                {asArray<EntityNode>(graph.operating).map((o) => (
                  <div key={o.code} className="card" style={{ padding: '14px 18px', minWidth: 200 }}>
                    <div className="dim" style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em' }}>Operating · LRD</div>
                    <div style={{ fontFamily: 'var(--font-disp)', fontSize: 16, fontWeight: 600 }}>{o.code}</div>
                    <div className="dim" style={{ fontSize: 11.5 }}>{o.name}</div>
                    <div className="row g6" style={{ marginTop: 8 }}><span className="chip neutral"><span className="d" />{o.ccy}</span></div>
                  </div>
                ))}
              </div>
            </div>
            {graph.cms && graph.cms.length > 0 && (
              <LayerNote>
                The CMs ({graph.cms.join(', ')}) sell to the principal; the principal on-sells to each LRD at the catalogue uplift. Risk and title sit with the principal for an instant.
              </LayerNote>
            )}
          </>
        )}
      </Card>

      {/* ── Central catalogue: maker proposes → checker activates → v2 supersedes ── */}
      <Card title="Central catalogue" icon={I.list} aux="maker proposes → checker activates → v2 supersedes · per-variant transfer terms (inter_entity)" style={{ padding: 0, marginBottom: 14 }} className="tablewrap">
        <table className="tbl">
          <thead>
            <tr>
              <th>SKU</th><th>Variant</th><th>Transfer terms</th>
              <th className="num">Base cost</th><th className="num">Uplift</th>
              <th>Version</th><th>Status</th><th></th>
            </tr>
          </thead>
          <tbody>
            {catState === 'loading' && <SkeletonRow cols={8} />}
            {catState === 'error' && <EmptyRow cols={8}>Couldn't load the catalogue ({catRes?.status}).</EmptyRow>}
            {catState === 'empty' && <EmptyRow cols={8}>No catalogue entries.</EmptyRow>}
            {catState === 'ready' && cat.map((c) => {
              const status = c.status ?? 'draft';
              const proposer = pick(c.proposedBy, c.proposed_by);
              const isMine = proposer != null && proposer === me;
              const proposed = status === 'draft' || status === 'proposed';
              return (
                <tr key={c.id}>
                  <td className="mono dim" style={{ fontSize: 11 }}>{c.sku}</td>
                  <td><b>{c.label ?? c.sku}</b></td>
                  <td className="dim">{pick(c.transferTerms, c.transfer_terms) ?? '—'}</td>
                  <td className="num"><Money value={pick(c.baseCost, c.base_cost) as any} ccy={c.baseCcy ?? 'USD'} layer={WALL} role={role} /></td>
                  <td className="num"><b>{((pick(c.upliftPct, c.uplift_pct) ?? 0) as number).toFixed(1)}%</b></td>
                  <td><span className="chip neutral"><span className="d" />{c.version ?? '—'}</span></td>
                  <td><Chip s={status}>{status}</Chip></td>
                  <td>
                    {proposed && (
                      <button
                        className="btn sm primary"
                        disabled={isMine || busy === c.id}
                        title={isMine ? 'You proposed this — a second checker must activate (maker-checker)' : undefined}
                        onClick={() => !isMine && activate(c)}
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
      </Card>

      {/* ── Flash-title ledger: matched principal/operating legs · the unwind nets to exactly zero ── */}
      <Card title="Flash-title ledger" icon={I.shield} aux="matched principal/operating legs · the unwind nets to exactly zero" style={{ padding: 0 }} className="tablewrap">
        {flashState === 'loading' && <div style={{ padding: 16 }}><Skeleton lines={4} /></div>}
        {flashState === 'error' && <div className="banner danger" style={{ margin: 16 }}>{I.alert()}Couldn't load the flash-title ledger ({flashRes?.status}).</div>}
        {flashState === 'empty' && <div className="dim" style={{ padding: 16 }}>No flash-title dispatches in this context.</div>}
        {flashState === 'ready' && flash.map((f, i) => {
          const legs = asArray<Leg>(f.legs);
          const net = legs.reduce((a, l) => a + (Number(l.amount) || 0), 0);
          const unwound = f.status === 'unwound' || f.status === 'void';
          const ccy = f.ccy ?? 'USD';
          return (
            <div key={f.dispatch + i} style={{ borderBottom: i < flash.length - 1 ? '1px solid var(--border)' : 'none', padding: '14px 16px' }}>
              <div className="row between" style={{ marginBottom: 10 }}>
                <div className="row g8">
                  <b className="mono" style={{ fontSize: 12 }}>{f.dispatch}</b>
                  {f.sku && <span className="dim" style={{ fontSize: 11.5 }}>{f.sku}</span>}
                  <Chip s={unwound ? 'neutral' : 'matched'}>{f.status ?? 'matched'}</Chip>
                </div>
                {unwound ? (
                  <span className="row g6" style={{ fontSize: 12 }}>
                    unwinds to <b className="num" style={{ color: net === 0 ? 'var(--ok)' : 'var(--danger)' }}>{net === 0 ? '£0.00' : <Money value={net} ccy={ccy} />}</b>
                    {net === 0 && I.check({ size: 14, style: { color: 'var(--ok)' } } as any)}
                  </span>
                ) : (
                  <span className="row g6" style={{ fontSize: 12 }}>
                    uplift booked <b className="num" style={{ color: 'var(--ok)' }}><Money value={pick(f.uplift, 0) as any} ccy={ccy} layer={WALL} role={role} /></b>
                  </span>
                )}
              </div>
              <div style={{ display: 'grid', gap: 4 }}>
                {legs.map((l, j) => (
                  <div key={j} className="row between" style={{ padding: '6px 11px', borderRadius: 8, background: l.amount < 0 ? 'var(--danger-bg)' : 'var(--bg-2)', fontSize: 12 }}>
                    <span>
                      <span className="mono dim" style={{ fontSize: 11 }}>{l.from} → {l.to}</span>
                      {l.basis && <span className="dim"> · {l.basis}</span>}
                      {(l.transferId || l.transfer_id) && <> <AuditRef id={pick(l.transferId, l.transfer_id)} /></>}
                    </span>
                    <span className="num" style={{ color: l.amount < 0 ? 'var(--danger)' : undefined }}>
                      <Money value={l.amount} ccy={l.ccy ?? ccy} layer={WALL} role={role} />
                    </span>
                  </div>
                ))}
              </div>
            </div>
          );
        })}
        {flashState === 'ready' && (
          <div className="layer-note" style={{ padding: '10px 16px' }}>
            {I.shield()}The conservation proof: a fully-voided dispatch's legs sum to <b>exactly zero</b> — the structure leaves no residue on the books.
          </div>
        )}
      </Card>
    </>
  );
}
