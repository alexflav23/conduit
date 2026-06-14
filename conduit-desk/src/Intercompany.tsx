import React, { useState } from 'react';
import { useApi, request } from './lib/query';
import { ApiError } from './lib/client';
import { PageHead, Card, Chip, AuditRef, LayerNote, gbp, SkeletonRow, useToast } from './kit/kit';
import { I } from './kit/icons';

// Conduit Desk — Intercompany (spec/ui/23-intercompany.md, doc 13 §8): the treasury / CFO cockpit and the most
// layer-walled screen in the desk. The ENTIRE surface projects to the `inter_entity` layer — for a viewer
// without that layer the API returns 403 and each panel shows the wall, never a zeroed view.
// The real backend (IntercompanyRoutes) exposes TWO read surfaces here:
//   · IC pairs   GET /api/v1/intercompany/movements?status   -> intercompany_link rows (per-dispatch legs, FX, settle)
//   · TP policy  GET /api/v1/intercompany/policies?status     -> transfer_price_policy rows; approve via
//                POST /api/v1/intercompany/policies/{id}/approve (maker-checker, self-approval blocked server-side)
// Hedge book (ASC-815) and §482 true-ups have NO route in this environment yet — those tabs render an honest
// "Not available in this environment yet" panel rather than guessed calls or stuck skeletons.
// Entity filtering: the route's from/to_entity_id params are UUIDs, but ctx.entity is a human label with no
// resolver — so we omit them and let the server scope-filter by the principal's entity.

type Tab = 'pairs' | 'tp' | 'hedges' | 'trueups';
const TABS: [Tab, string][] = [['pairs', 'IC pairs'], ['tp', 'TP policy'], ['hedges', 'Hedge book'], ['trueups', '§482 true-ups']];

type Surface = 'loading' | 'forbidden' | 'error' | 'empty' | 'ready';
const surfaceOf = (loading: boolean, err: ApiError | null, empty: boolean): Surface =>
  loading ? 'loading' : err?.forbidden ? 'forbidden' : err ? 'error' : empty ? 'empty' : 'ready';

const fx = (v: number | string | null | undefined) => {
  const n = typeof v === 'string' ? parseFloat(v) : v;
  return n == null || Number.isNaN(n) ? '—' : Number(n).toFixed(4);
};
const signed = (v: number | string | null | undefined, ccy?: string) => {
  const n = typeof v === 'string' ? parseFloat(v) : v;
  if (n == null || Number.isNaN(n)) return <span className="dim">—</span>;
  if (n === 0) return <span className="dim">0.00</span>;
  return <span style={{ color: n < 0 ? 'var(--danger)' : 'var(--ok)' }}>{gbp(n, ccy)}</span>;
};
const short = (s: string | null | undefined) => (s && s.length > 8 ? s.slice(0, 8) : s || '—');

