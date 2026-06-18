import React from 'react';
import { useApi } from './lib/query';
import { PageHead, Card, Chip, num } from './kit/kit';
import { I } from './kit/icons';

// M12-Treasury desk: the provider-agnostic FX hedging program (Ebury today) + the economic effectiveness stream
// (hedged vs counterfactual all-spot). Reads /treasury/program and /treasury/effectiveness — all real data.

interface Facility { creditLimit?: number; limitCurrency?: string; pairFrom?: string; pairTo?: string; marginCallPct?: number; marginVariationPct?: number; openedOn?: string }
interface Policy { exposureType?: string; hedgeRatio?: number; paymentTermsDays?: number }
interface Contract { contractNo?: string; status?: string; contractedRate?: number; notional?: number; hedgeRatio?: number; supplier?: string; validFrom?: string; validTo?: string }
interface Program { facility?: Facility | null; policy?: Policy[]; contracts?: Contract[]; coverage?: { exposure_usd?: number; hedged_notional_gbp?: number } }
interface EffRow { periodMonth?: string; supplier?: string; exposureUsd?: number; hedgeRatio?: number; hedgeRate?: number; spotRate?: number; effectiveRate?: number; hedgedGbp?: number; spotGbp?: number; savingGbp?: number; contractNo?: string }
interface Eff { rows?: EffRow[]; total_saving_gbp?: number }

const V2G = '#14b8a6';
const gbp0 = (n?: number | null) => (n == null ? '—' : '£' + num(Math.round(n)));
const stat = (label: string, value: React.ReactNode, tone?: string, note?: string) => (
  <Card>
    <div className="muted" style={{ fontSize: 'var(--fs-small)' }}>{label}</div>
    <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600, marginTop: 3, color: tone }}>{value}</div>
    {note && <div className="dim" style={{ fontSize: 'var(--fs-xs)' }}>{note}</div>}
  </Card>
);
const std = (xs: number[]) => {
  if (xs.length < 2) return 0;
  const m = xs.reduce((a, b) => a + b, 0) / xs.length;
  return Math.sqrt(xs.reduce((a, b) => a + (b - m) ** 2, 0) / xs.length);
};

