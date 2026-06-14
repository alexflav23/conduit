import React, { useState } from 'react';
import { useApi } from './lib/query';
import { marketId } from './api';
import { PageHead, Card, Chip, Money, LayerNote, AuditRef, EmptyRow, LoadBar, SkeletonRow, num, gbp } from './kit/kit';
import { I } from './kit/icons';

// 21 — Activation ingest + warranty provision (spec/ui/21-activation.md). The sell-through + after-sales
// surface (doc 07 M8): charger ACTIVATIONS ingested first-write-wins from the UFE placement stream (the real
// "a unit went live at a customer", distinct from sell-in / dispatch), and the WARRANTY PROVISION each
// activation opens, releasing straight-line over the term from the activation date (not dispatch).
//
// Backend: M8 (Phase 2) — there is NO activation/warranty route in this deployment (no ActivationRoutes; the
// "activation" mentions under PricingRoutes/IntercompanyRoutes are maker-checker POLICY activation, unrelated).
// Both reads go through React Query against the paths these endpoints will land on; a 404 (notImplemented)
// renders the honest "Not available in this environment yet" panel — never a stuck skeleton, never a guessed
// call. 401/403 (forbidden) renders the layer wall. The screen is correct the moment the routes ship.
//
// Re-fetches on a context-market switch and on the in-page market filter (both feed the query key). Activation
// identity is the `volume` layer; warranty provision money is `profitability` and COLLAPSES (never £0).

type AnyRole = { layers?: string[] };

interface ActivationProps {
  role: AnyRole;
  ctx: { market?: string; entity?: string; period?: string; scenario?: string };
  toast: (m: string, k?: string) => void;
}

interface ActivationRow {
  sn?: string;
  serial?: string;
  activated_at?: string;
  activatedAt?: string;
  installer?: string;
  owner?: string;
  market?: string;
}

interface SellInVsThrough {
  dispatched?: number | null;
  activated?: number | null;
}

interface ActivationFeed {
  rows?: ActivationRow[];
  total?: number;
  sellInVsThrough?: SellInVsThrough;
  sell_in_vs_through?: SellInVsThrough;
}

interface WarrantyRow {
  sn?: string;
  serial?: string;
  owner?: string;
  provision?: number | string | null;
  outstanding?: number | string | null;
  pct?: number | string;
  releasedPct?: number | string;
  audit_ref?: string;
  auditRef?: string;
}

interface WarrantyTotals {
  provision?: number | string | null;
  outstanding?: number | string | null;
  released?: number | string | null;
}

interface WarrantyRegister {
  rows?: WarrantyRow[];
  totals?: WarrantyTotals;
  hasCost?: boolean;
}

const fmtPct = (n: number) => (Number.isFinite(n) ? n : 0).toFixed(0);

// An honest "endpoint not built" panel (404). Distinct from a stuck skeleton or a £0.
function NotAvailable({ which }: { which: string }) {
  return (
    <div
      data-testid="activation-not-available"
      style={{ padding: '28px 18px', textAlign: 'center', color: 'var(--muted)', border: '1px dashed var(--border)', borderRadius: 10, background: 'var(--bg-2)' }}
    >
      <div style={{ marginBottom: 6, color: 'var(--faint)' }}>{I.wifiOff({ size: 22 })}</div>
      <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600, color: 'var(--text)' }}>Not available in this environment yet</div>
      <div className="dim" style={{ fontSize: 12, marginTop: 4 }}>The {which} (M8 — activation ingest) isn't built in this deployment.</div>
    </div>
  );
}

