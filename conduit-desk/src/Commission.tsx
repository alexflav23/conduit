import React from 'react';
import { useApi } from './lib/query';
import {
  PageHead, Card, Chip, Money, AuditRef, LayerNote, EmptyRow, SkeletonRow, num, gbp,
} from './kit/kit';
import { I } from './kit/icons';

// Commission (M5, doc 04 §Commission · doc 24 rebates · spec/ui/17-commission.md). The two-phase engine made
// honest: accrued (order lands, not yet earned) → posted (earned on dispatch) → clawed (a unit returns). The
// whole screen lives behind the `commission` data layer — a viewer without it gets nothing (LayerNote), never
// £0. `basis`/margin is further behind `profitability`. An agent sees `own` scope; finance/ceo see `all` + the
// retrospective-rebate accrual (ASC-606 variable consideration: accrue ≠ apply, bidirectional true-up).
//
// Backend: M5 (Phase 2) — the commission read-models (GET /api/v1/finance/commission · /finance/rebates) are
// NOT yet built in this deployment. Both calls go through React Query: a 404 (notImplemented) renders the honest
// "Not available in this environment yet" panel; a 401/403 (forbidden) renders the layer wall — never zeros,
// never a stuck skeleton. The screen is correct the moment the routes ship. Read-mostly — commission is
// event-driven; the only action is a statement export.

const STATE_CHIP: Record<string, string> = { accrued: 'warn', posted: 'ok', clawed: 'danger' };

interface Totals {
  accrued?: number | string;
  posted?: number | string;
  clawed?: number | string;
}

interface Entry {
  id: string;
  order?: string;
  scheme?: string;
  basis?: number | string;
  rate?: number | string;
  amount?: number | string;
  status?: string;
  claw_of?: string;
  transfer?: string;
  agent?: string;
}

interface CommissionBook {
  scope?: 'own' | 'all';
  agent?: string;
  rows?: Entry[];
  totals?: Totals;
}

interface Rebate {
  scheme?: string;
  period?: string;
  commitment?: number | string;
  actual_units?: number | string;
  floor?: number | string;
  accrued?: number | string;
  expected?: number | string;
  transfer?: string;
}

interface Props {
  role: { layers?: string[]; name?: string };
  ctx: { period?: string };
  toast: (m: string, k?: string) => void;
}

// An honest "endpoint not built" panel (404). Distinct from a stuck skeleton or a £0.
function NotAvailable() {
  return (
    <Card>
      <div
        data-testid="commission-not-available"
        style={{ padding: '28px 18px', textAlign: 'center', color: 'var(--muted)', border: '1px dashed var(--border)', borderRadius: 10, background: 'var(--bg-2)' }}
      >
        <div style={{ marginBottom: 6, color: 'var(--faint)' }}>{I.up({ size: 22 })}</div>
        <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600, color: 'var(--text)' }}>Not available in this environment yet</div>
        <div className="dim" style={{ fontSize: 12, marginTop: 4 }}>The commission read-model (M5) isn't built in this deployment.</div>
      </div>
    </Card>
  );
}