export function Treasury(_props: { role?: { layers?: string[] } }) {
  const progApi = useApi<Program>(['treasury-program'], '/api/v1/treasury/program');
  const effApi = useApi<Eff>(['treasury-effectiveness'], '/api/v1/treasury/effectiveness');
  const prog = progApi.data ?? null;
  const eff = effApi.data ?? null;
  const fac = prog?.facility ?? null;
  const contracts = prog?.contracts ?? [];
  const policy = prog?.policy ?? [];
  const cov = prog?.coverage ?? null;
  const effRows = eff?.rows ?? [];

  const effVol = std(effRows.map((r) => r.effectiveRate ?? 0));
  const spotVol = std(effRows.map((r) => r.spotRate ?? 0));
  const volCut = spotVol > 0 ? (1 - effVol / spotVol) * 100 : 0;
  const totalSaving = eff?.total_saving_gbp ?? 0;

  const statusTone = (s?: string) => (s === 'executed' || s === 'extended' ? 'ok' : s === 'proposed' ? 'warn' : 'neutral');

  return (
    <div className="page" style={{ maxWidth: 1320 }}>
      <PageHead
        crumb="Treasury · M12 · doc 28 / Ebury USD FX hedging policy"
        title="FX Hedging"
        sub={
          <span style={{ display: 'block', maxWidth: 880 }}>
            The provider-agnostic GBP/USD hedging program protecting the USD payables to the contract manufacturer
            (Volex, Luxshare from Dec&nbsp;2026). Ebury is the current provider. Effectiveness is measured against a
            counterfactual all-spot valuation — the hedge buys <b>rate stability</b>, not guaranteed savings.
          </span>
        }
      />

      {/* headline */}
      <div className="grid" style={{ gridTemplateColumns: 'repeat(4, 1fr)', gap: 12, marginBottom: 14 }}>
        {stat('Facility', fac ? `${fac.limitCurrency} ${num(Math.round((fac.creditLimit ?? 0) / 1000))}k` : '—', undefined, fac ? `${fac.pairFrom}/${fac.pairTo} · ${Math.round((fac.marginCallPct ?? 0) * 100)}% margin call` : '')}
        {stat('Open contracts', String(contracts.filter((c) => c.status === 'executed' || c.status === 'extended').length), undefined, `${contracts.length} total`)}
        {stat('Hedge contribution', gbp0(totalSaving), totalSaving >= 0 ? 'var(--ok)' : 'var(--warn)', 'vs all-spot, executed months')}
        {stat('Volatility cut', `${volCut.toFixed(0)}%`, V2G, 'effective vs spot rate')}
      </div>

      <div className="grid" style={{ gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 14, alignItems: 'start' }}>
        {/* policy */}
        <Card title="Hedging policy" icon={I.shield} aux="ratio per exposure type">
          {progApi.isLoading ? (
            <div className="skel skel-line" style={{ height: 80 }} />
          ) : (
            <div className="row g16" style={{ flexWrap: 'wrap' }}>
              {policy.map((p) => (
                <div key={p.exposureType} style={{ minWidth: 140 }}>
                  <div style={{ fontFamily: 'var(--font-disp)', fontSize: 22, fontWeight: 600 }}>{Math.round((p.hedgeRatio ?? 0) * 100)}%</div>
                  <div className="dim" style={{ fontSize: 'var(--fs-xs)' }}>{p.exposureType}{p.paymentTermsDays ? ` · ${p.paymentTermsDays}d terms` : ''}</div>
                </div>
              ))}
              {policy.length === 0 && <span className="dim">No policy configured.</span>}
            </div>
          )}
        </Card>
        {/* coverage */}
        <Card title="Exposure & coverage" icon={I.scale} aux="forecast USD payables vs hedged notional">
          <div className="row" style={{ gap: 36, flexWrap: 'wrap', alignItems: 'flex-start' }}>
            <div style={{ minWidth: 150 }}>
              <div style={{ fontFamily: 'var(--font-disp)', fontSize: 24, fontWeight: 600, lineHeight: 1.1 }}>${num(Math.round((cov?.exposure_usd ?? 0) / 1000))}k</div>
              <div className="dim" style={{ fontSize: 'var(--fs-xs)', marginTop: 5 }}>forecast USD exposure</div>
            </div>
            <div style={{ minWidth: 150 }}>
              <div style={{ fontFamily: 'var(--font-disp)', fontSize: 24, fontWeight: 600, lineHeight: 1.1, color: V2G }}>{gbp0(cov?.hedged_notional_gbp)}</div>
              <div className="dim" style={{ fontSize: 'var(--fs-xs)', marginTop: 5 }}>open hedged notional</div>
            </div>
          </div>
        </Card>
      </div>

      {/* contracts */}
      <Card title="Hedge contracts" icon={I.list} aux="forward contracts on the facility" style={{ padding: 0, marginBottom: 14 }} className="tablewrap">
        <table className="tbl">
          <thead><tr><th>Contract</th><th>Supplier</th><th>Status</th><th className="num">Rate</th><th className="num">Notional</th><th className="num">Ratio</th><th>Window from</th><th>Window to</th></tr></thead>
          <tbody>
            {contracts.map((c) => (
              <tr key={c.contractNo}>
                <td>{c.contractNo}</td>
                <td className="dim">{c.supplier}</td>
                <td><Chip s={statusTone(c.status)}>{c.status}</Chip></td>
                <td className="num mono">{c.contractedRate}</td>
                <td className="num">{gbp0(c.notional)}</td>
                <td className="num">{Math.round((c.hedgeRatio ?? 0) * 100)}%</td>
                <td className="dim mono" style={{ fontSize: 'var(--fs-small)' }}>{c.validFrom}</td>
                <td className="dim mono" style={{ fontSize: 'var(--fs-small)' }}>{c.validTo}</td>
              </tr>
            ))}
            {contracts.length === 0 && <tr><td colSpan={7}><span className="dim">No contracts.</span></td></tr>}
          </tbody>
        </table>
      </Card>

      {/* effectiveness */}
      <Card title="Hedge effectiveness" icon={I.trend} aux="hedged vs counterfactual all-spot — measurement only, never posted" style={{ padding: 0 }} className="tablewrap">
        <table className="tbl">
          <thead><tr><th>Month</th><th className="num">Exposure $</th><th className="num">Spot</th><th className="num">Hedge</th><th className="num">Effective</th><th className="num">Hedged £</th><th className="num">Spot £</th><th className="num">Saving £</th></tr></thead>
          <tbody>
            {effRows.map((r) => (
              <tr key={r.periodMonth}>
                <td>{(r.periodMonth ?? '').slice(0, 7)}</td>
                <td className="num">${num(Math.round(r.exposureUsd ?? 0))}</td>
                <td className="num mono">{r.spotRate}</td>
                <td className="num mono">{r.hedgeRate}</td>
                <td className="num mono">{Number(r.effectiveRate).toFixed(4)}</td>
                <td className="num">{gbp0(r.hedgedGbp)}</td>
                <td className="num">{gbp0(r.spotGbp)}</td>
                <td className="num" style={{ color: (r.savingGbp ?? 0) >= 0 ? 'var(--ok)' : 'var(--warn)' }}>{gbp0(r.savingGbp)}</td>
              </tr>
            ))}
            {effRows.length === 0 && <tr><td colSpan={8}><span className="dim">No executed-contract months yet.</span></td></tr>}
          </tbody>
        </table>
        <div className="layer-note" style={{ padding: '10px 16px' }}>
          <I.shield />Over the executed period the lock netted <b>{gbp0(totalSaving)}</b> vs spot, but cut effective-rate
          volatility by <b style={{ color: V2G }}>~{volCut.toFixed(0)}%</b> — the protection is rate stability. This stream is a
          measurement (real ECB spot via the FX register); the books carry hedged COGS + the ASC-815 MTM.
        </div>
      </Card>
    </div>
  );
}
