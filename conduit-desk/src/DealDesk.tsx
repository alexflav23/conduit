import React, { useState } from 'react';
import { useApi, request } from './lib/query';
import { ApiError } from './lib/client';
import { PageHead, Card, Chip, Drawer, EmptyRow, LayerNote, AuditRef, SkeletonRow, Skeleton, Money } from './kit/kit';
import { I } from './kit/icons';

// 02 — Deal Desk (ADLP exceptions). spec/ui/02-deal-desk.md.
// The governed price-exception workflow (doc 20 D5/D6, doc 24): a price below the tier band is a
// price-tier REQUEST (maker-checker → CEO), never an ad-hoc number. The decision IS the activation —
// approving releases + re-quotes the held orders.
//
// Layout: pending queue (worklist, sorted by age + deviation) → exception detail Drawer with the
// deviation-vs-band HERO metric → structured narrative (maker) → decision (checker, maker ≠ checker).
// Prices/deviation are the `commercial` layer; margin context is `profitability`. Auto-load on mount +
// when ctx changes — no Load/Refresh buttons. Four states everywhere.
//
// Backing routes (DealDeskRoutes):
//   GET  /api/v1/adlp/exceptions?status=<s>           — the worklist (view:adlp_exception)
//   GET  /api/v1/adlp/exceptions/{id}                 — one exception, projected to the viewer's layers
//   POST /api/v1/adlp/exceptions/{id}/submit          — maker narrative (edit:adlp_exception)
//   POST /api/v1/adlp/exceptions/{id}/decision        — CEO approve/reject (approve:adlp_exception)
// Status filter maps: open -> pending_ceo, approved -> approved, rejected -> rejected, all -> (omitted).

interface Exception {
  id: string;
  status: string;
  order_no?: string;
  sku?: string;
  party?: string;
  agent?: string;
  list_price?: string | number;
  requested_price?: string | number;
  max_discount_pct?: string | number;
  requested_discount_pct?: string | number;
  created_at?: string;
  narrative?: unknown;
  decision?: { by?: string; memo?: string; validTo?: string; volumeMin?: number; auditRef?: string };
}

const num = (v: unknown): number | null => {
  if (v == null || v === '') return null;
  const n = typeof v === 'string' ? parseFloat(v) : (v as number);
  return Number.isFinite(n) ? n : null;
};

// Deviation of the requested price below the bottom of the ADLP band — THE hero number.
function deviation(e: Exception): { reqPct: number | null; bandPct: number | null; overshootPct: number | null } {
  const reqPct = num(e.requested_discount_pct);
  const bandPct = num(e.max_discount_pct);
  const overshootPct = reqPct != null && bandPct != null ? reqPct - bandPct : null;
  return { reqPct, bandPct, overshootPct };
}

function ageDays(e: Exception): number {
  if (!e.created_at) return 0;
  const t = Date.parse(e.created_at);
  return Number.isFinite(t) ? (Date.now() - t) / 86400000 : 0;
}

type Filter = 'open' | 'approved' | 'rejected' | 'all';
const STATUS_PARAM: Record<Filter, string> = { open: 'pending_ceo', approved: 'approved', rejected: 'rejected', all: '' };

