import React, { useState } from 'react';
import { useApi, request } from './lib/query';
import { ApiError } from './lib/client';
import { marketId } from './api';
import {
  PageHead, Card, Chip, Drawer, Money, LayerNote, AuditRef,
  SkeletonRow, Skeleton, EmptyRow, gbp, num,
} from './kit/kit';
import { I } from './kit/icons';

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
//
// Backing routes: the customer-master LIST and the sales PIPELINE board have no GET route in this environment yet
// (CommerceRoutes ships create/credit-profile per-party POSTs and CreditRoutes the per-party credit-terms admin,
// but no scope-filtered party worklist and no deal pipeline). So the data surfaces render the honest
// "Not available in this environment yet" panel — a 404 from the canonical CRM paths drives that state, and the
// screen lights up automatically the day the worklist/pipeline reads ship. The credit-limit maker-checker write
// targets the per-party credit-terms route that DOES exist.
//   GET  /api/v1/crm/parties?market=<id>&sector=<s>   — party worklist (unbacked → notImplemented panel)
//   GET  /api/v1/crm/pipeline?market=<id>             — deal pipeline   (unbacked → notImplemented panel)
//   PUT  /api/v1/parties/{id}/credit-terms            — credit-limit change (real; maker-checker, edit:credit_profile)

type Ctx = { entity: string; market: string; period: string; scenario: string };
type Role = { token?: string; name?: string; title?: string; layers?: string[] };

interface PartyContact {
  id?: string;
  name?: string;
  role?: string;
  title?: string;
  email?: string | null;
  phone?: string | null;
  collapsed?: boolean;
  erased?: boolean;
  status?: string;
}
interface PartyCredit {
  block?: boolean;
  limit?: number | string | null;
  outstanding?: number | string | null;
  terms?: string | null;
  last_changed_by?: string | null;
  requested_by?: string | null;
}
interface Party {
  id?: string;
  party_id?: string;
  display_name?: string;
  displayName?: string;
  legal_name?: string;
  legalName?: string;
  party_type?: string;
  type?: string;
  market?: string;
  market_name?: string;
  channel?: string;
  sector?: string;
  status?: string;
  currency?: string;
  branches?: number;
  account_manager?: string;
  manager?: string;
  payment_terms_days?: number;
  credit_limit?: number | string | null;
  credit_block?: boolean;
  outstanding?: number | string | null;
  roles?: string[];
  contacts?: PartyContact[];
  credit?: PartyCredit;
}
interface PartyList {
  rows?: Party[];
  sectors?: string[];
}
interface Deal {
  id?: string;
  party?: string;
  party_name?: string;
  sector?: string;
  owner?: string;
  age?: string;
  stage?: string;
  value?: number | string | null;
  weight?: number | string | null;
}
interface Pipeline {
  deals?: Deal[];
  stages?: string[];
}

const STAGE_CHIP: Record<string, string> = {
  lead: 'neutral', qualified: 'warn', proposal: 'accent', won: 'ok', lost: 'danger',
};
const OPEN_STAGES = ['lead', 'qualified', 'proposal'];
const DEFAULT_STAGES = ['lead', 'qualified', 'proposal', 'won', 'lost'];

const asArray = <T,>(x: unknown): T[] => (Array.isArray(x) ? (x as T[]) : []);

