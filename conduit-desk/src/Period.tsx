import React, { useState, useEffect, useCallback } from 'react';
import { apiFetch } from './api';
import { asArray } from './state';
import { PageHead, Card, Chip, Drawer, Money, LayerNote, AuditRef, EmptyRow, Skeleton, SkeletonRow } from './kit/kit';
import { I } from './kit/icons';

// 12 — Period governance + investigation (spec/ui/12-period.md). The auditor/finance front door to ONE
// accounting period end to end, plus the group close roll-up (ASC 810 coterminous): a group period can't lock
// until every operating entity's period is locked. The roll-up gate is the hero — "3 of 4 locked, HV-SG still
// open" must be unmissable. Period assignment is a re-projection of the UTC instant, never a stored stamp.
//
// Auto-loads on mount + when the period key changes (no Load button). Four states everywhere: loading
// (skeleton) / empty ("unknown group period") / 403 (LayerNote — requires view:accounting_period) / error.
// Journal amounts are commercial/profitability-layered and COLLAPSE (never £0) for a volume-only viewer.

type AnyRole = { layers?: string[] };

interface PeriodProps {
  role: AnyRole;
  ctx: { period?: string; entity?: string; market?: string; scenario?: string };
  toast: (m: string, k?: string) => void;
}

const GROUP_KEY = /^\d{4}-Q[1-4]$/;

