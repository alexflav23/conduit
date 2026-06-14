import React, { useEffect, useState } from 'react';
import { apiFetch } from './api';
import { PageHead, Card, Chip, AuditRef, LayerNote, gbp, SkeletonRow, useToast } from './kit/kit';
import { tableState, asArray, type ApiResult } from './state';
import { I } from './kit/icons';

// Conduit Desk — Intercompany (spec/ui/23-intercompany.md, doc 28 §5): the treasury / CFO cockpit and the most
// layer-walled screen in the desk. The ENTIRE surface is `inter_entity` — for a viewer without that layer it is
// ABSENT (a 403 wall), never a zeroed view. Four sub-surfaces, each auto-loaded on mount + ctx change:
//   · TP policy   — governed cost-plus tiers, propose → CFO approve (SoD, self-approval blocked)
//   · IC pairs    — per dispatch: principal/operating legs in lockstep, markup, ASC-830 remeasure Δ, settlement
//   · Hedge book  — ASC-815 per-market MTM (Reg S-K 305), designation gated on a contemporaneous doc-ref
//   · True-ups    — §482 period margin vs target → approve & post the IC pair
// Multi-currency figures carry the native amount + the rate + its source; every figure drills to TigerBeetle.

type Tab = 'pairs' | 'tp' | 'hedges' | 'trueups';
const TABS: [Tab, string][] = [['pairs', 'IC pairs'], ['tp', 'TP policy'], ['hedges', 'Hedge book'], ['trueups', '§482 true-ups']];

const fx = (v: number | null | undefined) => (v == null ? '—' : Number(v).toFixed(4));
const signed = (v: number | string | null | undefined, ccy?: string) => {
  const n = typeof v === 'string' ? parseFloat(v) : v;
  if (n == null || Number.isNaN(n)) return <span className="dim">—</span>;
  if (n === 0) return <span className="dim">0.00</span>;
  return <span style={{ color: n < 0 ? 'var(--danger)' : 'var(--ok)' }}>{gbp(n, ccy)}</span>;
};

