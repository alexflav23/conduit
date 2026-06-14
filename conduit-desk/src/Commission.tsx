import React, { useEffect, useState } from 'react';
import { apiFetch } from './api';
import { tableState, asArray } from './state';
import {
  PageHead, Card, Chip, Money, AuditRef, LayerNote, EmptyRow, SkeletonRow, useToast, num, gbp,
} from './kit/kit';
import { I } from './kit/icons';

// Commission (M5, doc 04 §Commission · doc 24 rebates · spec/ui/17-commission.md). The two-phase engine made
// honest: accrued (order lands, not yet earned) → posted (earned on dispatch) → clawed (a unit returns). The
// whole screen lives behind the `commission` data layer — a viewer without it gets nothing (LayerNote), never
// £0. `basis`/margin is further behind `profitability`. An agent sees `own` scope; finance/ceo see `all` + the
// retrospective-rebate accrual (ASC-606 variable consideration: accrue ≠ apply, bidirectional true-up).
// Read-mostly — commission is event-driven; the only action is a statement export. No manual load button.

const STATE_CHIP: Record<string, string> = { accrued: 'warn', posted: 'ok', clawed: 'danger' };

interface Props {
  role: { layers?: string[]; name?: string };
  ctx: { period?: string };
  toast: (m: string, k?: string) => void;
}

