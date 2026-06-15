import React, { useEffect, useState } from 'react';
import { useApi, request } from './lib/query';
import { ApiError } from './lib/client';
import { PageHead, Card, ZoneTag, Money, EmptyRow, SkeletonRow, LayerNote, useToast } from './kit/kit';
import { I } from './kit/icons';

// Supply window (spec/ui/05-supply.md · doc 20 D11/D12): the contract-manufacturer supply horizon — the
// commitment ladder (firm/flex/indicative zones), auto-PO proposals behind a human approve gate, and loud
// divergence warnings when frozen-window demand moves against a firm PO.
//
// Backing routes (H6QRoutes): GET /api/v1/h6q/suppliers -> [{id,name}]; and three supplier-scoped reads
// GET /api/v1/h6q/supply/{commitments,proposals,warnings}?supplier=<uuid>, each gated on view:pipeline_coverage
// (403 -> collapse to a LayerNote). Approval is POST /api/v1/h6q/supply/approve {supplier,variant,target}.
// Auto-load on mount + when ctx or the chosen contract manufacturer changes — no Load/Refresh buttons.

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
const PROPOSAL_COLS = 10;

type Surface = 'loading' | 'forbidden' | 'notImplemented' | 'error' | 'empty' | 'ready';

function surface(isLoading: boolean, err: ApiError | null, count: number): Surface {
  if (isLoading) return 'loading';
  if (err?.forbidden) return 'forbidden';
  if (err?.notImplemented) return 'notImplemented';
  if (err) return 'error';
  return count === 0 ? 'empty' : 'ready';
}

function StateBody({ st, cols, children }: { st: Surface; cols: number; children: React.ReactNode }) {
  if (st === 'loading') return <SkeletonRow cols={cols} />;
  if (st === 'forbidden')
    return (
      <EmptyRow cols={cols}>
        <LayerNote>hidden — requires view:pipeline_coverage</LayerNote>
      </EmptyRow>
    );
  if (st === 'notImplemented')
    return <EmptyRow cols={cols}>Not available in this environment yet.</EmptyRow>;
  if (st === 'error') return <EmptyRow cols={cols}>Couldn't load — try again shortly.</EmptyRow>;
  return <>{children}</>;
}