export function Intercompany({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const layers: string[] = role?.layers ?? [];
  const walled = layers.indexOf('inter_entity') < 0;
  const viewerName: string = role?.name ?? '';

  const [tab, setTab] = useState<Tab>('pairs');
  const [pairs, setPairs] = useState<ApiResult | null>(null);
  const [tp, setTp] = useState<ApiResult | null>(null);
  const [hedges, setHedges] = useState<ApiResult | null>(null);
  const [trueups, setTrueups] = useState<ApiResult | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [toastNode, fire] = useToast();
  const notify = (m: string, k?: string) => { fire(m, (k as any) || 'ok'); toast(m, k); };

  const entity = ctx?.entity ?? '';
  const period = ctx?.period ?? '';

  useEffect(() => {
    if (walled) return;
    let live = true;
    const q = `?entity=${encodeURIComponent(entity)}&period=${encodeURIComponent(period)}`;
    setPairs(null); setTp(null); setHedges(null); setTrueups(null);
    apiFetch(`/api/v1/intercompany/pairs${q}`).then((r) => live && setPairs(r));
    apiFetch(`/api/v1/intercompany/tp-policy${q}`).then((r) => live && setTp(r));
    apiFetch(`/api/v1/intercompany/hedges${q}`).then((r) => live && setHedges(r));
    apiFetch(`/api/v1/intercompany/true-ups${q}`).then((r) => live && setTrueups(r));
    return () => { live = false; };
  }, [walled, entity, period]);

  const reloadTp = () => apiFetch(`/api/v1/intercompany/tp-policy?entity=${encodeURIComponent(entity)}&period=${encodeURIComponent(period)}`).then(setTp);
  const reloadTrueups = () => apiFetch(`/api/v1/intercompany/true-ups?entity=${encodeURIComponent(entity)}&period=${encodeURIComponent(period)}`).then(setTrueups);

  const approveTp = (id: string) => {
    setBusy(id);
    apiFetch(`/api/v1/intercompany/tp-policy/${encodeURIComponent(id)}/approve`, { method: 'POST' })
      .then((r) => {
        if (r.status === 200) { notify('TP policy approved — new dated tier active', 'ok'); reloadTp(); }
        else if (r.status === 409) notify('Self-approval blocked (maker ≠ checker)', 'warn');
        else notify(`Approve failed (${r.status})`, 'err');
      })
      .finally(() => setBusy(null));
  };

  const approveTrueup = (id: string) => {
    setBusy(id);
    apiFetch(`/api/v1/intercompany/true-ups/${encodeURIComponent(id)}/approve`, { method: 'POST' })
      .then((r) => {
        if (r.status === 200) { notify('True-up posted — IC pair adjusted', 'ok'); reloadTrueups(); }
        else if (r.status === 409) notify('Self-approval blocked (maker ≠ checker)', 'warn');
        else notify(`Post failed (${r.status})`, 'err');
      })
      .finally(() => setBusy(null));
  };

  if (walled) {
    return (
      <div className="page" style={{ maxWidth: 1320 }}>
        <PageHead crumb="Inter-entity finance (doc 28 §5) · treasury / CFO cockpit" title="Intercompany" />
        <Card>
          <LayerNote>Intercompany is fully walled — this surface requires the <b>inter_entity</b> data layer. It is absent from your view, not zeroed.</LayerNote>
        </Card>
        {toastNode}
      </div>
    );
  }

  return (
    <div className="page" style={{ maxWidth: 1320 }}>
      <PageHead
        crumb="Inter-entity finance (doc 28 §5) · treasury / CFO cockpit"
        title="Intercompany"
        sub={<span style={{ display: 'block', maxWidth: 820 }}>Operating entity and principal in lockstep: flash-title at dispatch, ASC-830 remeasurement at spot, hedges offsetting per market, §482 true-ups. Every figure carries its rate provenance and drills to TigerBeetle.</span>}
      />

      <div className="banner info" style={{ marginBottom: 16 }}>
        {I.shield()}
        <div>This whole surface is <span className="bb">inter_entity</span>-walled — for a viewer without the layer it is <span className="bb">absent</span>, not a zeroed view. Multi-currency figures show the native amount, the rate, and its source.</div>
      </div>

      <div className="seg" style={{ marginBottom: 18 }} data-testid="ic-tabs">
        {TABS.map(([k, l]) => (
          <button key={k} className={tab === k ? 'on' : ''} data-testid={'ic-tab-' + k} onClick={() => setTab(k)}>{l}</button>
        ))}
      </div>

      {tab === 'pairs' && <PairsTable res={pairs} />}
      {tab === 'tp' && <TpTable res={tp} viewerName={viewerName} busy={busy} onApprove={approveTp} />}
      {tab === 'hedges' && <HedgeTable res={hedges} />}
      {tab === 'trueups' && <TrueupTable res={trueups} viewerName={viewerName} busy={busy} onApprove={approveTrueup} />}

      {toastNode}
    </div>
  );
}

function StateRows({ state, cols, label }: { state: ReturnType<typeof tableState>; cols: number; label: string }) {
  if (state === 'loading') return <>{Array.from({ length: 3 }).map((_, i) => <SkeletonRow key={i} cols={cols} />)}</>;
  if (state === 'forbidden') return <tr><td colSpan={cols} style={{ padding: '14px 16px' }}><LayerNote>hidden — requires <b>inter_entity</b></LayerNote></td></tr>;
  if (state === 'error') return <tr><td className="dim" colSpan={cols} style={{ padding: '18px 12px', textAlign: 'center' }}>Couldn't load {label} — retry by changing the entity / period context.</td></tr>;
  if (state === 'empty') return <tr><td className="dim" colSpan={cols} style={{ padding: '18px 12px', textAlign: 'center' }}>No {label} for this entity / period.</td></tr>;
  return null;
}

function PairsTable({ res }: { res: ApiResult | null }) {
  const rows = asArray<any>(res?.json);
  const state = tableState(res, rows);
  const ready = state === 'ready';
  return (
    <Card title="IC pair ledger" icon={I.layers} aux="per dispatch — operating/principal legs in lockstep, remeasure Δ, settlement" style={{ padding: 0 }} className="tablewrap">
      <table className="tbl" data-testid="ic-pairs-table">
        <thead><tr><th>Pair</th><th>Dispatch</th><th className="num">Principal leg</th><th className="num">Operating leg</th><th className="num">Markup</th><th className="num">Spot</th><th className="num">Remeasure Δ</th><th>Settle</th><th>TB</th></tr></thead>
        <tbody>
          {ready
            ? rows.map((p) => (
              <tr key={p.id} data-testid="ic-pair-row">
                <td><b style={{ fontSize: 12.5 }}>{p.pair}</b></td>
                <td className="mono dim" style={{ fontSize: 11 }}>{p.dispatch}</td>
                <td className="num">{gbp(p.principal_leg, p.native_ccy)}</td>
                <td className="num">{gbp(p.operating_leg, p.native_ccy)}</td>
                <td className="num"><b>{gbp(p.markup, p.native_ccy)}</b></td>
                <td className="num mono">{fx(p.spot)}</td>
                <td className="num">{signed(p.remeasure_delta, p.functional_ccy || 'GBP')}</td>
                <td><Chip s={p.settle === 'settled' ? 'ok' : 'warn'}>{p.settle}</Chip></td>
                <td>{p.tb ? <AuditRef id={p.tb} /> : <span className="dim">—</span>}</td>
              </tr>
            ))
            : <StateRows state={state} cols={9} label="IC pairs" />}
        </tbody>
      </table>
      {ready && <div className="layer-note" style={{ padding: '10px 16px' }}>{I.globe()}ASC-830: the native amount remeasures to each entity's functional currency at spot; the delta flows through earnings.</div>}
    </Card>
  );
}

function TpTable({ res, viewerName, busy, onApprove }: { res: ApiResult | null; viewerName: string; busy: string | null; onApprove: (id: string) => void }) {
  const rows = asArray<any>(res?.json);
  const state = tableState(res, rows);
  const ready = state === 'ready';
  return (
    <Card title="Transfer-pricing policy" icon={I.shield} aux="cost-plus tiers · propose → CFO approve (SoD) · validity windows" style={{ padding: 0 }} className="tablewrap">
      <table className="tbl" data-testid="ic-tp-table">
        <thead><tr><th>Policy</th><th>Pair</th><th>Basis</th><th className="num">Markup</th><th>Valid from</th><th>Status</th><th>Proposer</th><th>Approver</th><th /></tr></thead>
        <tbody>
          {ready
            ? rows.map((p) => {
              const isSelf = !!viewerName && p.proposed_by === viewerName;
              const pending = p.status === 'proposed' || p.status === 'draft';
              return (
                <tr key={p.id} data-testid="ic-tp-row">
                  <td><b className="mono" style={{ fontSize: 11.5 }}>{p.id?.slice ? p.id.slice(0, 8) : p.id}</b></td>
                  <td>{p.pair}</td>
                  <td className="dim">{p.basis}</td>
                  <td className="num"><b>{p.markup_pct != null ? Number(p.markup_pct).toFixed(2) + '%' : '—'}</b></td>
                  <td className="dim">{p.valid_from}</td>
                  <td><Chip s={p.status}>{p.status}</Chip></td>
                  <td className="dim">{p.proposed_by || '—'}</td>
                  <td className="dim">{p.approver || (pending ? 'pending' : '—')}</td>
                  <td>
                    {pending && (
                      <button
                        className="btn sm primary"
                        data-testid="ic-tp-approve"
                        disabled={busy === p.id || isSelf}
                        title={isSelf ? 'You proposed this — self-approval is blocked (maker ≠ checker)' : 'CFO approves a new dated tier'}
                        onClick={() => onApprove(p.id)}
                      >
                        Approve (CFO)
                      </button>
                    )}
                  </td>
                </tr>
              );
            })
            : <StateRows state={state} cols={9} label="TP policy tiers" />}
        </tbody>
      </table>
      {ready && <div className="layer-note" style={{ padding: '10px 16px' }}>{I.shield()}A markup change is a new dated row through maker-checker — the CFO approves; the proposer cannot self-activate.</div>}
    </Card>
  );
}

function HedgeTable({ res }: { res: ApiResult | null }) {
  const rows = asArray<any>(res?.json);
  const state = tableState(res, rows);
  const ready = state === 'ready';
  return (
    <Card title="Hedge book" icon={I.trend} aux="ASC-815 · per-market MTM (Reg S-K 305) · designation gates on a doc-ref" style={{ padding: 0 }} className="tablewrap">
      <table className="tbl" data-testid="ic-hedges-table">
        <thead><tr><th>Hedge</th><th>Pair</th><th>Market</th><th className="num">Contracted</th><th className="num">Notional</th><th className="num">Cumulative MTM</th><th>Designation</th></tr></thead>
        <tbody>
          {ready
            ? rows.map((h) => (
              <tr key={h.id} data-testid="ic-hedge-row">
                <td><b className="mono" style={{ fontSize: 11.5 }}>{h.id?.slice ? h.id.slice(0, 8) : h.id}</b></td>
                <td className="mono">{h.pair}</td>
                <td><span className="chip neutral"><span className="d" />{h.market}</span></td>
                <td className="num mono">{fx(h.contracted)}</td>
                <td className="num">{gbp(h.notional, h.native_ccy || 'USD')}</td>
                <td className="num"><b>{signed(h.mtm, h.functional_ccy || 'GBP')}</b></td>
                <td>
                  {h.designation === 'cash_flow' || h.designation === 'net_investment'
                    ? <span className="chip ok" title={'doc-ref ' + (h.docref || '—')}><span className="d" />{h.designation === 'cash_flow' ? 'cash-flow' : 'net-investment'} ✓</span>
                    : <span className="chip neutral"><span className="d" />economic</span>}
                </td>
              </tr>
            ))
            : <StateRows state={state} cols={7} label="hedges" />}
        </tbody>
      </table>
      {ready && <div className="layer-note" style={{ padding: '10px 16px' }}>{I.shield()}Cash-flow / net-investment designation requires a contemporaneous doc-ref — without it a hedge can only be <b>economic</b> (MTM through earnings).</div>}
    </Card>
  );
}

function TrueupTable({ res, viewerName, busy, onApprove }: { res: ApiResult | null; viewerName: string; busy: string | null; onApprove: (id: string) => void }) {
  const rows = asArray<any>(res?.json);
  const state = tableState(res, rows);
  const ready = state === 'ready';
  return (
    <Card title="§482 true-ups" icon={I.refresh} aux="period margin vs target → approve → post the IC pair" style={{ padding: 0 }} className="tablewrap">
      <table className="tbl" data-testid="ic-trueups-table">
        <thead><tr><th>True-up</th><th>Pair</th><th>Period</th><th className="num">Target</th><th className="num">Actual</th><th className="num">Adjustment</th><th>Status</th><th /></tr></thead>
        <tbody>
          {ready
            ? rows.map((tu) => {
              const isSelf = !!viewerName && tu.proposed_by === viewerName;
              const below = Number(tu.actual_margin) < Number(tu.target_margin);
              return (
                <tr key={tu.id} data-testid="ic-trueup-row">
                  <td><b className="mono" style={{ fontSize: 11.5 }}>{tu.id?.slice ? tu.id.slice(0, 8) : tu.id}</b></td>
                  <td>{tu.pair}</td>
                  <td className="dim">{tu.period}</td>
                  <td className="num">{tu.target_margin != null ? Number(tu.target_margin).toFixed(1) + '%' : '—'}</td>
                  <td className="num"><span style={{ color: below ? 'var(--warn)' : 'var(--ok)' }}>{tu.actual_margin != null ? Number(tu.actual_margin).toFixed(1) + '%' : '—'}</span></td>
                  <td className="num"><b>{gbp(tu.adjustment, tu.functional_ccy || 'GBP')}</b></td>
                  <td><Chip s={tu.status}>{tu.status}</Chip></td>
                  <td>
                    {tu.status === 'proposed' && (
                      <button
                        className="btn sm primary"
                        data-testid="ic-trueup-approve"
                        disabled={busy === tu.id || isSelf}
                        title={isSelf ? 'You proposed this — self-approval is blocked (maker ≠ checker)' : 'Approve & post the IC pair'}
                        onClick={() => onApprove(tu.id)}
                      >
                        Approve &amp; post
                      </button>
                    )}
                  </td>
                </tr>
              );
            })
            : <StateRows state={state} cols={8} label="true-ups" />}
        </tbody>
      </table>
    </Card>
  );
}
