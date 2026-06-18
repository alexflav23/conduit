import React, { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useApi } from './lib/query';
import { ApiError } from './lib/client';
import { PageHead, Card, Chip, num } from './kit/kit';
import { I } from './kit/icons';

// The master customer record as a full route (/crm/account/:id) — one unified view, no side panels.
// • An ORG (installer/wholesaler) shows its CUSTOMERS (each a real individual → links to their own page),
//   its source lineage, branches and order book.
// • An INDIVIDUAL is the singular customer view: who they are (HubSpot + MRPeasy + placement identities unified),
//   the installer that sold to them, and their charger(s) + warranty/lifecycle.

const SRC_LABEL: Record<string, string> = {
  mrpeasy: 'MRPeasy', hubspot_company: 'HubSpot', hubspot_contact: 'HubSpot', placement_owner: 'Charger registry',
};

interface AcctSource { system: string; source_id?: string; name?: string; method?: string }
interface AcctBranch { id: string; name: string; orders?: number }
interface AcctOrder { id?: string; conduit_ref?: string; order_no?: string; date?: string; total?: number | string }
interface AcctCharger { id: string; serial: string; sku?: string; status?: string; warranty_days_left?: number; replaces?: string | null; replaced_by?: string[] | null }
interface AcctDetail {
  id: string; name: string; segment?: string | null; type?: string | null;
  parent?: { id: string; name: string } | null;
  sold_via?: { id: string; name: string; match: string } | null;
  sources?: AcctSource[]; branches?: AcctBranch[]; orders?: AcctOrder[]; chargers?: AcctCharger[];
}
interface Customer { id: string; first_name?: string | null; last_name?: string | null; email?: string | null; phone?: string | null; chargers?: number }

const PAGE = 50;

