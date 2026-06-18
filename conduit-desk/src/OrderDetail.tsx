import React from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useApi } from './lib/query';
import { ApiError } from './lib/client';
import { PageHead, Card, Chip, gbp, num } from './kit/kit';
import { I } from './kit/icons';

// The Conduit order as a golden record (/orders/:id) — the top of the topology. The Conduit ref sits above its
// source identities (MRPeasy order, customer PO, HubSpot deal), with the priced line items and every downstream
// artifact already linked to it: invoices, dispatches + tranches, recognition. Backed by GET /orders/{id}/lineage.

const SRC_LABEL: Record<string, string> = {
  mrpeasy: 'MRPeasy order', customer_po: 'Customer PO', hubspot_deal: 'HubSpot deal', payment: 'Payment',
};
const statusTone = (s?: string) => (s === 'placed' || s === 'paid' || s === 'delivered' ? 'ok' : s === 'void' || s === 'cancelled' ? 'danger' : 'neutral');

interface Src { system: string; ref?: string; detail?: string }
interface Line { name?: string; sku?: string; qty?: number; unit_price_ex_vat?: number | string; vat?: number | string; status?: string }
interface Inv { invoice_no?: string; total_inc_vat?: number | string; status?: string; issued_at?: string }
interface Disp { dispatch_no?: string; date?: string; status?: string; lines?: number; tranches?: number }
interface Lineage {
  id: string; conduit_ref?: string; order_no?: string; status?: string; order_date?: string;
  currency?: string; subtotal_ex_vat?: number | string; vat_total?: number | string; total_inc_vat?: number | string;
  customer?: { id: string; name: string } | null;
  sources?: Src[]; lines?: Line[]; invoices?: Inv[]; dispatches?: Disp[];
  recognition?: { dispatches?: number; revenue_ex_vat?: number | string; cogs?: number | string };
}

