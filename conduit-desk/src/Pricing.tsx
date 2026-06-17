import React, { useMemo, useState } from 'react';
import { useApi } from './lib/query';
import { ApiError } from './lib/client';
import { PageHead, Card, Chip, LayerNote, EmptyRow, SkeletonRow, num } from './kit/kit';
import { I } from './kit/icons';

// Pricing — the governed price book (doc 24, M3). Nobody types a price: every order line binds to one of these
// preset tiers. open_list = the default (Retail); segment scopes to a customer class (Installers); customer_set is
// a named account's contract (Octopus, YESSS, …). Read-only surface over the real, seeded book.
//   GET /api/v1/pricing/rules

interface Rule {
  id: string; sku: string | null; agreement: string; applies_to: string;
  currency: string; authorised_price: string; min_qty: number; status: string;
}

const APPLIES_CHIP: Record<string, string> = { open_list: 'accent', segment: 'plum', customer_set: 'neutral' };
const APPLIES_LABEL: Record<string, string> = { open_list: 'default (retail)', segment: 'segment', customer_set: 'contract' };

export function Pricing(_props: any) {
  const q = useApi<Rule[]>(['price-rules'], '/api/v1/pricing/rules');
  const [filter, setFilter] = useState('');

  const err = q.error as ApiError | null;
  const forbidden = !!err?.forbidden;
  const rules = Array.isArray(q.data) ? q.data : [];

  // group by agreement
  const groups = useMemo(() => {
    const m = new Map<string, Rule[]>();
    rules.forEach((r) => { (m.get(r.agreement) ?? m.set(r.agreement, []).get(r.agreement)!).push(r); });
    return Array.from(m.entries())
      .filter(([name]) => !filter || name.toLowerCase().includes(filter.toLowerCase()))
      .sort((a, b) => (a[1][0]?.applies_to === 'open_list' ? -1 : b[1][0]?.applies_to === 'open_list' ? 1 : a[0].localeCompare(b[0])));
  }, [rules, filter]);

  return (
    <>
      <PageHead
        crumb="Sell · governed price book (doc 24, M3)"
        title="Pricing"
        sub="Every order line binds to a preset, governed tier — nobody types a price. open_list is the retail default; segment scopes to a customer class; contract tiers are a named account's negotiated price."
        right={<input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="filter channel/customer…"
          style={{ padding: '7px 11px', borderRadius: 8, border: '1px solid var(--border)', background: 'var(--tint)', color: 'inherit', fontSize: 13, width: 220 }} />}
      />

      {forbidden && <LayerNote>hidden — requires view:price_rule</LayerNote>}

      {!forbidden && (
        <>
          <div className="dim" style={{ fontSize: 12.5, marginBottom: 12 }}>
            {q.isLoading ? 'loading…' : `${rules.length} tiers across ${groups.length} agreements`}
          </div>
          {q.isLoading && <Card style={{ padding: 16 }}><SkeletonRow cols={1} /></Card>}
          {!q.isLoading && groups.length === 0 && <Card style={{ padding: 16 }}><EmptyRow cols={1}>No price tiers.</EmptyRow></Card>}
          {groups.map(([name, rs]) => {
            const applies = rs[0]?.applies_to ?? 'open_list';
            const ccy = rs[0]?.currency ?? 'GBP';
            return (
              <Card key={name} title={name} icon={I.flag}
                aux={<Chip s={APPLIES_CHIP[applies] ?? 'neutral'}>{APPLIES_LABEL[applies] ?? applies}</Chip>}>
                <table className="tbl">
                  <thead><tr><th>SKU</th><th style={{ textAlign: 'right' }}>Price ({ccy})</th><th style={{ textAlign: 'right' }}>Min qty</th><th>Status</th></tr></thead>
                  <tbody>
                    {rs.sort((a, b) => (a.sku || '').localeCompare(b.sku || '')).map((r) => (
                      <tr key={r.id}>
                        <td style={{ fontFamily: 'var(--font-mono)', fontSize: 12.5 }}>{r.sku || '—'}</td>
                        <td style={{ textAlign: 'right' }}>{r.currency === 'EUR' ? '€' : '£'}{num(parseFloat(r.authorised_price))}</td>
                        <td style={{ textAlign: 'right' }} className="dim">{r.min_qty}</td>
                        <td><Chip s={r.status === 'active' ? 'ok' : 'neutral'}>{r.status}</Chip></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Card>
            );
          })}
        </>
      )}
    </>
  );
}
