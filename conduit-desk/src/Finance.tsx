import React from 'react';
import { useApi } from './lib/query';
import { ApiError } from './lib/client';
import { marketId } from './api';
import {
  PageHead, Card, LayerNote, Money, SkeletonRow, Skeleton, EmptyRow,
} from './kit/kit';
import { I } from './kit/icons';

// Finance (spec/ui/07-finance.md): the CFO read-models — P&L by market/period and the cash waterfall. The hero
// teaching moment is the LAYER COLLAPSE: revenue is `commercial`, margin/COGS is `profitability` — a viewer
// without profitability sees revenue but the margin is honestly ABSENT (a LayerNote), never £0.
//
// Backing routes (CreditRoutes.scala):
//   GET /api/v1/finance/pnl?market={uuid}&period={YYYY-MM}  -> { revenue_ex_vat, vat, cogs, gross_margin } (strings)
//   GET /api/v1/finance/cash-waterfall?currency=GBP          -> [{ due_month, currency, expected_cash, invoices }]
// Both gate server-side on view:credit_profile (403 -> forbidden) and key on ctx.market / ctx.period so a
// context switch refetches. There is NO list-credit-customers endpoint (only per-party GET/PUT credit-terms),
// so the credit-control editor renders the honest "not available yet" panel rather than a guessed call.

type Ctx = { entity: string; market: string; period: string; scenario: string };
type Role = { token?: string; name?: string; title?: string; layers?: string[] };

interface Pnl {
  revenue_ex_vat: string;
  vat: string;
  cogs: string;
  gross_margin: string;
}
interface WaterfallRow {
  due_month: string;
  currency: string;
  expected_cash: string | number;
  invoices: number;
}

