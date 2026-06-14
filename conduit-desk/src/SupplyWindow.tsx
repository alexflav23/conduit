import React, { useCallback, useEffect, useState } from 'react';
import {
  authToken,
  getContractManufacturers,
  getSupplyCommitments,
  getProposals,
  getSupplyWarnings,
  approvePo,
} from './api';
import { tableState, asArray, type ApiResult } from './state';
import { PageHead, Card, ZoneTag, Money, EmptyRow, SkeletonRow, LayerNote, useToast } from './kit/kit';
import { I } from './kit/icons';

// Supply window (spec/ui/05-supply.md · doc 20 D11/D12): the contract-manufacturer supply horizon — the
// commitment ladder (firm/flex/indicative zones), auto-PO proposals behind a human approve gate, and loud
// divergence warnings when frozen-window demand moves against a firm PO.
//
// Data-layer wall (doc 05): quantities/zones are `volume`; PO value is `commercial`; CM/entity context may be
// `inter_entity` and COLLAPSES. Auto-load on mount + when ctx changes — no Load/Refresh buttons.

interface Cm {
  id: string;
  name: string;
  currency?: string;
  entity?: string;
}
interface Commitment {
  sku?: string;
  product_variant_id?: string;
  target_date?: string;
  qty?: number;
  po_value?: number | string;
  currency?: string;
  zone?: string;
  version?: number;
  reason?: string;
}
interface Proposal {
  sku?: string;
  product_variant_id?: string;
  target_date?: string;
  demand?: number;
  committed?: number;
  net_need?: number;
  proposed_delta?: number;
  blocked_qty?: number;
  po_value?: number | string;
  currency?: string;
  zone?: string;
  status?: string;
  proposer?: string;
  approved_ref?: string;
}
interface Warning {
  sku?: string;
  product_variant_id?: string;
  zone?: string;
  committed?: number;
  demand?: number;
  delta?: number;
  severity?: string;
  message?: string;
}

const COMMIT_COLS = 6;
const PROPOSAL_COLS = 9;

function StateBody({ st, cols, children }: { st: string; cols: number; children: React.ReactNode }) {
  if (st === 'loading') return <SkeletonRow cols={cols} />;
  if (st === 'forbidden')
    return (
      <EmptyRow cols={cols}>
        <LayerNote>hidden — requires the supply layer</LayerNote>
      </EmptyRow>
    );
  if (st === 'error') return <EmptyRow cols={cols}>Couldn't load — try again shortly.</EmptyRow>;
  return <>{children}</>;
}

