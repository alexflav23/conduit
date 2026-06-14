import { useEffect, useState } from 'react';
import { Card, Chip, EmptyRow, LayerNote, Money, SkeletonRow, useToast } from './kit/kit';
import { I } from './kit/icons';
import { apiFetch } from './api';
import { asArray, tableState } from './state';

// Reseller portal (spec/ui/25) — the externally-facing, SCOPED, rate-limited surface (doc 19 §A.1).
// A reseller signs in with a scoped JWT and sees ONLY their own catalogue-for-me pricing (commercial layer
// only — profitability/inter_entity are ABSENT, the wall does the work), places tier-governed orders
// (nobody types a price; non-tier → 422) and tracks their own orders/invoices. Calls are rate-limited to
// their tier (429 + Retry-After → a graceful "slow down", never a hard error). A distinct, calmer shell so
// an external user never feels they are inside a back-office tool.

interface ResellerInfo {
  name: string;
  tier: string;
}
interface CatalogueItem {
  sku: string;
  label: string;
  price: number | string | null;
  floor: number | string | null;
  currency?: string;
}
interface OrderRow {
  order: string;
  date: string;
  units: number;
  total: number | string | null;
  status: string;
  invoice?: string | null;
  currency?: string;
}
interface Portal {
  reseller: ResellerInfo;
  catalogue: CatalogueItem[];
  orders: OrderRow[];
}

const PORTAL = '/api/v1/reseller/portal';