export function CRM({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const r = (role || {}) as Role;
  const c = (ctx || {}) as Ctx;
  const layers = r.layers || [];
  const hasCommercial = layers.indexOf('commercial') >= 0;
  const hasPii = layers.indexOf('pii') >= 0;
  const market = c.market ? marketId(c.market) : '';

  const [tab, setTab] = useState<'accounts' | 'review' | 'parties' | 'pipeline' | 'deals'>('accounts');
  const [reviewPage, setReviewPage] = useState(0);
  const [acctSeg, setAcctSeg] = useState('all');
  const [acctQ, setAcctQ] = useState('');
  const [acctPage, setAcctPage] = useState(0);
  const [acctSel, setAcctSel] = useState<string | null>(null);
  const [dealPipeline, setDealPipeline] = useState('all');
  const [dealStatus, setDealStatus] = useState('all');
  const [dealSort, setDealSort] = useState('created');
  const [dealDir, setDealDir] = useState('desc');
  const [dealQ, setDealQ] = useState('');
  const [dealPage, setDealPage] = useState(0);
  const [sector, setSector] = useState('all');
  const [sel, setSel] = useState<Party | null>(null);

  // ---- credit-limit maker-checker request ----
  const [limitReq, setLimitReq] = useState<Party | null>(null);
  const [limitDraft, setLimitDraft] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // AUTO-LOAD via the production data layer. Keyed on the ctx fields the scope-filtered reads depend on
  // (entity/market) plus the sector filter, so a context switch refetches. No Load/Refresh buttons.
  const partyQ = new URLSearchParams();
  if (market) partyQ.set('market', market);
  if (sector !== 'all') partyQ.set('sector', sector);
  const parties = useApi<PartyList | Party[]>(
    ['crm', 'parties', c.entity, market, sector],
    `/api/v1/crm/parties${partyQ.toString() ? `?${partyQ}` : ''}`,
  );

  // The Conduit master accounts (golden records) — MRPeasy + HubSpot + contacts roll up into one entity.
  const ACCT_PAGE = 50;
  const acctQs = new URLSearchParams({ limit: String(ACCT_PAGE), offset: String(acctPage * ACCT_PAGE) });
  if (acctSeg !== 'all') acctQs.set('segment', acctSeg);
  if (acctQ.trim()) acctQs.set('q', acctQ.trim());
  const accounts = useApi<{ rows?: MasterAccount[]; total?: number }>(
    ['crm', 'accounts', acctSeg, acctQ, acctPage],
    `/api/v1/crm/accounts?${acctQs}`,
    { enabled: tab === 'accounts' },
  );
  const acctDetail = useApi<AccountDetail>(
    ['crm', 'account', acctSel ?? ''],
    `/api/v1/crm/accounts/${encodeURIComponent(acctSel ?? '')}`,
    { enabled: !!acctSel },
  );

  // The match-review queue — fuzzy candidates the model didn't auto-merge, with its verdict + reasoning.
  const REVIEW_PAGE = 25;
  const review = useApi<{ rows?: ReviewItem[]; total?: number }>(
    ['crm', 'review', reviewPage],
    `/api/v1/crm/account-candidates?limit=${REVIEW_PAGE}&offset=${reviewPage * REVIEW_PAGE}`,
    { enabled: tab === 'review' },
  );

  const pipeQ = new URLSearchParams();
  if (market) pipeQ.set('market', market);
  const pipeline = useApi<Pipeline | Deal[]>(
    ['crm', 'pipeline', c.entity, market],
    `/api/v1/crm/pipeline${pipeQ.toString() ? `?${pipeQ}` : ''}`,
    { enabled: tab === 'pipeline' },
  );

  // The attributed deal/PO book — every customer deal tied to the installer/wholesaler/retail company that placed it.
  const DEAL_PAGE = 50;
  const dealQs = new URLSearchParams({ limit: String(DEAL_PAGE), offset: String(dealPage * DEAL_PAGE), sort: dealSort, dir: dealDir });
  if (dealPipeline !== 'all') dealQs.set('pipeline', dealPipeline);
  if (dealStatus !== 'all') dealQs.set('status', dealStatus);
  if (dealQ.trim()) dealQs.set('q', dealQ.trim());
  const dealBook = useApi<{ rows?: DealRow[]; total?: number }>(
    ['crm', 'deals', dealPipeline, dealStatus, dealSort, dealDir, dealQ, dealPage],
    `/api/v1/crm/deals?${dealQs}`,
    { enabled: tab === 'deals' },
  );
  const dealSummary = useApi<{ segments?: DealAgg[]; pipelines?: DealAgg[] }>(
    ['crm', 'deals-summary'],
    '/api/v1/crm/deals/summary',
    { enabled: tab === 'deals' },
  );

  const submitLimit = (p: Party) => {
    const id = p.party_id || p.id;
    if (!id) return;
    const amt = parseFloat(limitDraft);
    if (!Number.isFinite(amt) || amt < 0) { toast('Enter a valid credit limit', 'warn'); return; }
    setSubmitting(true);
    request(`/api/v1/parties/${encodeURIComponent(id)}/credit-terms`, {
      method: 'PUT',
      body: JSON.stringify({
        payment_terms_days: p.payment_terms_days ?? 30,
        credit_limit: amt,
        currency: p.currency || 'GBP',
      }),
    })
      .then(() => {
        toast(`Credit-limit change to ${gbp(amt, p.currency)} submitted for approval — maker-checker`, 'ok');
        setLimitReq(null);
        parties.refetch();
      })
      .catch((e) => {
        const ae = e as ApiError;
        if (ae?.status === 409) toast('You raised this limit — a different approver must clear it', 'err');
        else if (ae?.forbidden) toast('Forbidden — credit limits require the commercial layer', 'err');
        else if (ae?.notImplemented) toast('Credit-limit changes are not wired in this environment yet', 'warn');
        else toast(`Submit failed (${ae?.status ?? '—'})${ae?.message ? `: ${ae.message}` : ''}`, 'err');
      })
      .finally(() => setSubmitting(false));
  };

  return (
    <div className="page" style={{ maxWidth: 1320 }}>
      <PageHead
        crumb={'Customer master + pipeline (doc 11) · the single source of who we sell to'}
        title="CRM"
        sub="The canonical party — organisation or channel — with its contacts, billing & credit profile, and the deal pipeline that feeds the forecast. Scope tags (market · channel · sector) drive the access wall."
        right={c.market ? <span className="stale"><span className="pulse" />market {String(c.market).slice(0, 8)}</span> : undefined}
      />

      <div className="seg" style={{ marginBottom: 18 }}>
        <button className={tab === 'accounts' ? 'on' : ''} data-testid="crm-tab-accounts" onClick={() => setTab('accounts')}>Accounts</button>
        <button className={tab === 'review' ? 'on' : ''} data-testid="crm-tab-review" onClick={() => setTab('review')}>Match review</button>
        <button className={tab === 'parties' ? 'on' : ''} data-testid="crm-tab-parties" onClick={() => setTab('parties')}>Parties (raw)</button>
        <button className={tab === 'pipeline' ? 'on' : ''} data-testid="crm-tab-pipeline" onClick={() => setTab('pipeline')}>Pipeline</button>
        <button className={tab === 'deals' ? 'on' : ''} data-testid="crm-tab-deals" onClick={() => setTab('deals')}>Deal book</button>
      </div>

      {tab === 'accounts' ? (
        <AccountsView
          list={accounts} detail={acctDetail} seg={acctSeg} setSeg={(s) => { setAcctSeg(s); setAcctPage(0); }}
          q={acctQ} setQ={(v) => { setAcctQ(v); setAcctPage(0); }} page={acctPage} setPage={setAcctPage} pageSize={ACCT_PAGE}
          sel={acctSel} setSel={setAcctSel} hasCommercial={hasCommercial} toast={toast}
        />
      ) : tab === 'parties' ? (
        <PartyListView
          q={parties} role={r} hasCommercial={hasCommercial} hasPii={hasPii}
          sector={sector} setSector={setSector} onSelect={setSel}
        />
      ) : tab === 'review' ? (
        <ReviewQueue review={review} page={reviewPage} setPage={setReviewPage} pageSize={REVIEW_PAGE}
          onChanged={() => { review.refetch(); }} toast={toast} />
      ) : tab === 'pipeline' ? (
        <PipelineView q={pipeline} hasCommercial={hasCommercial} />
      ) : (
        <DealBook
          book={dealBook} summary={dealSummary}
          pipeline={dealPipeline} setPipeline={(s) => { setDealPipeline(s); setDealPage(0); }}
          status={dealStatus} setStatus={(s) => { setDealStatus(s); setDealPage(0); }}
          sort={dealSort} dir={dealDir} setSort={(s, d) => { setDealSort(s); setDealDir(d); setDealPage(0); }}
          q={dealQ} setQ={(v) => { setDealQ(v); setDealPage(0); }} page={dealPage} setPage={setDealPage} pageSize={DEAL_PAGE}
          hasCommercial={hasCommercial}
        />
      )}

      <PartyDrawer
        party={sel} role={r} hasCommercial={hasCommercial} hasPii={hasPii} viewerName={r.name}
        onClose={() => setSel(null)}
        onRaiseLimit={(p) => { setLimitReq(p); setLimitDraft(String(p.credit?.limit ?? p.credit_limit ?? '')); }}
      />

      {limitReq && (
        <CreditLimitRequest
          party={limitReq} draft={limitDraft} setDraft={setLimitDraft} submitting={submitting}
          onCancel={() => setLimitReq(null)} onSubmit={() => submitLimit(limitReq)}
        />
      )}
    </div>
  );
}

// A clean, styled panel for a data surface whose GET route isn't built in this environment yet.
function NotBacked({ title, line }: { title: string; line: string }) {
  return (
    <Card style={{ padding: '34px 28px', textAlign: 'center' }}>
      <div style={{ display: 'grid', placeItems: 'center', gap: 10 }} data-testid="crm-unbacked">
        <span style={{ width: 44, height: 44, borderRadius: 12, display: 'grid', placeItems: 'center', background: 'var(--panel-2)' }}>{I.user({ size: 22 })}</span>
        <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>{title}</div>
        <div className="dim" style={{ fontSize: 12.5, maxWidth: 480 }}>{line}</div>
      </div>
    </Card>
  );
}

// ---------------- Party list ----------------
function PartyListView({ q, role, hasCommercial, hasPii, sector, setSector, onSelect }: {
  q: ReturnType<typeof useApi<PartyList | Party[]>>;
  role: Role; hasCommercial: boolean; hasPii: boolean;
  sector: string; setSector: (s: string) => void; onSelect: (p: Party) => void;
}) {
  const err = q.error as ApiError | null;
  const forbidden = !!err?.forbidden;
  const notImplemented = !!err?.notImplemented;
  const otherError = !!err && !forbidden && !notImplemented;
  const payload = q.data ?? null;
  const rows = asArray<Party>(Array.isArray(payload) ? payload : (payload as PartyList | null)?.rows);
  const sectors = asArray<string>(Array.isArray(payload) ? [] : (payload as PartyList | null)?.sectors);
  const ready = !q.isLoading && !err;

  if (notImplemented) {
    return (
      <NotBacked
        title="Not available in this environment yet"
        line="The customer master appears once the party worklist read is wired in this environment. Parties can be created and credit profiles set via the API today, but the scope-filtered list view is not yet served."
      />
    );
  }

  if (forbidden) {
    return (
      <Card title="Parties" icon={I.user}>
        <LayerNote>The customer master is hidden — requires <b>view:party</b> for this scope.</LayerNote>
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
        <span className="dim" style={{ fontSize: 12 }}>{ready ? `${rows.length} parties` : '…'}</span>
      </div>
      <table className="tbl" data-testid="crm-parties">
        <thead>
          <tr>
            <th>Party</th><th>Type</th><th>Market</th><th>Channel</th><th>Sector</th>
            <th className="num">Credit limit</th><th>Status</th><th />
          </tr>
        </thead>
        <tbody>
          {q.isLoading && <><SkeletonRow cols={8} /><SkeletonRow cols={8} /><SkeletonRow cols={8} /></>}
          {otherError && <EmptyRow cols={8}>Couldn't load the customer master (HTTP {err?.status}){err?.message ? ` — ${err.message}` : ''}. It retries on the next context change.</EmptyRow>}
          {ready && rows.length === 0 && <EmptyRow cols={8}>No parties in this scope.</EmptyRow>}
          {ready && rows.map((p) => {
            const id = p.party_id || p.id;
            const block = !!(p.credit?.block ?? p.credit_block);
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
function CreditLimitCell({ party, role, hasCommercial }: { party: Party; role: Role; hasCommercial: boolean }) {
  if (!hasCommercial) return <span className="dim" style={{ fontStyle: 'italic', fontSize: 11.5 }}>hidden</span>;
  const limit = party.credit?.limit != null ? party.credit.limit : party.credit_limit;
  if (limit == null) return <span className="dim">—</span>;
  if (Number(limit) === 0) return <span className="dim">prepaid</span>;
  return <Money value={limit} ccy={party.currency} role={{ layers: role.layers || [] }} layer="commercial" />;
}

// ---------------- Party drawer (the hub) ----------------
function PartyDrawer({ party, role, hasCommercial, hasPii, viewerName, onClose, onRaiseLimit }: {
  party: Party | null; role: Role; hasCommercial: boolean; hasPii: boolean; viewerName?: string;
  onClose: () => void; onRaiseLimit: (p: Party) => void;
}) {
  const open = !!party;
  const p = party || {};
  const credit = p.credit || {};
  const block = !!(credit.block ?? p.credit_block);
  const limit = credit.limit != null ? credit.limit : p.credit_limit;
  const outstanding = credit.outstanding != null ? credit.outstanding : p.outstanding;
  const roles = asArray<string>(p.roles);
  const contacts = asArray<PartyContact>(p.contacts);
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
function Contacts({ contacts, hasPii }: { contacts: PartyContact[]; hasPii: boolean }) {
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
function PipelineView({ q, hasCommercial }: { q: ReturnType<typeof useApi<Pipeline | Deal[]>>; hasCommercial: boolean }) {
  const err = q.error as ApiError | null;
  const forbidden = !!err?.forbidden;
  const notImplemented = !!err?.notImplemented;
  const otherError = !!err && !forbidden && !notImplemented;
  const payload = q.data ?? null;
  const deals = asArray<Deal>(Array.isArray(payload) ? payload : (payload as Pipeline | null)?.deals);
  const stagesRaw = asArray<string>(Array.isArray(payload) ? [] : (payload as Pipeline | null)?.stages);
  const stages = stagesRaw.length ? stagesRaw : DEFAULT_STAGES;
  const ready = !q.isLoading && !err;

  if (notImplemented) {
    return (
      <NotBacked
        title="Not available in this environment yet"
        line="The deal pipeline appears once the CRM pipeline read is wired in this environment. The forecast demand pipeline (H6Q) is served today, but the sales-deal board is not yet backed."
      />
    );
  }
  if (forbidden) {
    return <Card title="Pipeline" icon={I.trend}><LayerNote>The deal pipeline is hidden — requires <b>view:deal</b> for this scope.</LayerNote></Card>;
  }
  if (otherError) {
    return <Card title="Pipeline" icon={I.trend}><div className="dim" style={{ padding: '14px 2px' }}>Couldn't load the pipeline (HTTP {err?.status}){err?.message ? ` — ${err.message}` : ''}. It retries on the next context change.</div></Card>;
  }
  if (q.isLoading) {
    return <Card title="Pipeline" icon={I.trend}><Skeleton lines={5} /></Card>;
  }
  if (ready && deals.length === 0) {
    return <Card title="Pipeline" icon={I.trend}><div className="dim" style={{ padding: '14px 2px' }}>No open deals in this scope.</div></Card>;
  }

  const byStage: Record<string, Deal[]> = {};
  stages.forEach((s) => { byStage[s] = deals.filter((d) => d.stage === s); });
  const weighted = deals
    .filter((d) => OPEN_STAGES.indexOf(d.stage ?? '') >= 0)
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
function CreditLimitRequest({ party, draft, setDraft, submitting, onCancel, onSubmit }: {
  party: Party; draft: string; setDraft: (s: string) => void; submitting: boolean;
  onCancel: () => void; onSubmit: () => void;
}) {
  const current = party.credit?.limit != null ? party.credit.limit : party.credit_limit;
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

// ============================================================ DEAL BOOK (attributed customer POs)
interface DealRow {
  deal_id: string; company_name?: string | null; segment?: string | null; pipeline?: string | null;
  amount?: string | null; created_at?: string | null; closed_at?: string | null; won?: boolean; status?: string;
}
interface DealAgg { key: string; segment?: string | null; deals: number; won: number; won_value: string; attributed: number }

function DealBook({ book, summary, pipeline, setPipeline, status, setStatus, sort, dir, setSort, q, setQ, page, setPage, pageSize, hasCommercial }: {
  book: ReturnType<typeof useApi<{ rows?: DealRow[]; total?: number }>>;
  summary: ReturnType<typeof useApi<{ segments?: DealAgg[]; pipelines?: DealAgg[] }>>;
  pipeline: string; setPipeline: (s: string) => void;
  status: string; setStatus: (s: string) => void;
  sort: string; dir: string; setSort: (s: string, d: string) => void;
  q: string; setQ: (v: string) => void;
  page: number; setPage: (n: number) => void; pageSize: number; hasCommercial: boolean;
}) {
  const pipes = (summary.data?.pipelines ?? []).filter((p) => p.deals > 0);
  const rows = book.data?.rows ?? [];
  const total = book.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const tone: Record<string, string> = { won: 'ok', lost: 'danger', open: 'warn' };
  const active = pipes.find((p) => p.key === pipeline);

  // a sortable column header: click toggles dir (or switches field, defaulting to desc)
  const SortTh = ({ field, label, align }: { field: string; label: string; align?: 'right' }) => {
    const on = sort === field;
    const arrow = on ? (dir === 'asc' ? ' ▲' : ' ▼') : '';
    return (
      <th style={{ textAlign: align, cursor: 'pointer', userSelect: 'none' }} data-testid={`deal-sort-${field}`}
        onClick={() => setSort(field, on && dir === 'desc' ? 'asc' : 'desc')}>{label}{arrow}</th>
    );
  };

  return (
    <div>
      {/* pipeline tabs */}
      <div className="seg" style={{ marginBottom: 14, flexWrap: 'wrap', rowGap: 6 }}>
        <button className={pipeline === 'all' ? 'on' : ''} data-testid="deal-pipe-all" onClick={() => setPipeline('all')}>
          All<span className="dim" style={{ marginLeft: 6 }}>{num(pipes.reduce((a, p) => a + p.deals, 0))}</span>
        </button>
        {summary.isLoading && <span className="dim" style={{ padding: '4px 8px', fontSize: 12 }}>loading pipelines…</span>}
        {pipes.map((p) => (
          <button key={p.key} className={pipeline === p.key ? 'on' : ''} data-testid={`deal-pipe-${p.key}`} onClick={() => setPipeline(p.key)}>
            {p.key}<span className="dim" style={{ marginLeft: 6 }}>{num(p.deals)}</span>
          </button>
        ))}
      </div>

      {/* filter + sort bar */}
      <div className="loadbar" style={{ marginBottom: 12, gap: 8, flexWrap: 'wrap' }}>
        <select className="cellinput" value={status} onChange={(e) => setStatus(e.target.value)} data-testid="deal-status">
          <option value="all">All statuses</option>
          <option value="won">Won</option>
          <option value="lost">Lost</option>
          <option value="open">Open</option>
        </select>
        <select className="cellinput" value={`${sort}:${dir}`} data-testid="deal-sortsel"
          onChange={(e) => { const [s, d] = e.target.value.split(':'); setSort(s, d); }}>
          <option value="created:desc">Newest first</option>
          <option value="created:asc">Oldest first</option>
          <option value="amount:desc">Amount high → low</option>
          <option value="amount:asc">Amount low → high</option>
          <option value="company:asc">Company A → Z</option>
          <option value="closed:desc">Recently closed</option>
        </select>
        <input className="cellinput" style={{ width: 220, textAlign: 'left' }} value={q} data-testid="deal-search"
          onChange={(e) => setQ(e.target.value)} placeholder="Search company…" />
        <span className="dim" style={{ fontSize: 12, marginLeft: 'auto' }}>
          {num(total)} deals{active && hasCommercial && Number(active.won_value) > 0 ? ` · ${gbp(active.won_value, 'GBP')} won` : ''}
        </span>
      </div>

      <Card title={pipeline === 'all' ? 'Customer deal / PO book' : pipeline} icon={I.list}
        aux={<span className="dim" style={{ fontSize: 11.5 }}>attributed to the installer / wholesaler / retail customer</span>}
        className="tablewrap" style={{ padding: 0 }}>
        <table className="tbl">
          <thead><tr>
            <SortTh field="company" label="Company" />
            <th>Segment</th><th>Pipeline</th>
            <SortTh field="amount" label="Amount" align="right" />
            <SortTh field="created" label="Created" />
            <th>Status</th>
          </tr></thead>
          <tbody>
            {book.isLoading && <><SkeletonRow cols={6} /><SkeletonRow cols={6} /><SkeletonRow cols={6} /></>}
            {!book.isLoading && rows.length === 0 && <EmptyRow cols={6}>No deals match.</EmptyRow>}
            {rows.map((d) => (
              <tr key={d.deal_id} data-testid="deal-row">
                <td>{d.company_name || <span className="dim">unattributed</span>}</td>
                <td><span className="dim">{d.segment || '—'}</span></td>
                <td className="dim" style={{ fontSize: 12 }}>{d.pipeline || '—'}</td>
                <td style={{ textAlign: 'right' }}>{hasCommercial ? gbp(d.amount, 'GBP') : <span className="dim">·</span>}</td>
                <td className="dim" style={{ fontSize: 12 }}>{d.created_at || '—'}</td>
                <td><Chip s={tone[d.status || ''] || 'neutral'}>{d.status || '—'}</Chip></td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>

      <div className="row g8" style={{ marginTop: 12, justifyContent: 'flex-end', alignItems: 'center' }}>
        <button className="btn sm" disabled={page <= 0} onClick={() => setPage(page - 1)} data-testid="deal-prev">Prev</button>
        <span className="dim" style={{ fontSize: 12 }}>Page {page + 1} of {pages}</span>
        <button className="btn sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)} data-testid="deal-next">Next</button>
      </div>
    </div>
  );
}

// ============================================================ CONDUIT MASTER ACCOUNTS (golden record)
interface MasterAccount {
  id: string; name: string; segment?: string | null; type?: string | null;
  mrpeasy?: number; hubspot_companies?: number; contacts?: number; branches?: number; orders?: number; order_value?: string;
}
interface AcctSource { system: string; source_id: string; name?: string | null; method?: string; confidence?: number }
interface AcctContact { name?: string; email?: string | null; phone?: string | null; role?: string | null; entity_type?: string }
interface AcctBranch { id: string; name: string; orders?: number }
interface AcctOrder { order_no?: string; date?: string; total?: string | number }
interface AcctCharger {
  id: string; serial: string; sku?: string | null; status?: string; activated_at?: string | null;
  warranty_end?: string | null; warranty_days_left?: number; replaces?: string | null; replaced_by?: string[] | null;
}
interface AccountDetail {
  id: string; name: string; segment?: string | null; type?: string | null;
  parent?: { id: string; name: string } | null;
  sources?: AcctSource[]; contacts?: AcctContact[]; branches?: AcctBranch[]; orders?: AcctOrder[]; chargers?: AcctCharger[];
}

const SRC_LABEL: Record<string, string> = { mrpeasy: 'MRPeasy', hubspot_company: 'HubSpot', hubspot_contact: 'Contact' };

function AccountsView({ list, detail, seg, setSeg, q, setQ, page, setPage, pageSize, sel, setSel, hasCommercial, toast }: {
  list: ReturnType<typeof useApi<{ rows?: MasterAccount[]; total?: number }>>;
  detail: ReturnType<typeof useApi<AccountDetail>>;
  seg: string; setSeg: (s: string) => void; q: string; setQ: (v: string) => void;
  page: number; setPage: (n: number) => void; pageSize: number;
  sel: string | null; setSel: (id: string | null) => void; hasCommercial: boolean;
  toast: (m: string, k?: string) => void;
}) {
  const rows = list.data?.rows ?? [];
  const total = list.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const d = detail.data ?? null;
  const segs = ['all', 'installer', 'wholesaler', 'retail', 'energy', 'automotive', 'commercial', 'other'];

  return (
    <div className="grid" style={{ gridTemplateColumns: sel ? '1.3fr 1fr' : '1fr', gap: 16, alignItems: 'start' }}>
      <div>
        <div className="loadbar" style={{ marginBottom: 12, gap: 8 }}>
          <select className="cellinput" value={seg} onChange={(e) => setSeg(e.target.value)} data-testid="acct-seg">
            {segs.map((s) => <option key={s} value={s}>{s === 'all' ? 'All segments' : s}</option>)}
          </select>
          <input className="cellinput" style={{ width: 280, textAlign: 'left' }} value={q} data-testid="acct-search"
            onChange={(e) => setQ(e.target.value)} placeholder="Search by name, email, or contact…" />
          <span className="dim" style={{ fontSize: 12, marginLeft: 'auto' }}>{num(total)} Conduit accounts</span>
        </div>
        <Card title="Conduit accounts" icon={I.user} aux={<span className="dim" style={{ fontSize: 11.5 }}>one entity · MRPeasy + HubSpot + contacts as parts</span>} className="tablewrap" style={{ padding: 0 }}>
          <table className="tbl">
            <thead><tr><th>Account</th><th>Segment</th><th>Sources</th><th style={{ textAlign: 'right' }}>Contacts</th><th style={{ textAlign: 'right' }}>Branches</th><th style={{ textAlign: 'right' }}>Orders</th></tr></thead>
            <tbody>
              {list.isLoading && <><SkeletonRow cols={6} /><SkeletonRow cols={6} /><SkeletonRow cols={6} /></>}
              {!list.isLoading && rows.length === 0 && <EmptyRow cols={6}>No accounts match.</EmptyRow>}
              {rows.map((a) => (
                <tr key={a.id} data-testid="acct-row" onClick={() => setSel(a.id)}
                  style={{ cursor: 'pointer', background: sel === a.id ? 'var(--surface-2)' : undefined }}>
                  <td><b>{a.name}</b></td>
                  <td><span className="dim">{a.segment || '—'}</span></td>
                  <td>
                    {(a.mrpeasy ?? 0) > 0 && <Chip s="approved">MRP</Chip>}
                    {(a.hubspot_companies ?? 0) > 0 && <Chip s="accent">HS</Chip>}
                  </td>
                  <td style={{ textAlign: 'right' }}>{num(a.contacts ?? 0)}</td>
                  <td style={{ textAlign: 'right' }}>{(a.branches ?? 0) > 0 ? num(a.branches!) : <span className="dim">—</span>}</td>
                  <td style={{ textAlign: 'right' }}>{num(a.orders ?? 0)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Card>
        <div className="row g8" style={{ marginTop: 12, justifyContent: 'flex-end', alignItems: 'center' }}>
          <button className="btn sm" disabled={page <= 0} onClick={() => setPage(page - 1)} data-testid="acct-prev">Prev</button>
          <span className="dim" style={{ fontSize: 12 }}>Page {page + 1} of {pages}</span>
          <button className="btn sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)} data-testid="acct-next">Next</button>
        </div>
      </div>

      {sel && (
        <Card title={d?.name || 'Account'} icon={I.user}
          aux={<button className="btn ghost sm" onClick={() => setSel(null)}>Close</button>}>
          {detail.isLoading && <Skeleton lines={8} />}
          {!detail.isLoading && d && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
              <div className="row g8" style={{ flexWrap: 'wrap' }}>
                {d.segment && <Chip s="neutral">{d.segment}</Chip>}
                {d.type && <Chip s="neutral">{d.type}</Chip>}
                {d.parent && <Chip s="warn">branch of {d.parent.name}</Chip>}
              </div>

              <BranchAssign account={d} onChanged={() => { detail.refetch(); list.refetch(); }} toast={toast} />


              <div>
                <div className="fldlabel" style={{ marginBottom: 6 }}>Source systems (lineage)</div>
                {(d.sources ?? []).length === 0 && <div className="dim" style={{ fontSize: 12 }}>No source links.</div>}
                {(d.sources ?? []).map((s, i) => (
                  <div key={i} className="row between" style={{ fontSize: 12.5, padding: '3px 0' }}>
                    <span><Chip s={s.system === 'mrpeasy' ? 'approved' : 'accent'}>{SRC_LABEL[s.system] || s.system}</Chip> <span className="mono">{s.name || s.source_id}</span></span>
                    <span className="dim">{s.method}{s.confidence != null ? ` · ${(Number(s.confidence) * 100).toFixed(0)}%` : ''}</span>
                  </div>
                ))}
              </div>

              {(d.branches ?? []).length > 0 && (
                <div>
                  <div className="fldlabel" style={{ marginBottom: 6 }}>Branches ({d.branches!.length})</div>
                  {d.branches!.map((b) => (
                    <div key={b.id} className="row between" style={{ fontSize: 12.5, padding: '2px 0', cursor: 'pointer' }} onClick={() => setSel(b.id)}>
                      <span>{b.name}</span><span className="dim">{num(b.orders ?? 0)} orders</span>
                    </div>
                  ))}
                </div>
              )}

              <div>
                {(() => {
                  const cs = d.contacts ?? [];
                  const ends = cs.filter((c) => c.entity_type === 'end_customer').length;
                  return (
                    <div className="fldlabel" style={{ marginBottom: 6 }}>
                      Contacts ({cs.length}){ends > 0 && <span className="dim"> · {ends} end customer{ends > 1 ? 's' : ''}</span>}
                    </div>
                  );
                })()}
                {(d.contacts ?? []).length === 0 && <div className="dim" style={{ fontSize: 12 }}>No contacts.</div>}
                {(d.contacts ?? []).slice(0, 25).map((c, i) => (
                  <div key={i} className="row between" style={{ fontSize: 12.5, padding: '2px 0' }}>
                    <span>{c.name || <span className="dim">—</span>}
                      {c.entity_type === 'end_customer'
                        ? <Chip s="accent">end customer</Chip>
                        : c.role && <span className="dim"> · {c.role}</span>}
                    </span>
                    <span className="dim mono" style={{ fontSize: 11 }}>{c.email || ''}</span>
                  </div>
                ))}
                {(d.contacts ?? []).length > 25 && <div className="dim" style={{ fontSize: 11, marginTop: 4 }}>+{d.contacts!.length - 25} more</div>}
              </div>

              {(d.chargers ?? []).length > 0 && (
                <div>
                  <div className="fldlabel" style={{ marginBottom: 6 }}>Chargers owned ({d.chargers!.length})</div>
                  {d.chargers!.slice(0, 30).map((ch) => {
                    const days = ch.warranty_days_left ?? 0;
                    const warr = !ch.warranty_end ? null : days > 0
                      ? { t: (days / 365).toFixed(1) + ' yr left', s: days < 90 ? 'warn' : 'ok' }
                      : { t: 'warranty expired', s: 'danger' };
                    return (
                      <div key={ch.id} style={{ padding: '4px 0', borderBottom: '1px solid var(--border)' }}>
                        <div className="row between" style={{ fontSize: 12.5 }}>
                          <span className="mono" style={{ cursor: 'pointer', color: 'var(--accent-bright)' }}
                            title="copy serial to trace in Batch & genealogy"
                            onClick={() => { navigator.clipboard?.writeText(ch.serial); toast('Serial copied — trace it in Batch & genealogy', 'ok'); }}>
                            {ch.serial}</span>
                          <span>{warr && <Chip s={warr.s}>{warr.t}</Chip>} <Chip s={ch.status === 'activated' ? 'ok' : 'neutral'}>{ch.status}</Chip></span>
                        </div>
                        <div className="dim" style={{ fontSize: 11 }}>
                          {[ch.sku, ch.activated_at ? 'activated ' + String(ch.activated_at).slice(0, 10) : null].filter(Boolean).join(' · ')}
                          {ch.replaces && <span> · replaced <span className="mono">{ch.replaces}</span></span>}
                          {(ch.replaced_by ?? []).length > 0 && <span> · → replaced by <span className="mono">{ch.replaced_by!.join(', ')}</span></span>}
                        </div>
                      </div>
                    );
                  })}
                  {d.chargers!.length > 30 && <div className="dim" style={{ fontSize: 11, marginTop: 4 }}>+{d.chargers!.length - 30} more</div>}
                </div>
              )}

              <div>
                <div className="fldlabel" style={{ marginBottom: 6 }}>Recent orders ({(d.orders ?? []).length})</div>
                {(d.orders ?? []).length === 0 && <div className="dim" style={{ fontSize: 12 }}>No orders.</div>}
                {(d.orders ?? []).slice(0, 12).map((o, i) => (
                  <div key={i} className="row between" style={{ fontSize: 12.5, padding: '2px 0' }}>
                    <span className="mono">{o.order_no}</span>
                    <span className="dim">{o.date}{hasCommercial && o.total != null ? ` · ${gbp(o.total, 'GBP')}` : ''}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </Card>
      )}
    </div>
  );
}

// ============================================================ MATCH REVIEW (fuzzy candidates + model verdict)
interface ReviewCand { party_id: string; name: string; orders?: number; score?: number }
interface ReviewItem {
  hs_company_id: string; hs_name?: string; hs_domain?: string | null;
  model_suggestion?: string | null; model_confidence?: number | null; model_reason?: string | null;
  candidates?: ReviewCand[];
}

function ReviewQueue({ review, page, setPage, pageSize, onChanged, toast }: {
  review: ReturnType<typeof useApi<{ rows?: ReviewItem[]; total?: number }>>;
  page: number; setPage: (n: number) => void; pageSize: number; onChanged: () => void; toast: (m: string, k?: string) => void;
}) {
  const rows = review.data?.rows ?? [];
  const total = review.data?.total ?? 0;
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const [busy, setBusy] = useState<string | null>(null);

  const act = async (path: string, body: unknown, label: string, hs: string) => {
    setBusy(hs);
    try {
      await request(path, { method: 'POST', body: JSON.stringify(body) });
      toast(label, 'ok');
      onChanged();
    } catch (e) {
      const ae = e as ApiError;
      toast(ae?.forbidden ? 'Forbidden — requires edit rights' : `Failed (${ae?.status ?? '?'})`, 'err');
    } finally {
      setBusy(null);
    }
  };

  // default merge target = the candidate whose name matches the model's suggestion
  const winnerFor = (it: ReviewItem): ReviewCand | undefined =>
    (it.candidates ?? []).find((c) => c.name === it.model_suggestion) ?? (it.candidates ?? [])[0];

  return (
    <div>
      <div className="row between" style={{ marginBottom: 12, alignItems: 'center' }}>
        <span className="dim" style={{ fontSize: 12 }}>{num(total)} companies to review · model verdict + order-history shown · accept merges into the survivor, preserving lineage</span>
      </div>
      {review.isLoading && <Card title="Match review" icon={I.user}><Skeleton lines={8} /></Card>}
      {!review.isLoading && rows.length === 0 && <Card title="Match review" icon={I.user}><div className="dim" style={{ padding: 14 }}>Nothing left to review — every candidate resolved.</div></Card>}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        {rows.map((it) => {
          const win = winnerFor(it);
          const conf = it.model_confidence != null ? Math.round(Number(it.model_confidence) * 100) : null;
          return (
            <Card key={it.hs_company_id} title={it.hs_name || 'HubSpot company'}
              aux={<span className="dim mono" style={{ fontSize: 11 }}>{it.hs_domain || ''}</span>}>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                {it.model_suggestion ? (
                  <div className="banner info" style={{ fontSize: 12.5 }}>
                    {I.shield()}<div><b>Model:</b> merge into <b>{it.model_suggestion}</b>{conf != null && <Chip s={conf >= 90 ? 'ok' : conf >= 75 ? 'warn' : 'neutral'}>{conf}%</Chip>}<br /><span className="dim">{it.model_reason}</span></div>
                  </div>
                ) : (
                  <div className="dim" style={{ fontSize: 12 }}>No model suggestion — likely distinct or junk.</div>
                )}
                <div>
                  <div className="fldlabel" style={{ marginBottom: 4 }}>Candidate accounts (order history)</div>
                  {(it.candidates ?? []).slice(0, 6).map((c) => (
                    <div key={c.party_id} className="row between" style={{ fontSize: 12.5, padding: '2px 0' }}>
                      <span>{c.name === it.model_suggestion ? <b>{c.name}</b> : c.name}</span>
                      <span className="dim">{num(c.orders ?? 0)} orders · {Math.round(Number(c.score ?? 0) * 100)}%</span>
                    </div>
                  ))}
                </div>
                <div className="row g8">
                  <button className="btn primary sm" disabled={!win || busy === it.hs_company_id} data-testid="review-merge"
                    onClick={() => win && act('/api/v1/crm/account-candidates/merge', { hs_company_id: it.hs_company_id, winner_party_id: win.party_id }, `Merged into ${win.name}`, it.hs_company_id)}>
                    {I.check({ size: 13 })}Merge into {win?.name ?? '—'}
                  </button>
                  <button className="btn ghost sm" disabled={busy === it.hs_company_id} data-testid="review-reject"
                    onClick={() => act('/api/v1/crm/account-candidates/reject', { hs_company_id: it.hs_company_id }, 'Marked distinct', it.hs_company_id)}>
                    Distinct (reject)
                  </button>
                </div>
              </div>
            </Card>
          );
        })}
      </div>
      <div className="row g8" style={{ marginTop: 12, justifyContent: 'flex-end', alignItems: 'center' }}>
        <button className="btn sm" disabled={page <= 0} onClick={() => setPage(page - 1)}>Prev</button>
        <span className="dim" style={{ fontSize: 12 }}>Page {page + 1} of {pages}</span>
        <button className="btn sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next</button>
      </div>
    </div>
  );
}

// ---- Branch hierarchy assignment (CEF-style): make an account a branch of a parent, or detach it ----
function BranchAssign({ account, onChanged, toast }: {
  account: AccountDetail; onChanged: () => void; toast: (m: string, k?: string) => void;
}) {
  const [q, setQ] = useState('');
  const [matches, setMatches] = useState<MasterAccount[]>([]);
  const [busy, setBusy] = useState(false);

  const search = async () => {
    if (!q.trim()) return;
    try {
      const r: any = await request(`/api/v1/crm/accounts?q=${encodeURIComponent(q.trim())}&limit=8`);
      const rows = (r?.rows ?? r?.json?.rows ?? []) as MasterAccount[];
      setMatches(rows.filter((m) => m.id !== account.id));
    } catch { setMatches([]); }
  };
  const setParent = async (parentId: string | null, label: string) => {
    setBusy(true);
    try {
      await request(`/api/v1/crm/accounts/${encodeURIComponent(account.id)}/parent`, { method: 'POST', body: JSON.stringify({ parent_id: parentId }) });
      toast(label, 'ok'); setQ(''); setMatches([]); onChanged();
    } catch (e) {
      const ae = e as ApiError;
      toast(ae?.forbidden ? 'Forbidden — requires edit rights' : `Failed (${ae?.status ?? '?'})`, 'err');
    } finally { setBusy(false); }
  };

  return (
    <div>
      <div className="fldlabel" style={{ marginBottom: 6 }}>Hierarchy</div>
      {account.parent ? (
        <div className="row between" style={{ fontSize: 12.5 }}>
          <span>Branch of <b>{account.parent.name}</b></span>
          <button className="btn ghost sm" disabled={busy} data-testid="branch-detach" onClick={() => setParent(null, 'Made standalone')}>Make standalone</button>
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <div className="row g8">
            <input className="cellinput" style={{ flex: 1, textAlign: 'left' }} value={q} data-testid="branch-search"
              placeholder="Make this a branch of… (search parent account)"
              onChange={(e) => setQ(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && search()} />
            <button className="btn sm" onClick={search}>Find</button>
          </div>
          {matches.map((m) => (
            <div key={m.id} className="row between" style={{ fontSize: 12.5, padding: '2px 0' }}>
              <span>{m.name} <span className="dim">· {num(m.orders ?? 0)} orders</span></span>
              <button className="btn ghost sm" disabled={busy} data-testid="branch-set" onClick={() => setParent(m.id, `Set as branch of ${m.name}`)}>Set as parent</button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