export function Period({ role, ctx, toast }: PeriodProps) {
  const layers = asArray<string>(role?.layers);
  const seed = ctx?.period && GROUP_KEY.test(ctx.period) ? ctx.period : '2026-Q2';

  const [key, setKey] = useState(seed);
  const [res, setRes] = useState<{ status: number; json: any } | null>(null);
  const [blocker, setBlocker] = useState<{ message: string; laggards: string[] } | null>(null);
  const [walk, setWalk] = useState<any | null>(null);
  const [locking, setLocking] = useState(false);

  const load = useCallback(async (k: string) => {
    setRes(null);
    setBlocker(null);
    setWalk(null);
    const r = await apiFetch(`/api/v1/finance/periods/${encodeURIComponent(k)}/investigation`);
    setRes(r);
  }, []);

  useEffect(() => { load(key); }, [key, load]);

  const data = res && res.status === 200 ? res.json : null;
  const gp = data?.period ?? data;
  const status = res === null ? 'loading' : (res.status === 401 || res.status === 403) ? 'forbidden' : res.status >= 400 ? 'error' : data ? 'ready' : 'empty';

  const entities = asArray<any>(gp?.entities);
  const lockedCount = data?.locked_count ?? entities.filter((e) => e.status === 'locked').length;
  const entityCount = data?.entity_count ?? entities.length;
  const groupStatus = gp?.status ?? 'open';
  const allLocked = entityCount > 0 && lockedCount === entityCount;
  const laggards = entities.filter((e) => e.status !== 'locked').map((e) => e.name || e.code);

  const journals = asArray<any>(data?.investigation?.journals ?? data?.journals?.lines ?? data?.journals);
  const events = asArray<any>(data?.investigation?.events ?? data?.events);
  const controls = asArray<any>(data?.investigation?.controls ?? data?.controls);
  const recons = asArray<any>(data?.investigation?.recons ?? data?.reconciliations);
  const documents = asArray<any>(data?.investigation?.documents ?? data?.documents);
  const invoices = asArray<any>(data?.investigation?.invoices ?? data?.lineage);

  const hasMoney = layers.length === 0 || layers.indexOf('commercial') >= 0;

  const lock = async () => {
    if (groupStatus === 'locked') { toast(`group period ${key} is already locked — no-op`, 'warn'); return; }
    setLocking(true);
    const r = await apiFetch(`/api/v1/finance/group-periods/${encodeURIComponent(key)}/lock`, { method: 'POST' });
    setLocking(false);
    if (r.status === 200) { setBlocker(null); toast(`group period ${key} locked`, 'ok'); load(key); return; }
    if (r.status === 422 || r.status === 409) {
      const lag = asArray<string>(r.json?.laggards).length ? asArray<string>(r.json.laggards) : laggards;
      setBlocker({ message: r.json?.message || 'one or more operating entities are still open', laggards: lag });
      toast('lock refused — entities still open', 'warn');
      return;
    }
    toast(`lock failed: ${r.json?.message ?? r.status}`, 'err');
  };

  const openWalk = async (invNo: string) => {
    const r = await apiFetch(`/api/v1/finance/lineage?invoice_no=${encodeURIComponent(invNo)}`);
    if (r.status === 200) setWalk(r.json);
    else toast(`could not trace ${invNo} (${r.status})`, 'err');
  };

  const lockDisabled = groupStatus === 'locked' || !allLocked || locking;
  const lockTip = groupStatus === 'locked' ? 'already locked — no-op'
    : !allLocked ? `blocked — ${laggards.length} operating ${laggards.length === 1 ? 'entity is' : 'entities are'} still open`
    : 'lock the group period';

  return (
    <>
      <PageHead
        crumb="Govern · group close roll-up (ASC 810 coterminous)"
        title="Period"
        sub="One accounting period, end to end — its trial-balance shape, events, controls and evidence. A group period can't lock until every operating entity's period is locked."
        right={
          <div className="row g8">
            <select className="fld sel" value={key} onChange={(e) => setKey(e.target.value)} style={{ minWidth: 130 }} data-testid="per-key">
              <option value="2026-Q2">2026-Q2</option>
              <option value="2026-Q1">2026-Q1</option>
              <option value="2025-Q4">2025-Q4</option>
            </select>
            {status === 'ready' && <Chip s={groupStatus === 'locked' ? 'locked' : 'open'}><span data-testid="per-group-status">group: {groupStatus}</span></Chip>}
          </div>
        }
      />

      {status === 'loading' && (
        <Card title="Loading period…" icon={I.clock}>
          <Skeleton lines={4} />
        </Card>
      )}

      {status === 'forbidden' && (
        <Card title="Period governance is withheld" icon={I.shield} style={{ maxWidth: 620 }}>
          <LayerNote>hidden — requires <b>view:accounting_period</b>. Your role's server-side projection doesn't carry this view; the data never reaches the browser.</LayerNote>
        </Card>
      )}

      {status === 'error' && (
        <Card title="Could not load the period" icon={I.alert} style={{ maxWidth: 620 }}>
          <div className="banner danger" data-testid="per-error">{I.alert()}<div>The investigation request failed ({res?.status}). The period is unreachable right now — try again shortly.</div></div>
        </Card>
      )}

      {status === 'empty' && (
        <Card title="Unknown group period" icon={I.search} style={{ maxWidth: 620 }}>
          <div className="dim" data-testid="per-empty" style={{ fontSize: 13.5, lineHeight: 1.55 }}>
            No close roll-up exists for <span className="mono">{key}</span>. Pick a different group period (e.g. <span className="mono">2026-Q2</span>) — a key is a year plus a fiscal quarter.
          </div>
        </Card>
      )}

      {status === 'ready' && (
        <>
          <div className="row between" data-testid="per-window" style={{ padding: '14px 18px', border: '1px solid var(--border)', borderRadius: 14, background: 'var(--bg-2)', marginBottom: 14 }}>
            <div className="row g12">
              <span style={{ width: 40, height: 40, borderRadius: 12, display: 'grid', placeItems: 'center', flex: '0 0 40px', background: groupStatus === 'locked' ? 'var(--accent-subtle)' : 'var(--warn-bg)', color: groupStatus === 'locked' ? 'var(--accent-bright)' : 'var(--warn)' }}>
                {groupStatus === 'locked' ? I.shield({ size: 20 }) : I.clock({ size: 20 })}
              </span>
              <div>
                <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>{gp?.key ?? key} · group close</div>
                <div className="dim" style={{ fontSize: 12.5 }}>window {gp?.from ?? '—'} → {gp?.to ?? '—'} · period assignment re-projected from the UTC instant</div>
              </div>
            </div>
            <Chip s={groupStatus === 'locked' ? 'locked' : 'open'}>{groupStatus}</Chip>
          </div>

          <Card title="Entity close board" icon={I.layers} aux="the roll-up gate — every entity must lock before the group can" style={{ marginBottom: 14 }}>
            <div className="row between" style={{ marginBottom: 14, alignItems: 'flex-end' }}>
              <div>
                <div className="dim" style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.06em' }}>Entities locked</div>
                <div data-testid="per-locked-count" style={{ fontFamily: 'var(--font-disp)', fontSize: 34, fontWeight: 600, letterSpacing: '-0.02em', marginTop: 2 }}>
                  {lockedCount}<span className="dim" style={{ fontSize: 18, fontWeight: 400 }}> / {entityCount}</span>
                </div>
              </div>
              <div style={{ flex: 1, maxWidth: 360, margin: '0 28px' }}>
                <div style={{ height: 8, borderRadius: 6, background: 'var(--surface3)', overflow: 'hidden', display: 'flex', gap: 2 }}>
                  {entities.map((e, i) => <div key={i} style={{ flex: 1, background: e.status === 'locked' ? 'var(--ok)' : 'var(--warn)' }} />)}
                </div>
                {!allLocked && <div className="dim" style={{ fontSize: 11.5, marginTop: 6 }}>{laggards.length} still open: {laggards.join(', ')}</div>}
              </div>
              <button className="btn primary" data-testid="per-lock" title={lockTip} disabled={lockDisabled} onClick={lock}>
                {I.shield({ size: 14 })} {locking ? 'Locking…' : 'Lock group period'}
              </button>
            </div>

            {blocker && (
              <div className="banner danger" data-testid="per-lock-blocker" style={{ marginBottom: 14 }}>
                {I.alert()}
                <div>
                  <b>Lock refused.</b> {blocker.message}. Every operating entity must be locked first — chase the laggard{blocker.laggards.length > 1 ? 's' : ''}: <b>{blocker.laggards.join(', ')}</b>.
                </div>
              </div>
            )}

            <div className="tablewrap" style={{ border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden' }}>
              <table className="tbl" data-testid="per-entities">
                <thead><tr><th>Entity</th><th>Code</th><th>Status</th><th>Closed at</th></tr></thead>
                <tbody>
                  {entities.length === 0 && <EmptyRow cols={4}>No operating entities in scope.</EmptyRow>}
                  {entities.map((e) => (
                    <tr key={e.code} data-testid="per-entity-row" className={e.status !== 'locked' ? 'sel' : ''}>
                      <td><b>{e.name}</b></td>
                      <td className="mono dim" style={{ fontSize: 11.5 }}>{e.code}</td>
                      <td><Chip s={e.status === 'locked' ? 'locked' : 'open'}>{e.status}</Chip></td>
                      <td className="dim">{e.closed_at || '— still open'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>

          <div className="grid" style={{ gridTemplateColumns: '1.3fr 1fr', alignItems: 'start', marginBottom: 14 }}>
            <Card title="Journals" icon={I.list} aux="netted per account — the trial-balance shape">
              <div className="tablewrap">
                <table className="tbl" data-testid="per-journals">
                  <thead><tr><th>Account</th><th>Side</th><th className="num">Amount</th></tr></thead>
                  <tbody>
                    {journals.length === 0 && <EmptyRow cols={3}>No posted legs in this window.</EmptyRow>}
                    {journals.map((j, i) => (
                      <tr key={i}>
                        <td><b style={{ fontWeight: 500 }}>{j.account}</b></td>
                        <td><span className={'chip ' + (j.side === 'DR' ? 'neutral' : 'accent')}><span className="d" />{j.side}</span></td>
                        <td className="num">{hasMoney ? <Money value={j.amount} layer="commercial" role={role as any} /> : <span className="dim">— layer</span>}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {!hasMoney && <LayerNote>Journal amounts hidden — requires the <b>commercial</b> layer.</LayerNote>}
            </Card>

            <Card title="Business events" icon={I.pulse} aux="counts per type in window">
              <div className="tablewrap">
                <table className="tbl" data-testid="per-events">
                  <thead><tr><th>Event type</th><th className="num">Count</th></tr></thead>
                  <tbody>
                    {events.length === 0 && <EmptyRow cols={2}>No business events in this window.</EmptyRow>}
                    {events.map((e, i) => (
                      <tr key={i}><td className="mono" style={{ fontSize: 11.5 }}>{e.type ?? e.event_type}</td><td className="num">{e.count}</td></tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          </div>

          <div className="grid" style={{ gridTemplateColumns: '1fr 1fr', alignItems: 'start', marginBottom: 14 }}>
            <Card title="Controls" icon={I.check} aux="re-performable evidence for the window">
              <div className="tablewrap">
                <table className="tbl" data-testid="per-controls">
                  <thead><tr><th>Code</th><th>Result</th><th className="num">Violations</th></tr></thead>
                  <tbody>
                    {controls.length === 0 && <EmptyRow cols={3}>No controls ran in this window.</EmptyRow>}
                    {controls.map((c, i) => (
                      <tr key={i}>
                        <td className="mono" style={{ fontSize: 11.5 }}><b>{c.code}</b></td>
                        <td><Chip s={c.result === 'pass' ? 'pass' : c.result === 'fail' ? 'fail' : 'neutral'}>{c.result}</Chip></td>
                        <td className="num">{c.violations}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>

            <Card title="Reconciliations" icon={I.scale} aux="matched + signed-off gate the close">
              <div className="tablewrap">
                <table className="tbl" data-testid="per-recs">
                  <thead><tr><th>Type</th><th>Status</th><th>Signed off</th></tr></thead>
                  <tbody>
                    {recons.length === 0 && <EmptyRow cols={3}>No reconciliations in this window.</EmptyRow>}
                    {recons.map((r, i) => (
                      <tr key={i}>
                        <td>{String(r.type || '').replace(/_/g, ' ')}</td>
                        <td><Chip s={r.status === 'matched' ? 'matched' : 'fail'}>{r.status}</Chip></td>
                        <td>{r.signed_off ? <span className="row g6" style={{ color: 'var(--ok)' }}>{I.check({ size: 14 })} yes</span> : <span className="dim">pending</span>}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          </div>

          <Card title="Documents issued & lineage entry-points" icon={I.layers} aux="every recognised invoice is one click from its evidence">
            <div className="row g8 wrap" style={{ marginBottom: 16 }}>
              {documents.length === 0 && <span className="dim">No documents issued in this window.</span>}
              {documents.map((d, i) => (
                <span key={i} className="chip neutral" data-testid="per-doc"><span className="d" />{typeof d === 'string' ? d : `${d.kind ?? ''} ${d.number ?? d.formatted_number ?? ''}`.trim()}</span>
              ))}
            </div>
            {invoices.length > 0 && (
              <>
                <div className="dim" style={{ fontSize: 12, marginBottom: 8 }}>Open a CM→PO journal walk</div>
                <div className="row g8 wrap">
                  {invoices.map((iv, i) => {
                    const no = typeof iv === 'string' ? iv : (iv.invoice_no ?? iv.number);
                    return <span key={i} data-testid="per-lineage-link" onClick={() => openWalk(no)} style={{ cursor: 'pointer' }}><AuditRef id={no} /></span>;
                  })}
                </div>
              </>
            )}
          </Card>
        </>
      )}

      <Drawer
        open={!!walk}
        onClose={() => setWalk(null)}
        width={560}
        chip={walk && <span className="chip accent"><span className="d" />Journal Atlas</span>}
        title={walk ? (walk.invoice_no ?? walk.number) : ''}
        sub={walk ? (walk.cm_po ? 'CM PO ' + walk.cm_po : `total ${walk.total_inc_vat ?? ''}`) : ''}
      >
        {walk && <JournalLegs walk={walk} hasMoney={hasMoney} role={role} />}
      </Drawer>
    </>
  );
}

// Shared DR/CR ledger view + a browser-recomputed conservation strip (the point of the walk: the double-entry
// invariant is re-checked client-side, never just asserted by the server).
function JournalLegs({ walk, hasMoney, role }: { walk: any; hasMoney: boolean; role: AnyRole }) {
  const legs = asArray<any>(walk.legs ?? walk.ledger_transfers);
  const numeric = legs.every((l) => typeof l === 'object' && l != null && 'amount' in l);
  const debits = numeric ? legs.filter((l) => l.side === 'DR').reduce((a, l) => a + Number(l.amount || 0), 0) : 0;
  const credits = numeric ? legs.filter((l) => l.side === 'CR').reduce((a, l) => a + Number(l.amount || 0), 0) : 0;
  const balanced = Math.round(debits * 100) === Math.round(credits * 100);

  return (
    <>
      <div className="tablewrap" style={{ border: '1px solid var(--border)', borderRadius: 12, overflow: 'hidden', marginBottom: 14 }}>
        <table className="tbl">
          <thead><tr><th>Account</th><th>Side</th><th className="num">Amount</th><th>Transfer</th></tr></thead>
          <tbody>
            {legs.length === 0 && <EmptyRow cols={4}>No ledger legs on this invoice.</EmptyRow>}
            {legs.map((l, i) => {
              if (typeof l === 'string') return <tr key={i}><td colSpan={3} className="mono dim" style={{ fontSize: 11 }}>{l}</td><td /></tr>;
              return (
                <tr key={i} className={l.orphan ? 'sel' : ''}>
                  <td><b style={{ fontWeight: 500 }}>{l.account}</b></td>
                  <td><span className={'chip ' + (l.side === 'DR' ? 'neutral' : 'accent')}><span className="d" />{l.side}</span></td>
                  <td className="num">{hasMoney ? <Money value={l.amount} layer="commercial" role={role as any} /> : <span className="dim">— layer</span>}</td>
                  <td className="mono dim" style={{ fontSize: 10.5 }}>{String(l.transfer_id ?? '').slice(0, 12)}…</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      {numeric && (
        <div className={'banner ' + (balanced ? 'ok' : 'danger')}>
          {balanced ? I.check() : I.alert()}
          <div>
            <b>{balanced ? 'Balanced.' : 'Conservation broken.'}</b>{' '}
            {hasMoney
              ? <>Σ debits {balanced ? '=' : '≠'} Σ credits — {balanced ? 'the double-entry invariant holds.' : 'a leg is missing or zeroed.'}</>
              : 'recomputed in the browser from the transfer legs.'}
          </div>
        </div>
      )}
    </>
  );
}