export function DealDesk({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const layers: string[] = role?.layers ?? [];
  const canSeeCommercial = layers.indexOf('commercial') >= 0;
  const viewerName: string = role?.name ?? '';
  const canDecide = (role?.title ?? '').toLowerCase().includes('ceo') || layers.indexOf('inter_entity') >= 0;

  const [filter, setFilter] = useState<Filter>('open');
  const [selId, setSelId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // narrative (maker)
  const [vol, setVol] = useState('500');
  const [denom, setDenom] = useState('P50');
  const [strategic, setStrategic] = useState('');
  const [just, setJust] = useState('');
  // decision (checker)
  const [memo, setMemo] = useState('');
  const [validTo, setValidTo] = useState('2026-12-31T00:00:00Z');
  const [volMin, setVolMin] = useState('400');

  const statusParam = STATUS_PARAM[filter];
  const listPath = `/api/v1/adlp/exceptions${statusParam ? `?status=${statusParam}` : ''}`;
  // Key on the ctx fields the worklist is scoped by so a context switch refetches.
  const list = useApi<Exception[]>(
    ['adlp-exceptions', filter, ctx?.entity, ctx?.market, ctx?.period],
    listPath,
  );

  const listErr = list.error as ApiError | null;
  const forbidden = !!listErr?.forbidden;
  const notImplemented = !!listErr?.notImplemented;
  const otherError = !!listErr && !forbidden && !notImplemented;

  const items: Exception[] = Array.isArray(list.data) ? list.data : [];
  const ready = !list.isLoading && !listErr;
  const empty = ready && items.length === 0;

  // Sort the worklist by age (oldest first) then deviation magnitude (largest first) — the spec's order.
  const sorted = [...items].sort((a, b) => {
    const ad = ageDays(b) - ageDays(a);
    if (Math.abs(ad) > 0.001) return ad;
    return (deviation(b).overshootPct ?? 0) - (deviation(a).overshootPct ?? 0);
  });

  // Fetch the selected exception's full detail (narrative + decision) when the drawer opens.
  const detail = useApi<Exception>(
    ['adlp-exception', selId],
    selId ? `/api/v1/adlp/exceptions/${selId}` : '',
    { enabled: !!selId },
  );
  const sel: Exception | null = selId ? ({ ...(sorted.find((e) => e.id === selId) || {}), ...(detail.data || {}) } as Exception) : null;
  const detailErr = detail.error as ApiError | null;

  const onSubmitNarrative = async () => {
    if (!sel || !selId) return;
    if (!just.trim()) {
      toast('Narrative is required before submitting to the CEO', 'warn');
      return;
    }
    setBusy(true);
    try {
      await request(`/api/v1/adlp/exceptions/${selId}/submit`, {
        method: 'POST',
        body: JSON.stringify({
          justification: just,
          volumeExpectation: parseInt(vol, 10),
          volumeDenomination: denom,
          strategicImportance: strategic || null,
          notes: null,
        }),
      });
      toast('Proposal submitted to the CEO');
      await Promise.all([detail.refetch(), list.refetch()]);
    } catch (e) {
      const ae = e as ApiError;
      toast(`Submit failed (${ae?.status ?? '—'})${ae?.message ? ': ' + ae.message : ''}`, 'err');
    } finally {
      setBusy(false);
    }
  };

  const onDecide = async (decisionKind: 'approve' | 'reject') => {
    if (!sel || !selId) return;
    if (!memo.trim()) {
      toast('A decision memo is recorded immutably — it is required', 'warn');
      return;
    }
    setBusy(true);
    try {
      const res = await request<{ decision?: { auditRef?: string } }>(`/api/v1/adlp/exceptions/${selId}/decision`, {
        method: 'POST',
        body: JSON.stringify({
          decision: decisionKind,
          memo,
          validFrom: '2026-06-01T00:00:00Z',
          validTo,
          volumeMin: parseInt(volMin, 10),
        }),
      });
      const ref = res?.decision?.auditRef ? ' · ' + res.decision.auditRef : '';
      toast(
        decisionKind === 'approve'
          ? 'Approved — tier minted, held orders released + re-quoted' + ref
          : 'Rejected — held orders cancelled' + ref,
        decisionKind === 'approve' ? 'ok' : 'warn',
      );
      await Promise.all([detail.refetch(), list.refetch()]);
    } catch (e) {
      const ae = e as ApiError;
      toast(`Decision failed (${ae?.status ?? '—'})${ae?.message ? ': ' + ae.message : ''}`, 'err');
    } finally {
      setBusy(false);
    }
  };

  const counts = {
    open: items.filter((e) => e.status === 'pending_ceo').length,
    approved: items.filter((e) => e.status === 'approved').length,
    rejected: items.filter((e) => e.status === 'rejected').length,
    all: items.length,
  };
  const FILTS: [Filter, string, number][] = [
    ['open', 'Pending CEO', counts.open],
    ['approved', 'Approved', counts.approved],
    ['rejected', 'Rejected', counts.rejected],
    ['all', 'All', counts.all],
  ];

  const isSelf = sel && viewerName && sel.agent === viewerName;
  const decided = sel?.status === 'approved' || sel?.status === 'rejected';
  const hasNarrative = sel?.narrative != null;

  return (
    <>
      <PageHead
        crumb="Deal Desk · ADLP"
        title="Deal Desk"
        sub="Governed price-tier requests — maker proposes, the CEO decides (maker ≠ checker). Approval mints the tier and releases the held order."
      />

      <div className="seg" style={{ marginBottom: 16 }}>
        {FILTS.map(([k, l, n]) => (
          <button key={k} className={filter === k ? 'on' : ''} onClick={() => setFilter(k)} data-testid={`filter-${k}`}>
            {l} <span style={{ opacity: 0.55, marginLeft: 2 }}>{n}</span>
          </button>
        ))}
      </div>

      {notImplemented ? (
        <Card style={{ padding: '34px 28px', textAlign: 'center' }} data-testid="dd-unbacked">
          <div style={{ display: 'grid', placeItems: 'center', gap: 10 }}>
            <span style={{ width: 44, height: 44, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'var(--panel-2)' }}>{I.flag({ size: 22 })}</span>
            <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>Not available in this environment yet</div>
            <div className="dim" style={{ fontSize: 12.5, maxWidth: 460 }}>The Deal Desk queue appears once the ADLP price-exception workflow is wired in this environment.</div>
          </div>
        </Card>
      ) : (
        <Card className="tablewrap" style={{ padding: 0 }}>
          <div className="ct" style={{ padding: '14px 16px', margin: 0, borderBottom: '1px solid var(--border)' }}>
            <div className="t">{I.flag()}Exception queue</div>
            <div className="aux">{ready ? `${sorted.length} shown` : ''}</div>
          </div>

          {forbidden ? (
            <div style={{ padding: 16 }}>
              <LayerNote>hidden — requires the commercial data layer</LayerNote>
            </div>
          ) : otherError ? (
            <div style={{ padding: 16 }} className="banner danger" data-testid="dd-error">
              {I.alert()} Could not load the exception queue (HTTP {listErr?.status}). It will retry on the next context change.
            </div>
          ) : (
            <table className="tbl">
              <thead>
                <tr>
                  <th>Exception</th>
                  <th>Party</th>
                  <th className="num">Requested</th>
                  <th className="num">Dev. vs band</th>
                  <th>Status</th>
                </tr>
              </thead>
              <tbody>
                {list.isLoading && (
                  <>
                    <SkeletonRow cols={5} />
                    <SkeletonRow cols={5} />
                    <SkeletonRow cols={5} />
                  </>
                )}
                {empty && <EmptyRow cols={5}>No exceptions in this queue.</EmptyRow>}
                {ready &&
                  sorted.map((e) => {
                    const { overshootPct } = deviation(e);
                    return (
                      <tr
                        key={e.id}
                        className={selId === e.id ? 'sel' : ''}
                        tabIndex={0}
                        data-testid={`exc-row-${e.id}`}
                        onClick={() => setSelId(e.id)}
                        onKeyDown={(ev) => ev.key === 'Enter' && setSelId(e.id)}
                      >
                        <td>
                          <b className="mono" style={{ fontSize: 11 }}>{e.order_no || e.id}</b>
                          <div className="dim" style={{ fontSize: 10 }}>{e.sku}</div>
                        </td>
                        <td><b>{e.party || '—'}</b></td>
                        <td className="num">
                          {canSeeCommercial ? <Money value={e.requested_price ?? null} ccy={ctx?.currency || 'GBP'} /> : <span className="dim">—</span>}
                        </td>
                        <td className="num" style={{ color: overshootPct != null && overshootPct > 0 ? 'var(--danger)' : undefined }}>
                          {canSeeCommercial && overshootPct != null ? `+${overshootPct.toFixed(1)}%` : <span className="dim">—</span>}
                        </td>
                        <td><Chip s={e.status} /></td>
                      </tr>
                    );
                  })}
              </tbody>
            </table>
          )}
        </Card>
      )}

      <Drawer
        open={!!selId}
        onClose={() => setSelId(null)}
        title={sel?.order_no || sel?.id || selId || ''}
        sub={sel ? `${sel.party || ''}${sel.sku ? ' · ' + sel.sku : ''}` : undefined}
        chip={sel?.status ? <Chip s={sel.status} /> : undefined}
        width={520}
      >
        {detail.isLoading ? (
          <div data-testid="exception-loading"><Skeleton lines={6} /></div>
        ) : detailErr?.forbidden ? (
          <LayerNote>hidden — requires the commercial data layer</LayerNote>
        ) : detailErr?.notImplemented ? (
          <div className="dim" style={{ fontSize: 12.5 }}>This exception is not available in this environment.</div>
        ) : detailErr ? (
          <div className="banner danger" data-testid="exception-error">{I.alert()} Could not load this exception (HTTP {detailErr.status}).</div>
        ) : sel ? (
          <div data-testid="exception">
            {!canSeeCommercial ? (
              <LayerNote>hidden — requires the commercial data layer</LayerNote>
            ) : (
              <>
                {/* THE hero: deviation vs band */}
                <DeviationHero e={sel} ccy={ctx?.currency || 'GBP'} role={role} />

                {/* 1 · maker proposal */}
                <div className="mini" style={{ marginTop: 18 }}>
                  1 · Agent proposal {hasNarrative && <Chip s="approved">submitted</Chip>}
                </div>
                {decided || hasNarrative ? (
                  <div className="mc" style={{ marginTop: 8 }}>
                    <div className="dim" style={{ fontSize: 12 }}>
                      Proposed by <b style={{ color: 'var(--text)' }}>{sel.agent || '—'}</b>
                    </div>
                  </div>
                ) : (
                  <>
                    <div className="loadbar" style={{ marginTop: 8 }}>
                      <span className="fldlabel">Volume</span>
                      <input className="fld" style={{ width: 90 }} value={vol} onChange={(e) => setVol(e.target.value)} data-testid="narr-volume" />
                      <select className="fld sel" value={denom} onChange={(e) => setDenom(e.target.value)} data-testid="narr-denomination">
                        <option>P20</option><option>P50</option><option>P80</option>
                      </select>
                    </div>
                    <input
                      className="fld"
                      style={{ width: '100%', marginBottom: 8 }}
                      placeholder="Strategic importance"
                      value={strategic}
                      onChange={(e) => setStrategic(e.target.value)}
                      data-testid="narr-strategic"
                    />
                    <textarea
                      className="fld"
                      style={{ width: '100%', minHeight: 64, marginBottom: 10 }}
                      placeholder="Narrative — the value you see in this deal"
                      value={just}
                      onChange={(e) => setJust(e.target.value)}
                      data-testid="narr-justification"
                    />
                    <button className="btn primary" onClick={onSubmitNarrative} disabled={busy || sel.status !== 'pending_ceo'} data-testid="submit-narrative">
                      {I.arrowR({ size: 14 })} Submit proposal
                    </button>
                  </>
                )}

                <div className="divider" />

                {/* 2 · CEO decision (maker ≠ checker) */}
                <div className="mini">2 · CEO decision · single approver</div>

                {decided ? (
                  <div className="mc" style={{ marginTop: 8 }}>
                    <div className="who">
                      Decided by <b>{sel.decision?.by || 'CEO'}</b>
                      {sel.decision?.auditRef && <span style={{ marginLeft: 'auto' }}><AuditRef id={sel.decision.auditRef} /></span>}
                    </div>
                    <div style={{ fontSize: 12.5, color: 'var(--muted)', marginTop: 9, lineHeight: 1.5 }}>{sel.decision?.memo || '(no memo)'}</div>
                    {sel.decision?.validTo && (
                      <div className="dim" style={{ fontSize: 11, marginTop: 7 }}>
                        Valid to {sel.decision.validTo} · min volume {sel.decision.volumeMin}
                      </div>
                    )}
                  </div>
                ) : (
                  <div className="mc" style={{ marginTop: 8 }}>
                    <div className="banner info" style={{ marginBottom: 10 }}>
                      {I.alert({ size: 14 })} Approving mints the price tier and <b>releases + re-quotes the held order</b>. Rejecting cancels it.
                    </div>
                    <textarea
                      className="fld"
                      style={{ width: '100%', minHeight: 56, marginBottom: 8 }}
                      placeholder="Decision memo (recorded immutably)"
                      value={memo}
                      onChange={(e) => setMemo(e.target.value)}
                      data-testid="dec-memo"
                    />
                    <div className="loadbar" style={{ marginBottom: 10 }}>
                      <span className="fldlabel">Valid to</span>
                      <input className="fld" style={{ width: 170 }} value={validTo} onChange={(e) => setValidTo(e.target.value)} data-testid="dec-valid-to" />
                      <span className="fldlabel">Min vol</span>
                      <input className="fld" style={{ width: 80 }} value={volMin} onChange={(e) => setVolMin(e.target.value)} data-testid="dec-volume-min" />
                    </div>
                    <div className="row g8">
                      <button
                        className="btn primary"
                        disabled={busy || !canDecide || !!isSelf || sel.status !== 'pending_ceo'}
                        title={!canDecide ? 'Decisions are CEO-only' : isSelf ? 'You proposed this — self-approval is blocked' : undefined}
                        onClick={() => onDecide('approve')}
                        data-testid="approve-btn"
                      >
                        {I.check({ size: 13 })} Approve
                      </button>
                      <button
                        className="btn danger"
                        disabled={busy || !canDecide || sel.status !== 'pending_ceo'}
                        title={!canDecide ? 'Decisions are CEO-only' : undefined}
                        onClick={() => onDecide('reject')}
                        data-testid="reject-btn"
                      >
                        Reject
                      </button>
                    </div>
                    {!canDecide && (
                      <span className="dim" style={{ fontSize: 11, display: 'block', marginTop: 8 }}>
                        Viewing as {role?.title || 'maker'} — decisions are CEO-only.
                      </span>
                    )}
                    {canDecide && isSelf && (
                      <span className="dim" style={{ fontSize: 11, display: 'block', marginTop: 8 }} data-testid="self-block">
                        You proposed this — self-approval is blocked (maker ≠ checker).
                      </span>
                    )}
                  </div>
                )}
              </>
            )}
          </div>
        ) : null}
      </Drawer>
    </>
  );
}

// The deviation-vs-band hero strip: list ex-VAT · ADLP band floor · requested · the deviation (THE number).
function DeviationHero({ e, ccy, role }: { e: Exception; ccy: string; role: any }) {
  const { reqPct, bandPct, overshootPct } = deviation(e);
  return (
    <div>
      <div
        className="card"
        style={{
          padding: '16px 18px',
          background: 'var(--accent-subtle)',
          border: '1px solid var(--accent-line)',
          marginBottom: 12,
          textAlign: 'center',
        }}
      >
        <div className="fldlabel">Deviation vs band</div>
        <div
          className="num"
          style={{ fontFamily: 'var(--font-disp)', fontSize: 42, fontWeight: 700, color: 'var(--danger)', lineHeight: 1.1, marginTop: 4 }}
          data-testid="exc-deviation"
        >
          {overshootPct != null ? `+${overshootPct.toFixed(1)}%` : '—'}
        </div>
        <div className="dim" style={{ fontSize: 12, marginTop: 4 }}>
          {reqPct != null ? `${reqPct.toFixed(1)}% requested` : '—'}{bandPct != null ? ` · band floor ${bandPct.toFixed(1)}%` : ''}
        </div>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3,1fr)', gap: 8 }}>
        <HeroCell label="List ex-VAT">
          <Money value={e.list_price ?? null} ccy={ccy} layer="commercial" role={role} />
        </HeroCell>
        <HeroCell label="ADLP band floor">
          <span className="num">{bandPct != null ? `${bandPct.toFixed(1)}%` : '—'}</span>
        </HeroCell>
        <HeroCell label="Requested" danger>
          <Money value={e.requested_price ?? null} ccy={ccy} layer="commercial" role={role} />
        </HeroCell>
      </div>
    </div>
  );
}

function HeroCell({ label, children, danger }: { label: string; children: React.ReactNode; danger?: boolean }) {
  return (
    <div className="card" style={{ padding: '10px 12px', background: 'var(--bg-2)' }}>
      <div className="fldlabel">{label}</div>
      <div
        className="num"
        style={{ fontFamily: 'var(--font-disp)', fontSize: 16, fontWeight: 600, marginTop: 4, color: danger ? 'var(--danger)' : 'var(--text)' }}
      >
        {children}
      </div>
    </div>
  );
}