export function SupplyWindow({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const token = authToken();
  const [toastNode, fire] = useToast();
  const tell = useCallback((m: string, k?: string) => { fire(m, (k as any) || 'ok'); toast(m, k); }, [fire, toast]);

  const [cms, setCms] = useState<Cm[]>([]);
  const [supplier, setSupplier] = useState<string>('');

  const [commitRes, setCommitRes] = useState<ApiResult | null>(null);
  const [proposalRes, setProposalRes] = useState<ApiResult | null>(null);
  const [warningRes, setWarningRes] = useState<ApiResult | null>(null);
  const [approving, setApproving] = useState<string>('');

  const commitments = asArray<Commitment>(commitRes?.json);
  const proposals = asArray<Proposal>(proposalRes?.json);
  const warnings = asArray<Warning>(warningRes?.json);

  const loadLanes = useCallback(
    (sup: string) => {
      setCommitRes(null);
      setProposalRes(null);
      setWarningRes(null);
      Promise.all([getSupplyCommitments(token, sup), getProposals(token, sup), getSupplyWarnings(token, sup)]).then(
        ([c, p, w]) => {
          setCommitRes(c);
          setProposalRes(p);
          setWarningRes(w);
        },
      );
    },
    [token],
  );

  useEffect(() => {
    let live = true;
    getContractManufacturers(token).then((s) => {
      if (!live) return;
      const list = asArray<Cm>(s.json);
      setCms(list);
      const first = list.length ? list[0].id : '';
      setSupplier(first);
      if (first) loadLanes(first);
    });
    return () => {
      live = false;
    };
  }, [token, loadLanes, ctx?.entity, ctx?.market, ctx?.scenario, ctx?.period]);

  const onPickSupplier = (id: string) => {
    setSupplier(id);
    loadLanes(id);
  };

  const approve = (p: Proposal) => {
    const variant = p.product_variant_id || p.sku || '';
    const target = p.target_date || '';
    const selfMade = !!p.proposer && !!role?.name && p.proposer === role.name;
    if (selfMade) return;
    setApproving(variant + target);
    approvePo(token, supplier, variant, target).then((res) => {
      setApproving('');
      if (res.status === 200) {
        tell('Auto-PO approved — committed within flex headroom', 'ok');
        loadLanes(supplier);
      } else if (res.status === 403) {
        tell("Forbidden — you can't approve this proposal", 'err');
      } else {
        tell(`Approve failed (${res.status})`, 'err');
      }
    });
  };

  const cm = cms.find((c) => c.id === supplier);
  const commitState = tableState(commitRes, commitments);
  const proposalState = tableState(proposalRes, proposals);
  const warningState = tableState(warningRes, warnings);
  const laneCcy = cm?.currency || 'GBP';

  return (
    <div className="page">
      {toastNode}
      <PageHead
        crumb={'H6Q · Supply window'}
        title="Supply window"
        sub="Firm-commitment horizon (frozen · flex · indicative), auto-PO proposals behind a human gate, and divergence warnings per contract manufacturer."
        right={
          cms.length > 0 ? (
            <select
              className="fld sel"
              data-testid="supply-cm"
              value={supplier}
              onChange={(e) => onPickSupplier(e.target.value)}
            >
              {cms.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                  {c.currency ? ` (${c.currency} CM)` : ''}
                </option>
              ))}
            </select>
          ) : undefined
        }
      />

      <Card
        title="Commitment ladder"
        icon={I.layers}
        aux={<span className="dim" style={{ fontSize: 12 }}>frozen (can't move) · flex (±tolerance) · indicative · per contract manufacturer</span>}
        className="tablewrap"
        style={{ marginBottom: 12 }}
      >
        <table className="tbl" data-testid="supply-commitments">
          <thead>
            <tr>
              <th>SKU</th>
              <th>Target week</th>
              <th>Zone</th>
              <th className="num">Firm PO</th>
              <th className="num">PO value</th>
              <th>Version</th>
            </tr>
          </thead>
          <tbody>
            <StateBody st={commitState} cols={COMMIT_COLS}>
              {commitState === 'empty' ? (
                <EmptyRow cols={COMMIT_COLS}>No commitments.</EmptyRow>
              ) : (
                commitments.map((c, i) => (
                  <tr key={i} data-testid="supply-commit-row">
                    <td>
                      <span className="mono">{c.sku || c.product_variant_id}</span>
                    </td>
                    <td>{c.target_date}</td>
                    <td>{c.zone && <ZoneTag zone={c.zone} />}</td>
                    <td className="num">
                      <b>{c.qty != null ? c.qty.toLocaleString('en-GB') : '—'}</b>
                    </td>
                    <td className="num">
                      <Money value={c.po_value ?? null} ccy={c.currency || laneCcy} layer="commercial" role={role} />
                    </td>
                    <td>
                      <span className="dim" style={{ fontSize: 12 }}>
                        {c.version != null ? `v${c.version}` : ''}
                        {c.reason ? ` · ${c.reason}` : ''}
                      </span>
                    </td>
                  </tr>
                ))
              )}
            </StateBody>
          </tbody>
        </table>
      </Card>

      <Card
        title="Auto-PO proposals"
        icon={I.cpu}
        aux={<span className="dim" style={{ fontSize: 12 }}>proposed within headroom · blocked = needs escalation · approval is a deliberate human gate</span>}
        className="tablewrap"
        style={{ marginBottom: 12 }}
      >
        <table className="tbl" data-testid="supply-proposals">
          <thead>
            <tr>
              <th>SKU</th>
              <th>Week</th>
              <th className="num">Demand</th>
              <th className="num">Committed</th>
              <th className="num">Net need</th>
              <th className="num">Proposed</th>
              <th className="num">Blocked</th>
              <th className="num">PO value</th>
              <th>Zone</th>
              <th />
            </tr>
          </thead>
          <tbody>
            <StateBody st={proposalState} cols={PROPOSAL_COLS + 1}>
              {proposalState === 'empty' ? (
                <EmptyRow cols={PROPOSAL_COLS + 1}>No proposals — demand sits within committed supply.</EmptyRow>
              ) : (
                proposals.map((p, i) => {
                  const variant = p.product_variant_id || p.sku || '';
                  const busy = approving === variant + (p.target_date || '');
                  const selfMade = !!p.proposer && !!role?.name && p.proposer === role.name;
                  const actionable = p.status === 'proposed' && (p.proposed_delta ?? 0) > 0;
                  return (
                    <tr key={i} data-testid="supply-proposal-row">
                      <td>
                        <span className="mono">{p.sku || variant}</span>
                      </td>
                      <td>{p.target_date}</td>
                      <td className="num">{p.demand != null ? p.demand.toLocaleString('en-GB') : '—'}</td>
                      <td className="num">{p.committed != null ? p.committed.toLocaleString('en-GB') : '—'}</td>
                      <td className="num">{p.net_need != null ? p.net_need.toLocaleString('en-GB') : '—'}</td>
                      <td className="num">
                        <b style={{ color: 'var(--ok)' }}>+{(p.proposed_delta ?? 0).toLocaleString('en-GB')}</b>
                      </td>
                      <td className="num">
                        {(p.blocked_qty ?? 0) > 0 ? (
                          <span className="dev-neg">⚠ {(p.blocked_qty as number).toLocaleString('en-GB')}</span>
                        ) : (
                          '0'
                        )}
                      </td>
                      <td className="num">
                        <Money value={p.po_value ?? null} ccy={p.currency || laneCcy} layer="commercial" role={role} />
                      </td>
                      <td>{p.zone && <ZoneTag zone={p.zone} />}</td>
                      <td>
                        {actionable ? (
                          <button
                            className="btn sm primary"
                            data-testid="supply-approve"
                            disabled={busy || selfMade}
                            title={selfMade ? 'You proposed this — a second approver is required' : undefined}
                            onClick={() => approve(p)}
                          >
                            {I.check({ size: 12 })} {busy ? 'Approving…' : 'Approve'}
                          </button>
                        ) : (
                          <span className="chip ok">
                            <span className="d" />
                            {p.approved_ref || 'approved'}
                          </span>
                        )}
                      </td>
                    </tr>
                  );
                })
              )}
            </StateBody>
          </tbody>
        </table>
      </Card>

      <Card
        title="Divergence warnings"
        icon={I.alert}
        aux={<span className="dim" style={{ fontSize: 12 }}>sales/automated demand vs a firm PO that can't move — loud, never silently dropped</span>}
        className="tablewrap"
      >
        {warningState === 'loading' && <div className="skel skel-line" style={{ margin: '8px 0' }} />}
        {warningState === 'forbidden' && <LayerNote>hidden — requires the supply layer</LayerNote>}
        {warningState === 'error' && <div className="dim" style={{ padding: '12px 0' }}>Couldn't load warnings — try again shortly.</div>}
        {warningState === 'empty' && (
          <div className="dim" style={{ padding: '12px 0' }} data-testid="supply-warnings">
            No divergence — demand sits within every committed window.
          </div>
        )}
        {warningState === 'ready' && (
          <div data-testid="supply-warnings">
            {warnings.map((w, i) => (
              <div
                key={i}
                className={'banner ' + (w.severity === 'warn' ? 'warn' : 'danger')}
                data-testid="supply-warning-row"
                style={{ marginBottom: 8 }}
              >
                {I.alert()}
                <div>
                  <span className="bb">
                    {w.sku || w.product_variant_id} · {w.zone}
                    {w.delta != null ? ` · delta ${w.delta.toLocaleString('en-GB')}` : ''}.
                  </span>{' '}
                  {w.message ||
                    `Committed ${w.committed?.toLocaleString('en-GB') ?? '—'} vs demand ${w.demand?.toLocaleString('en-GB') ?? '—'}.`}
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}