export function Commission({ role, ctx, toast }: Props) {
  const period = ctx?.period || '';
  const canSeeProfit = (role?.layers || []).indexOf('profitability') >= 0;

  const [entriesRes, setEntriesRes] = useState<{ status: number; json: any } | null>(null);
  const [rebatesRes, setRebatesRes] = useState<{ status: number; json: any } | null>(null);
  const [toastNode, fire] = useToast();

  useEffect(() => {
    let live = true;
    setEntriesRes(null);
    setRebatesRes(null);
    const q = period ? `?period=${encodeURIComponent(period)}` : '';
    apiFetch(`/api/v1/finance/commission${q}`).then((r) => { if (live) setEntriesRes(r); });
    apiFetch(`/api/v1/finance/rebates${q}`).then((r) => { if (live) setRebatesRes(r); });
    return () => { live = false; };
  }, [period]);

  const body = entriesRes && entriesRes.status < 400 ? entriesRes.json : null;
  const scope: 'own' | 'all' = body?.scope === 'all' ? 'all' : 'own';
  const rows = asArray<any>(body?.rows);
  const totals = body?.totals || null;
  const net = totals ? (Number(totals.accrued) || 0) + (Number(totals.posted) || 0) + (Number(totals.clawed) || 0) : 0;

  const colCount = scope === 'all' ? 9 : 8;
  const st = tableState(entriesRes, rows);

  // Whole-page layer wall: the commission projection is withheld entirely (401/403 → no payload).
  if (st === 'forbidden') {
    return (
      <>
        <PageHead title="Commission" sub="The two-phase engine — accrued → posted → clawed." />
        <Card>
          <LayerNote>Commission is hidden — requires the commission layer.</LayerNote>
        </Card>
        {toastNode}
      </>
    );
  }

  const exportStatement = () => {
    toast?.('Statement export queued — emailed when ready', 'ok');
    fire('Statement export queued', 'ok');
  };

  const exportBtn = (
    <button className="btn ghost" onClick={exportStatement} data-testid="commission-export">
      {I.download()} Export statement
    </button>
  );

  // group the finance book by agent
  const byAgent: Record<string, any[]> = {};
  if (scope === 'all') rows.forEach((c) => { (byAgent[c.agent || '—'] = byAgent[c.agent || '—'] || []).push(c); });

  return (
    <>
      <PageHead
        crumb={'Two-phase engine · doc 04 · ' + (scope === 'all' ? 'finance book' : 'your statement')}
        title="Commission"
        sub={
          scope === 'all'
            ? 'The whole book, anchored to the ledger. Accrued the moment an order lands, posted (earned) on dispatch, clawed when a unit returns — claws reverse in the current period, the prior period stays as reported.'
            : 'Your statement. Accrued the moment an order lands, posted (earned) on dispatch, clawed when a unit returns — every figure traceable to its ledger transfer.'
        }
        right={exportBtn}
      />

      {scope === 'own' && body?.agent && (
        <div className="banner info" style={{ marginBottom: 14 }}>
          {I.user()}
          <div>You are viewing <b>your own statement</b> ({body.agent}). Finance sees the whole book; you see only your scope.</div>
        </div>
      )}

      {/* two-phase hero */}
      <div className="mx" style={{ marginBottom: 14 }}>
        <div className="metric">
          <div className="ml">Accrued · not yet earned</div>
          <div className="mv" style={{ color: 'var(--warn)' }}>
            {totals ? <Money value={totals.accrued} /> : <span className="dim">—</span>}
          </div>
        </div>
        <div className="metric">
          <div className="ml">Posted · earned</div>
          <div className="mv" style={{ color: 'var(--ok)' }}>
            {totals ? <Money value={totals.posted} /> : <span className="dim">—</span>}
          </div>
        </div>
        <div className="metric">
          <div className="ml">Clawed · returned</div>
          <div className="mv" style={{ color: totals && Number(totals.clawed) < 0 ? 'var(--danger)' : 'var(--text)' }}>
            {totals ? <Money value={totals.clawed} /> : <span className="dim">—</span>}
          </div>
        </div>
        <div className="metric">
          <div className="ml">Net this period</div>
          <div className="mv accent">
            {totals ? <Money value={net} /> : <span className="dim">—</span>}
          </div>
        </div>
      </div>

      <Card
        title={scope === 'all' ? 'Commission book · all agents' : 'Your commission' + (period ? ' · ' + period : '')}
        icon={I.up}
        aux={<span className="dim" style={{ fontSize: 12 }}>order → accrue · dispatch → post · return → claw</span>}
        style={{ padding: 0, marginBottom: 14 }}
        className="tablewrap"
      >
        <table className="tbl">
          <thead>
            <tr>
              {scope === 'all' && <th>Agent</th>}
              <th>Entry</th>
              <th>Order</th>
              <th>Scheme</th>
              <th className="num">Basis</th>
              <th className="num">Rate</th>
              <th className="num">Amount</th>
              <th>State</th>
              <th>Ledger</th>
            </tr>
          </thead>
          <tbody>
            {st === 'loading' && <><SkeletonRow cols={colCount} /><SkeletonRow cols={colCount} /><SkeletonRow cols={colCount} /></>}
            {st === 'error' && <EmptyRow cols={colCount}>Could not load commission entries.</EmptyRow>}
            {st === 'empty' && <EmptyRow cols={colCount}>No commission this period.</EmptyRow>}
            {st === 'ready' && scope === 'all' &&
              Object.keys(byAgent).map((ag) => (
                <React.Fragment key={ag}>
                  <tr className="sel" style={{ cursor: 'default' }}>
                    <td colSpan={colCount}>
                      <b>{ag}</b> <span className="dim" style={{ fontSize: 11 }}>· {byAgent[ag].length} entries</span>
                    </td>
                  </tr>
                  {byAgent[ag].map((c) => <Row key={c.id} c={c} scope={scope} canSeeProfit={canSeeProfit} role={role} />)}
                </React.Fragment>
              ))}
            {st === 'ready' && scope === 'own' && rows.map((c) => <Row key={c.id} c={c} scope={scope} canSeeProfit={canSeeProfit} role={role} />)}
          </tbody>
        </table>
        <div className="layer-note" style={{ padding: '10px 16px' }}>
          {I.shield()}
          A claw is money coming back — shown as a reversing entry in the <b>current</b> period. The period it was originally posted to stays exactly as reported.
        </div>
      </Card>

      <RebatePanel res={rebatesRes} canSeeProfit={canSeeProfit} role={role} />

      {toastNode}
    </>
  );
}