export function SupplyWindow({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const [toastNode, fire] = useToast();
  const tell = (m: string, k?: string) => {
    fire(m, (k as any) || 'ok');
    toast(m, k);
  };

  const ctxKey = [ctx?.entity, ctx?.market, ctx?.period, ctx?.scenario];

  const cmsQ = useApi<Cm[]>(['supply-cms', ...ctxKey], '/api/v1/h6q/suppliers');
  const cms: Cm[] = Array.isArray(cmsQ.data) ? cmsQ.data : [];

  const [supplier, setSupplier] = useState<string>('');
  useEffect(() => {
    if (!supplier && cms.length) setSupplier(cms[0].id);
  }, [cms, supplier]);

  const lane = (path: string) =>
    `/api/v1/h6q/supply/${path}?supplier=${encodeURIComponent(supplier)}`;
  const on = !!supplier;

  const commitQ = useApi<Commitment[]>(['supply-commit', supplier, ...ctxKey], lane('commitments'), { enabled: on });
  const proposalQ = useApi<Proposal[]>(['supply-proposals', supplier, ...ctxKey], lane('proposals'), { enabled: on });
  const warningQ = useApi<Warning[]>(['supply-warnings', supplier, ...ctxKey], lane('warnings'), { enabled: on });

  const commitments: Commitment[] = Array.isArray(commitQ.data) ? commitQ.data : [];
  const proposals: Proposal[] = Array.isArray(proposalQ.data) ? proposalQ.data : [];
  const warnings: Warning[] = Array.isArray(warningQ.data) ? warningQ.data : [];

  const cmsErr = cmsQ.error as ApiError | null;
  const commitErr = commitQ.error as ApiError | null;
  const proposalErr = proposalQ.error as ApiError | null;
  const warningErr = warningQ.error as ApiError | null;

  const commitState = surface(!on || commitQ.isLoading, commitErr, commitments.length);
  const proposalState = surface(!on || proposalQ.isLoading, proposalErr, proposals.length);
  const warningState = surface(!on || warningQ.isLoading, warningErr, warnings.length);

  const [approving, setApproving] = useState<string>('');

  const approve = (p: Proposal) => {
    const variant = p.product_variant_id || p.sku || '';
    const target = p.target_date || '';
    const selfMade = !!p.proposer && !!role?.name && p.proposer === role.name;
    if (selfMade) return;
    setApproving(variant + target);
    request('/api/v1/h6q/supply/approve', {
      method: 'POST',
      body: JSON.stringify({ supplier, variant, target }),
    })
      .then(() => {
        tell('Auto-PO approved — committed within flex headroom', 'ok');
        proposalQ.refetch();
        commitQ.refetch();
        warningQ.refetch();
      })
      .catch((e: unknown) => {
        if (e instanceof ApiError && e.forbidden) tell("Forbidden — you can't approve this proposal", 'err');
        else if (e instanceof ApiError && e.status === 409) tell('Cannot commit — outside firm headroom', 'err');
        else tell(`Approve failed (${e instanceof ApiError ? e.status : 'network'})`, 'err');
      })
      .finally(() => setApproving(''));
  };

  const cm = cms.find((c) => c.id === supplier);
  const laneCcy = cm?.currency || 'GBP';

  // The suppliers list itself can be forbidden / unbacked — surface that honestly at the page level.
  if (cmsErr?.forbidden) {
    return (
      <div className="page">
        {toastNode}
        <PageHead crumb={'H6Q · Supply window'} title="Supply window" sub="Firm-commitment horizon, auto-PO proposals behind a human gate, and divergence warnings per contract manufacturer." />
        <Card style={{ padding: '34px 28px' }}>
          <LayerNote>hidden — requires view:pipeline_coverage</LayerNote>
        </Card>
      </div>
    );
  }
  if (cmsErr?.notImplemented) {
    return (
      <div className="page">
        {toastNode}
        <PageHead crumb={'H6Q · Supply window'} title="Supply window" sub="Firm-commitment horizon, auto-PO proposals behind a human gate, and divergence warnings per contract manufacturer." />
        <Card style={{ padding: '34px 28px', textAlign: 'center' }} data-testid="supply-unbacked">
          <div style={{ display: 'grid', placeItems: 'center', gap: 10 }}>
            <span style={{ width: 44, height: 44, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'var(--panel-2)' }}>{I.cpu({ size: 22 })}</span>
            <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>Not available in this environment yet</div>
            <div className="dim" style={{ fontSize: 12.5, maxWidth: 460 }}>The supply window appears once contract manufacturers and a firm-commitment horizon have been registered.</div>
          </div>
        </Card>
      </div>
    );
  }

  // No contract manufacturers ⇒ the supply side isn't in this environment (the ingested data is sales-side:
  // orders, shipments, activations — there are no supplier POs/commitments). Show one honest panel rather than
  // rendering the per-lane skeletons, which would spin forever because no supplier can be selected.
  if (!cmsQ.isLoading && !cmsErr && cms.length === 0) {
    return (
      <div className="page">
        {toastNode}
        <PageHead crumb={'H6Q · Supply window'} title="Supply window" sub="Firm-commitment horizon, auto-PO proposals behind a human gate, and divergence warnings per contract manufacturer." />
        <Card style={{ padding: '34px 28px', textAlign: 'center' }} data-testid="supply-no-cms">
          <div style={{ display: 'grid', placeItems: 'center', gap: 10 }}>
            <span style={{ width: 44, height: 44, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'var(--panel-2)' }}>{I.cpu({ size: 22 })}</span>
            <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>No contract manufacturers in this environment</div>
            <div className="dim" style={{ fontSize: 12.5, maxWidth: 480 }}>The supply window tracks firm commitments + auto-PO proposals against contract manufacturers (e.g. Luxshare). The ingested data is sales-side (orders, shipments, activations) — no supplier POs/commitments exist yet, so there's nothing to schedule against.</div>
          </div>
        </Card>
      </div>
    );
  }

  return (
    <div className="page">
      {toastNode}
      <PageHead
        crumb={'H6Q · Supply window'}
        title="Supply window"
        sub="Firm-commitment horizon (frozen · flex · indicative), auto-PO proposals behind a human gate, and divergence warnings per contract manufacturer."
        right={
          cmsQ.isLoading ? (
            <span className="dim" style={{ fontSize: 12 }}>Loading manufacturers…</span>
          ) : cms.length > 0 ? (
            <select
              className="fld sel"
              data-testid="supply-cm"
              value={supplier}
              onChange={(e) => setSupplier(e.target.value)}
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

      {cmsErr && !cmsErr.forbidden && !cmsErr.notImplemented && (
        <div className="banner danger" style={{ marginBottom: 12 }} data-testid="supply-cm-error">
          {I.alert()}
          <div>Couldn't load contract manufacturers (HTTP {cmsErr.status}). Try again shortly.</div>
        </div>
      )}

      {!cmsQ.isLoading && !cmsErr && cms.length === 0 && (
        <Card style={{ padding: '26px 24px', marginBottom: 12 }}>
          <div className="dim" data-testid="supply-no-cms">No contract manufacturers registered yet.</div>
        </Card>
      )}

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
            <StateBody st={proposalState} cols={PROPOSAL_COLS}>
              {proposalState === 'empty' ? (
                <EmptyRow cols={PROPOSAL_COLS}>No proposals — demand sits within committed supply.</EmptyRow>
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
        {warningState === 'forbidden' && <LayerNote>hidden — requires view:pipeline_coverage</LayerNote>}
        {warningState === 'notImplemented' && (
          <div className="dim" style={{ padding: '12px 0' }} data-testid="supply-warnings">
            Not available in this environment yet.
          </div>
        )}
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
