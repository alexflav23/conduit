import React, { useState } from 'react';
import { getShelfBoard } from './api';
import { PageHead, Card } from './kit/kit';
import { I } from './kit/icons';

// The Shelf desk (design spec doc 20 §2.5 / spec/ui/08-shelf.md): real-time per-account stock from the
// serial register — shipped / activated / on-shelf, attributed by Conduit at dispatch (no MRPeasy). On-shelf
// falls live as the activation stream consumes serials. Ported to the desk kit (.tbl), testids preserved.

export function Shelf({ token }: { token: string }) {
  const [rows, setRows] = useState<any[]>([]);
  const load = async () => { const r = await getShelfBoard(token); setRows(Array.isArray(r.json) ? r.json : []); };

  return (
    <>
      <PageHead
        title="Shelf"
        sub="Per-account stock from the serial register — shipped − activated = on-shelf, live"
        right={<button className="btn primary" data-testid="shelf-load" onClick={load}>{I.refresh({ size: 14 })} Load shelf board</button>}
      />
      <Card title="Per-account stock" icon={I.battery} aux={<span className="dim" style={{ fontSize: 12 }}>serial-attributed by Conduit at dispatch</span>}>
        <div className="tablewrap">
          <table className="tbl" data-testid="shelf-board">
            <thead><tr>
              <th>Account</th><th className="num">Shipped</th><th className="num">Activated</th><th className="num">On-shelf</th>
            </tr></thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={i} data-testid="shelf-row">
                  <td><b>{r.name ?? (r.company_id ?? '').slice(0, 8)}</b></td>
                  <td className="num">{r.shipped}</td>
                  <td className="num">{r.activated}</td>
                  <td className="num" style={{ fontWeight: 700, color: 'var(--accent)' }}>{r.on_shelf}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Card>
    </>
  );
}