const gbpn = (v: number | string | null | undefined): string | null =>
  v == null ? null : `£${Number(v).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

export function Finance({ role, ctx }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const r = (role || {}) as Role;
  const c = (ctx || {}) as Ctx;
  const layers = r.layers || [];
  const hasCommercial = layers.indexOf('commercial') >= 0;
  const hasProfit = layers.indexOf('profitability') >= 0;
  const market = marketId(c.market || 'UK');
  const period = c.period || '2026-09';

  const pnlQ = useApi<Pnl>(
    ['finance-pnl', market, period],
    `/api/v1/finance/pnl?market=${encodeURIComponent(market)}&period=${encodeURIComponent(period)}`,
    { enabled: hasCommercial && !!market },
  );

  const wfQ = useApi<WaterfallRow[]>(
    ['finance-cash-waterfall', 'GBP'],
    '/api/v1/finance/cash-waterfall?currency=GBP',
    { enabled: hasCommercial },
  );

  // The whole screen sits behind the commercial layer (revenue is commercial). No commercial -> collapse.
  if (!hasCommercial) {
    return (
      <div className="page" style={{ maxWidth: 1180 }}>
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
      <PageHead crumb={'Finance · ' + period + ' · ASC-606'} title="Finance"
        sub="Recognised on dispatch, proved on the immutable ledger. Every figure ties to the penny."
        right={<span className="stale"><span className="pulse" />market {market.slice(0, 8)}</span>} />

      <div className="grid" style={{ gridTemplateColumns: '1.6fr 1fr', alignItems: 'start' }}>
        <PnlCard q={pnlQ} period={period} hasProfit={hasProfit} role={r} />
        <MarginCard q={pnlQ} hasProfit={hasProfit} role={r} />
      </div>

      <CashWaterfall q={wfQ} />

      <CreditTerms />
    </div>
  );
}

// ---------------- P&L ----------------
function PnlCard({ q, period, hasProfit, role }: {
  q: ReturnType<typeof useApi<Pnl>>; period: string; hasProfit: boolean; role: any;
}) {
  const err = q.error as ApiError | null;
  const forbidden = !!err?.forbidden;
  const notImplemented = !!err?.notImplemented;
  const otherError = !!err && !forbidden && !notImplemented;
  const pnl = q.data ?? null;
  const empty = !q.isLoading && !err && !pnl;

  return (
    <Card title={'Profit & loss · ' + period} icon={I.sessions} className="tablewrap" style={{ padding: 0 }}>
      <table className="tbl">
        <thead><tr><th>Line</th><th className="num">Layer</th><th className="num">{period}</th></tr></thead>
        <tbody>
          {q.isLoading && <><SkeletonRow cols={3} /><SkeletonRow cols={3} /><SkeletonRow cols={3} /></>}
          {forbidden && <tr><td colSpan={3} style={{ padding: 0 }}><LayerNote>P&L hidden — requires view:credit_profile.</LayerNote></td></tr>}
          {notImplemented && <tr><td colSpan={3} style={{ padding: 0 }}><LayerNote>P&L is not available in this environment yet.</LayerNote></td></tr>}
          {otherError && <EmptyRow cols={3}>Could not load the P&L (HTTP {err?.status}) — try again shortly.</EmptyRow>}
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
                      <Money value={pnl.gross_margin ?? (Number(pnl.revenue_ex_vat) - Number(pnl.cogs))} role={role} layer="profitability" />
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
function MarginCard({ q, hasProfit, role }: {
  q: ReturnType<typeof useApi<Pnl>>; hasProfit: boolean; role: any;
}) {
  const pnl = q.data ?? null;

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

  if (q.isLoading) {
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
function CashWaterfall({ q }: { q: ReturnType<typeof useApi<WaterfallRow[]>> }) {
  const err = q.error as ApiError | null;
  const forbidden = !!err?.forbidden;
  const notImplemented = !!err?.notImplemented;
  const otherError = !!err && !forbidden && !notImplemented;
  const rows: WaterfallRow[] = Array.isArray(q.data) ? q.data : [];
  const ready = !q.isLoading && !err;
  const total = rows.reduce((a, x) => a + (Number(x.expected_cash) || 0), 0);
  const invoices = rows.reduce((a, x) => a + (Number(x.invoices) || 0), 0);
  const max = Math.max(1, ...rows.map((x) => Number(x.expected_cash) || 0));

  return (
    <Card title="Cash waterfall · expected collections (GBP)" icon={I.trend} className="tablewrap" style={{ padding: 0, marginTop: 14 }}
      aux={<span className="dim" style={{ fontSize: 11.5 }}>open invoices bucketed by each contact's contractual due date</span>}>
      <table className="tbl">
        <thead><tr><th>Due month</th><th>Currency</th><th style={{ width: '38%' }}>Expected cash</th><th className="num">Amount</th><th className="num">Invoices</th></tr></thead>
        <tbody>
          {q.isLoading && <><SkeletonRow cols={5} /><SkeletonRow cols={5} /><SkeletonRow cols={5} /></>}
          {forbidden && <tr><td colSpan={5} style={{ padding: 0 }}><LayerNote>Cash waterfall hidden — requires view:credit_profile.</LayerNote></td></tr>}
          {notImplemented && <tr><td colSpan={5} style={{ padding: 0 }}><LayerNote>The cash waterfall is not available in this environment yet.</LayerNote></td></tr>}
          {otherError && <EmptyRow cols={5}>Could not load the cash waterfall (HTTP {err?.status}) — try again shortly.</EmptyRow>}
          {ready && rows.length === 0 && <EmptyRow cols={5}>No open invoices — nothing scheduled to collect.</EmptyRow>}
          {ready && rows.map((x, i) => (
            <tr key={i} data-testid="fin-wf-row" style={{ cursor: 'default' }}>
              <td><b>{x.due_month}</b></td>
              <td className="dim">{x.currency}</td>
              <td><div className="covbar"><div className="track" style={{ height: 7 }}><i style={{ width: ((Number(x.expected_cash) || 0) / max) * 100 + '%', background: 'var(--brand-grad)' }} /></div></div></td>
              <td className="num"><b>{gbpn(x.expected_cash)}</b></td>
              <td className="num">{x.invoices}</td>
            </tr>
          ))}
        </tbody>
        {ready && rows.length > 0 && (
          <tfoot><tr><td><b>Total</b></td><td /><td /><td className="num"><b>{gbpn(total)}</b></td><td className="num"><b>{invoices}</b></td></tr></tfoot>
        )}
      </table>
    </Card>
  );
}

// ---------------- Credit terms editor ----------------
// No list-credit-customers route exists in this environment — the backend exposes only per-party
// GET/PUT /api/v1/parties/{id}/credit-terms, not a market roll-up. Render the honest "not available" panel
// rather than a guessed call; the editor lights up once a credit-customer roster endpoint is wired.
function CreditTerms() {
  return (
    <Card title="Credit control" icon={I.user} style={{ marginTop: 14 }}
      aux={<span className="dim" style={{ fontSize: 11.5 }}>terms &amp; limits per trade customer — drives due dates and the waterfall</span>}>
      <div style={{ display: 'grid', placeItems: 'center', gap: 10, padding: '34px 28px', textAlign: 'center' }} data-testid="fin-terms-unbacked">
        <span style={{ width: 44, height: 44, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'var(--panel-2)' }}>{I.user({ size: 22 })}</span>
        <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>Not available in this environment yet</div>
        <div className="dim" style={{ fontSize: 12.5, maxWidth: 480, lineHeight: 1.5 }}>
          Per-customer credit terms are editable on each party's profile, but the market-wide credit-control roster
          (terms, limits and outstanding by customer) is not yet exposed as a finance read-model here.
        </div>
      </div>
    </Card>
  );
}