export function OrderDetail(_props: { token: string; role: any; ctx: any; toast: (m: string, k?: 'ok' | 'warn' | 'err') => void }) {
  const { id } = useParams();
  const navigate = useNavigate();
  const q = useApi<Lineage>(['order-lineage', id ?? ''], `/api/v1/orders/${encodeURIComponent(id ?? '')}/lineage`, { enabled: !!id });
  const d = q.data;
  const err = q.error as ApiError | null;
  const ccy = d?.currency || 'GBP';
  const rec = d?.recognition;

  return (
    <>
      <PageHead
        crumb={<span style={{ cursor: 'pointer' }} onClick={() => navigate(-1 as any)}>← Back</span>}
        title={d?.conduit_ref ?? (id ?? '').slice(0, 8)}
        sub="Order — the top of the topology: its source identities, line items, invoices, dispatches and recognition."
        right={d && <div className="row g6">{d.status && <Chip s={statusTone(d.status)}>{d.status}</Chip>}</div>}
      />

      {q.isLoading && <Card title="Loading…" icon={I.charger}><div className="dim" style={{ padding: 16 }}>Loading order…</div></Card>}
      {err && <Card title="Order" icon={I.charger}><div className="banner danger" style={{ margin: 8 }}>Couldn't load this order (HTTP {err.status}).</div></Card>}

      {d && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {/* Header strip: customer + source identities + totals */}
          <Card title="Order" icon={I.charger}>
            <div className="row g24" style={{ flexWrap: 'wrap', alignItems: 'flex-start' }}>
              <div style={{ minWidth: 180 }}>
                <div className="fldlabel">Customer</div>
                <div style={{ fontFamily: 'var(--font-disp)', fontSize: 17, fontWeight: 600, marginTop: 3, cursor: d.customer ? 'pointer' : 'default', color: d.customer ? 'var(--accent-bright)' : undefined }}
                  onClick={() => d.customer && navigate('/crm/account/' + d.customer.id)}>{d.customer?.name ?? '—'}{d.customer && ' ↗'}</div>
                <div className="dim" style={{ fontSize: 11.5, marginTop: 3 }}>order date {d.order_date ?? '—'}</div>
              </div>
              <div style={{ minWidth: 220 }}>
                <div className="fldlabel">Source identities</div>
                <div className="row g8" style={{ flexWrap: 'wrap', marginTop: 6 }}>
                  {(d.sources ?? []).map((s, i) => (
                    <span key={i} className="row g6" style={{ fontSize: 12, padding: '3px 8px', background: 'var(--bg-2)', borderRadius: 8 }}>
                      <Chip s={s.system === 'mrpeasy' ? 'approved' : 'accent'}>{SRC_LABEL[s.system] || s.system}</Chip>
                      <span className="mono" style={{ fontSize: 11 }}>{s.ref}</span>
                    </span>
                  ))}
                  {(d.sources ?? []).length === 0 && <span className="dim" style={{ fontSize: 12 }}>—</span>}
                </div>
              </div>
              <div style={{ minWidth: 120, marginLeft: 'auto', textAlign: 'right' }}>
                <div className="fldlabel">Total inc VAT</div>
                <div style={{ fontFamily: 'var(--font-disp)', fontSize: 22, fontWeight: 600, marginTop: 3 }}>{gbp(d.total_inc_vat, ccy)}</div>
                <div className="dim" style={{ fontSize: 11.5, marginTop: 2 }}>{gbp(d.subtotal_ex_vat, ccy)} ex · {gbp(d.vat_total, ccy)} VAT</div>
              </div>
            </div>
          </Card>

          {/* Line items */}
          <Card title={`Line items (${(d.lines ?? []).length})`} icon={I.list} className="tablewrap" style={{ padding: 0 }}>
            <table className="tbl">
              <thead><tr><th>Product</th><th>SKU</th><th className="num">Qty</th><th className="num">Unit ex-VAT</th><th className="num">VAT</th><th>Status</th></tr></thead>
              <tbody>
                {(d.lines ?? []).map((l, i) => (
                  <tr key={i}>
                    <td><b>{l.name}</b></td><td className="mono" style={{ fontSize: 11.5 }}>{l.sku}</td>
                    <td className="num">{num(l.qty)}</td><td className="num">{gbp(l.unit_price_ex_vat, ccy)}</td>
                    <td className="num">{gbp(l.vat, ccy)}</td><td><Chip s={statusTone(l.status)}>{l.status}</Chip></td>
                  </tr>
                ))}
                {(d.lines ?? []).length === 0 && <tr><td className="dim" colSpan={6} style={{ padding: 14 }}>No line items.</td></tr>}
              </tbody>
            </table>
          </Card>

          <div className="grid" style={{ gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            {/* Invoices */}
            <Card title={`Invoices (${(d.invoices ?? []).length})`} icon={I.sessions} className="tablewrap" style={{ padding: 0 }}>
              <table className="tbl">
                <thead><tr><th>Invoice</th><th>Issued</th><th className="num">Total inc VAT</th><th>Status</th></tr></thead>
                <tbody>
                  {(d.invoices ?? []).map((iv, i) => (
                    <tr key={i}><td className="mono">{iv.invoice_no}</td><td className="dim">{iv.issued_at ? iv.issued_at.slice(0, 10) : '—'}</td>
                      <td className="num">{gbp(iv.total_inc_vat, ccy)}</td><td><Chip s={statusTone(iv.status)}>{iv.status}</Chip></td></tr>
                  ))}
                  {(d.invoices ?? []).length === 0 && <tr><td className="dim" colSpan={4} style={{ padding: 14 }}>No invoices.</td></tr>}
                </tbody>
              </table>
            </Card>
            {/* Dispatches / tranches */}
            <Card title={`Dispatches (${(d.dispatches ?? []).length})`} icon={I.charger} className="tablewrap" style={{ padding: 0 }}>
              <table className="tbl">
                <thead><tr><th>Dispatch</th><th>Date</th><th className="num">Lines</th><th className="num">Tranches</th><th>Status</th></tr></thead>
                <tbody>
                  {(d.dispatches ?? []).map((dp, i) => (
                    <tr key={i}><td className="mono">{dp.dispatch_no}</td><td className="dim">{dp.date}</td>
                      <td className="num">{num(dp.lines)}</td><td className="num">{num(dp.tranches)}</td><td><Chip s={statusTone(dp.status)}>{dp.status}</Chip></td></tr>
                  ))}
                  {(d.dispatches ?? []).length === 0 && <tr><td className="dim" colSpan={5} style={{ padding: 14 }}>Not yet dispatched.</td></tr>}
                </tbody>
              </table>
            </Card>
          </div>

          {/* Recognition */}
          {rec && (rec.dispatches ?? 0) > 0 && (
            <Card title="Revenue recognition" icon={I.pulse} aux="ASC 606 — recognised on dispatch">
              <div className="row g24" style={{ flexWrap: 'wrap' }}>
                {[['Dispatches recognised', num(rec.dispatches)], ['Revenue ex-VAT', gbp(rec.revenue_ex_vat, ccy)], ['COGS', gbp(rec.cogs, ccy)]].map(([k, v]) => (
                  <div key={k} style={{ minWidth: 150 }}>
                    <div style={{ fontFamily: 'var(--font-disp)', fontSize: 22, fontWeight: 600 }}>{v}</div>
                    <div className="dim" style={{ fontSize: 'var(--fs-xs)', marginTop: 4 }}>{k}</div>
                  </div>
                ))}
              </div>
            </Card>
          )}
        </div>
      )}
    </>
  );
}