export function CrmAccount(_props: { token: string; role: any; ctx: any; toast: (m: string, k?: 'ok' | 'warn' | 'err') => void }) {
  const { id } = useParams();
  const navigate = useNavigate();
  const [page, setPage] = useState(0);

  const detail = useApi<AcctDetail>(['crm-account', id ?? ''], `/api/v1/crm/accounts/${encodeURIComponent(id ?? '')}`, { enabled: !!id });
  const d = detail.data;
  const err = detail.error as ApiError | null;
  const isOrg = !!d?.type && d.type !== 'individual';

  const customers = useApi<{ rows?: Customer[]; total?: number }>(
    ['crm-account-customers', id ?? '', page],
    `/api/v1/crm/accounts/${encodeURIComponent(id ?? '')}/customers?limit=${PAGE}&offset=${page * PAGE}`,
    { enabled: !!id && isOrg },
  );
  const custRows = customers.data?.rows ?? [];
  const custTotal = customers.data?.total ?? 0;
  const fullName = (c: Customer) => [c.first_name, c.last_name].filter(Boolean).join(' ') || c.email || '—';

  const open = (pid: string) => navigate('/crm/account/' + pid);

  return (
    <>
      <PageHead
        crumb={<span style={{ cursor: 'pointer' }} onClick={() => navigate('/crm')}>← CRM · Accounts</span>}
        title={d?.name ?? (id ?? '').slice(0, 8)}
        sub={isOrg ? 'Master account — its customers, source lineage, branches and order book.' : 'Customer — unified identity, installer, and charger lifecycle.'}
        right={d && (
          <div className="row g6">
            {d.segment && <Chip s="neutral">{d.segment}</Chip>}
            <Chip s={isOrg ? 'neutral' : 'accent'}>{isOrg ? d.type : 'customer'}</Chip>
          </div>
        )}
      />

      {detail.isLoading && <Card title="Loading…" icon={I.user}><div className="dim" style={{ padding: 16 }}>Loading…</div></Card>}
      {err && <Card title="Account" icon={I.user}><div className="banner danger" style={{ margin: 8 }}>Couldn't load this account (HTTP {err.status}).</div></Card>}

      {d && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {(d.parent || d.sold_via) && (
            <div className="row g8" style={{ flexWrap: 'wrap' }}>
              {d.parent && <span style={{ cursor: 'pointer' }} onClick={() => open(d.parent!.id)}><Chip s="warn">branch of {d.parent.name} ↗</Chip></span>}
              {d.sold_via && <span title={`Linked by ${d.sold_via.match} match`} style={{ cursor: 'pointer' }} onClick={() => open(d.sold_via!.id)}><Chip s="accent">installer: {d.sold_via.name} ↗</Chip></span>}
            </div>
          )}

          {/* ORG: the hero is its customers (each → their own individual page) */}
          {isOrg && (
            <Card title={`Customers${custTotal ? ` (${num(custTotal)})` : ''}`} icon={I.user}
              aux={<span className="dim" style={{ fontSize: 11.5 }}>end-customers who got a charger through this account — click any to open them</span>}
              className="tablewrap" style={{ padding: 0 }}>
              {customers.isLoading && <div className="dim" style={{ padding: 14, fontSize: 12 }}>Loading customers…</div>}
              {!customers.isLoading && custRows.length === 0 && <div className="dim" style={{ padding: 14, fontSize: 12.5 }}>No end-customers linked to this account yet.</div>}
              {custRows.length > 0 && (
                <table className="tbl">
                  <thead><tr><th>Customer</th><th>Email</th><th>Phone</th><th className="num">Chargers</th><th /></tr></thead>
                  <tbody>
                    {custRows.map((c) => (
                      <tr key={c.id} style={{ cursor: 'pointer' }} onClick={() => open(c.id)} data-testid="customer-row">
                        <td><b>{fullName(c)}</b></td>
                        <td className="mono" style={{ fontSize: 11.5 }}>{c.email || ''}</td>
                        <td className="mono" style={{ fontSize: 11.5 }}>{c.phone || ''}</td>
                        <td className="num">{(c.chargers ?? 0) > 0 ? <Chip s="accent">{num(c.chargers ?? 0)}</Chip> : <span className="dim">—</span>}</td>
                        <td className="dim" style={{ fontSize: 11, textAlign: 'right' }}>open ↗</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              {custTotal > PAGE && (
                <div className="row between" style={{ padding: '8px 12px' }}>
                  <button className="btn ghost sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>← Prev</button>
                  <span className="dim" style={{ fontSize: 12 }}>{page * PAGE + 1}–{Math.min((page + 1) * PAGE, custTotal)} of {num(custTotal)}</span>
                  <button className="btn ghost sm" disabled={(page + 1) * PAGE >= custTotal} onClick={() => setPage((p) => p + 1)}>Next →</button>
                </div>
              )}
            </Card>
          )}

          {/* Chargers + lifecycle (the heart of the individual customer view; also shown for orgs that own units) */}
          {(d.chargers ?? []).length > 0 && (
            <Card title={`Charger${d.chargers!.length > 1 ? 's' : ''} (${d.chargers!.length})`} icon={I.charger} className="tablewrap" style={{ padding: 0 }}>
              <table className="tbl">
                <thead><tr><th>Serial</th><th>Product</th><th>Status</th><th className="num">Warranty left</th><th>Replacement</th></tr></thead>
                <tbody>
                  {d.chargers!.map((ch) => (
                    <tr key={ch.id} style={{ cursor: 'pointer' }} onClick={() => navigate('/batch?serial=' + encodeURIComponent(ch.serial))} title="open in Batch & genealogy">
                      <td className="mono">{ch.serial} ↗</td><td className="mono" style={{ fontSize: 11.5 }}>{ch.sku}</td>
                      <td>{ch.status}</td><td className="num">{ch.warranty_days_left != null ? num(ch.warranty_days_left) + 'd' : '—'}</td>
                      <td className="dim" style={{ fontSize: 11.5 }}>{ch.replaces ? `↩ replaced ${ch.replaces}` : ''}{ch.replaced_by?.length ? `→ ${ch.replaced_by.join(', ')}` : ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>
          )}

          {/* Source lineage — the unified identities behind this one record (NOT separate panels) */}
          <Card title="Identity & lineage" icon={I.list}
            aux={<span className="dim" style={{ fontSize: 11.5 }}>every system this record was assembled from</span>}>
            {(d.sources ?? []).length === 0 && <div className="dim" style={{ fontSize: 12 }}>No source links.</div>}
            <div className="row g8" style={{ flexWrap: 'wrap' }}>
              {(d.sources ?? []).map((s, i) => (
                <span key={i} className="row g6" style={{ fontSize: 12, padding: '3px 8px', background: 'var(--bg-2)', borderRadius: 8 }}>
                  <Chip s={s.system === 'mrpeasy' ? 'approved' : 'accent'}>{SRC_LABEL[s.system] || s.system}</Chip>
                  <span className="mono" style={{ fontSize: 11 }}>{s.name || s.source_id}</span>
                </span>
              ))}
            </div>
          </Card>

          {isOrg && (d.branches ?? []).length > 0 && (
            <Card title={`Branches (${d.branches!.length})`} icon={I.list}>
              {d.branches!.map((b) => (
                <div key={b.id} className="row between" style={{ fontSize: 12.5, padding: '3px 0', cursor: 'pointer' }} onClick={() => open(b.id)}>
                  <span>{b.name} <span className="dim">↗</span></span><span className="dim">{num(b.orders ?? 0)} orders</span>
                </div>
              ))}
            </Card>
          )}

          {(d.orders ?? []).length > 0 && (
            <Card title="Recent orders" icon={I.charger} aux={<span className="dim" style={{ fontSize: 11.5 }}>click an order for its full topology</span>} className="tablewrap" style={{ padding: 0 }}>
              <table className="tbl">
                <thead><tr><th>Order ID</th><th>MRP / source ref</th><th>Date</th><th className="num">Total inc VAT</th><th /></tr></thead>
                <tbody>
                  {d.orders!.map((o, i) => (
                    <tr key={(o.id ?? o.order_no ?? '') + i} style={{ cursor: o.id ? 'pointer' : 'default' }}
                      onClick={o.id ? () => navigate('/orders/' + o.id) : undefined} data-testid="order-row">
                      <td><b>{o.conduit_ref ?? '—'}</b></td>
                      <td className="mono" style={{ fontSize: 11.5 }}>{o.order_no}</td>
                      <td className="dim">{o.date}</td>
                      <td className="num">{num(o.total as number)}</td>
                      <td className="dim" style={{ fontSize: 11, textAlign: 'right' }}>{o.id ? 'open ↗' : ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>
          )}
        </div>
      )}
    </>
  );
}