export function Activation({ role, ctx, toast }: ActivationProps) {
  const layers = role?.layers ?? [];
  const canSeeProvision = layers.length === 0 || layers.indexOf('profitability') >= 0;

  const [market, setMarket] = useState('all');

  const feedQ = (() => {
    const mk = market !== 'all' ? market : ctx?.market || 'all';
    const q = mk && mk !== 'all' ? `?market=${encodeURIComponent(marketId(mk))}&limit=60` : '?limit=60';
    return q;
  })();

  const feedApi = useApi<ActivationFeed>(['activations', market, ctx?.market], `/api/v1/activations${feedQ}`);
  const warrApi = useApi<WarrantyRegister>(['warranty-provisions', ctx?.market], '/api/v1/warranty/provisions');

  const feedErr = feedApi.error;
  const feedForbidden = feedErr?.forbidden ?? false;
  const feedNotImpl = feedErr?.notImplemented ?? false;
  const feedOther = !!feedErr && !feedForbidden && !feedNotImpl;
  const feedReady = !feedApi.isLoading && !feedErr;

  const warrErr = warrApi.error;
  const warrForbidden = warrErr?.forbidden ?? false;
  const warrNotImpl = warrErr?.notImplemented ?? false;
  const warrOther = !!warrErr && !warrForbidden && !warrNotImpl;
  const warrReady = !warrApi.isLoading && !warrErr;

  const feed = feedApi.data ?? null;
  const warr = warrApi.data ?? null;

  const acts: ActivationRow[] = Array.isArray(feed?.rows) ? feed!.rows! : [];
  const sit = feed?.sellInVsThrough ?? feed?.sell_in_vs_through ?? null;
  const dispatched = sit?.dispatched ?? null;
  const activated = sit?.activated ?? null;
  const throughPct = dispatched ? ((activated ?? 0) / dispatched) * 100 : 0;
  const feedTotal = feed?.total ?? acts.length;

  const wrows: WarrantyRow[] = Array.isArray(warr?.rows) ? warr!.rows! : [];
  const wt = warr?.totals ?? null;
  // The register reports whether the cost layer was projected in; honour it but also respect role.layers.
  const provisionVisible = canSeeProvision && (warr ? warr.hasCost !== false : true);

  const heroNote =
    feedNotImpl ? 'not available yet' : feedForbidden ? 'requires the volume layer' : feedOther ? 'failed to load' : '';

  return (
    <div className="page" style={{ maxWidth: 1320 }}>
      <PageHead
        crumb="Sell-through & after-sales · doc 07 M8"
        title="Activation & Warranty"
        sub={
          <span style={{ display: 'block', maxWidth: 820 }}>
            Activations are the real sale signal — a unit went live at a customer — ingested first-write-wins from
            the placement stream (a later version never overrides the first). Each opens a warranty provision that
            releases straight-line over the term. The warranty clock starts at <b>activation, not dispatch</b>.
          </span>
        }
      />

      {/* sell-in → sell-through hero + provision summary */}
      <div className="grid" style={{ gridTemplateColumns: '1.6fr 1fr 1fr', marginBottom: 14, alignItems: 'stretch' }}>
        <Card>
          <div className="muted" style={{ fontSize: 'var(--fs-small)', marginBottom: 'var(--sp-2)' }}>Sell-in → sell-through</div>
          {feedApi.isLoading ? (
            <div className="skel skel-line" style={{ width: 220, height: 26 }} />
          ) : feedErr ? (
            <div className="dim" style={{ fontSize: 'var(--fs-xs)' }}>{heroNote}</div>
          ) : (
            <>
              <div className="row g12" style={{ alignItems: 'flex-end' }}>
                <div>
                  <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600 }}>{dispatched != null ? num(dispatched) : '—'}</div>
                  <div className="dim" style={{ fontSize: 'var(--fs-xs)' }}>dispatched (sell-in)</div>
                </div>
                <I.arrowR style={{ color: 'var(--faint)', marginBottom: 14 }} />
                <div>
                  <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600, color: 'var(--ok)' }}>{activated != null ? num(activated) : '—'}</div>
                  <div className="dim" style={{ fontSize: 'var(--fs-xs)' }}>activated (sell-through)</div>
                </div>
              </div>
              {sit && (
                <div style={{ marginTop: 12 }}>
                  <div style={{ height: 6, borderRadius: 4, background: 'var(--surface3)', overflow: 'hidden' }}>
                    <div style={{ width: `${Math.min(throughPct, 100)}%`, height: '100%', background: 'var(--ok)' }} />
                  </div>
                  <div className="dim" style={{ fontSize: 'var(--fs-xs)', marginTop: 4 }}>
                    {fmtPct(throughPct)}% of dispatched units are live — the rest are on a shelf, feeding H6Q depletion.
                  </div>
                </div>
              )}
            </>
          )}
        </Card>

        <Card>
          <div className="muted" style={{ fontSize: 'var(--fs-small)' }}>Warranty provision</div>
          <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600, marginTop: 3 }}>
            {warrApi.isLoading ? (
              <div className="skel skel-line" style={{ width: 90, height: 22 }} />
            ) : warrReady && provisionVisible ? (
              <Money value={wt?.provision ?? null} />
            ) : (
              <span className="dim">hidden</span>
            )}
          </div>
          <div className="dim" style={{ fontSize: 'var(--fs-xs)' }}>
            {warrNotImpl ? 'not available yet' : warrReady && provisionVisible ? 'total opened' : 'requires profitability'}
          </div>
        </Card>

        <Card>
          <div className="muted" style={{ fontSize: 'var(--fs-small)' }}>Outstanding liability</div>
          <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600, marginTop: 3, color: warrReady && provisionVisible ? 'var(--warn)' : undefined }}>
            {warrApi.isLoading ? (
              <div className="skel skel-line" style={{ width: 90, height: 22 }} />
            ) : warrReady && provisionVisible ? (
              <Money value={wt?.outstanding ?? null} />
            ) : (
              <span className="dim">hidden</span>
            )}
          </div>
          <div className="dim" style={{ fontSize: 'var(--fs-xs)' }}>
            {warrNotImpl ? 'not available yet' : warrReady && provisionVisible ? `${gbp(wt?.released)} released to date` : 'requires profitability'}
          </div>
        </Card>
      </div>

      <div className="grid" style={{ gridTemplateColumns: '1fr 1fr', alignItems: 'start' }}>
        {/* Activation feed — volume layer */}
        <Card title="Activation feed" icon={I.wifi} aux="first-write-wins · sell-through signal" style={{ padding: 0 }} className="tablewrap">
          <LoadBar>
            <div className="seg">
              {['all', 'UK', 'IE'].map((m) => (
                <button key={m} className={market === m ? 'on' : ''} onClick={() => setMarket(m)}>{m === 'all' ? 'All' : m}</button>
              ))}
            </div>
            <div style={{ flex: 1 }} />
            <span className="dim" style={{ fontSize: 'var(--fs-small)' }}>
              {feedReady ? `${num(feedTotal)} activations` : feedApi.isLoading ? 'loading…' : ''}
            </span>
          </LoadBar>
          {feedNotImpl ? (
            <div style={{ padding: 16 }}><NotAvailable which="activation feed" /></div>
          ) : (
            <div style={{ maxHeight: 460, overflowY: 'auto' }}>
              <table className="tbl">
                <thead><tr><th>Serial</th><th>Activated</th><th>Installer</th><th>Owner</th><th>Mkt</th></tr></thead>
                <tbody>
                  {feedApi.isLoading && <SkeletonRow cols={5} />}
                  {feedForbidden && (
                    <tr><td colSpan={5}><LayerNote>Activation feed hidden — requires the <b>volume</b> layer (<code>view:activation</code>).</LayerNote></td></tr>
                  )}
                  {feedOther && (
                    <tr><td colSpan={5}><div className="banner danger">Could not load activations ({feedErr?.status}).</div></td></tr>
                  )}
                  {feedReady && acts.map((a, i) => (
                    <tr key={a.sn ?? a.serial ?? i}>
                      <td className="mono">{a.sn ?? a.serial}</td>
                      <td className="dim">{a.activated_at ?? a.activatedAt}</td>
                      <td>{a.installer}</td>
                      <td className="dim">{a.owner}</td>
                      <td><Chip s="neutral">{a.market}</Chip></td>
                    </tr>
                  ))}
                  {feedReady && acts.length === 0 && <EmptyRow cols={5}>No activations in this market yet — units are dispatched but not live.</EmptyRow>}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        {/* Warranty provision register — profitability layer (collapses) */}
        <Card title="Warranty provision register" icon={I.shield} aux="straight-line release from activation date" style={{ padding: 0 }} className="tablewrap">
          {warrNotImpl ? (
            <div style={{ padding: 16 }}><NotAvailable which="warranty provision register" /></div>
          ) : (
            <>
              <div style={{ maxHeight: 502, overflowY: 'auto' }}>
                <table className="tbl">
                  <thead><tr><th>Serial</th><th>Owner</th><th className="num">Provision</th><th style={{ width: 140 }}>Released</th><th className="num">Outstanding</th><th>Ref</th></tr></thead>
                  <tbody>
                    {warrApi.isLoading && <SkeletonRow cols={6} />}
                    {warrForbidden && (
                      <tr><td colSpan={6}><LayerNote>Warranty provision hidden — requires the <b>profitability</b> layer.</LayerNote></td></tr>
                    )}
                    {warrOther && (
                      <tr><td colSpan={6}><div className="banner danger">Could not load the provision register ({warrErr?.status}).</div></td></tr>
                    )}
                    {warrReady && wrows.map((w, i) => {
                      const pct = Number(w.pct ?? w.releasedPct ?? 0);
                      return (
                        <tr key={w.sn ?? w.serial ?? i}>
                          <td className="mono">{w.sn ?? w.serial}</td>
                          <td className="dim" style={{ fontSize: 'var(--fs-small)' }}>{w.owner}</td>
                          <td className="num">{provisionVisible ? <Money value={w.provision ?? null} /> : <span className="dim">—</span>}</td>
                          <td>
                            {provisionVisible ? (
                              <div className="row g6" style={{ alignItems: 'center' }}>
                                <div style={{ flex: 1, height: 5, borderRadius: 3, background: 'var(--surface3)', overflow: 'hidden' }}>
                                  <div style={{ width: `${Math.min(pct, 100)}%`, height: '100%', background: 'var(--ok)' }} />
                                </div>
                                <span className="dim" style={{ fontSize: 10 }}>{fmtPct(pct)}%</span>
                              </div>
                            ) : <span className="dim">— layer</span>}
                          </td>
                          <td className="num">{provisionVisible ? <Money value={w.outstanding ?? null} /> : <span className="dim">—</span>}</td>
                          <td>{(w.audit_ref ?? w.auditRef) ? <AuditRef id={w.audit_ref ?? w.auditRef} /> : <span className="dim">—</span>}</td>
                        </tr>
                      );
                    })}
                    {warrReady && wrows.length === 0 && <EmptyRow cols={6}>No warranty provisions opened yet.</EmptyRow>}
                  </tbody>
                </table>
              </div>
              {warrReady && !provisionVisible && (
                <LayerNote>Provision figures hidden — requires the <b>profitability</b> layer.</LayerNote>
              )}
              {warrReady && provisionVisible && (
                <div className="layer-note" style={{ padding: '10px 16px' }}>
                  <I.clock />The warranty clock starts the day a unit activates. A warranty claim from Returns draws this provision down.
                </div>
              )}
            </>
          )}
        </Card>
      </div>
    </div>
  );
}
