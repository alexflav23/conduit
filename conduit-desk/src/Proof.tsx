import React, { useState } from 'react';
import { useApi, request } from './lib/query';
import { ApiError } from './lib/client';
import { PageHead, Card, Chip, LayerNote, AuditRef, Skeleton, EmptyRow } from './kit/kit';
import { I } from './kit/icons';

// 14 — Proof Center (doc 31). The interactive formal proof the CTO/auditor uses to convince themselves the
// books are sound: the law register that re-runs its controls live (green is EARNED, never cached), the
// per-invoice ASC-606 walk, the journal walk with conservation recomputed in THIS browser, the trial-balance
// tie, and the non-prod tamper sandbox (corrupt → the control names the break → restore → green).
//
// Real endpoints (api ProofRoutes), all gated view:proof_center, tamper additionally manage:proof_center + non-prod:
//   GET  /api/v1/proof/laws                          -> { laws: [{ id, title, statement, mechanism, origin, pins[] }] }
//   POST /api/v1/proof/controls/{code}/run           -> { code, result, violations }
//   GET  /api/v1/proof/trial-balance/{entityId}      -> { balanced, total_debits, total_credits, currency, accounts[] }  (entityId MUST be a uuid)
//   GET  /api/v1/proof/journal/{invoiceNo}           -> { invoice_no, currency, legs[], cm_po? }
//   GET  /api/v1/proof/asc606/{orderId}              -> { order_no, customer, units, currency, steps[], principal_lrd? }  (orderId MUST be a uuid; 404 unknown)
//   POST /api/v1/proof/tamper/{kind}                 -> { ... } | 404 (prod) | 403 (no manage)
//   POST /api/v1/proof/tamper-restore                -> { ... } | 404 (prod) | 403 (no manage)
//
// Data-layer wall: the principal/LRD ASC-606 overlay + the journal money are inter_entity/commercial — when
// the viewer lacks the layer the figure/overlay is ABSENT from the payload, so the UI collapses (a LayerNote),
// never a zero. All states (loading / empty / 403 / 404 / error) on every sub-page; everything auto-loads.

type Role = { token: string; title?: string; layers?: string[] };
type Ctx = { entity?: string; market?: string; period?: string; scenario?: string };
type Toast = (m: string, k?: string) => void;

const arr = <T,>(x: unknown): T[] => (Array.isArray(x) ? (x as T[]) : []);
const isUuid = (s: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-/i.test(s || '');
const forbidden = (e: unknown): e is ApiError => e instanceof ApiError && e.forbidden;
const notImpl = (e: unknown): e is ApiError => e instanceof ApiError && e.notImplemented;

function relTime(iso?: string | null): string {
  if (!iso) return '';
  const s = Math.round((Date.now() - new Date(iso).getTime()) / 1000);
  return s < 5 ? 'just now' : s < 60 ? `${s}s ago` : `${Math.round(s / 60)}m ago`;
}

function NotAvailable({ testid }: { testid?: string }) {
  return (
    <div className="card" data-testid={testid} style={{ textAlign: 'center', padding: '40px 24px' }}>
      <div style={{ marginBottom: 10, opacity: 0.5 }}>{I.alert({ size: 26 })}</div>
      <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600 }}>Not available in this environment yet</div>
      <div className="dim" style={{ fontSize: 12.5, marginTop: 6 }}>This surface has no backing endpoint here.</div>
    </div>
  );
}

