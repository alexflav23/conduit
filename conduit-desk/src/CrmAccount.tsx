import React, { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useApi } from './lib/query';
import { ApiError } from './lib/client';
import { PageHead, Card, Chip, num } from './kit/kit';
import { I } from './kit/icons';

// The bespoke per-account page (/crm/account/:id) — the master customer record as a full route, not a side panel.
// For an installer/wholesaler the hero is its CUSTOMERS (the end-customers it sold to: phone-bridged charger
// owners + end-customer contacts); for any account it also shows source lineage, branches, chargers and orders.
// Backed by GET /crm/accounts/{id} (detail) + GET /crm/accounts/{id}/customers (paginated).

const SRC_LABEL: Record<string, string> = {
  mrpeasy: 'MRPeasy', hubspot_company: 'HubSpot', hubspot_contact: 'HubSpot contact', placement_owner: 'Owner',
};

interface AcctSource { system: string; source_id?: string; name?: string; method?: string; confidence?: number }
interface AcctContact { name?: string; email?: string; phone?: string; role?: string; entity_type?: string }
interface AcctBranch { id: string; name: string; orders?: number }
interface AcctOrder { order_no?: string; date?: string; total?: number | string }
interface AcctCharger { id: string; serial: string; sku?: string; status?: string; warranty_days_left?: number; replaces?: string | null; replaced_by?: string[] | null }
interface AcctDetail {
  id: string; name: string; segment?: string | null; type?: string | null;
  parent?: { id: string; name: string } | null;
  sold_via?: { id: string; name: string; match: string } | null;
  sources?: AcctSource[]; contacts?: AcctContact[]; branches?: AcctBranch[]; orders?: AcctOrder[]; chargers?: AcctCharger[];
}
interface Customer { kind: 'owner' | 'contact'; id?: string | null; first_name?: string | null; last_name?: string | null; email?: string | null; phone?: string | null; chargers?: number }

const PAGE = 50;

