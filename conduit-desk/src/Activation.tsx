import React, { useState, useEffect, useCallback } from 'react';
import { apiFetch } from './api';
import { asArray } from './state';
import { PageHead, Card, Chip, Money, LayerNote, AuditRef, EmptyRow, LoadBar, SkeletonRow, num, gbp } from './kit/kit';
import { I } from './kit/icons';

// 21 — Activation ingest + warranty provision (spec/ui/21-activation.md). The sell-through + after-sales
// surface (doc 07 M8): charger ACTIVATIONS ingested first-write-wins from the UFE placement stream (the real
// "a unit went live at a customer", distinct from sell-in / dispatch), and the WARRANTY PROVISION each
// activation opens, releasing straight-line over the term from the activation date (not dispatch).
//
// Auto-loads on mount + when ctx.market changes + when the market filter changes (no Load button). Four states
// everywhere: loading (skeleton) / empty (EmptyRow) / 403 (LayerNote) / error. Activation identity is the
// `volume` layer; warranty provision money is `profitability` and COLLAPSES (never £0) via the kit Money.

type AnyRole = { layers?: string[] };

interface ActivationProps {
  role: AnyRole;
  ctx: { market?: string; entity?: string; period?: string; scenario?: string };
  toast: (m: string, k?: string) => void;
}

type Res = { status: number; json: any } | null;

const stateOf = (res: Res): 'loading' | 'forbidden' | 'error' | 'ready' =>
  res === null ? 'loading' : (res.status === 401 || res.status === 403) ? 'forbidden' : res.status >= 400 ? 'error' : 'ready';

const fmtPct = (n: number) => (Number.isFinite(n) ? n : 0).toFixed(0);