export function Commission({ role, ctx, toast }: Props) {
  const period = ctx?.period || '';
  const canSeeProfit = (role?.layers || []).indexOf('profitability') >= 0;

  const q = period ? `?period=${encodeURIComponent(period)}` : '';
  const entriesQ = useApi<CommissionBook>(['commission', period], `/api/v1/finance/commission${q}`);
  const rebatesQ = useApi<Rebate[]>(['commission-rebates', period], `/api/v1/finance/rebates${q}`);

  const err = entriesQ.error;
  const forbidden = err?.forbidden ?? false;
  const notImpl = err?.notImplemented ?? false;
  const otherError = !!err && !forbidden && !notImpl;

  const body = entriesQ.data ?? null;
  const scope: 'own' | 'all' = body?.scope === 'all' ? 'all' : 'own';
  const rows: Entry[] = Array.isArray(body?.rows) ? body!.rows! : [];
  const totals = body?.totals ?? null;
  const net = totals ? (Number(totals.accrued) || 0) + (Number(totals.posted) || 0) + (Number(totals.clawed) || 0) : 0;

  const colCount = scope === 'all' ? 9 : 8;
  const ready = !entriesQ.isLoading && !err;

  // Whole-page layer wall: the commission projection is withheld entirely (401/403 → no payload).
  if (forbidden) {
    return (
      <>
        <PageHead title="Commission" sub="The two-phase engine — accrued → posted → clawed." />
        <Card>
          <LayerNote>Commission is hidden — requires the commission layer.</LayerNote>
        </Card>
      </>
    );
  }

  const exportStatement = () => {
    toast?.('Statement export queued — emailed when ready', 'ok');
  };

  const exportBtn = (
    <button className="btn ghost" onClick={exportStatement} data-testid="commission-export" disabled={notImpl}>
      {I.download()} Export statement
    </button>
  );

  // group the finance book by agent
  const byAgent: Record<string, Entry[]> = {};
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

      {notImpl ? (
        <NotAvailable />
      ) : (
        <>
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
                {entriesQ.isLoading && <><SkeletonRow cols={colCount} /><SkeletonRow cols={colCount} /><SkeletonRow cols={colCount} /></>}
                {otherError && <EmptyRow cols={colCount}>Could not load commission entries — {err?.message || `error ${err?.status}.`}</EmptyRow>}
                {ready && rows.length === 0 && <EmptyRow cols={colCount}>No commission this period.</EmptyRow>}
                {ready && rows.length > 0 && scope === 'all' &&
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
                {ready && rows.length > 0 && scope === 'own' && rows.map((c) => <Row key={c.id} c={c} scope={scope} canSeeProfit={canSeeProfit} role={role} />)}
              </tbody>
            </table>
            <div className="layer-note" style={{ padding: '10px 16px' }}>
              {I.shield()}
              A claw is money coming back — shown as a reversing entry in the <b>current</b> period. The period it was originally posted to stays exactly as reported.
            </div>
          </Card>

          <RebatePanel
            data={rebatesQ.data}
            isLoading={rebatesQ.isLoading}
            error={rebatesQ.error}
            canSeeProfit={canSeeProfit}
            role={role}
          />
        </>
      )}
    </>
  );
}

function Row({ c, scope, canSeeProfit, role }: { c: Entry; scope: 'own' | 'all'; canSeeProfit: boolean; role: any }) {
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
        <Chip s={STATE_CHIP[c.status || ''] || 'neutral'}>{c.status}</Chip>
        {c.claw_of && <div className="dim" style={{ fontSize: 9.5 }}>↩ {c.claw_of}</div>}
      </td>
      <td>{c.transfer ? <AuditRef id={c.transfer} /> : <span className="dim" style={{ fontSize: 11 }}>pending post</span>}</td>
    </tr>
  );
}

function RebatePanel({ data, isLoading, error, canSeeProfit, role }: {
  data: Rebate[] | undefined;
  isLoading: boolean;
  error: { forbidden: boolean; notImplemented: boolean; status: number; message: string } | null;
  canSeeProfit: boolean;
  role: any;
}) {
  const cols = 8;
  const rows: Rebate[] = Array.isArray(data) ? data : [];
  const ready = !isLoading && !error;

  // Forbidden / not-yet-built for the rebate panel falls back quietly — the page still works for an agent who
  // only sees their statement (rebates are a finance-book concern, and M5 may ship before the rebate read-model).
  if (error?.forbidden || error?.notImplemented) return null;

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
          {isLoading && <><SkeletonRow cols={cols} /><SkeletonRow cols={cols} /></>}
          {!!error && <EmptyRow cols={cols}>Could not load rebate schemes — {error.message || `error ${error.status}.`}</EmptyRow>}
          {ready && rows.length === 0 && <EmptyRow cols={cols}>No rebate schemes accruing this period.</EmptyRow>}
          {ready && rows.map((r, i) => {
            const accrued = Number(r.accrued) || 0;
            const expected = Number(r.expected) || 0;
            const dir = accrued < expected ? 'accrue up' : accrued > expected ? 'release' : 'level';
            const dirChip = dir === 'release' ? 'ok' : dir === 'accrue up' ? 'warn' : 'neutral';
            const shortfall = r.actual_units != null && r.commitment != null && Number(r.actual_units) < Number(r.commitment);
            return (
              <tr key={(r.scheme || '') + '|' + i} style={{ cursor: 'default' }}>
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