export function CrmAccount(_props: { token: string; role: any; ctx: any; toast: (m: string, k?: 'ok' | 'warn' | 'err') => void }) {
  const { id } = useParams();
  const navigate = useNavigate();
  const [page, setPage] = useState(0);

  const detail = useApi<AcctDetail>(['crm-account', id ?? ''], `/api/v1/crm/accounts/${encodeURIComponent(id ?? '')}`, { enabled: !!id });
  const customers = useApi<{ rows?: Customer[]; total?: number }>(
    ['crm-account-customers', id ?? '', page],
    `/api/v1/crm/accounts/${encodeURIComponent(id ?? '')}/customers?limit=${PAGE}&offset=${page * PAGE}`,
    { enabled: !!id },
  );
  const d = detail.data;
  const err = detail.error as ApiError | null;
  const custRows = customers.data?.rows ?? [];
  const custTotal = customers.data?.total ?? 0;
  const isOrg = d?.type && d.type !== 'individual';

  const fullName = (c: Customer) => [c.first_name, c.last_name].filter(Boolean).join(' ') || c.email || '—';

  return (
    <>
      <PageHead
        crumb={<span style={{ cursor: 'pointer' }} onClick={() => navigate('/crm')}>← CRM · Accounts</span>}
        title={d?.name ?? (id ?? '').slice(0, 8)}
        sub="The master customer record — its end-customers, source lineage, branches, chargers and order book."
        right={d && <div className="row g6">{d.segment && <Chip s="neutral">{d.segment}</Chip>}{d.type && <Chip s="neutral">{d.type}</Chip>}</div>}
      />

      {detail.isLoading && <Card title="Loading…" icon={I.user}><div className="dim" style={{ padding: 16 }}>Loading account…</div></Card>}
      {err && <Card title="Account" icon={I.user}><div className="banner danger" style={{ margin: 8 }}>Couldn't load this account (HTTP {err.status}).</div></Card>}

      {d && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          {(d.parent || d.sold_via) && (
            <div className="row g8" style={{ flexWrap: 'wrap' }}>
              {d.parent && <Chip s="warn">branch of {d.parent.name}</Chip>}
              {d.sold_via && (
                <span title={`Associated by ${d.sold_via.match} match`} style={{ cursor: 'pointer' }} onClick={() => navigate('/crm/account/' + d.sold_via!.id)}>
                  <Chip s="accent">installer: {d.sold_via.name}</Chip>
                </span>
              )}
            </div>
          )}

          {/* HERO: the installer's customers */}
          <Card title={`Customers${custTotal ? ` (${num(custTotal)})` : ''}`} icon={I.user}
            aux={<span className="dim" style={{ fontSize: 11.5 }}>end-customers this account sold to — phone-matched owners + end-customer contacts</span>}
            className="tablewrap" style={{ padding: 0 }}>
            {customers.isLoading && <div className="dim" style={{ padding: 14, fontSize: 12 }}>Loading customers…</div>}
            {!customers.isLoading && custRows.length === 0 && (
              <div className="dim" style={{ padding: 14, fontSize: 12.5 }}>No end-customers associated with this account yet.</div>
            )}
            {custRows.length > 0 && (
              <table className="tbl">
                <thead><tr><th>First name</th><th>Last name</th><th>Email</th><th>Phone</th><th className="num">Chargers</th><th>Link</th></tr></thead>
                <tbody>
                  {custRows.map((c, i) => (
                    <tr key={(c.id ?? '') + i}
                      style={{ cursor: c.kind === 'owner' && c.id ? 'pointer' : 'default' }}
                      onClick={c.kind === 'owner' && c.id ? () => navigate('/crm/account/' + c.id) : undefined}>
                      <td>{c.first_name || (c.last_name ? '' : fullName(c))}{c.kind === 'owner' && <Chip s="accent">owner</Chip>}</td>
                      <td>{c.last_name || ''}</td>
                      <td className="mono" style={{ fontSize: 11.5 }}>{c.email || ''}</td>
                      <td className="mono" style={{ fontSize: 11.5 }}>{c.phone || ''}</td>
                      <td className="num">{c.kind === 'owner' ? num(c.chargers ?? 0) : ''}</td>
                      <td>{c.kind === 'owner' && c.id && <span className="dim" style={{ fontSize: 11 }}>open ↗</span>}</td>
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

          {/* Owner chargers (for an individual account) */}
          {(d.chargers ?? []).length > 0 && (
            <Card title={`Chargers (${d.chargers!.length})`} icon={I.charger} className="tablewrap" style={{ padding: 0 }}>
              <table className="tbl">
                <thead><tr><th>Serial</th><th>SKU</th><th>Status</th><th className="num">Warranty left</th><th>Replaces / replaced by</th></tr></thead>
                <tbody>
                  {d.chargers!.map((ch) => (
                    <tr key={ch.id} style={{ cursor: 'pointer' }} onClick={() => navigate('/batch?serial=' + encodeURIComponent(ch.serial))}>
                      <td className="mono">{ch.serial}</td><td className="mono" style={{ fontSize: 11.5 }}>{ch.sku}</td>
                      <td>{ch.status}</td><td className="num">{ch.warranty_days_left != null ? num(ch.warranty_days_left) + 'd' : '—'}</td>
                      <td className="dim" style={{ fontSize: 11.5 }}>{ch.replaces ? `↩ ${ch.replaces}` : ''}{ch.replaced_by?.length ? ` → ${ch.replaced_by.join(', ')}` : ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </Card>
          )}

          <div className="grid" style={{ gridTemplateColumns: isOrg ? '1fr 1fr' : '1fr', gap: 16 }}>
            <Card title="Source systems (lineage)" icon={I.list}>
              {(d.sources ?? []).length === 0 && <div className="dim" style={{ fontSize: 12 }}>No source links.</div>}
              {(d.sources ?? []).map((s, i) => (
                <div key={i} className="row between" style={{ fontSize: 12.5, padding: '3px 0' }}>
                  <span><Chip s={s.system === 'mrpeasy' ? 'approved' : 'accent'}>{SRC_LABEL[s.system] || s.system}</Chip> <span className="mono">{s.name || s.source_id}</span></span>
                  <span className="dim">{s.method}</span>
                </div>
              ))}
            </Card>
            <Card title={`Contacts (${(d.contacts ?? []).length})`} icon={I.user}>
              {(d.contacts ?? []).length === 0 && <div className="dim" style={{ fontSize: 12 }}>No contacts.</div>}
              {(d.contacts ?? []).slice(0, 40).map((c, i) => (
                <div key={i} className="row between" style={{ fontSize: 12.5, padding: '2px 0' }}>
                  <span>{c.name || <span className="dim">—</span>}{c.entity_type === 'end_customer' ? <Chip s="accent">end customer</Chip> : c.role && <span className="dim"> · {c.role}</span>}</span>
                  <span className="dim mono" style={{ fontSize: 11 }}>{c.email || ''}</span>
                </div>
              ))}
            </Card>
          </div>

          {(d.branches ?? []).length > 0 && (
            <Card title={`Branches (${d.branches!.length})`} icon={I.list}>
              {d.branches!.map((b) => (
                <div key={b.id} className="row between" style={{ fontSize: 12.5, padding: '2px 0', cursor: 'pointer' }} onClick={() => navigate('/crm/account/' + b.id)}>
                  <span>{b.name}</span><span className="dim">{num(b.orders ?? 0)} orders</span>
                </div>
              ))}
            </Card>
          )}

          {(d.orders ?? []).length > 0 && (
            <Card title="Recent orders" icon={I.charger} className="tablewrap" style={{ padding: 0 }}>
              <table className="tbl">
                <thead><tr><th>Order</th><th>Date</th><th className="num">Total inc VAT</th></tr></thead>
                <tbody>
                  {d.orders!.map((o, i) => (
                    <tr key={(o.order_no ?? '') + i}><td className="mono">{o.order_no}</td><td className="dim">{o.date}</td><td className="num">{num(o.total as number)}</td></tr>
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