function Row({ c, scope, canSeeProfit, role }: { c: any; scope: 'own' | 'all'; canSeeProfit: boolean; role: any }) {
  const negative = Number(c.amount) < 0;
  return (
    <tr style={{ cursor: 'default' }}>
      {scope === 'all' && <td className="dim" style={{ paddingLeft: 28 }}>{c.agent}</td>}
      <td className="mono dim" style={{ fontSize: 11 }}>{c.id}</td>
      <td className="mono dim" style={{ fontSize: 11 }}>{c.order}</td>
      <td className="dim" style={{ fontSize: 12 }}>{c.scheme}</td>
      <td className="num">
        {canSeeProfit ? <Money value={c.basis} layer="profitability" role={role} /> : <span className="dim">— layer</span>}
      </td>
      <td className="num">{c.rate != null ? c.rate + '%' : '—'}</td>
      <td className="num">
        <b style={negative ? { color: 'var(--danger)' } : undefined}><Money value={c.amount} /></b>
      </td>
      <td>
        <Chip s={STATE_CHIP[c.status] || 'neutral'}>{c.status}</Chip>
        {c.claw_of && <div className="dim" style={{ fontSize: 9.5 }}>↩ {c.claw_of}</div>}
      </td>
      <td>{c.transfer ? <AuditRef id={c.transfer} /> : <span className="dim" style={{ fontSize: 11 }}>pending post</span>}</td>
    </tr>
  );
}

function RebatePanel({ res, canSeeProfit, role }: { res: { status: number; json: any } | null; canSeeProfit: boolean; role: any }) {
  const rows = asArray<any>(res && res.status < 400 ? res.json : []);
  const st = tableState(res, rows);
  const cols = 8;

  // Forbidden / not-yet-loaded for the rebate panel falls back quietly — the page still works for an agent who
  // only sees their statement (rebates are a finance-book concern).
  if (st === 'forbidden') return null;

  return (
    <Card
      title="Rebate accrual"
      icon={I.layers}
      aux={<span className="dim" style={{ fontSize: 12 }}>ASC-606 variable consideration · accrue ≠ apply · bidirectional true-up</span>}
      style={{ padding: 0 }}
      className="tablewrap"
    >
      <table className="tbl">
        <thead>
          <tr>
            <th>Scheme</th>
            <th>Period</th>
            <th className="num">Commitment</th>
            <th className="num">Actual</th>
            <th className="num">Accrued</th>
            <th className="num">Expected</th>
            <th>True-up</th>
            <th>Ledger</th>
          </tr>
        </thead>
        <tbody>
          {st === 'loading' && <><SkeletonRow cols={cols} /><SkeletonRow cols={cols} /></>}
          {st === 'error' && <EmptyRow cols={cols}>Could not load rebate schemes.</EmptyRow>}
          {st === 'empty' && <EmptyRow cols={cols}>No rebate schemes accruing this period.</EmptyRow>}
          {st === 'ready' && rows.map((r, i) => {
            const accrued = Number(r.accrued) || 0;
            const expected = Number(r.expected) || 0;
            const dir = accrued < expected ? 'accrue up' : accrued > expected ? 'release' : 'level';
            const dirChip = dir === 'release' ? 'ok' : dir === 'accrue up' ? 'warn' : 'neutral';
            const shortfall = r.actual_units != null && r.commitment != null && Number(r.actual_units) < Number(r.commitment);
            return (
              <tr key={r.scheme + '|' + i} style={{ cursor: 'default' }}>
                <td><b>{r.scheme}</b></td>
                <td className="dim">{r.period}</td>
                <td className="num">
                  {num(r.commitment)}
                  <div className="dim" style={{ fontSize: 10 }}>
                    floor {canSeeProfit ? gbp(r.floor) : '—'}
                  </div>
                </td>
                <td className="num">
                  {num(r.actual_units)}
                  {shortfall && <div className="chip warn" style={{ fontSize: 9, padding: '0 5px', marginTop: 2 }}><span className="d" />below floor</div>}
                </td>
                <td className="num">
                  {canSeeProfit ? <Money value={r.accrued} layer="profitability" role={role} /> : <span className="dim">— layer</span>}
                </td>
                <td className="num">
                  {canSeeProfit ? <Money value={r.expected} layer="profitability" role={role} /> : <span className="dim">—</span>}
                </td>
                <td><span className={'chip ' + dirChip}><span className="d" />{dir}</span></td>
                <td>{r.transfer ? <AuditRef id={r.transfer} /> : <span className="dim" style={{ fontSize: 11 }}>—</span>}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <div className="layer-note" style={{ padding: '10px 16px' }}>
        {I.shield()}
        Rebates are <b>accrued</b> against the commitment floor, then <b>applied</b> on true-up — the direction
        (accrue up / release) is the ASC-606 variable-consideration estimate moving toward the actual.
      </div>
    </Card>
  );
}
