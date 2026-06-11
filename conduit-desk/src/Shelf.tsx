import React, { useState } from 'react';
import * as stylex from '@stylexjs/stylex';
import { colors } from './styles/tokens.stylex';
import { getShelfBoard } from './api';

// The Shelf desk (design spec doc 20 §2.5): real-time per-account stock from the serial register —
// shipped / activated / on-shelf, attributed by Conduit at dispatch (no MRPeasy). On-shelf falls live as the
// activation stream consumes serials. Mirrors ghost-busters /stock/dashboard, but native and authoritative.

const styles = stylex.create({
  card: { backgroundColor: colors.surface, border: `1px solid ${colors.border}`, borderRadius: '14px', padding: '1.25rem', marginBottom: '1.25rem', maxWidth: '820px' },
  section: { fontSize: '0.8rem', textTransform: 'uppercase', letterSpacing: '0.06em', color: colors.muted, marginBottom: '0.6rem' },
  row: { display: 'flex', gap: '0.75rem', alignItems: 'center', marginBottom: '0.6rem' },
  button: { backgroundColor: colors.accent, color: '#fff', border: 'none', borderRadius: '10px', padding: '0.5rem 1.05rem', fontSize: '0.92rem', fontWeight: 600, cursor: 'pointer' },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '0.9rem', fontVariantNumeric: 'tabular-nums' },
  th: { textAlign: 'left', color: colors.muted, fontSize: '0.72rem', textTransform: 'uppercase', letterSpacing: '0.05em', padding: '0.45rem 0.7rem', borderBottom: `1px solid ${colors.border}` },
  num: { textAlign: 'right' },
  td: { padding: '0.4rem 0.7rem', borderBottom: `1px solid ${colors.border}` },
  shelf: { fontWeight: 700, color: colors.accent },
  label: { color: colors.muted, fontSize: '0.8rem' },
});

export function Shelf({ token }: { token: string }) {
  const [rows, setRows] = useState<any[]>([]);
  const load = async () => { const r = await getShelfBoard(token); setRows(Array.isArray(r.json) ? r.json : []); };

  return (
    <div {...stylex.props(styles.card)}>
      <div {...stylex.props(styles.row)}>
        <button {...stylex.props(styles.button)} data-testid="shelf-load" onClick={load}>Load shelf board</button>
        <span {...stylex.props(styles.label)}>shipped − activated = on-shelf, live per account</span>
      </div>
      <div {...stylex.props(styles.section)}>Per-account stock (real-time, serial-attributed by Conduit at dispatch)</div>
      <table {...stylex.props(styles.table)} data-testid="shelf-board">
        <thead><tr>
          <th {...stylex.props(styles.th)}>Account</th>
          <th {...stylex.props(styles.th, styles.num)}>Shipped</th>
          <th {...stylex.props(styles.th, styles.num)}>Activated</th>
          <th {...stylex.props(styles.th, styles.num)}>On-shelf</th>
        </tr></thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i} data-testid="shelf-row">
              <td {...stylex.props(styles.td)}>{r.name ?? (r.company_id ?? '').slice(0, 8)}</td>
              <td {...stylex.props(styles.td, styles.num)}>{r.shipped}</td>
              <td {...stylex.props(styles.td, styles.num)}>{r.activated}</td>
              <td {...stylex.props(styles.td, styles.num, styles.shelf)}>{r.on_shelf}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
