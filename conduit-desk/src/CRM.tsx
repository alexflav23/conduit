import React, { useCallback, useEffect, useState } from 'react';
import { apiFetch } from './api';
import {
  PageHead, Card, Chip, Drawer, Money, LayerNote, AuditRef,
  SkeletonRow, Skeleton, EmptyRow, useToast, gbp, num,
} from './kit/kit';
import { I } from './kit/icons';
import { asArray } from './state';

// CRM (spec/ui/22-crm.md): the customer master + sales pipeline. The PARTY is the hub — the doc-02 unification
// of company/customer — and everything (orders, credit, pipeline, sell-through) hangs off it. Three things are
// load-bearing here:
//   1. SCOPE TAGS (market · channel · sector) are first-class — they drive the access wall server-side; the rows
//      a viewer sees are already scope-filtered ("UK-wholesale-energy" sees only those parties).
//   2. THE LAYER WALL: identity is `volume`; credit limit + deal value are `commercial`; contact email/phone are
//      `pii`. A withheld layer is ABSENT from the payload, so the desk COLLAPSES — Money renders nothing (never
//      £0.00), PII contacts show a respectful «hidden — requires pii» / «erased» tombstone, never raw or redacted.
//   3. CREDIT BLOCK is loud (it blocks order placement) and raising a credit LIMIT is maker-checker (a request a
//      different approver must clear — self-approval is disabled with a tooltip).
// Auto-loads on mount + when ctx.market / ctx.entity change. No manual Load/Refresh buttons.

type Ctx = { entity: string; market: string; period: string; scenario: string };
type Role = { token?: string; name?: string; title?: string; layers?: string[] };
type Res = { status: number; json: any } | null;

const STAGE_CHIP: Record<string, string> = {
  lead: 'neutral', qualified: 'warn', proposal: 'accent', won: 'ok', lost: 'danger',
};
const OPEN_STAGES = ['lead', 'qualified', 'proposal'];