// gbp formatter local to this view (so step/flash/leg figures stay mono-string without a kit Money node wrap).
function gbpStr(v: number | string | null | undefined, ccy?: string): string {
  if (v == null || v === '') return '—';
  const n = typeof v === 'string' ? parseFloat(v) : v;
  const sym = ccy === 'USD' ? '$' : ccy === 'EUR' ? '€' : '£';
  return (n < 0 ? '−' : '') + sym + Math.abs(n).toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

// 1 — LAWS register: each control pin re-performs live on click; green is earned now, never cached.
function Laws({ role }: { role: Role }) {
  const q = useApi<{ laws?: any[] }>(['proof-laws'], '/api/v1/proof/laws');
  const [overrides, setOverrides] = useState<Record<string, { last_result?: string; last_violations?: number; ran_at?: string }>>({});
  const [running, setRunning] = useState<string | null>(null);

  const laws = arr<any>(q.data?.laws);
  const err = q.error;
  const isForbidden = forbidden(err);
  const isNotImpl = notImpl(err);
  const isOtherError = err && !isForbidden && !isNotImpl;

  const reRun = async (code: string) => {
    setRunning(code);
    try {
      const r = await request<{ result?: string; violations?: number }>(`/api/v1/proof/controls/${code}/run`, { method: 'POST' });
      setOverrides((prev) => ({ ...prev, [code]: { last_result: r?.result, last_violations: r?.violations ?? 0, ran_at: new Date().toISOString() } }));
    } catch {
      // a control run failure is surfaced as a fail pin on next read; keep the prior pin chip otherwise.
    }
    setRunning(null);
  };

  const chip = (r: string | null, v: number | null) =>
    r === 'pass' || (r == null && v === 0) ? 'pass' : r === 'fail' ? 'fail' : 'neutral';

  return (
    <Card title="The engineering formalism (doc 30 · L1–L14)" icon={I.shield}
      aux={<span className="dim" style={{ fontSize: 12 }}>statement · mechanism · the artifact that pins it — green is earned on click, never cached</span>}>
      {q.isLoading && <Skeleton lines={6} />}
      {!q.isLoading && isNotImpl && <NotAvailable testid="proof-laws-na" />}
      {!q.isLoading && isForbidden && <LayerNote>law register hidden — requires <b>view:proof_center</b></LayerNote>}
      {!q.isLoading && isOtherError && <div className="banner danger">{I.alert()}<div><span className="bb">Could not load.</span> The server returned an error.</div></div>}
      {!q.isLoading && !err && laws.length === 0 && <div className="dim" style={{ fontStyle: 'italic', padding: '6px 0' }}>No laws registered.</div>}
      {!q.isLoading && !err && laws.length > 0 && (
        <div className="grid" style={{ gridTemplateColumns: '1fr 1fr', alignItems: 'start' }}>
          {laws.map((l) => (
            <div className="card" key={l.id} data-testid="proof-law-row" style={{ padding: '16px 18px' }}>
              <div className="row g8" style={{ alignItems: 'center', marginBottom: 8 }}>
                <span className="chip accent" style={{ fontFamily: 'var(--font-mono)' }}><span className="d" />{l.id}</span>
                <b style={{ fontFamily: 'var(--font-disp)', fontSize: 15 }}>{l.title}</b>
              </div>
              <p style={{ fontSize: 13, lineHeight: 1.55, margin: '0 0 12px', color: 'var(--text)' }}>{l.statement}</p>
              {(l.mechanism || l.origin || l.origin_bug) && (
                <div className="kv" style={{ marginBottom: 12 }}>
                  {l.mechanism && <><span className="k">Mechanism</span><span className="v" style={{ color: 'var(--muted)' }}>{l.mechanism}</span></>}
                  {(l.origin || l.origin_bug) && <><span className="k">Origin bug</span><span className="v" style={{ color: 'var(--muted)' }}>{l.origin || l.origin_bug}</span></>}
                </div>
              )}
              <div style={{ display: 'grid', gap: 8 }}>
                {arr<any>(l.pins).map((p, i) => {
                  const o = overrides[p.ref];
                  const lastResult = o?.last_result ?? p.last_result ?? null;
                  const lastViol = o?.last_violations ?? p.last_violations ?? null;
                  const ranAt = o?.ran_at ?? p.last_run_at ?? p.ran_at;
                  return (
                    <div key={i} className="row between" style={{ padding: '9px 12px', border: '1px solid var(--border)', borderRadius: 10, background: 'var(--bg-2)' }}>
                      <div className="row g8" style={{ alignItems: 'center' }}>
                        <span className="mono dim" style={{ fontSize: 11 }}>{p.ref}</span>
                        {p.re_performable !== false ? (
                          <Chip s={chip(lastResult, lastViol)}>
                            <span data-testid={`proof-pin-${p.ref}`}>{lastResult ?? 'not run'}{lastViol != null ? ` · ${lastViol}` : ''}</span>
                          </Chip>
                        ) : <span className="chip neutral"><span className="d" />{p.kind ?? 'artifact'}</span>}
                        {ranAt && <span className="dim" style={{ fontSize: 10.5 }}>{relTime(ranAt)}</span>}
                      </div>
                      {p.re_performable !== false && (
                        <button className="btn sm primary" data-testid={`proof-run-${p.ref}`} disabled={running === p.ref} onClick={() => reRun(p.ref)}>
                          {running === p.ref ? 'Running…' : <>{I.refresh({ size: 12 })}Run</>}
                        </button>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      )}
    </Card>
  );
}

// 2 — ASC-606 five-step walk for ONE real order (orderId must be a uuid). The principal/LRD overlay is
// inter_entity-walled: absent for finance (a LayerNote), present (a banner) only for inter_entity holders.
function Asc606({ role, ctx }: { role: Role; ctx: Ctx; toast: Toast }) {
  const [orderId, setOrderId] = useState('');
  const [query, setQuery] = useState('');
  const hasMoney = (role.layers ?? []).indexOf('commercial') >= 0;
  const hasIc = (role.layers ?? []).indexOf('inter_entity') >= 0;

  const q = useApi<any>(
    ['proof-asc606', query, ctx.entity],
    `/api/v1/proof/asc606/${encodeURIComponent(query)}`,
    { enabled: !!query.trim() && isUuid(query.trim()) },
  );

  const badId = !!query.trim() && !isUuid(query.trim());
  const err = q.error;
  const isForbidden = forbidden(err);
  const isNotImpl = notImpl(err);
  const isBadReq = err instanceof ApiError && err.status === 400;
  const isOtherError = err && !isForbidden && !isNotImpl && !isBadReq;
  const b = q.data ?? null;
  const steps = arr<any>(b?.steps);
  const flash = b?.principal_lrd ?? b?.step5_recognition_flash;

  return (
    <Card title={b ? `ASC-606 · ${b.order_no ?? orderId}` : 'ASC-606 — the five steps for one real order'} icon={I.layers}
      aux={b ? <span className="dim" style={{ fontSize: 12 }}>{b.customer}{b.units != null ? ` · ${b.units} units` : ''}</span> : <span className="dim" style={{ fontSize: 12 }}>each step pinned to its law and control</span>}>
      <div className="loadbar">
        <span className="fldlabel">Order</span>
        <input className="cellinput" style={{ width: 280, textAlign: 'left' }} data-testid="asc606-order" value={orderId}
          onChange={(e) => setOrderId(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && setQuery(orderId)} placeholder="order id (uuid)" />
        <button className="btn primary" data-testid="asc606-load" onClick={() => setQuery(orderId)}>{I.search({ size: 13 })}Walk recognition</button>
      </div>

      {!query.trim() && <div className="dim" style={{ fontStyle: 'italic', padding: '14px 0 4px' }}>Enter an order id to walk its recognition.</div>}
      {badId && <div className="banner warn" style={{ marginTop: 8 }}>{I.alert()}<div><span className="bb">That isn’t a valid order id.</span> The ASC-606 walk resolves by order uuid.</div></div>}
      {!!query.trim() && isUuid(query.trim()) && (
        <>
          {q.isLoading && <div style={{ marginTop: 12 }}><Skeleton lines={5} /></div>}
          {!q.isLoading && isNotImpl && <div style={{ marginTop: 12 }}><div className="banner info" data-testid="asc606-empty">{I.search()}<div>No order found for <b>{query}</b>.</div></div></div>}
          {!q.isLoading && isBadReq && <div className="banner warn" style={{ marginTop: 12 }}>{I.alert()}<div><span className="bb">Invalid order id.</span> {err instanceof ApiError ? err.message : ''}</div></div>}
          {!q.isLoading && isForbidden && <div style={{ marginTop: 12 }}><LayerNote>recognition walk hidden — requires <b>view:proof_center</b></LayerNote></div>}
          {!q.isLoading && isOtherError && <div className="banner danger" style={{ marginTop: 12 }}>{I.alert()}<div><span className="bb">Could not walk recognition.</span> The server returned an error.</div></div>}
          {!q.isLoading && !err && b && steps.length === 0 && <div className="dim" style={{ fontStyle: 'italic', padding: '14px 0 4px' }}>No recognition steps recorded for this order.</div>}
          {!q.isLoading && !err && b && steps.length > 0 && (
            <>
              <div data-testid="asc606-order-head" style={{ marginBottom: 8 }}>
                {steps.map((s: any, i: number) => (
                  <div className="row g12" key={s.n ?? i} style={{ alignItems: 'flex-start', padding: '4px 0 18px' }} data-testid={`asc606-step-${s.n ?? i}`}>
                    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', flex: '0 0 30px' }}>
                      <span style={{ width: 30, height: 30, borderRadius: 9, display: 'grid', placeItems: 'center', fontFamily: 'var(--font-disp)', fontWeight: 600, fontSize: 13, background: s.recognised ? 'var(--ok)' : 'var(--accent-subtle)', color: s.recognised ? 'var(--on-accent)' : 'var(--accent-bright)' }}>{s.recognised ? I.check({ size: 16 }) : (s.n ?? i + 1)}</span>
                      {i < steps.length - 1 && <span style={{ width: 2, flex: 1, minHeight: 26, background: 'var(--border)', marginTop: 4 }} />}
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div className="row between">
                        <b style={{ fontSize: 13.5 }}>{s.name}</b>
                        {s.value != null && (hasMoney ? <span className="num">{gbpStr(s.value, b.currency)}</span> : <span className="dim" style={{ fontSize: 11.5 }}>hidden — requires commercial</span>)}
                      </div>
                      {s.detail && <p className="dim" style={{ fontSize: 12.5, lineHeight: 1.5, margin: '4px 0 0', maxWidth: 640 }}>{s.detail}</p>}
                      {arr<string>(s.pins).length > 0 && (
                        <div className="row g8" style={{ flexWrap: 'wrap', marginTop: 6 }}>
                          {arr<string>(s.pins).map((x, j) => <span key={j} className="aref mono" style={{ fontSize: 10.5 }}>{x}</span>)}
                        </div>
                      )}
                    </div>
                  </div>
                ))}
              </div>
              {hasIc && flash ? (
                <div className="banner info" data-testid="asc606-flash" style={{ marginTop: 4 }}>
                  {I.layers()}<div><span className="bb">Principal / LRD overlay (inter-entity).</span> {flash.note ?? flash.explain}
                    {arr<any>(flash.matches).map((m: any, i: number) => (
                      <div key={i} style={{ marginTop: 4 }}>· landed {gbpStr(m.landed_total, b.currency)} → transfer {gbpStr(m.transfer_total, b.currency)} (uplift {gbpStr(m.uplift_total, b.currency)}{m.reversed ? ', reversed' : ''})</div>
                    ))}
                  </div>
                </div>
              ) : (
                <div data-testid="asc606-no-flash">
                  <LayerNote>Principal / LRD flash-title overlay hidden — requires the inter_entity layer. The two intercompany legs behind this single external recognition never reach your projection.</LayerNote>
                </div>
              )}
            </>
          )}
        </>
      )}
    </Card>
  );
}

// 3 — Journal walk: an invoice's DR/CR legs with conservation RECOMPUTED in the browser (Σdebits == Σcredits),
// plus the Journal Atlas link (AuditRef → the CM PO). Money collapses without the commercial layer.
function JournalWalk({ role, ctx }: { role: Role; ctx: Ctx; toast: Toast }) {
  const [invoiceNo, setInvoiceNo] = useState('INV-FLOW');
  const [query, setQuery] = useState('INV-FLOW');
  const hasMoney = (role.layers ?? []).indexOf('commercial') >= 0;

  const q = useApi<any>(
    ['proof-journal', query, ctx.entity],
    `/api/v1/proof/journal/${encodeURIComponent(query)}`,
    { enabled: !!query.trim() },
  );

  const err = q.error;
  const isForbidden = forbidden(err);
  const isNotImpl = notImpl(err);
  const isOtherError = err && !isForbidden && !isNotImpl;
  const walk = q.data ?? null;
  const legs = arr<any>(walk?.legs);
  const amt = (l: any) => Number(l.amount ?? l.amount_minor ?? 0);
  const side = (l: any) => String(l.side ?? '').toUpperCase();
  const debits = legs.filter((l) => side(l) === 'DR' || side(l) === 'DEBIT').reduce((a, l) => a + amt(l), 0);
  const credits = legs.filter((l) => side(l) === 'CR' || side(l) === 'CREDIT').reduce((a, l) => a + amt(l), 0);
  const balanced = Math.round(debits * 100) === Math.round(credits * 100);

  return (
    <Card title={walk ? `Journal · ${walk.invoice_no ?? invoiceNo}` : 'Journal walk'} icon={I.list}
      aux={<span className="dim" style={{ fontSize: 12 }}>conservation recomputed in your browser</span>}>
      <div className="loadbar">
        <span className="fldlabel">Invoice</span>
        <input className="cellinput" style={{ width: 200, textAlign: 'left' }} data-testid="proof-invoice" value={invoiceNo}
          onChange={(e) => setInvoiceNo(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && setQuery(invoiceNo)} placeholder="e.g. INV-FLOW" />
        <button className="btn primary" data-testid="proof-walk" onClick={() => setQuery(invoiceNo)}>{I.search({ size: 13 })}Walk journal</button>
        {walk && (walk.cm_po || walk.cm_po_ref) && <><div className="sp" /><AuditRef id={walk.cm_po ?? walk.cm_po_ref} /></>}
      </div>

      {!query.trim() && <div className="dim" style={{ fontStyle: 'italic', padding: '14px 0 4px' }}>Enter an invoice number to walk its journal.</div>}
      {!!query.trim() && (
        <>
          {q.isLoading && <div style={{ marginTop: 12 }}><Skeleton lines={4} /></div>}
          {!q.isLoading && isNotImpl && <div style={{ marginTop: 12 }}><div className="banner info" data-testid="proof-journal-empty">{I.search()}<div>No journal found for <b>{query}</b>.</div></div></div>}
          {!q.isLoading && isForbidden && <div style={{ marginTop: 12 }}><LayerNote>journal walk hidden — requires <b>view:proof_center</b></LayerNote></div>}
          {!q.isLoading && isOtherError && <div className="banner danger" style={{ marginTop: 12 }}>{I.alert()}<div><span className="bb">Could not walk journal.</span> The server returned an error.</div></div>}
          {!q.isLoading && !err && walk && (
            <>
              <div className="tablewrap">
                <table className="tbl">
                  <thead><tr><th>Account</th><th>Side</th><th className="num">Amount</th><th>Transfer id</th></tr></thead>
                  <tbody>
                    {legs.length === 0 && <EmptyRow cols={4}>No posted legs for that invoice.</EmptyRow>}
                    {legs.map((l, i) => (
                      <tr key={i} data-testid="proof-leg" className={l.orphan ? 'sel' : ''}>
                        <td><b style={{ fontWeight: 500 }}>{l.account ?? l.account_key}</b></td>
                        <td><span className={'chip ' + ((side(l) === 'DR' || side(l) === 'DEBIT') ? 'neutral' : 'accent')} style={{ padding: '1px 8px' }}><span className="d" />{(side(l) === 'DR' || side(l) === 'DEBIT') ? 'DR' : 'CR'}</span></td>
                        <td className="num">{hasMoney ? gbpStr(amt(l), l.currency ?? walk.currency) : <span className="dim" style={{ fontSize: 11.5 }}>hidden — commercial</span>}</td>
                        <td className="mono dim" style={{ fontSize: 10.5 }}>{String(l.transfer_id ?? l.tb_transfer_id ?? '').slice(0, 16)}…</td>
                      </tr>
                    ))}
                  </tbody>
                  {legs.length > 0 && (
                    <tfoot>
                      <tr><td colSpan={2} style={{ textAlign: 'right', fontWeight: 600 }}>Σ debits</td><td className="num"><b>{hasMoney ? gbpStr(debits, walk.currency) : 'hidden'}</b></td><td /></tr>
                      <tr><td colSpan={2} style={{ textAlign: 'right', fontWeight: 600 }}>Σ credits</td><td className="num"><b>{hasMoney ? gbpStr(credits, walk.currency) : 'hidden'}</b></td><td /></tr>
                    </tfoot>
                  )}
                </table>
              </div>
              {legs.length > 0 && (
                <div className={'banner ' + (balanced ? 'ok' : 'danger')} data-testid="proof-conservation" style={{ marginTop: 12 }}>
                  {balanced ? I.check() : I.alert()}
                  <div><span className="bb">{balanced ? 'Balanced — conservation holds.' : 'Conservation broken.'}</span> Σ debits {balanced ? '=' : '≠'} Σ credits, recomputed here in the browser from {legs.length} transfer legs — {balanced ? 'this is proof, not a stored flag.' : 'a leg is missing or zeroed (see the Tamper sandbox).'}</div>
                </div>
              )}
            </>
          )}
        </>
      )}
    </Card>
  );
}

// 3b — Reconcile: the trial balance ties (Σdebits == Σcredits per entity), from the gl_entry mirror.
// The trial-balance endpoint resolves by entity uuid (400 on a non-uuid), so the entity must be a uuid in ctx.
function Reconcile({ role, ctx }: { role: Role; ctx: Ctx }) {
  const hasMoney = (role.layers ?? []).indexOf('commercial') >= 0;
  const entity = (ctx.entity ?? '').trim();
  const entityOk = isUuid(entity);

  const q = useApi<any>(
    ['proof-tb', entity],
    `/api/v1/proof/trial-balance/${encodeURIComponent(entity)}`,
    { enabled: entityOk },
  );

  const err = q.error;
  const isForbidden = forbidden(err);
  const isNotImpl = notImpl(err);
  const isOtherError = err && !isForbidden && !isNotImpl;
  const tb = q.data ?? null;
  const accounts = arr<any>(tb?.accounts);

  return (
    <Card title="Reconcile — the trial balance ties" icon={I.scale}
      aux={<span className="dim" style={{ fontSize: 12 }}>per entity, from the gl_entry mirror</span>}>
      {!entityOk && (
        <div className="banner info">{I.alert()}<div><span className="bb">Pick an entity.</span> The trial balance ties per operating entity — choose one (by id) in the context bar above.</div></div>
      )}
      {entityOk && (
        <>
          {q.isLoading && <Skeleton lines={5} />}
          {!q.isLoading && isNotImpl && <NotAvailable testid="proof-tb-na" />}
          {!q.isLoading && isForbidden && <LayerNote>trial balance hidden — requires <b>view:proof_center</b></LayerNote>}
          {!q.isLoading && isOtherError && <div className="banner danger">{I.alert()}<div><span className="bb">Could not load the trial balance.</span> The server returned an error.</div></div>}
          {!q.isLoading && !err && tb && (
            <>
              <div className={'banner ' + (tb.balanced ? 'ok' : 'danger')} data-testid="proof-tb-balanced" style={{ marginBottom: 12 }}>
                {tb.balanced ? I.check() : I.alert()}
                <div><span className="bb">{tb.balanced ? 'Balanced.' : 'Out of balance.'}</span> Σ DR {hasMoney ? gbpStr(tb.total_debits, tb.currency) : 'hidden'} {tb.balanced ? '=' : '≠'} Σ CR {hasMoney ? gbpStr(tb.total_credits, tb.currency) : 'hidden'}.</div>
              </div>
              <div className="tablewrap">
                <table className="tbl">
                  <thead><tr><th>Account</th><th>Ccy</th><th className="num">Debits</th><th className="num">Credits</th><th className="num">Balance</th></tr></thead>
                  <tbody>
                    {accounts.length === 0 && <EmptyRow cols={5}>No accounts for that entity.</EmptyRow>}
                    {accounts.map((a, i) => (
                      <tr key={i} data-testid="proof-tb-row">
                        <td>{a.account}</td><td>{a.currency}</td>
                        <td className="num">{hasMoney ? gbpStr(a.debits, a.currency) : '—'}</td>
                        <td className="num">{hasMoney ? gbpStr(a.credits, a.currency) : '—'}</td>
                        <td className="num">{hasMoney ? gbpStr(a.balance, a.currency) : '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </>
      )}
    </Card>
  );
}

const TAMPER_KINDS: [string, string, string][] = [
  ['delete_leg', 'Delete a ledger leg', 'Drop a journal CR leg from the invoice'],
  ['orphan_transfer', 'Orphan a transfer', 'Post to suspense with no owning invoice'],
  ['strip_reversal', 'Strip a reversal', 'Zero the VAT output reversal leg'],
];

// 4 — Tamper sandbox (admin, non-prod): corrupt → CTRL-LINEAGE-CLOSURE names the break → restore → green.
// Double-gated server-side: in prod the endpoint 404s (we surface "unavailable in production", not an error);
// without manage:proof_center it 403s. These are mutation flows (corrupt / restore / re-run), so they go through
// `request` directly rather than useApi — there is nothing to auto-load until the operator acts.
function Tamper({ role, toast }: { role: Role; toast: Toast }) {
  const [ctrl, setCtrl] = useState<any>(null);
  const [active, setActive] = useState(false);
  const [busy, setBusy] = useState(false);
  const [prodUnavailable, setProdUnavailable] = useState(false);
  const canManage = (role.layers ?? []).indexOf('inter_entity') >= 0 || (role.title ?? '').toLowerCase().includes('admin') || (role.title ?? '').toLowerCase().includes('ceo');

  const reRun = async () => {
    setBusy(true);
    try {
      const r = await request<{ result?: string; violations?: number; detail?: string; named?: string }>('/api/v1/proof/controls/CTRL-LINEAGE-CLOSURE/run', { method: 'POST' });
      setCtrl({ ...r, at: new Date().toISOString() });
    } catch (e) {
      toast(e instanceof ApiError ? (e.message ?? `control failed`) : 'control failed', 'err');
    }
    setBusy(false);
  };
  const doTamper = async (kind: string) => {
    try {
      await request(`/api/v1/proof/tamper/${kind}`, { method: 'POST' });
      setActive(true); setCtrl(null); toast(`Books corrupted — ${kind}`, 'warn');
    } catch (e) {
      if (notImpl(e)) { setProdUnavailable(true); toast('Tamper surface is unavailable in production', 'warn'); return; }
      if (forbidden(e)) { toast('Requires manage:proof_center', 'err'); return; }
      toast(e instanceof ApiError ? (e.message ?? 'tamper rejected') : 'tamper rejected', 'err');
    }
  };
  const restore = async () => {
    try {
      await request('/api/v1/proof/tamper-restore', { method: 'POST' });
      setActive(false); setCtrl(null); toast('Restored — books clean', 'ok');
    } catch (e) {
      if (notImpl(e)) { setProdUnavailable(true); return; }
      toast(e instanceof ApiError ? (e.message ?? 'restore failed') : 'restore failed', 'err');
    }
  };

  const lockTip = !canManage ? 'Needs manage:proof_center — not yours to operate' : prodUnavailable ? 'Unavailable in production' : undefined;
  const disabled = !canManage || prodUnavailable;
  return (
    <>
      {prodUnavailable && (
        <div className="banner warn" style={{ marginBottom: 14 }} data-testid="proof-tamper-prod">
          {I.shield()}<div><span className="bb">Unavailable in production.</span> The tamper sandbox endpoint does not exist in prod — there is nothing to operate here.</div>
        </div>
      )}
      {!canManage && !prodUnavailable && (
        <div className="banner warn" style={{ marginBottom: 14 }} data-testid="proof-tamper-readonly">
          {I.shield()}<div><span className="bb">Read-only.</span> The tamper sandbox needs <span className="mono">manage:proof_center</span> and a non-prod environment. As {role.title ?? 'this role'} you can watch, but not operate it. In production this surface does not exist at all.</div>
        </div>
      )}
      <div className="grid" style={{ gridTemplateColumns: '1fr 1fr', alignItems: 'start' }}>
        <Card title="Break the books" icon={I.alert} aux={<span className="dim" style={{ fontSize: 12 }}>non-prod sandbox — reversible</span>}>
          <p className="dim" style={{ fontSize: 12.5, lineHeight: 1.55, margin: '0 0 14px' }}>Each button injects a specific corruption. The lineage-closure control should catch it on the next run and name the break precisely.</p>
          <div style={{ display: 'grid', gap: 9 }}>
            {TAMPER_KINDS.map(([k, label, desc]) => (
              <button key={k} className="row between" data-testid={`proof-tamper-${k}`} title={lockTip} disabled={disabled || active}
                onClick={() => doTamper(k)}
                style={{ textAlign: 'left', padding: '11px 13px', border: '1px solid var(--border)', borderRadius: 10, background: 'var(--bg-2)', cursor: disabled || active ? 'not-allowed' : 'pointer', opacity: disabled || active ? 0.5 : 1, font: 'inherit', color: 'inherit' }}>
                <div><div style={{ fontWeight: 600, fontSize: 13 }}>{label}</div><div className="dim" style={{ fontSize: 11.5 }}>{desc}</div></div>
                {I.arrowR({ size: 15 })}
              </button>
            ))}
          </div>
          {active && canManage && !prodUnavailable && (
            <button className="btn primary" data-testid="proof-tamper-restore" style={{ width: '100%', justifyContent: 'center', marginTop: 12 }} onClick={restore}>{I.refresh({ size: 14 })}Restore to green</button>
          )}
        </Card>

        <Card title="CTRL-LINEAGE-CLOSURE" icon={I.shield} aux={<span className="dim" style={{ fontSize: 12 }}>re-performs live — green is earned now</span>}>
          <div style={{ textAlign: 'center', padding: '14px 0 18px' }} data-testid="proof-tamper-control">
            {ctrl ? (
              <>
                <div style={{ width: 64, height: 64, borderRadius: 18, display: 'grid', placeItems: 'center', margin: '0 auto 12px', background: ctrl.result === 'pass' ? 'var(--ok-bg)' : 'var(--danger-bg)', color: ctrl.result === 'pass' ? 'var(--ok)' : 'var(--danger)' }}>{ctrl.result === 'pass' ? I.check({ size: 32 }) : I.alert({ size: 32 })}</div>
                <div style={{ fontFamily: 'var(--font-disp)', fontSize: 22, fontWeight: 600, color: ctrl.result === 'pass' ? 'var(--ok)' : 'var(--danger)' }}>{ctrl.result === 'pass' ? 'PASS' : 'FAIL'}</div>
                <div className="dim" style={{ fontSize: 11.5, marginTop: 2 }}>{ctrl.violations ?? ctrl.violation_count ?? 0} violation{(ctrl.violations ?? ctrl.violation_count) === 1 ? '' : 's'} · re-performed {relTime(ctrl.at)}</div>
              </>
            ) : (
              <div className="dim" style={{ fontSize: 13, padding: '20px 0' }}>Not run yet. Re-perform the control to see {active ? 'it catch the break' : 'earned green'}.</div>
            )}
          </div>
          {ctrl && (ctrl.detail || ctrl.named) && <div className="banner danger" style={{ marginBottom: 12 }}>{I.alert()}<div><span className="bb">Named:</span> {ctrl.detail ?? ctrl.named}</div></div>}
          <button className="btn primary" style={{ width: '100%', justifyContent: 'center' }} disabled={busy} onClick={reRun}>{busy ? 'Re-performing…' : <>{I.refresh({ size: 14 })}Re-perform control</>}</button>
          <div className="layer-note">{I.shield()}The pass/fail is computed on click against the live ledger — never a cached badge. Corrupt the books, run it, watch it fail; restore, run it, watch green return.</div>
        </Card>
      </div>
    </>
  );
}

export function Proof({ role, ctx, toast }: { role: any; ctx: any; toast: (m: string, k?: string) => void }) {
  const t: Toast = (m, k) => toast(m, k);
  const [page, setPage] = useState<'laws' | 'asc606' | 'journal' | 'reconcile' | 'tamper'>('laws');
  const tab = (id: typeof page, label: string) => (
    <button className={page === id ? 'on' : ''} data-testid={`proof-nav-${id}`} onClick={() => setPage(id)}>{label}</button>
  );
  return (
    <div className="page" style={{ maxWidth: 1320 }}>
      <PageHead
        crumb="Interactive formal proof (doc 31) · the screen you demo to an auditor"
        title="Proof Center"
        sub="Convince yourself the books are sound. The law register re-runs its controls live — green is earned, never cached. Walk any recognition, recompute conservation in the browser, then break the books and watch a control name it."
        right={
          <div className="seg">
            {tab('laws', 'Laws')}
            {tab('asc606', 'ASC-606 walk')}
            {tab('journal', 'Journal walk')}
            {tab('reconcile', 'Reconcile')}
            {tab('tamper', 'Tamper sandbox')}
          </div>
        }
      />
      {page === 'laws' ? <Laws role={role} />
        : page === 'asc606' ? <Asc606 role={role} ctx={ctx} toast={t} />
        : page === 'journal' ? <JournalWalk role={role} ctx={ctx} toast={t} />
        : page === 'reconcile' ? <Reconcile role={role} ctx={ctx} />
        : <Tamper role={role} toast={t} />}
    </div>
  );
}