export function Intercompany({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const layers: string[] = role?.layers ?? [];
  const walled = layers.indexOf('inter_entity') < 0;

  const [tab, setTab] = useState<Tab>('pairs');
  const [toastNode, fire] = useToast();
  const notify = (m: string, k?: string) => { fire(m, (k as any) || 'ok'); toast(m, k); };

  if (walled) {
    return (
      <div className="page" style={{ maxWidth: 1320 }}>
        <PageHead crumb="Inter-entity finance (doc 13 §8) · treasury / CFO cockpit" title="Intercompany" />
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
        crumb="Inter-entity finance (doc 13 §8) · treasury / CFO cockpit"
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

      {tab === 'pairs' && <PairsTable />}
      {tab === 'tp' && <TpTable notify={notify} />}
      {tab === 'hedges' && <UnbackedPanel testid="ic-hedges-unbacked" line="The ASC-815 hedge book — per-market MTM, designation and the contemporaneous doc-ref gate — has no backend route in this environment yet." />}
      {tab === 'trueups' && <UnbackedPanel testid="ic-trueups-unbacked" line="The §482 period-margin true-up worklist has no backend route in this environment yet." />}

      {toastNode}
    </div>
  );
}

function UnbackedPanel({ testid, line }: { testid: string; line: string }) {
  return (
    <Card style={{ padding: 0 }}>
      <div style={{ padding: '40px 24px', textAlign: 'center' }} data-testid={testid}>
        <div style={{ display: 'grid', placeItems: 'center', gap: 10 }}>
          <span style={{ width: 44, height: 44, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'var(--panel-2)' }}>{I.shield()}</span>
          <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>Not available in this environment yet</div>
          <div className="dim" style={{ fontSize: 12.5, maxWidth: 520 }}>{line}</div>
        </div>
      </div>
    </Card>
  );
}

function StateRows({ state, cols, label }: { state: Surface; cols: number; label: string }) {
  if (state === 'loading') return <>{Array.from({ length: 3 }).map((_, i) => <SkeletonRow key={i} cols={cols} />)}</>;
  if (state === 'forbidden') return <tr><td colSpan={cols} style={{ padding: '14px 16px' }}><LayerNote>hidden — requires <b>inter_entity</b></LayerNote></td></tr>;
  if (state === 'error') return <tr><td className="dim" colSpan={cols} style={{ padding: '18px 12px', textAlign: 'center' }}>Couldn't load {label} — retry by changing the entity / period context.</td></tr>;
  if (state === 'empty') return <tr><td className="dim" colSpan={cols} style={{ padding: '18px 12px', textAlign: 'center' }}>No {label} for this entity / period.</td></tr>;
  return null;
}

interface MovementRow {
  id: string;
  from_entity_id?: string;
  to_entity_id?: string;
  status?: string;
  hop_seq?: number | null;
  transfer_price_total?: number | string | null;
  tp_currency?: string | null;
  fx_rate?: number | string | null;
  fx_basis?: string | null;
  import_tax_status?: string | null;
  accounting_period_key?: string | null;
  stock_transfer_id?: string | null;
}

function PairsTable() {
  const q = useApi<MovementRow[]>(['ic-movements'], '/api/v1/intercompany/movements');
  const err = q.error as ApiError | null;
  const rows = Array.isArray(q.data) ? q.data : [];
  const state = surfaceOf(q.isLoading, err, rows.length === 0);
  const ready = state === 'ready';
  return (
    <Card title="IC pair ledger" icon={I.layers} aux="per dispatch — operating/principal legs in lockstep, FX basis, settlement" style={{ padding: 0 }} className="tablewrap">
      <table className="tbl" data-testid="ic-pairs-table">
        <thead><tr><th>Pair</th><th className="num">Hop</th><th className="num">Transfer price</th><th className="num">FX rate</th><th>FX basis</th><th>Import tax</th><th>Period</th><th>Settle</th><th>TB</th></tr></thead>
        <tbody>
          {ready
            ? rows.map((p) => (
              <tr key={p.id} data-testid="ic-pair-row">
                <td><b className="mono" style={{ fontSize: 11.5 }}>{short(p.from_entity_id)} → {short(p.to_entity_id)}</b></td>
                <td className="num mono dim">{p.hop_seq ?? '—'}</td>
                <td className="num"><b>{gbp(p.transfer_price_total, p.tp_currency || 'GBP')}</b></td>
                <td className="num mono">{fx(p.fx_rate)}</td>
                <td className="dim" style={{ fontSize: 11.5 }}>{p.fx_basis || '—'}</td>
                <td>{p.import_tax_status ? <Chip s={p.import_tax_status === 'cleared' ? 'ok' : 'warn'}>{p.import_tax_status}</Chip> : <span className="dim">—</span>}</td>
                <td className="dim mono" style={{ fontSize: 11 }}>{p.accounting_period_key || '—'}</td>
                <td><Chip s={p.status === 'settled' ? 'ok' : 'warn'}>{p.status || '—'}</Chip></td>
                <td>{p.stock_transfer_id ? <AuditRef id={short(p.stock_transfer_id)} /> : <span className="dim">—</span>}</td>
              </tr>
            ))
            : <StateRows state={state} cols={9} label="IC pairs" />}
        </tbody>
      </table>
      {ready && <div className="layer-note" style={{ padding: '10px 16px' }}>{I.globe()}ASC-830: the native transfer price remeasures to each entity's functional currency at the FX rate on the stated basis; the delta flows through earnings.</div>}
    </Card>
  );
}

interface PolicyRow {
  id: string;
  from_entity_id?: string;
  to_entity_id?: string;
  method?: string;
  markup_pct?: number | string | null;
  resale_margin_pct?: number | string | null;
  fixed_price?: number | string | null;
  tp_currency?: string | null;
  status?: string;
  version?: number;
  documentation_method?: string | null;
}

function TpTable({ notify }: { notify: (m: string, k?: string) => void }) {
  const q = useApi<PolicyRow[]>(['ic-policies'], '/api/v1/intercompany/policies');
  const err = q.error as ApiError | null;
  const rows = Array.isArray(q.data) ? q.data : [];
  const state = surfaceOf(q.isLoading, err, rows.length === 0);
  const ready = state === 'ready';
  const [busy, setBusy] = useState<string | null>(null);

  const approve = (id: string) => {
    setBusy(id);
    request(`/api/v1/intercompany/policies/${encodeURIComponent(id)}/approve`, { method: 'POST' })
      .then(() => { notify('TP policy approved — new dated tier active', 'ok'); q.refetch(); })
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.status === 422) notify('Self-approval blocked (maker ≠ checker)', 'warn');
        else if (e instanceof ApiError && e.forbidden) notify('Not permitted — approve requires the inter_entity layer', 'warn');
        else notify(`Approve failed${e instanceof ApiError ? ` (${e.status})` : ''}`, 'err');
      })
      .finally(() => setBusy(null));
  };

  return (
    <Card title="Transfer-pricing policy" icon={I.shield} aux="cost-plus / resale tiers · propose → CFO approve (SoD) · dated versions" style={{ padding: 0 }} className="tablewrap">
      <table className="tbl" data-testid="ic-tp-table">
        <thead><tr><th>Policy</th><th>Pair</th><th>Method</th><th className="num">Markup</th><th className="num">Resale margin</th><th className="num">Ver</th><th>Status</th><th>Docs</th><th /></tr></thead>
        <tbody>
          {ready
            ? rows.map((p) => {
              const pending = p.status === 'proposed' || p.status === 'draft';
              return (
                <tr key={p.id} data-testid="ic-tp-row">
                  <td><b className="mono" style={{ fontSize: 11.5 }}>{short(p.id)}</b></td>
                  <td className="mono" style={{ fontSize: 11.5 }}>{short(p.from_entity_id)} → {short(p.to_entity_id)}</td>
                  <td className="dim">{p.method || '—'}</td>
                  <td className="num"><b>{p.markup_pct != null ? Number(p.markup_pct).toFixed(2) + '%' : '—'}</b></td>
                  <td className="num">{p.resale_margin_pct != null ? Number(p.resale_margin_pct).toFixed(2) + '%' : '—'}</td>
                  <td className="num mono dim">{p.version ?? '—'}</td>
                  <td><Chip s={p.status || 'neutral'}>{p.status || '—'}</Chip></td>
                  <td className="dim" style={{ fontSize: 11.5 }}>{p.documentation_method || '—'}</td>
                  <td>
                    {pending && (
                      <button
                        className="btn sm primary"
                        data-testid="ic-tp-approve"
                        disabled={busy === p.id}
                        title="CFO approves a new dated tier — the proposer cannot self-activate"
                        onClick={() => approve(p.id)}
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
      {ready && <div className="layer-note" style={{ padding: '10px 16px' }}>{I.shield()}A markup change is a new dated row through maker-checker — the CFO approves; the proposer cannot self-activate (server returns 422).</div>}
    </Card>
  );
}