export function Activation({ role, ctx, toast }: ActivationProps) {
  const layers = asArray<string>(role?.layers);
  const canSeeProvision = layers.length === 0 || layers.indexOf('profitability') >= 0;

  const [market, setMarket] = useState('all');
  const [feedRes, setFeedRes] = useState<Res>(null);
  const [warrRes, setWarrRes] = useState<Res>(null);

  const load = useCallback(async (mk: string) => {
    setFeedRes(null);
    setWarrRes(null);
    const q = mk && mk !== 'all' ? `?market=${encodeURIComponent(mk)}&limit=60` : '?limit=60';
    const [f, w] = await Promise.all([
      apiFetch(`/api/v1/activations${q}`),
      apiFetch('/api/v1/warranty/provisions'),
    ]);
    setFeedRes(f);
    setWarrRes(w);
  }, []);

  // Re-load on mount + whenever the context market or the in-page filter changes.
  useEffect(() => { load(market); }, [market, ctx?.market, load]);

  const feedState = stateOf(feedRes);
  const warrState = stateOf(warrRes);

  const feed = feedRes && feedRes.status < 400 ? feedRes.json : null;
  const warr = warrRes && warrRes.status < 400 ? warrRes.json : null;

  const acts = asArray<any>(feed?.rows ?? feed);
  const sit = feed?.sellInVsThrough ?? feed?.sell_in_vs_through ?? null;
  const dispatched = sit?.dispatched ?? null;
  const activated = sit?.activated ?? null;
  const throughPct = dispatched ? (activated / dispatched) * 100 : 0;
  const feedTotal = feed?.total ?? acts.length;

  const wrows = asArray<any>(warr?.rows ?? warr);
  const wt = warr?.totals ?? null;
  // The register reports whether the cost layer was projected in; honour it but also respect role.layers.
  const provisionVisible = canSeeProvision && (warr ? warr.hasCost !== false : true);

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
          {feedState === 'loading' ? (
            <div className="skel skel-line" style={{ width: 220, height: 26 }} />
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
            {provisionVisible ? <Money value={wt?.provision ?? null} /> : <span className="dim">hidden</span>}
          </div>
          <div className="dim" style={{ fontSize: 'var(--fs-xs)' }}>{provisionVisible ? 'total opened' : 'requires profitability'}</div>
        </Card>

        <Card>
          <div className="muted" style={{ fontSize: 'var(--fs-small)' }}>Outstanding liability</div>
          <div style={{ fontFamily: 'var(--font-disp)', fontSize: 26, fontWeight: 600, marginTop: 3, color: provisionVisible ? 'var(--warn)' : undefined }}>
            {provisionVisible ? <Money value={wt?.outstanding ?? null} /> : <span className="dim">hidden</span>}
          </div>
          <div className="dim" style={{ fontSize: 'var(--fs-xs)' }}>
            {provisionVisible ? `${gbp(wt?.released)} released to date` : 'requires profitability'}
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
              {feedState === 'ready' ? `${num(feedTotal)} activations` : feedState === 'loading' ? 'loading…' : ''}
            </span>
          </LoadBar>
          <div style={{ maxHeight: 460, overflowY: 'auto' }}>
            <table className="tbl">
              <thead><tr><th>Serial</th><th>Activated</th><th>Installer</th><th>Owner</th><th>Mkt</th></tr></thead>
              <tbody>
                {feedState === 'loading' && <SkeletonRow cols={5} />}
                {feedState === 'forbidden' && (
                  <tr><td colSpan={5}><LayerNote>Activation feed hidden — requires the <b>volume</b> layer (<code>view:activation</code>).</LayerNote></td></tr>
                )}
                {feedState === 'error' && (
                  <tr><td colSpan={5}><div className="banner danger">Could not load activations ({feedRes?.status}).</div></td></tr>
                )}
                {feedState === 'ready' && acts.map((a, i) => (
                  <tr key={a.sn ?? a.serial ?? i}>
                    <td className="mono">{a.sn ?? a.serial}</td>
                    <td className="dim">{a.activated_at ?? a.activatedAt}</td>
                    <td>{a.installer}</td>
                    <td className="dim">{a.owner}</td>
                    <td><Chip s="neutral">{a.market}</Chip></td>
                  </tr>
                ))}
                {feedState === 'ready' && acts.length === 0 && <EmptyRow cols={5}>No activations in this market yet — units are dispatched but not live.</EmptyRow>}
              </tbody>
            </table>
          </div>
        </Card>

        {/* Warranty provision register — profitability layer (collapses) */}
        <Card title="Warranty provision register" icon={I.shield} aux="straight-line release from activation date" style={{ padding: 0 }} className="tablewrap">
          <div style={{ maxHeight: 502, overflowY: 'auto' }}>
            <table className="tbl">
              <thead><tr><th>Serial</th><th>Owner</th><th className="num">Provision</th><th style={{ width: 140 }}>Released</th><th className="num">Outstanding</th><th>Ref</th></tr></thead>
              <tbody>
                {warrState === 'loading' && <SkeletonRow cols={6} />}
                {warrState === 'forbidden' && (
                  <tr><td colSpan={6}><LayerNote>Warranty provision hidden — requires the <b>profitability</b> layer.</LayerNote></td></tr>
                )}
                {warrState === 'error' && (
                  <tr><td colSpan={6}><div className="banner danger">Could not load the provision register ({warrRes?.status}).</div></td></tr>
                )}
                {warrState === 'ready' && wrows.map((w, i) => {
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
                {warrState === 'ready' && wrows.length === 0 && <EmptyRow cols={6}>No warranty provisions opened yet.</EmptyRow>}
              </tbody>
            </table>
          </div>
          {warrState === 'ready' && !provisionVisible && (
            <LayerNote>Provision figures hidden — requires the <b>profitability</b> layer.</LayerNote>
          )}
          {warrState === 'ready' && provisionVisible && (
            <div className="layer-note" style={{ padding: '10px 16px' }}>
              <I.clock />The warranty clock starts the day a unit activates. A warranty claim from Returns draws this provision down.
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
