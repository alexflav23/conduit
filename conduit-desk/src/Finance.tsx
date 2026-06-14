import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from './api';
import {
  PageHead, Card, LayerNote, Coverage, Money, SkeletonRow, Skeleton, EmptyRow, AuditRef, useToast,
} from './kit/kit';
import { I } from './kit/icons';
import { asArray } from './state';

// Finance (spec/ui/07-finance.md): the CFO read-models — P&L by market/period, the cash waterfall, and the
// per-party credit-terms editor. The hero teaching moment is the LAYER COLLAPSE: revenue is `commercial`,
// margin/COGS is `profitability` — a viewer without profitability sees revenue but the margin is honestly
// ABSENT (a LayerNote), never £0. Credit-terms edits are real money mutations (confirm + audit affordance).
// Auto-loads on mount + when ctx.market / ctx.period change — no manual Load/Refresh buttons.

type Ctx = { entity: string; market: string; period: string; scenario: string };
type Role = { token?: string; name?: string; title?: string; layers?: string[] };

const FINANCE_MARKET = '22222222-2222-2222-2222-222222222222';

const gbpn = (v: any) =>
  v == null ? null : `£${Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

export function Finance({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const r = (role || {}) as Role;
  const c = (ctx || {}) as Ctx;
  const layers = r.layers || [];
  const hasCommercial = layers.indexOf('commercial') >= 0;
  const hasProfit = layers.indexOf('profitability') >= 0;
  const market = c.market || FINANCE_MARKET;
  const period = c.period || '2026-09';

  const [toastNode, fire] = useToast();
  const fireToast = useCallback((m: string, k?: string) => { fire(m, (k as any) || 'ok'); toast(m, k); }, [fire, toast]);

  // ---- P&L ----
  const [pnlRes, setPnlRes] = useState<{ status: number; json: any } | null>(null);
  // ---- cash waterfall ----
  const [wfRes, setWfRes] = useState<{ status: number; json: any } | null>(null);
  // ---- credit terms ----
  const [termsRes, setTermsRes] = useState<{ status: number; json: any } | null>(null);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [confirming, setConfirming] = useState<any | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    setPnlRes(null);
    apiFetch(`/api/v1/finance/pnl?market=${encodeURIComponent(market)}&period=${encodeURIComponent(period)}`).then(setPnlRes);
  }, [market, period]);

  useEffect(() => {
    setWfRes(null);
    apiFetch(`/api/v1/finance/cash-waterfall?currency=GBP`).then(setWfRes);
  }, [market, period]);

  const loadTerms = useCallback(() => {
    setTermsRes(null);
    apiFetch(`/api/v1/finance/credit-terms?market=${encodeURIComponent(market)}`).then((res) => {
      setTermsRes(res);
      const seed: Record<string, string> = {};
      asArray<any>(res.json).forEach((p) => { seed[p.party_id || p.id] = String(p.payment_terms_days ?? ''); });
      setDrafts(seed);
    });
  }, [market]);

  useEffect(loadTerms, [loadTerms]);

  const saveTerms = (p: any) => {
    const id = p.party_id || p.id;
    const days = parseInt(drafts[id], 10);
    if (!Number.isFinite(days)) { fireToast('Enter a valid number of days', 'warn'); return; }
    setSaving(true);
    apiFetch(`/api/v1/parties/${encodeURIComponent(id)}/credit-terms`, {
      method: 'PUT',
      body: JSON.stringify({ payment_terms_days: days, credit_limit: p.credit_limit }),
    }).then((res) => {
      setSaving(false);
      setConfirming(null);
      if (res.status === 200 || res.status === 202) {
        fireToast(`${days}-day terms saved for ${p.name || id} — audited, re-dates open invoices`);
        loadTerms();
      } else if (res.status === 403) {
        fireToast('Forbidden — credit terms require the commercial layer', 'err');
      } else {
        fireToast(`Save failed (${res.status})`, 'err');
      }
    });
  };

  // The whole screen sits behind the commercial layer (revenue is commercial). No commercial -> collapse.
  if (!hasCommercial) {
    return (
      <div className="page" style={{ maxWidth: 1180 }}>
        {toastNode}
        <PageHead crumb={'Finance · ' + period} title="Finance"
          sub="P&L, cash and credit — read-models recognised on dispatch and proved on the ledger." />
        <Card title="Financial projections" icon={I.shield}>
          <div style={{ display: 'grid', placeItems: 'center', padding: '48px 20px', textAlign: 'center' }}>
            {I.shield({ size: 24 })}
            <div style={{ fontWeight: 600, marginTop: 10, fontSize: 14 }}>Restricted to the commercial layer</div>
            <div className="dim" style={{ marginTop: 6, maxWidth: 420, lineHeight: 1.5, fontSize: 12.5 }}>
              Your role ({r.title || r.name || 'viewer'}) does not carry the commercial layer. The server projection
              contains no monetary fields for finance — there is nothing to render.
            </div>
          </div>
          <LayerNote>Finance read-models are hidden — requires the commercial layer.</LayerNote>
        </Card>
      </div>
    );
  }

  return (
    <div className="page" style={{ maxWidth: 1280 }}>
      {toastNode}
      <PageHead crumb={'Finance · ' + period + ' · ASC-606'} title="Finance"
        sub="Recognised on dispatch, proved on the immutable ledger. Every figure ties to the penny."
        right={<span className="stale"><span className="pulse" />market {market.slice(0, 8)}</span>} />

      <div className="grid" style={{ gridTemplateColumns: '1.6fr 1fr', alignItems: 'start' }}>
        <PnlCard res={pnlRes} period={period} hasProfit={hasProfit} role={r} />
        <MarginCard res={pnlRes} hasProfit={hasProfit} role={r} />
      </div>

      <CashWaterfall res={wfRes} />

      <CreditTerms
        res={termsRes} drafts={drafts} setDrafts={setDrafts}
        onSave={(p) => setConfirming(p)} role={r}
      />

      {confirming && (
        <ConfirmTerms
          party={confirming} days={drafts[confirming.party_id || confirming.id]}
          saving={saving} onCancel={() => setConfirming(null)} onConfirm={() => saveTerms(confirming)}
        />
      )}
    </div>
  );
}

// ---------------- P&L ----------------
function PnlCard({ res, period, hasProfit, role }: { res: any; period: string; hasProfit: boolean; role: any }) {
  const loading = res === null;
  const forbidden = res && (res.status === 401 || res.status === 403);
  const error = res && res.status >= 400 && !forbidden;
  const pnl = res && res.status < 400 ? res.json : null;
  const empty = res && res.status < 400 && !pnl;

  return (
    <Card title={'Profit & loss · ' + period} icon={I.sessions} className="tablewrap" style={{ padding: 0 }}>
      <table className="tbl">
        <thead><tr><th>Line</th><th className="num">Layer</th><th className="num">{period}</th></tr></thead>
        <tbody>
          {loading && <><SkeletonRow cols={3} /><SkeletonRow cols={3} /><SkeletonRow cols={3} /></>}
          {forbidden && <tr><td colSpan={3} style={{ padding: 0 }}><LayerNote>P&L hidden — requires the commercial layer.</LayerNote></td></tr>}
          {error && <EmptyRow cols={3}>Could not load the P&L — try again shortly.</EmptyRow>}
          {empty && <EmptyRow cols={3}>No recognised revenue in {period} yet.</EmptyRow>}
          {pnl && (
            <>
              <tr style={{ cursor: 'default' }}>
                <td><b>Revenue ex-VAT</b></td>
                <td className="num"><span className="chip neutral"><span className="d" />commercial</span></td>
                <td className="num"><b><Money value={pnl.revenue_ex_vat} role={role} layer="commercial" /></b></td>
              </tr>
              <tr style={{ cursor: 'default' }}>
                <td className="dim">VAT collected (output)</td>
                <td className="num"><span className="chip neutral"><span className="d" />commercial</span></td>
                <td className="num"><Money value={pnl.vat} role={role} layer="commercial" /></td>
              </tr>
              {hasProfit ? (
                <>
                  <tr style={{ cursor: 'default' }}>
                    <td>less Cost of goods sold</td>
                    <td className="num"><span className="chip accent"><span className="d" />profitability</span></td>
                    <td className="num"><Money value={pnl.cogs} role={role} layer="profitability" /></td>
                  </tr>
                  <tr style={{ cursor: 'default', background: 'var(--accent-subtle)' }}>
                    <td><b>Gross margin</b></td>
                    <td className="num"><span className="chip accent"><span className="d" />profitability</span></td>
                    <td className="num"><b style={{ color: 'var(--accent-bright)' }}>
                      <Money value={pnl.gross_margin ?? (pnl.revenue_ex_vat - pnl.cogs)} role={role} layer="profitability" />
                    </b></td>
                  </tr>
                </>
              ) : (
                <tr style={{ cursor: 'default' }}>
                  <td className="dim">Cost of goods sold · Gross margin</td>
                  <td className="num"><span className="chip accent"><span className="d" />profitability</span></td>
                  <td className="num dim" style={{ fontStyle: 'italic' }}>hidden</td>
                </tr>
              )}
            </>
          )}
        </tbody>
      </table>
      {pnl && !hasProfit && <LayerNote>Cost of goods sold and gross margin sit behind the profitability layer — absent for your role, never shown as £0.</LayerNote>}
    </Card>
  );
}

// ---------------- Margin composition ----------------
function MarginCard({ res, hasProfit, role }: { res: any; hasProfit: boolean; role: any }) {
  const pnl = res && res.status < 400 ? res.json : null;

  if (!hasProfit) {
    return (
      <Card title="Margin composition" icon={I.trend}>
        <div className="metric"><div className="ml">Revenue ex-VAT</div>
          <div className="mv"><Money value={pnl?.revenue_ex_vat} role={role} layer="commercial" /></div>
        </div>
        <div className="divider" />
        <LayerNote>Margin composition needs the profitability layer — there is no cost or margin figure in your projection.</LayerNote>
      </Card>
    );
  }

  if (res === null) {
    return <Card title="Margin composition" icon={I.trend}><Skeleton lines={4} /></Card>;
  }
  if (!pnl) {
    return <Card title="Margin composition" icon={I.trend}><div className="dim" style={{ padding: '10px 2px' }}>No margin to compose yet.</div></Card>;
  }

  const rev = Number(pnl.revenue_ex_vat) || 0;
  const cogs = Number(pnl.cogs) || 0;
  const gm = pnl.gross_margin != null ? Number(pnl.gross_margin) : rev - cogs;
  const marginPct = rev ? (gm / rev) * 100 : 0;

  return (
    <Card title="Margin composition" icon={I.trend}>
      <div style={{ textAlign: 'center', padding: '6px 0 14px' }}>
        <div className="accent" style={{ fontFamily: 'var(--font-disp)', fontSize: 44, fontWeight: 600, letterSpacing: '-0.02em' }}>
          {marginPct.toFixed(1)}%
        </div>
        <div className="dim" style={{ fontSize: 12 }}>gross margin on {gbpn(rev)} revenue</div>
      </div>
      <div style={{ display: 'flex', height: 14, borderRadius: 8, overflow: 'hidden', marginBottom: 12 }}>
        <div style={{ width: (rev ? (cogs / rev) * 100 : 0) + '%', background: 'var(--surface3)' }} title="COGS" />
        <div style={{ width: (rev ? (gm / rev) * 100 : 0) + '%', background: 'var(--brand-grad)' }} title="Gross margin" />
      </div>
      <div className="kv">
        <span className="k">COGS</span><span className="v num">{gbpn(cogs)}</span>
        <span className="k">Gross margin</span><span className="v num">{gbpn(gm)}</span>
      </div>
      <div className="divider" />
      <div className="dim" style={{ fontSize: 11.5, lineHeight: 1.5 }}>
        Recognised strictly on dispatch (ASC-606). Margin uses specific-identification batch cost — no averaging.
      </div>
    </Card>
  );
}

// ---------------- Cash waterfall ----------------
function CashWaterfall({ res }: { res: any }) {
  const loading = res === null;
  const forbidden = res && (res.status === 401 || res.status === 403);
  const error = res && res.status >= 400 && !forbidden;
  const rows = asArray<any>(res && res.status < 400 ? res.json : []);
  const total = rows.reduce((a, x) => a + (Number(x.expected_cash) || 0), 0);
  const invoices = rows.reduce((a, x) => a + (Number(x.invoices) || 0), 0);
  const max = Math.max(1, ...rows.map((x) => Number(x.expected_cash) || 0));

  return (
    <Card title="Cash waterfall · expected collections (GBP)" icon={I.trend} className="tablewrap" style={{ padding: 0, marginTop: 14 }}
      aux={<span className="dim" style={{ fontSize: 11.5 }}>open invoices bucketed by each contact's contractual due date</span>}>
      <table className="tbl">
        <thead><tr><th>Due month</th><th>Currency</th><th style={{ width: '38%' }}>Expected cash</th><th className="num">Amount</th><th className="num">Invoices</th></tr></thead>
        <tbody>
          {loading && <><SkeletonRow cols={5} /><SkeletonRow cols={5} /><SkeletonRow cols={5} /></>}
          {forbidden && <tr><td colSpan={5} style={{ padding: 0 }}><LayerNote>Cash waterfall hidden — requires the commercial layer.</LayerNote></td></tr>}
          {error && <EmptyRow cols={5}>Could not load the cash waterfall — try again shortly.</EmptyRow>}
          {res && !forbidden && !error && rows.length === 0 && <EmptyRow cols={5}>No open invoices — nothing scheduled to collect.</EmptyRow>}
          {rows.map((x, i) => (
            <tr key={i} data-testid="fin-wf-row" style={{ cursor: 'default' }}>
              <td><b>{x.due_month}</b></td>
              <td className="dim">{x.currency}</td>
              <td><div className="covbar"><div className="track" style={{ height: 7 }}><i style={{ width: ((Number(x.expected_cash) || 0) / max) * 100 + '%', background: 'var(--brand-grad)' }} /></div></div></td>
              <td className="num"><b>{gbpn(x.expected_cash)}</b></td>
              <td className="num">{x.invoices}</td>
            </tr>
          ))}
        </tbody>
        {rows.length > 0 && (
          <tfoot><tr><td><b>Total</b></td><td /><td /><td className="num"><b>{gbpn(total)}</b></td><td className="num"><b>{invoices}</b></td></tr></tfoot>
        )}
      </table>
    </Card>
  );
}

// ---------------- Credit terms editor (maker mutation) ----------------
function CreditTerms({ res, drafts, setDrafts, onSave, role }: {
  res: any; drafts: Record<string, string>; setDrafts: (d: Record<string, string>) => void;
  onSave: (p: any) => void; role: any;
}) {
  const loading = res === null;
  const forbidden = res && (res.status === 401 || res.status === 403);
  const error = res && res.status >= 400 && !forbidden;
  const rows = asArray<any>(res && res.status < 400 ? res.json : []);

  return (
    <Card title="Credit control" icon={I.user} className="tablewrap" style={{ padding: 0, marginTop: 14 }}
      aux={<span className="dim" style={{ fontSize: 11.5 }}>terms &amp; limits per trade customer — drives due dates and the waterfall</span>}>
      <table className="tbl" data-testid="fin-terms">
        <thead><tr><th>Customer</th><th>Type</th><th className="num">Terms (days)</th><th className="num">Credit limit</th><th className="num">Outstanding</th><th style={{ width: 150 }}>Utilisation</th><th /></tr></thead>
        <tbody>
          {loading && <><SkeletonRow cols={7} /><SkeletonRow cols={7} /></>}
          {forbidden && <tr><td colSpan={7} style={{ padding: 0 }}><LayerNote>Credit terms hidden — requires the commercial layer.</LayerNote></td></tr>}
          {error && <EmptyRow cols={7}>Could not load credit terms — try again shortly.</EmptyRow>}
          {res && !forbidden && !error && rows.length === 0 && <EmptyRow cols={7}>No credit customers in this market.</EmptyRow>}
          {rows.map((p) => {
            const id = p.party_id || p.id;
            const limit = Number(p.credit_limit) || 0;
            const outstanding = Number(p.outstanding) || 0;
            const util = limit ? (outstanding / limit) * 100 : 0;
            return (
              <tr key={id} data-testid="fin-terms-row" style={{ cursor: 'default' }}>
                <td><b>{p.name || id}</b></td>
                <td className="dim">{p.party_type || p.type || '—'}</td>
                <td className="num">
                  <input className="cellinput" style={{ width: 64 }} data-testid="fin-terms-days" value={drafts[id] ?? ''}
                    onChange={(e) => setDrafts({ ...drafts, [id]: e.target.value })} />
                </td>
                <td className="num"><Money value={p.credit_limit} role={role} layer="commercial" /></td>
                <td className="num"><Money value={p.outstanding} role={role} layer="commercial" /></td>
                <td><Coverage pct={util} /></td>
                <td><button className="btn sm" data-testid="fin-save-terms" onClick={() => onSave(p)}>{I.check({ size: 12 })}Save</button></td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {rows.length > 0 && <div className="layer-note" style={{ padding: '10px 16px' }}>{I.shield()}Changing terms is a money mutation — audited, and re-dates open invoices in the next reconciliation run.</div>}
    </Card>
  );
}

// ---------------- Confirm dialog (real money mutation) ----------------
function ConfirmTerms({ party, days, saving, onCancel, onConfirm }: {
  party: any; days: string; saving: boolean; onCancel: () => void; onConfirm: () => void;
}) {
  return (
    <>
      <div className="scrim open" onClick={onCancel} />
      <div className="drawer open" style={{ width: 440 }}>
        <div className="dh">
          <div style={{ flex: 1, minWidth: 0 }}>
            <span className="chip warn"><span className="d" />confirm</span>
            <div style={{ fontFamily: 'var(--font-disp)', fontSize: 19, fontWeight: 600, marginTop: 7 }}>Change credit terms</div>
            <div className="dim" style={{ fontSize: 12.5, marginTop: 3 }}>{party.name || party.party_id || party.id}</div>
          </div>
          <div className="ibtn" onClick={onCancel}>{I.x()}</div>
        </div>
        <div className="db">
          <div className="kv" style={{ marginBottom: 14 }}>
            <span className="k">New payment terms</span><span className="v num">{days} days</span>
            <span className="k">Credit limit</span><span className="v num">{gbpn(party.credit_limit) || '—'}</span>
          </div>
          <div className="banner info" style={{ marginBottom: 4 }}>
            {I.shield()}This is an audited money mutation. Open invoices for this customer are re-dated in the next reconciliation run.
          </div>
          <div className="row g8" style={{ marginTop: 10 }}>
            <span className="dim" style={{ fontSize: 11.5 }}>posts to the audit log as</span>
            <AuditRef id={`credit_terms_changed`} />
          </div>
        </div>
        <div className="df">
          <button className="btn ghost" onClick={onCancel}>Cancel</button>
          <button className="btn primary" data-testid="fin-terms-confirm" disabled={saving} onClick={onConfirm}>
            {I.check({ size: 13 })}{saving ? 'Saving…' : 'Confirm change'}
          </button>
        </div>
      </div>
    </>
  );
}
