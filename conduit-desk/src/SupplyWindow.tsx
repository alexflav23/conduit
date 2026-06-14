import React, { useState } from 'react';
import { getContractManufacturers, getSupplyCommitments, getProposals, getSupplyWarnings, approvePo } from './api';
import { PageHead, Card, ZoneTag } from './kit/kit';
import { I } from './kit/icons';

// The Supply window desk (design spec doc 20 §2.4 / spec/ui/07-supply.md): the firm-commitment horizon
// (frozen/flex/free), the auto-PO proposals (auto-fill within headroom + blocked remainder), and the
// divergence warnings — per contract manufacturer. Ported to the desk kit (.tbl + ZoneTag), testids preserved.

export function SupplyWindow({ token }: { token: string }) {
  const [cms, setCms] = useState<any[]>([]);
  const [supplier, setSupplier] = useState<string>('');
  const [commitments, setCommitments] = useState<any[]>([]);
  const [proposals, setProposals] = useState<any[]>([]);
  const [warnings, setWarnings] = useState<any[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = async (sup: string) => {
    setError(null);
    const [c, p, w] = await Promise.all([getSupplyCommitments(token, sup), getProposals(token, sup), getSupplyWarnings(token, sup)]);
    setCommitments(c.json ?? []); setProposals(p.json ?? []); setWarnings(w.json ?? []);
  };
  const init = async () => {
    setError(null);
    const s = await getContractManufacturers(token);
    setCms(s.json ?? []);
    if ((s.json ?? []).length) { setSupplier(s.json[0].id); await load(s.json[0].id); }
  };
  const approve = async (variant: string, target: string) => {
    const res = await approvePo(token, supplier, variant, target);
    if (res.status === 200) await load(supplier);
    else setError(`Approve failed (${res.status}): ${res.json?.message ?? ''}`);
  };

  return (
    <>
      <PageHead
        title="Supply window"
        sub="Firm-commitment horizon, auto-PO proposals within headroom, and divergence warnings per CM"
        right={
          <div className="row g8">
            <button className="btn primary" data-testid="supply-load" onClick={init}>{I.refresh({ size: 14 })} Load supply window</button>
            {supplier && (
              <select className="fld sel" data-testid="supply-cm" value={supplier} onChange={(e) => { setSupplier(e.target.value); load(e.target.value); }}>
                {cms.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            )}
            {error && <span className="dim" data-testid="supply-error" style={{ color: 'var(--danger)' }}>{error}</span>}
          </div>
        }
      />

      <Card title="Firm-commitment horizon" icon={I.cpu} aux={<span className="dim" style={{ fontSize: 12 }}>frozen (can't move) · flex (±tolerance) · free</span>}>
        <div className="tablewrap">
          <table className="tbl" data-testid="supply-commitments">
            <thead><tr><th>SKU</th><th>Week</th><th className="num">Firm PO</th><th>Zone</th></tr></thead>
            <tbody>
              {commitments.map((c, i) => (
                <tr key={i} data-testid="supply-commit-row">
                  <td><b>{c.sku}</b></td><td>{c.target_date}</td><td className="num">{c.qty}</td><td><ZoneTag zone={c.zone} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <Card title="Auto-PO proposals" icon={I.list} aux={<span className="dim" style={{ fontSize: 12 }}>proposed within headroom; blocked = needs escalation</span>}>
        <div className="tablewrap">
          <table className="tbl" data-testid="supply-proposals">
            <thead><tr>
              <th>SKU</th><th>Week</th><th className="num">Demand</th><th className="num">Committed</th>
              <th className="num">Net need</th><th className="num">Proposed</th><th className="num">Blocked</th><th>Zone</th><th></th>
            </tr></thead>
            <tbody>
              {proposals.map((p, i) => (
                <tr key={i} data-testid="supply-proposal-row">
                  <td><b>{p.sku}</b></td>
                  <td>{p.target_date}</td>
                  <td className="num">{p.demand}</td>
                  <td className="num">{p.committed}</td>
                  <td className="num">{p.net_need}</td>
                  <td className="num">{p.proposed_delta}</td>
                  <td className="num" style={p.blocked_qty > 0 ? { color: 'var(--danger)', fontWeight: 700 } : undefined}>{p.blocked_qty > 0 ? `⚠ ${p.blocked_qty}` : '0'}</td>
                  <td><ZoneTag zone={p.zone} /></td>
                  <td>{p.status === 'proposed' && p.proposed_delta > 0 && <button className="btn primary sm" data-testid="supply-approve" onClick={() => approve(p.product_variant_id, p.target_date)}>Approve</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>

      <Card title="Divergence warnings" icon={I.shield} aux={<span className="dim" style={{ fontSize: 12 }}>sales/automated demand vs a firm PO that can't move</span>}>
        <div className="tablewrap">
          <table className="tbl" data-testid="supply-warnings">
            <thead><tr><th>SKU</th><th>Zone</th><th className="num">Committed</th><th className="num">Demand</th><th>Severity</th><th>Message</th></tr></thead>
            <tbody>
              {warnings.map((w, i) => (
                <tr key={i} data-testid="supply-warning-row">
                  <td><b>{w.sku}</b></td>
                  <td><ZoneTag zone={w.zone} /></td>
                  <td className="num">{w.committed}</td>
                  <td className="num">{w.demand}</td>
                  <td style={{ color: 'var(--danger)', fontWeight: 700 }}>{w.severity}</td>
                  <td className="dim">{w.message}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </>
  );
}
