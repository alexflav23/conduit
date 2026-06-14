import React, { useState, useEffect, useCallback } from 'react';
import { authToken, listExceptions, getException, submitNarrative, decide } from './api';
import { PageHead, Card, Chip, Drawer, EmptyRow, LayerNote, AuditRef, SkeletonRow, Money } from './kit/kit';
import { tableState, asArray } from './state';
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

type Res = { status: number; json: any } | null;

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

export function DealDesk({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const token = authToken();
  const layers: string[] = role?.layers ?? [];
  const canSeeCommercial = layers.indexOf('commercial') >= 0;
  const viewerName: string = role?.name ?? '';
  const canDecide = (role?.title ?? '').toLowerCase().includes('ceo') || layers.indexOf('inter_entity') >= 0;

  const [res, setRes] = useState<Res>(null);
  const [items, setItems] = useState<Exception[]>([]);
  const [filter, setFilter] = useState<'open' | 'approved' | 'rejected' | 'all'>('open');
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

  const load = useCallback(async () => {
    setRes(null);
    const statusParam = filter === 'open' ? 'pending_ceo' : filter === 'all' ? '' : filter;
    const r = await listExceptions(token, statusParam);
    setRes(r);
    setItems(asArray<Exception>(r.json));
  }, [token, filter]);

  // Auto-load on mount, when the queue filter changes, and when the entity/market/period context shifts.
  useEffect(() => {
    void load();
  }, [load, ctx?.entity, ctx?.market, ctx?.period]);

  const st = tableState(res, items);

  // Sort the worklist by age (oldest first) then deviation magnitude (largest first) — the spec's order.
  const sorted = [...items].sort((a, b) => {
    const ad = ageDays(b) - ageDays(a);
    if (Math.abs(ad) > 0.001) return ad;
    return (deviation(b).overshootPct ?? 0) - (deviation(a).overshootPct ?? 0);
  });

  const sel = sorted.find((e) => e.id === selId) || null;

  const refreshOne = async (id: string) => {
    const r = await getException(token, id);
    if (r.status === 200 && r.json) {
      setItems((prev) => prev.map((e) => (e.id === id ? { ...e, ...r.json } : e)));
    }
    await load();
  };

  const onSubmitNarrative = async () => {
    if (!sel) return;
    if (!just.trim()) {
      toast('Narrative is required before submitting to the CEO', 'warn');
      return;
    }
    setBusy(true);
    const r = await submitNarrative(token, sel.id, {
      justification: just,
      volumeExpectation: parseInt(vol, 10),
      volumeDenomination: denom,
      strategicImportance: strategic,
    });
    setBusy(false);
    if (r.status === 200) {
      toast('Proposal submitted to the CEO');
      await refreshOne(sel.id);
    } else {
      toast(`Submit failed (${r.status})${r.json?.message ? ': ' + r.json.message : ''}`, 'err');
    }
  };

  const onDecide = async (decisionKind: 'approve' | 'reject') => {
    if (!sel) return;
    if (!memo.trim()) {
      toast('A decision memo is recorded immutably — it is required', 'warn');
      return;
    }
    setBusy(true);
    const r = await decide(token, sel.id, {
      decision: decisionKind,
      memo,
      validFrom: '2026-06-01T00:00:00Z',
      validTo,
      volumeMin: parseInt(volMin, 10),
    });
    setBusy(false);
    if (r.status === 200) {
      const ref = r.json?.decision?.auditRef ? ' · ' + r.json.decision.auditRef : '';
      toast(
        decisionKind === 'approve'
          ? 'Approved — tier minted, held orders released + re-quoted' + ref
          : 'Rejected — held orders cancelled' + ref,
        decisionKind === 'approve' ? 'ok' : 'warn',
      );
      await refreshOne(sel.id);
    } else {
      toast(`Decision failed (${r.status})${r.json?.message ? ': ' + r.json.message : ''}`, 'err');
    }
  };

  const counts = {
    open: items.filter((e) => e.status === 'pending_ceo').length,
    approved: items.filter((e) => e.status === 'approved').length,
    rejected: items.filter((e) => e.status === 'rejected').length,
    all: items.length,
  };
  const FILTS: [typeof filter, string, number][] = [
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

      <Card className="tablewrap" style={{ padding: 0 }}>
        <div className="ct" style={{ padding: '14px 16px', margin: 0, borderBottom: '1px solid var(--border)' }}>
          <div className="t">{I.flag()}Exception queue</div>
          <div className="aux">{st === 'ready' ? `${sorted.length} shown` : ''}</div>
        </div>

        {st === 'forbidden' ? (
          <div style={{ padding: 16 }}>
            <LayerNote>hidden — requires the commercial data layer</LayerNote>
          </div>
        ) : st === 'error' ? (
          <div style={{ padding: 16 }} className="banner danger" data-testid="dd-error">
            {I.alert()} Could not load the exception queue ({res?.status}). It will retry on the next context change.
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
              {st === 'loading' && (
                <>
                  <SkeletonRow cols={5} />
                  <SkeletonRow cols={5} />
                  <SkeletonRow cols={5} />
                </>
              )}
              {st === 'empty' && <EmptyRow cols={5}>No pending exceptions.</EmptyRow>}
              {st === 'ready' &&
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

      <Drawer
        open={!!sel}
        onClose={() => setSelId(null)}
        title={sel?.order_no || sel?.id}
        sub={sel ? `${sel.party || ''}${sel.sku ? ' · ' + sel.sku : ''}` : undefined}
        chip={sel ? <Chip s={sel.status} /> : undefined}
        width={520}
      >
        {sel && (
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
        )}
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
