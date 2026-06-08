import React, { useState } from 'react';
import * as stylex from '@stylexjs/stylex';
import { colors } from './styles/tokens.stylex';
import {
  taxQuote, getTaxRates, proposeTaxRate, activateTaxRate, getTaxRouting, getTaxNexus, TAX_DEMO_ENTITY,
  type TaxQuoteInput,
} from './api';

// The Tax desk (M13-Tax, doc 16): the determination engine made tangible. A live quote tester shows the resolved
// place-of-supply + the multi-level jurisdiction breakdown (US state+county+district, CA GST+PST, UK VAT), and the
// rate-table admin manages effective-dated rates with maker-checker governance (propose → CFO activates). Tax is a
// QUOTE, not a rate column — and rates carry validity periods so a VAT change is a new dated row, never an edit.
const styles = stylex.create({
  card: { backgroundColor: colors.surface, border: `1px solid ${colors.border}`, borderRadius: '14px', padding: '1.25rem', marginBottom: '1.25rem', maxWidth: '980px' },
  section: { fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.06em', color: colors.muted, marginBottom: '0.7rem' },
  row: { display: 'flex', gap: '0.6rem', alignItems: 'center', marginBottom: '0.7rem', flexWrap: 'wrap' },
  button: { backgroundColor: colors.accent, color: '#fff', border: 'none', borderRadius: '10px', padding: '0.5rem 1.05rem', fontSize: '0.92rem', fontWeight: 600, cursor: 'pointer' },
  ghost: { backgroundColor: 'transparent', color: colors.accent, border: `1px solid ${colors.accent}`, borderRadius: '8px', padding: '0.3rem 0.7rem', fontSize: '0.82rem', fontWeight: 600, cursor: 'pointer' },
  input: { backgroundColor: colors.bg, color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.45rem 0.6rem', fontSize: '0.9rem', width: '120px' },
  select: { backgroundColor: colors.bg, color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.45rem 0.6rem', fontSize: '0.9rem' },
  label: { color: colors.muted, fontSize: '0.78rem' },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '0.86rem', fontVariantNumeric: 'tabular-nums' },
  th: { textAlign: 'left', color: colors.muted, fontSize: '0.7rem', textTransform: 'uppercase', letterSpacing: '0.05em', padding: '0.4rem 0.6rem', borderBottom: `1px solid ${colors.border}` },
  td: { padding: '0.4rem 0.6rem', borderBottom: `1px solid ${colors.border}` },
  num: { textAlign: 'right' },
  result: { display: 'flex', gap: '1.5rem', flexWrap: 'wrap', marginBottom: '0.8rem' },
  metric: { display: 'flex', flexDirection: 'column' },
  metricVal: { fontSize: '1.3rem', fontWeight: 700 },
  kind: { fontSize: '1.3rem', fontWeight: 700, color: colors.accent },
  badge: { fontSize: '0.66rem', fontWeight: 700, padding: '0.1rem 0.45rem', borderRadius: '999px', textTransform: 'uppercase', backgroundColor: 'rgba(150,45,255,0.18)', color: colors.accent },
  draft: { backgroundColor: 'rgba(230,170,40,0.18)', color: '#e0a83a' },
});

export function Tax({ token }: { token: string }) {
  const [q, setQ] = useState<TaxQuoteInput>({
    shipFromJurisdiction: 'GB', shipToJurisdiction: 'US', shipToRegion: 'CA', shipToPostcode: '90001',
    partyTaxStatus: 'consumer', buyerTaxId: '', currency: 'USD', taxableAmount: '100.00',
  });
  const [result, setResult] = useState<any>(null);
  const [rates, setRates] = useState<any[]>([]);
  const [rateJuris, setRateJuris] = useState('GB');
  const [nexus, setNexus] = useState<any[]>([]);
  const [routing, setRouting] = useState<any[]>([]);
  const [pr, setPr] = useState({ jurisdiction: 'FR', tax_type: 'VAT', region: '', postcode_prefix: '', level: 'national', name: 'France VAT', rate_pct: '20.0', effective_from: '2026-01-01' });
  const [proposeStatus, setProposeStatus] = useState<string | null>(null);

  const set = (k: keyof TaxQuoteInput) => (e: any) => setQ({ ...q, [k]: e.target.value });

  const runQuote = async () => {
    const r = await taxQuote(token, q);
    setResult(r.status === 200 ? r.json : { error: `${r.status}: ${r.json?.message ?? ''}` });
  };
  const loadRates = async (juris?: string) => setRates(await getTaxRates(token, (juris ?? rateJuris) || undefined).then((r) => (Array.isArray(r.json) ? r.json : [])));
  const loadNexus = async () => setNexus(await getTaxNexus(token, TAX_DEMO_ENTITY).then((r) => (Array.isArray(r.json) ? r.json : [])));
  const loadRouting = async () => setRouting(await getTaxRouting(token).then((r) => (Array.isArray(r.json) ? r.json : [])));

  const propose = async () => {
    const r = await proposeTaxRate(token, {
      ...pr, region: pr.region || null, postcode_prefix: pr.postcode_prefix || null,
      tax_category_code: 'goods_standard', rate_pct: Number(pr.rate_pct), kind: 'standard',
    });
    setProposeStatus(r.status === 200 ? `proposed draft ${r.json.id?.slice(0, 8)}` : `failed (${r.status})`);
    if (r.status === 200) { setRateJuris(pr.jurisdiction); await loadRates(pr.jurisdiction); }
  };
  const activate = async (id: string) => {
    const r = await activateTaxRate(token, id);
    setProposeStatus(r.status === 200 ? 'activated' : `activate failed (${r.status}: ${r.json?.message ?? ''})`);
    await loadRates();
  };

  return (
    <div>
      {/* ---- live quote tester: place of supply + the multi-level breakdown ---- */}
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.section)}>Quote tester — supply facts in, tax + jurisdiction breakdown out</div>
        <div {...stylex.props(styles.row)}>
          <span {...stylex.props(styles.label)}>Ship from</span>
          <input {...stylex.props(styles.input)} data-testid="tax-from" value={q.shipFromJurisdiction} onChange={set('shipFromJurisdiction')} style={{ width: '60px' }} />
          <span {...stylex.props(styles.label)}>to</span>
          <input {...stylex.props(styles.input)} data-testid="tax-to" value={q.shipToJurisdiction} onChange={set('shipToJurisdiction')} style={{ width: '60px' }} />
          <input {...stylex.props(styles.input)} data-testid="tax-region" value={q.shipToRegion} onChange={set('shipToRegion')} placeholder="region" style={{ width: '80px' }} />
          <input {...stylex.props(styles.input)} data-testid="tax-postcode" value={q.shipToPostcode} onChange={set('shipToPostcode')} placeholder="postcode" style={{ width: '100px' }} />
        </div>
        <div {...stylex.props(styles.row)}>
          <span {...stylex.props(styles.label)}>Buyer</span>
          <select {...stylex.props(styles.select)} data-testid="tax-party-status" value={q.partyTaxStatus} onChange={set('partyTaxStatus')}>
            <option value="consumer">consumer</option>
            <option value="business">business</option>
            <option value="business_with_vat_id">business + VAT id</option>
          </select>
          <input {...stylex.props(styles.input)} data-testid="tax-vatid" value={q.buyerTaxId} onChange={set('buyerTaxId')} placeholder="VAT id" style={{ width: '140px' }} />
          <span {...stylex.props(styles.label)}>Amount</span>
          <input {...stylex.props(styles.input)} data-testid="tax-amount" value={q.taxableAmount} onChange={set('taxableAmount')} style={{ width: '90px' }} />
          <input {...stylex.props(styles.input)} data-testid="tax-currency" value={q.currency} onChange={set('currency')} style={{ width: '64px' }} />
          <button {...stylex.props(styles.button)} data-testid="tax-quote-btn" onClick={runQuote}>Quote</button>
        </div>
        {result && !result.error && (
          <div>
            <div {...stylex.props(styles.result)}>
              <div {...stylex.props(styles.metric)}><span {...stylex.props(styles.label)}>Supply kind</span><span {...stylex.props(styles.kind)} data-testid="tax-supply-kind">{result.supplyKind}</span></div>
              <div {...stylex.props(styles.metric)}><span {...stylex.props(styles.label)}>Total tax</span><span {...stylex.props(styles.metricVal)} data-testid="tax-total">{Number(result.taxTotal).toFixed(2)} {result.currency}</span></div>
              <div {...stylex.props(styles.metric)}><span {...stylex.props(styles.label)}>Reverse charge</span><span {...stylex.props(styles.metricVal)} data-testid="tax-reverse">{String(result.reverseCharge)}</span></div>
            </div>
            <table {...stylex.props(styles.table)} data-testid="tax-components">
              <thead><tr>
                <th {...stylex.props(styles.th)}>Level</th><th {...stylex.props(styles.th)}>Jurisdiction</th><th {...stylex.props(styles.th)}>Name</th>
                <th {...stylex.props(styles.th)}>Type</th><th {...stylex.props(styles.th, styles.num)}>Rate %</th><th {...stylex.props(styles.th, styles.num)}>Amount</th>
              </tr></thead>
              <tbody>
                {(result.lines?.[0]?.components ?? []).map((c: any, i: number) => (
                  <tr key={i} data-testid="tax-comp-row">
                    <td {...stylex.props(styles.td)}>{c.level}</td>
                    <td {...stylex.props(styles.td)}>{c.jurisdiction}{c.region ? `/${c.region}` : ''}</td>
                    <td {...stylex.props(styles.td)}>{c.name}</td>
                    <td {...stylex.props(styles.td)}>{c.taxType}</td>
                    <td {...stylex.props(styles.td, styles.num)}>{c.ratePct}</td>
                    <td {...stylex.props(styles.td, styles.num)}>{Number(c.amount).toFixed(2)}</td>
                  </tr>
                ))}
                {(result.lines?.[0]?.components ?? []).length === 0 && <tr><td {...stylex.props(styles.td)} colSpan={6} style={{ color: colors.muted }}>No tax components (zero-rated / reverse-charge / export).</td></tr>}
              </tbody>
            </table>
          </div>
        )}
        {result?.error && <span {...stylex.props(styles.label)} data-testid="tax-error">{result.error}</span>}
      </div>

      {/* ---- rate-table admin: effective-dated rates + maker-checker governance ---- */}
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.section)}>Rate table — effective-dated, multi-level (a change is a new dated row, never an edit)</div>
        <div {...stylex.props(styles.row)}>
          <span {...stylex.props(styles.label)}>Jurisdiction</span>
          <input {...stylex.props(styles.input)} data-testid="tax-rate-juris" value={rateJuris} onChange={(e) => setRateJuris(e.target.value)} style={{ width: '70px' }} />
          <button {...stylex.props(styles.button)} data-testid="tax-rates-load" onClick={loadRates}>Load rates</button>
        </div>
        <table {...stylex.props(styles.table)} data-testid="tax-rates-table">
          <thead><tr>
            <th {...stylex.props(styles.th)}>Type</th><th {...stylex.props(styles.th)}>Region</th><th {...stylex.props(styles.th)}>Postcode</th>
            <th {...stylex.props(styles.th)}>Level</th><th {...stylex.props(styles.th)}>Name</th><th {...stylex.props(styles.th, styles.num)}>Rate %</th>
            <th {...stylex.props(styles.th)}>From</th><th {...stylex.props(styles.th)}>To</th><th {...stylex.props(styles.th)}>Status</th><th {...stylex.props(styles.th)} />
          </tr></thead>
          <tbody>
            {rates.map((r, i) => (
              <tr key={i} data-testid="tax-rate-row">
                <td {...stylex.props(styles.td)}>{r.tax_type}</td>
                <td {...stylex.props(styles.td)}>{r.region ?? '—'}</td>
                <td {...stylex.props(styles.td)}>{r.postcode_prefix ?? '—'}</td>
                <td {...stylex.props(styles.td)}>{r.level}</td>
                <td {...stylex.props(styles.td)}>{r.name}</td>
                <td {...stylex.props(styles.td, styles.num)}>{r.rate_pct ?? '—'}</td>
                <td {...stylex.props(styles.td)}>{r.effective_from}</td>
                <td {...stylex.props(styles.td)}>{r.effective_to ?? '—'}</td>
                <td {...stylex.props(styles.td)}>
                  {r.status === 'draft' ? <span {...stylex.props(styles.badge, styles.draft)}>draft</span> : r.status}
                </td>
                <td {...stylex.props(styles.td)}>
                  {r.status === 'draft' && <button {...stylex.props(styles.ghost)} data-testid="tax-activate" onClick={() => activate(r.id)}>Activate (CFO)</button>}
                </td>
              </tr>
            ))}
            {rates.length === 0 && <tr><td {...stylex.props(styles.td)} colSpan={10} style={{ color: colors.muted }}>Load a jurisdiction's rates.</td></tr>}
          </tbody>
        </table>
        <div {...stylex.props(styles.section)} style={{ marginTop: '1rem' }}>Propose a rate (tax specialist) → CFO activates</div>
        <div {...stylex.props(styles.row)}>
          <input {...stylex.props(styles.input)} data-testid="tax-pr-juris" value={pr.jurisdiction} onChange={(e) => setPr({ ...pr, jurisdiction: e.target.value })} placeholder="juris" style={{ width: '60px' }} />
          <input {...stylex.props(styles.input)} data-testid="tax-pr-type" value={pr.tax_type} onChange={(e) => setPr({ ...pr, tax_type: e.target.value })} placeholder="type" style={{ width: '80px' }} />
          <input {...stylex.props(styles.input)} data-testid="tax-pr-region" value={pr.region} onChange={(e) => setPr({ ...pr, region: e.target.value })} placeholder="region" style={{ width: '80px' }} />
          <input {...stylex.props(styles.input)} data-testid="tax-pr-level" value={pr.level} onChange={(e) => setPr({ ...pr, level: e.target.value })} placeholder="level" style={{ width: '90px' }} />
          <input {...stylex.props(styles.input)} data-testid="tax-pr-name" value={pr.name} onChange={(e) => setPr({ ...pr, name: e.target.value })} placeholder="name" style={{ width: '120px' }} />
          <input {...stylex.props(styles.input)} data-testid="tax-pr-rate" value={pr.rate_pct} onChange={(e) => setPr({ ...pr, rate_pct: e.target.value })} placeholder="rate" style={{ width: '60px' }} />
          <input {...stylex.props(styles.input)} data-testid="tax-pr-from" value={pr.effective_from} onChange={(e) => setPr({ ...pr, effective_from: e.target.value })} placeholder="from" style={{ width: '110px' }} />
          <button {...stylex.props(styles.button)} data-testid="tax-propose-btn" onClick={propose}>Propose</button>
          {proposeStatus && <span {...stylex.props(styles.label)} data-testid="tax-propose-status">{proposeStatus}</span>}
        </div>
      </div>

      {/* ---- nexus board + provider routing (config, not code) ---- */}
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.section)}>Provider routing — which engine serves a market (flipping to a vendor is a row)</div>
        <div {...stylex.props(styles.row)}>
          <button {...stylex.props(styles.button)} data-testid="tax-routing-load" onClick={loadRouting}>Load routing</button>
          <button {...stylex.props(styles.button)} data-testid="tax-nexus-load" onClick={loadNexus}>Load nexus board</button>
        </div>
        <table {...stylex.props(styles.table)} data-testid="tax-routing-table">
          <thead><tr><th {...stylex.props(styles.th)}>Jurisdiction</th><th {...stylex.props(styles.th)}>Tax type</th><th {...stylex.props(styles.th)}>Provider</th><th {...stylex.props(styles.th, styles.num)}>Priority</th></tr></thead>
          <tbody>
            {routing.map((r, i) => (
              <tr key={i} data-testid="tax-routing-row">
                <td {...stylex.props(styles.td)}>{r.jurisdiction ?? 'all'}</td>
                <td {...stylex.props(styles.td)}>{r.tax_type ?? 'all'}</td>
                <td {...stylex.props(styles.td)}>{r.provider}</td>
                <td {...stylex.props(styles.td, styles.num)}>{r.priority}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {nexus.length > 0 && (
          <table {...stylex.props(styles.table)} data-testid="tax-nexus-table" style={{ marginTop: '1rem' }}>
            <thead><tr><th {...stylex.props(styles.th)}>Jurisdiction</th><th {...stylex.props(styles.th)}>Region</th><th {...stylex.props(styles.th, styles.num)}>Sales</th><th {...stylex.props(styles.th, styles.num)}>Threshold</th><th {...stylex.props(styles.th)}>Status</th></tr></thead>
            <tbody>
              {nexus.map((n, i) => (
                <tr key={i} data-testid="tax-nexus-row">
                  <td {...stylex.props(styles.td)}>{n.jurisdiction}</td>
                  <td {...stylex.props(styles.td)}>{n.region}</td>
                  <td {...stylex.props(styles.td, styles.num)}>{n.sales_to_date ?? '—'}</td>
                  <td {...stylex.props(styles.td, styles.num)}>{n.threshold_amount ?? '—'}</td>
                  <td {...stylex.props(styles.td)}>{n.status}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