export function Reseller({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const [res, setRes] = useState<{ status: number; json: any } | null>(null);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [throttled, setThrottled] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [toastNode] = useToast();

  // Auto-load on mount and whenever the scope context changes — no manual load/refresh button.
  useEffect(() => {
    let live = true;
    setRes(null);
    apiFetch(PORTAL).then((r) => live && setRes(r));
    return () => {
      live = false;
    };
  }, [ctx.entity, ctx.market, ctx.period]);

  const data: Portal | null = res && res.status < 400 ? (res.json as Portal) : null;
  const catalogue = asArray<CatalogueItem>(data?.catalogue);
  const orders = asArray<OrderRow>(data?.orders);
  const r = data?.reseller;

  const headState = tableState(res, res && res.status < 400 ? [r] : null);
  const forbidden = res !== null && (res.status === 401 || res.status === 403);
  const errored = res !== null && res.status >= 400 && res.status !== 401 && res.status !== 403;

  const slow = (retry: number) => {
    setThrottled(true);
    toast('Slow down — tier rate limit reached, retry in ' + retry + 's', 'warn');
    setTimeout(() => setThrottled(false), Math.min(retry, 5) * 1000 || 2500);
  };

  const tryQuote = async (sku: string) => {
    if (throttled) return;
    setBusy(sku);
    const q = await apiFetch('/api/v1/reseller/quote', {
      method: 'POST',
      body: JSON.stringify({ sku, unitPriceExVat: draft[sku] || null }),
    });
    setBusy(null);
    if (q.status === 429) {
      slow(Number(q.json?.retry_after ?? q.json?.retryAfter ?? 2));
    } else if (q.status === 422) {
      const near = q.json?.nearest ?? q.json?.nearestTier;
      toast('Below your tier floor — nearest allowed ' + fmt(near, q.json?.currency), 'warn');
    } else if (q.status >= 400) {
      toast('Quote failed', 'err');
    } else {
      toast('Quote accepted — ' + fmt(q.json?.price, q.json?.currency), 'ok');
    }
  };

  return (
    <div className="page" style={{ maxWidth: 1080 }}>
      {toastNode}

      {/* Distinct, calmer branded header — the external "partner portal" feel, not the internal desk. */}
      <div
        className="row between"
        style={{
          padding: '18px 22px',
          borderRadius: 16,
          background: 'linear-gradient(135deg, var(--surface) 0%, var(--bg-2) 100%)',
          border: '1px solid var(--border)',
          marginBottom: 18,
        }}
      >
        <div className="row g12" style={{ alignItems: 'center' }}>
          <span
            style={{ width: 40, height: 40, borderRadius: 11, display: 'grid', placeItems: 'center', background: 'var(--brand-grad)', color: '#fff' }}
          >
            <I.bolt size={20} />
          </span>
          <div>
            <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>Hypervolt Partner Portal</div>
            <div className="dim" style={{ fontSize: 12 }}>
              {r ? (
                <>
                  Signed in as <b style={{ color: 'var(--text)' }}>{r.name}</b> · {r.tier} tier · scoped session
                </>
              ) : (
                'scoped reseller session'
              )}
            </div>
          </div>
        </div>
        {r ? (
          <span className="chip ok">
            <span className="d" />
            scoped JWT · {r.tier}
          </span>
        ) : null}
      </div>

      <div className="banner info" style={{ marginBottom: 18 }}>
        <I.shield />
        <div>
          You see <span className="bb">only your own</span> catalogue pricing, orders and invoices. Internal cost, margin and other resellers
          are <span className="bb">absent</span> from your session — not hidden behind a zero. Calls are rate-limited to your tier.
        </div>
      </div>

      {throttled ? (
        <div className="banner warn" style={{ marginBottom: 18 }}>
          <I.clock />
          <div>
            <span className="bb">Slow down.</span> You've hit your tier's request rate. We'll resume in a moment — your session is safe, this is a
            soft throttle to keep the platform fair.
          </div>
        </div>
      ) : null}

      {forbidden ? (
        <Card title="Catalogue — your pricing" icon={I.list} style={{ marginBottom: 18 }}>
          <LayerNote>Your session can't reach the partner portal — requires a scoped reseller grant (commercial).</LayerNote>
        </Card>
      ) : (
        <Card
          title="Catalogue — your pricing"
          icon={I.list}
          aux="your contracted tier prices · no internal cost or margin, ever"
          style={{ padding: 0, marginBottom: 18 }}
          className="tablewrap"
        >
          <table className="tbl">
            <thead>
              <tr>
                <th>Variant</th>
                <th>SKU</th>
                <th className="num">Your price</th>
                <th className="num">Tier floor</th>
                <th>Quote (tier-governed)</th>
              </tr>
            </thead>
            <tbody>
              {headState === 'loading' ? (
                <>
                  <SkeletonRow cols={5} />
                  <SkeletonRow cols={5} />
                  <SkeletonRow cols={5} />
                </>
              ) : errored ? (
                <EmptyRow cols={5}>Couldn't load your catalogue — please retry shortly.</EmptyRow>
              ) : catalogue.length === 0 ? (
                <EmptyRow cols={5}>No catalogue items in your contract yet.</EmptyRow>
              ) : (
                catalogue.map((c) => (
                  <tr key={c.sku} style={{ cursor: 'default' }}>
                    <td>
                      <b>{c.label}</b>
                    </td>
                    <td className="mono dim" style={{ fontFamily: 'var(--font-mono)', fontSize: 11 }}>
                      {c.sku}
                    </td>
                    <td className="num">
                      <b>
                        <Money value={c.price} ccy={c.currency} layer="commercial" role={role} />
                      </b>
                    </td>
                    <td className="num dim">
                      <Money value={c.floor} ccy={c.currency} layer="commercial" role={role} />
                    </td>
                    <td>
                      <div className="row g6">
                        <input
                          className="cellinput"
                          style={{ width: 80 }}
                          placeholder={c.price != null ? String(c.price) : 'tier'}
                          value={draft[c.sku] || ''}
                          onChange={(e) => setDraft(Object.assign({}, draft, { [c.sku]: e.target.value }))}
                        />
                        <button className="btn sm primary" disabled={throttled || busy === c.sku} onClick={() => tryQuote(c.sku)}>
                          {throttled ? 'slow down…' : busy === c.sku ? 'quoting…' : 'Quote'}
                        </button>
                      </div>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
          <div className="layer-note" style={{ padding: '10px 16px' }}>
            <I.shield />
            Nobody types a price below tier — a non-tier quote is rejected (422) with the nearest allowed. Calls are rate-limited to your tier
            (429 → a graceful "slow down").
          </div>
        </Card>
      )}

      {forbidden ? null : (
        <Card title="My orders & invoices" icon={I.sessions} aux="your orders only · scope-walled to your party" style={{ padding: 0 }} className="tablewrap">
          <table className="tbl">
            <thead>
              <tr>
                <th>Order</th>
                <th>Date</th>
                <th className="num">Units</th>
                <th className="num">Total</th>
                <th>Status</th>
                <th>Invoice</th>
              </tr>
            </thead>
            <tbody>
              {headState === 'loading' ? (
                <>
                  <SkeletonRow cols={6} />
                  <SkeletonRow cols={6} />
                </>
              ) : errored ? (
                <EmptyRow cols={6}>Couldn't load your orders — please retry shortly.</EmptyRow>
              ) : orders.length === 0 ? (
                <EmptyRow cols={6}>No orders yet — place your first from the catalogue above.</EmptyRow>
              ) : (
                orders.map((o) => (
                  <tr key={o.order} style={{ cursor: 'default' }}>
                    <td>
                      <b className="mono" style={{ fontFamily: 'var(--font-mono)', fontSize: 11.5 }}>
                        {o.order}
                      </b>
                    </td>
                    <td className="dim">{o.date}</td>
                    <td className="num">{o.units}</td>
                    <td className="num">
                      <b>
                        <Money value={o.total} ccy={o.currency} layer="commercial" role={role} />
                      </b>
                    </td>
                    <td>
                      <Chip s={o.status === 'delivered' ? 'ok' : o.status === 'dispatched' ? 'accent' : 'warn'}>{o.status}</Chip>
                    </td>
                    <td>
                      {o.invoice ? (
                        <span className="aref">
                          <I.download size={11} />
                          {o.invoice}
                        </span>
                      ) : (
                        <span className="dim">pending</span>
                      )}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </Card>
      )}
    </div>
  );
}

function fmt(v: number | string | null | undefined, ccy?: string): string {
  if (v == null || v === '') return '—';
  const n = typeof v === 'string' ? Number(v) : v;
  const sym = ccy === 'USD' ? '$' : ccy === 'EUR' ? '€' : '£';
  return sym + (isFinite(n) ? n.toFixed(2) : String(v));
}