export function CRM({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const r = (role || {}) as Role;
  const c = (ctx || {}) as Ctx;
  const layers = r.layers || [];
  const hasCommercial = layers.indexOf('commercial') >= 0;
  const hasPii = layers.indexOf('pii') >= 0;
  const market = c.market || '';

  const [toastNode, fire] = useToast();
  const fireToast = useCallback((m: string, k?: string) => { fire(m, (k as any) || 'ok'); toast(m, k); }, [fire, toast]);

  const [tab, setTab] = useState<'parties' | 'pipeline'>('parties');
  const [sector, setSector] = useState('all');

  const [partyRes, setPartyRes] = useState<Res>(null);
  const [pipeRes, setPipeRes] = useState<Res>(null);
  const [sel, setSel] = useState<any | null>(null);

  // ---- credit-limit maker-checker request ----
  const [limitReq, setLimitReq] = useState<any | null>(null);
  const [limitDraft, setLimitDraft] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const loadParties = useCallback(() => {
    setPartyRes(null);
    const q = new URLSearchParams();
    if (market) q.set('market', market);
    if (sector !== 'all') q.set('sector', sector);
    apiFetch(`/api/v1/crm/parties${q.toString() ? `?${q}` : ''}`).then(setPartyRes);
  }, [market, sector]);

  const loadPipeline = useCallback(() => {
    setPipeRes(null);
    const q = new URLSearchParams();
    if (market) q.set('market', market);
    apiFetch(`/api/v1/crm/pipeline${q.toString() ? `?${q}` : ''}`).then(setPipeRes);
  }, [market]);

  useEffect(loadParties, [loadParties]);
  useEffect(loadPipeline, [loadPipeline]);

  const submitLimit = (p: any) => {
    const id = p.party_id || p.id;
    const amt = parseFloat(limitDraft);
    if (!Number.isFinite(amt) || amt < 0) { fireToast('Enter a valid credit limit', 'warn'); return; }
    setSubmitting(true);
    apiFetch(`/api/v1/crm/parties/${encodeURIComponent(id)}/credit-limit`, {
      method: 'POST',
      body: JSON.stringify({ credit_limit: amt, currency: p.currency || 'GBP' }),
    }).then((res) => {
      setSubmitting(false);
      setLimitReq(null);
      if (res.status === 200 || res.status === 202) {
        fireToast(`Credit-limit change to ${gbp(amt, p.currency)} submitted for approval — maker-checker`);
        loadParties();
      } else if (res.status === 403) {
        fireToast('Forbidden — credit limits require the commercial layer', 'err');
      } else if (res.status === 409) {
        fireToast('You raised this limit — a different approver must clear it', 'err');
      } else {
        fireToast(`Submit failed (${res.status})`, 'err');
      }
    });
  };

  return (
    <div className="page" style={{ maxWidth: 1320 }}>
      {toastNode}
      <PageHead
        crumb={'Customer master + pipeline (doc 11) · the single source of who we sell to'}
        title="CRM"
        sub="The canonical party — organisation or channel — with its contacts, billing & credit profile, and the deal pipeline that feeds the forecast. Scope tags (market · channel · sector) drive the access wall."
        right={market ? <span className="stale"><span className="pulse" />market {market.slice(0, 8)}</span> : undefined}
      />

      <div className="seg" style={{ marginBottom: 18 }}>
        <button className={tab === 'parties' ? 'on' : ''} data-testid="crm-tab-parties" onClick={() => setTab('parties')}>Parties</button>
        <button className={tab === 'pipeline' ? 'on' : ''} data-testid="crm-tab-pipeline" onClick={() => setTab('pipeline')}>Pipeline</button>
      </div>

      {tab === 'parties' ? (
        <PartyList
          res={partyRes} role={r} hasCommercial={hasCommercial} hasPii={hasPii}
          sector={sector} setSector={setSector} onSelect={setSel}
        />
      ) : (
        <Pipeline res={pipeRes} role={r} hasCommercial={hasCommercial} />
      )}

      <PartyDrawer
        party={sel} role={r} hasCommercial={hasCommercial} hasPii={hasPii} viewerName={r.name}
        onClose={() => setSel(null)}
        onRaiseLimit={(p) => { setLimitReq(p); setLimitDraft(String(p.credit_limit ?? '')); }}
      />

      {limitReq && (
        <CreditLimitRequest
          party={limitReq} draft={limitDraft} setDraft={setLimitDraft} submitting={submitting}
          viewerName={r.name} onCancel={() => setLimitReq(null)} onSubmit={() => submitLimit(limitReq)}
        />
      )}
    </div>
  );
}

// ---------------- Party list ----------------
function PartyList({ res, role, hasCommercial, hasPii, sector, setSector, onSelect }: {
  res: Res; role: Role; hasCommercial: boolean; hasPii: boolean;
  sector: string; setSector: (s: string) => void; onSelect: (p: any) => void;
}) {
  const loading = res === null;
  const forbidden = !!res && (res.status === 401 || res.status === 403);
  const error = !!res && res.status >= 400 && !forbidden;
  const payload = res && res.status < 400 ? res.json : null;
  const rows = asArray<any>(payload && payload.rows ? payload.rows : payload);
  const sectors = asArray<string>(payload && payload.sectors);

  if (forbidden) {
    return (
      <Card title="Parties" icon={I.user}>
        <LayerNote>The customer master is hidden — requires view:party for this scope.</LayerNote>
      </Card>
    );
  }

  return (
    <Card style={{ padding: 0 }} className="tablewrap">
      <div className="loadbar" style={{ padding: '11px 16px', margin: 0, borderBottom: '1px solid var(--border)' }}>
        <span className="fldlabel">Sector</span>
        <select className="fld sel" data-testid="crm-sector" value={sector} onChange={(e) => setSector(e.target.value)}>
          <option value="all">All sectors</option>
          {sectors.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        <span className="sp" />
        {!hasPii && <span className="chip neutral" style={{ fontSize: 11 }}>{I.shield({ size: 11 })}PII collapsed for your role</span>}
        <span className="dim" style={{ fontSize: 12 }}>{loading ? '…' : `${rows.length} parties`}</span>
      </div>
      <table className="tbl" data-testid="crm-parties">
        <thead>
          <tr>
            <th>Party</th><th>Type</th><th>Market</th><th>Channel</th><th>Sector</th>
            <th className="num">Credit limit</th><th>Status</th><th />
          </tr>
        </thead>
        <tbody>
          {loading && <><SkeletonRow cols={8} /><SkeletonRow cols={8} /><SkeletonRow cols={8} /></>}
          {error && <EmptyRow cols={8}>Could not load the customer master — try again shortly.</EmptyRow>}
          {!loading && !error && rows.length === 0 && <EmptyRow cols={8}>No parties in this scope.</EmptyRow>}
          {!loading && rows.map((p) => {
            const id = p.party_id || p.id;
            const block = !!(p.credit && (p.credit.block ?? p.credit_block));
            return (
              <tr key={id} tabIndex={0} data-testid="crm-party-row" onClick={() => onSelect(p)}
                onKeyDown={(e) => e.key === 'Enter' && onSelect(p)} style={{ cursor: 'pointer' }}>
                <td><b>{p.display_name || p.displayName}</b><div className="dim" style={{ fontSize: 10.5 }}>{p.legal_name || p.legalName || ''}</div></td>
                <td className="dim">{p.party_type || p.type || '—'}</td>
                <td>{(p.market || p.market_name) ? <span className="chip neutral"><span className="d" />{p.market || p.market_name}</span> : <span className="dim">—</span>}</td>
                <td className="dim">{p.channel || '—'}</td>
                <td className="dim">{p.sector || '—'}</td>
                <td className="num"><CreditLimitCell party={p} role={role} hasCommercial={hasCommercial} /></td>
                <td>{block ? <Chip s="danger">credit block</Chip> : <Chip s="active">active</Chip>}</td>
                <td>{I.chevR({ size: 15 })}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
      {!hasCommercial && rows.length > 0 && (
        <LayerNote>Credit limits sit behind the commercial layer — absent for your role, never shown as £0.</LayerNote>
      )}
    </Card>
  );
}

// Credit limit collapses (never £0) when the viewer lacks commercial, and reads "prepaid" for a zero limit.
function CreditLimitCell({ party, role, hasCommercial }: { party: any; role: Role; hasCommercial: boolean }) {
  if (!hasCommercial) return <span className="dim" style={{ fontStyle: 'italic', fontSize: 11.5 }}>hidden</span>;
  const limit = party.credit && party.credit.limit != null ? party.credit.limit : party.credit_limit;
  if (limit == null) return <span className="dim">—</span>;
  if (Number(limit) === 0) return <span className="dim">prepaid</span>;
  return <Money value={limit} ccy={party.currency} role={{ layers: role.layers || [] }} layer="commercial" />;
}

// ---------------- Party drawer (the hub) ----------------
function PartyDrawer({ party, role, hasCommercial, hasPii, viewerName, onClose, onRaiseLimit }: {
  party: any; role: Role; hasCommercial: boolean; hasPii: boolean; viewerName?: string;
  onClose: () => void; onRaiseLimit: (p: any) => void;
}) {
  const open = !!party;
  const p = party || {};
  const credit = p.credit || {};
  const block = !!(credit.block ?? p.credit_block);
  const limit = credit.limit != null ? credit.limit : p.credit_limit;
  const outstanding = credit.outstanding != null ? credit.outstanding : p.outstanding;
  const roles = asArray<string>(p.roles);
  const contacts = asArray<any>(p.contacts);
  // A viewer can only raise a limit they did not last change (maker-checker self-block, enforced server-side too).
  const lastMaker = credit.last_changed_by || credit.requested_by;
  const selfBlocked = !!lastMaker && !!viewerName && lastMaker === viewerName;

  return (
    <Drawer
      open={open} onClose={onClose} width={560}
      chip={open && (
        <div className="row g8">
          <span className="chip neutral"><span className="d" />{p.party_type || p.type || 'party'}</span>
          {(p.market || p.channel) && <span className="chip accent"><span className="d" />{[p.market, p.channel].filter(Boolean).join(' · ')}</span>}
          {block && <Chip s="danger">credit block</Chip>}
        </div>
      )}
      title={p.display_name || p.displayName || ''}
      sub={p.legal_name || p.legalName || ''}
    >
      {open && (
        <>
          {block && (
            <div className="banner danger" style={{ marginBottom: 16 }}>
              {I.alert()}
              <div>
                <span className="bb">Credit block active.</span>{' '}
                {hasCommercial && outstanding != null && limit != null
                  ? <>Outstanding {gbp(outstanding, p.currency)} against a {gbp(limit, p.currency)} limit — order placement is blocked until cleared.</>
                  : <>Order placement is blocked for this party until cleared.</>}
              </div>
            </div>
          )}

          <div className="mini">Scope & roles</div>
          <div className="row g6 wrap" style={{ marginBottom: 18 }}>
            {p.sector && <span className="chip neutral"><span className="d" />{p.sector}</span>}
            {p.channel && <span className="chip neutral"><span className="d" />{p.channel}</span>}
            {roles.map((role_) => <span key={role_} className="chip accent" style={{ padding: '2px 9px' }}>{role_}</span>)}
            {Number(p.branches) > 1 && <span className="chip neutral"><span className="d" />{p.branches} branches</span>}
          </div>

          <div className="grid" style={{ gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 18 }}>
            <div className="card" style={{ padding: '13px 15px', background: 'var(--bg-2)' }}>
              <div className="fldlabel">Credit</div>
              {hasCommercial ? (
                <div className="kv" style={{ marginTop: 8 }}>
                  <span className="k">Terms</span><span className="v">{credit.terms || (p.payment_terms_days != null ? `${p.payment_terms_days} days` : '—')}</span>
                  <span className="k">Limit</span><span className="v num">{Number(limit) === 0 ? 'prepaid' : gbp(limit, p.currency)}</span>
                  <span className="k">Outstanding</span><span className="v num">{gbp(outstanding, p.currency)}</span>
                </div>
              ) : (
                <div style={{ marginTop: 10 }}><LayerNote>Credit needs the commercial layer.</LayerNote></div>
              )}
            </div>
            <div className="card" style={{ padding: '13px 15px', background: 'var(--bg-2)' }}>
              <div className="fldlabel">Account</div>
              <div className="kv" style={{ marginTop: 8 }}>
                <span className="k">Manager</span><span className="v">{p.account_manager || p.manager || '—'}</span>
                <span className="k">Type</span><span className="v">{p.party_type || p.type || '—'}</span>
              </div>
            </div>
          </div>

          {hasCommercial && (
            <div style={{ marginBottom: 18 }}>
              <button className="btn sm" data-testid="crm-raise-limit" disabled={selfBlocked}
                title={selfBlocked ? 'You last changed this limit — a different approver must clear the next change' : undefined}
                onClick={() => !selfBlocked && onRaiseLimit(p)}>
                {I.scale({ size: 12 })}Change credit limit
              </button>
              {selfBlocked && <span className="dim" style={{ fontSize: 11, marginLeft: 8 }}>maker-checker · you cannot approve your own change</span>}
            </div>
          )}

          <div className="mini">
            Contacts {!hasPii && <span className="dim" style={{ textTransform: 'none', letterSpacing: 0 }}>· PII collapsed</span>}
          </div>
          <Contacts contacts={contacts} hasPii={hasPii} />
        </>
      )}
    </Drawer>
  );
}

// PII-aware contact list: a withheld email/phone is absent — show a respectful tombstone, never raw or redacted.
// An erased contact (DSAR crypto-shred) shows «erased», never a placeholder.
function Contacts({ contacts, hasPii }: { contacts: any[]; hasPii: boolean }) {
  if (contacts.length === 0) {
    return <div className="dim" style={{ fontSize: 12.5 }}>No named contacts (channel party).</div>;
  }
  return (
    <div style={{ display: 'grid', gap: 8 }}>
      {contacts.map((ct, i) => {
        const erased = !!(ct.erased || ct.status === 'erased');
        const collapsed = !hasPii || ct.collapsed;
        return (
          <div key={ct.id || i} className="row between" style={{ padding: '11px 13px', border: '1px solid var(--border)', borderRadius: 10 }}>
            <div>
              <div style={{ fontWeight: 600, fontSize: 13 }}>{erased ? <span className="dim" style={{ fontStyle: 'italic' }}>«erased»</span> : ct.name}</div>
              <div className="dim" style={{ fontSize: 11.5 }}>{ct.role || ct.title || ''}</div>
            </div>
            <div style={{ textAlign: 'right' }}>
              {erased ? (
                <div className="dim" style={{ fontSize: 11.5, fontStyle: 'italic' }}>«erased» — DSAR crypto-shred</div>
              ) : collapsed ? (
                <div className="dim" style={{ fontSize: 11.5, fontStyle: 'italic' }}>«hidden — requires pii»</div>
              ) : (
                <>
                  <div className="mono" style={{ fontSize: 12 }}>{ct.email || '—'}</div>
                  <div className="dim mono" style={{ fontSize: 11 }}>{ct.phone || ''}</div>
                </>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}

// ---------------- Pipeline ----------------
function Pipeline({ res, role, hasCommercial }: { res: Res; role: Role; hasCommercial: boolean }) {
  const loading = res === null;
  const forbidden = !!res && (res.status === 401 || res.status === 403);
  const error = !!res && res.status >= 400 && !forbidden;
  const payload = res && res.status < 400 ? res.json : null;
  const deals = asArray<any>(payload && payload.deals ? payload.deals : payload);
  const stages = asArray<string>(payload && payload.stages).length
    ? asArray<string>(payload && payload.stages)
    : ['lead', 'qualified', 'proposal', 'won', 'lost'];

  if (forbidden) {
    return <Card title="Pipeline" icon={I.trend}><LayerNote>The deal pipeline is hidden — requires view:deal for this scope.</LayerNote></Card>;
  }
  if (error) {
    return <Card title="Pipeline" icon={I.trend}><div className="dim" style={{ padding: '14px 2px' }}>Could not load the pipeline — try again shortly.</div></Card>;
  }
  if (loading) {
    return <Card title="Pipeline" icon={I.trend}><Skeleton lines={5} /></Card>;
  }
  if (deals.length === 0) {
    return <Card title="Pipeline" icon={I.trend}><div className="dim" style={{ padding: '14px 2px' }}>No open deals in this scope.</div></Card>;
  }

  const byStage: Record<string, any[]> = {};
  stages.forEach((s) => { byStage[s] = deals.filter((d) => d.stage === s); });
  const weighted = deals
    .filter((d) => OPEN_STAGES.indexOf(d.stage) >= 0)
    .reduce((a, d) => a + (Number(d.value) || 0) * (Number(d.weight) || 0), 0);
  const wonCount = (byStage['won'] || []).length;
  const openCount = deals.filter((d) => d.stage !== 'won' && d.stage !== 'lost').length;

  return (
    <div>
      <div className="grid" style={{ gridTemplateColumns: 'repeat(3,1fr)', marginBottom: 14 }}>
        <Card style={{ padding: '15px 18px' }}>
          <div className="fldlabel">Weighted pipeline</div>
          <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600, marginTop: 3 }}>
            {hasCommercial ? gbp(weighted) : <LayerNote>requires commercial</LayerNote>}
          </div>
        </Card>
        <Card style={{ padding: '15px 18px' }}>
          <div className="fldlabel">Won (period)</div>
          <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600, marginTop: 3, color: 'var(--ok)' }}>{num(wonCount)}</div>
        </Card>
        <Card style={{ padding: '15px 18px' }}>
          <div className="fldlabel">Open deals</div>
          <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600, marginTop: 3 }}>{num(openCount)}</div>
        </Card>
      </div>

      <div className="grid" style={{ gridTemplateColumns: `repeat(${stages.length},1fr)`, gap: 10, alignItems: 'start' }} data-testid="crm-pipeline">
        {stages.map((s) => (
          <div key={s}>
            <div className="row between" style={{ marginBottom: 8, padding: '0 2px' }}>
              <span style={{ fontWeight: 600, fontSize: 12.5, textTransform: 'capitalize' }}>{s}</span>
              <span className="dim" style={{ fontSize: 11 }}>{(byStage[s] || []).length}</span>
            </div>
            <div style={{ display: 'grid', gap: 8 }}>
              {(byStage[s] || []).map((d) => (
                <div key={d.id} className="card" data-testid="crm-deal" style={{ padding: '11px 12px' }}>
                  <div style={{ fontWeight: 600, fontSize: 12.5 }}>{d.party || d.party_name}</div>
                  <div className="dim" style={{ fontSize: 10.5, marginBottom: 6 }}>
                    {[d.sector, d.owner, d.age].filter(Boolean).join(' · ')}
                  </div>
                  <div className="row between">
                    <span className="num" style={{ fontSize: 12 }}>
                      {hasCommercial ? gbp(d.value) : <span className="dim" style={{ fontStyle: 'italic', fontSize: 11 }}>hidden</span>}
                    </span>
                    <span className={'chip ' + (STAGE_CHIP[s] || 'neutral')} style={{ padding: '1px 7px', fontSize: 9.5 }}>
                      {s === 'won' || s === 'lost' ? s : `${((Number(d.weight) || 0) * 100).toFixed(0)}%`}
                    </span>
                  </div>
                </div>
              ))}
              {(byStage[s] || []).length === 0 && (
                <div className="dim" style={{ fontSize: 11, padding: '8px 2px', textAlign: 'center' }}>—</div>
              )}
            </div>
          </div>
        ))}
      </div>

      {!hasCommercial && <LayerNote>Deal values sit behind the commercial layer — absent for your role, never shown as £0.</LayerNote>}
    </div>
  );
}

// ---------------- Credit-limit change (maker-checker request) ----------------
function CreditLimitRequest({ party, draft, setDraft, submitting, viewerName, onCancel, onSubmit }: {
  party: any; draft: string; setDraft: (s: string) => void; submitting: boolean;
  viewerName?: string; onCancel: () => void; onSubmit: () => void;
}) {
  const current = party.credit && party.credit.limit != null ? party.credit.limit : party.credit_limit;
  return (
    <>
      <div className="scrim open" onClick={onCancel} />
      <div className="drawer open" style={{ width: 440 }}>
        <div className="dh">
          <div style={{ flex: 1, minWidth: 0 }}>
            <span className="chip warn"><span className="d" />maker-checker</span>
            <div style={{ fontFamily: 'var(--font-disp)', fontSize: 19, fontWeight: 600, marginTop: 7 }}>Change credit limit</div>
            <div className="dim" style={{ fontSize: 12.5, marginTop: 3 }}>{party.display_name || party.displayName || party.party_id || party.id}</div>
          </div>
          <div className="ibtn" onClick={onCancel}>{I.x()}</div>
        </div>
        <div className="db">
          <div className="kv" style={{ marginBottom: 14 }}>
            <span className="k">Current limit</span><span className="v num">{Number(current) === 0 ? 'prepaid' : gbp(current, party.currency)}</span>
          </div>
          <div className="mini">New credit limit</div>
          <input className="cellinput" data-testid="crm-limit-input" style={{ width: 160, textAlign: 'right' }} value={draft}
            inputMode="decimal" onChange={(e) => setDraft(e.target.value)} placeholder="0.00" />
          <div className="banner info" style={{ marginTop: 14 }}>
            {I.shield()}This is a governed money mutation. The change is submitted as a request — a different approver must clear it (you cannot approve your own).
          </div>
          <div className="row g8" style={{ marginTop: 10 }}>
            <span className="dim" style={{ fontSize: 11.5 }}>posts to the audit log as</span>
            <AuditRef id="credit_limit_change_requested" />
          </div>
        </div>
        <div className="df">
          <button className="btn ghost" onClick={onCancel}>Cancel</button>
          <button className="btn primary" data-testid="crm-limit-submit" disabled={submitting} onClick={onSubmit}>
            {I.check({ size: 13 })}{submitting ? 'Submitting…' : 'Submit for approval'}
          </button>
        </div>
      </div>
    </>
  );
}
