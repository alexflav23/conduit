import React, { useState } from 'react';
import * as stylex from '@stylexjs/stylex';
import { colors } from './styles/tokens.stylex';
import { getContractManufacturers, getSupplyCommitments, getProposals, getSupplyWarnings, approvePo } from './api';

// The Supply window desk (design spec doc 20 §2.4): the firm-commitment horizon (frozen/flex/free), the auto-PO
// proposals (auto-fill within headroom + blocked remainder), and the divergence warnings — per contract manufacturer.

const styles = stylex.create({
  card: { backgroundColor: colors.surface, border: `1px solid ${colors.border}`, borderRadius: '14px', padding: '1.25rem', marginBottom: '1.25rem', maxWidth: '980px' },
  section: { fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.06em', color: colors.muted, marginBottom: '0.6rem' },
  row: { display: 'flex', gap: '0.75rem', alignItems: 'center', marginBottom: '0.6rem', flexWrap: 'wrap' },
  input: { backgroundColor: colors.bg, color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.45rem 0.6rem', fontSize: '0.9rem' },
  button: { backgroundColor: colors.accent, color: '#fff', border: 'none', borderRadius: '10px', padding: '0.5rem 1.05rem', fontSize: '0.92rem', fontWeight: 600, cursor: 'pointer' },
  approve: { backgroundColor: colors.ok, color: '#06210f', border: 'none', borderRadius: '8px', padding: '0.3rem 0.7rem', fontWeight: 700, fontSize: '0.78rem', cursor: 'pointer' },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '0.88rem', fontVariantNumeric: 'tabular-nums' },
  th: { textAlign: 'left', color: colors.muted, fontSize: '0.72rem', textTransform: 'uppercase', letterSpacing: '0.05em', padding: '0.45rem 0.6rem', borderBottom: `1px solid ${colors.border}` },
  td: { padding: '0.4rem 0.6rem', borderBottom: `1px solid ${colors.border}` },
  num: { textAlign: 'right' },
  zoneFrozen: { color: '#7aa2ff', fontWeight: 700 },
  zoneFlex: { color: colors.warn, fontWeight: 700 },
  zoneFree: { color: colors.ok, fontWeight: 700 },
  blocked: { color: colors.warn, fontWeight: 700 },
});

const zoneStyle = (z: string) => z === 'frozen' ? styles.zoneFrozen : z === 'flex' ? styles.zoneFlex : styles.zoneFree;

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
    <div>
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.row)}>
          <button {...stylex.props(styles.button)} data-testid="supply-load" onClick={init}>Load supply window</button>
          {supplier && (
            <select {...stylex.props(styles.input)} data-testid="supply-cm" value={supplier} onChange={(e) => { setSupplier(e.target.value); load(e.target.value); }}>
              {cms.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          )}
          {error && <span data-testid="supply-error" style={{ color: colors.warn }}>{error}</span>}
        </div>

        <div {...stylex.props(styles.section)}>Firm-commitment horizon — frozen (can't move) · flex (±tolerance) · free</div>
        <table {...stylex.props(styles.table)} data-testid="supply-commitments">
          <thead><tr><th {...stylex.props(styles.th)}>SKU</th><th {...stylex.props(styles.th)}>Week</th><th {...stylex.props(styles.th, styles.num)}>Firm PO</th><th {...stylex.props(styles.th)}>Zone</th></tr></thead>
          <tbody>
            {commitments.map((c, i) => (
              <tr key={i} data-testid="supply-commit-row">
                <td {...stylex.props(styles.td)}>{c.sku}</td>
                <td {...stylex.props(styles.td)}>{c.target_date}</td>
                <td {...stylex.props(styles.td, styles.num)}>{c.qty}</td>
                <td {...stylex.props(styles.td, zoneStyle(c.zone))}>{c.zone}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.section)}>Auto-PO proposals — proposed within headroom; blocked = needs escalation</div>
        <table {...stylex.props(styles.table)} data-testid="supply-proposals">
          <thead><tr>
            <th {...stylex.props(styles.th)}>SKU</th><th {...stylex.props(styles.th)}>Week</th>
            <th {...stylex.props(styles.th, styles.num)}>Demand</th><th {...stylex.props(styles.th, styles.num)}>Committed</th>
            <th {...stylex.props(styles.th, styles.num)}>Net need</th><th {...stylex.props(styles.th, styles.num)}>Proposed</th>
            <th {...stylex.props(styles.th, styles.num)}>Blocked</th><th {...stylex.props(styles.th)}>Zone</th><th {...stylex.props(styles.th)}></th>
          </tr></thead>
          <tbody>
            {proposals.map((p, i) => (
              <tr key={i} data-testid="supply-proposal-row">
                <td {...stylex.props(styles.td)}>{p.sku}</td>
                <td {...stylex.props(styles.td)}>{p.target_date}</td>
                <td {...stylex.props(styles.td, styles.num)}>{p.demand}</td>
                <td {...stylex.props(styles.td, styles.num)}>{p.committed}</td>
                <td {...stylex.props(styles.td, styles.num)}>{p.net_need}</td>
                <td {...stylex.props(styles.td, styles.num)}>{p.proposed_delta}</td>
                <td {...stylex.props(styles.td, styles.num)} {...(p.blocked_qty > 0 ? stylex.props(styles.td, styles.num, styles.blocked) : {})}>{p.blocked_qty > 0 ? `⚠ ${p.blocked_qty}` : '0'}</td>
                <td {...stylex.props(styles.td, zoneStyle(p.zone))}>{p.zone}</td>
                <td {...stylex.props(styles.td)}>{p.status === 'proposed' && p.proposed_delta > 0 && <button {...stylex.props(styles.approve)} data-testid="supply-approve" onClick={() => approve(p.product_variant_id, p.target_date)}>Approve</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.section)}>Divergence warnings — sales/automated demand vs a firm PO that can't move</div>
        <table {...stylex.props(styles.table)} data-testid="supply-warnings">
          <thead><tr><th {...stylex.props(styles.th)}>SKU</th><th {...stylex.props(styles.th)}>Zone</th><th {...stylex.props(styles.th, styles.num)}>Committed</th><th {...stylex.props(styles.th, styles.num)}>Demand</th><th {...stylex.props(styles.th)}>Severity</th><th {...stylex.props(styles.th)}>Message</th></tr></thead>
          <tbody>
            {warnings.map((w, i) => (
              <tr key={i} data-testid="supply-warning-row">
                <td {...stylex.props(styles.td)}>{w.sku}</td>
                <td {...stylex.props(styles.td, zoneStyle(w.zone))}>{w.zone}</td>
                <td {...stylex.props(styles.td, styles.num)}>{w.committed}</td>
                <td {...stylex.props(styles.td, styles.num)}>{w.demand}</td>
                <td {...stylex.props(styles.td, styles.blocked)}>{w.severity}</td>
                <td {...stylex.props(styles.td)}>{w.message}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
