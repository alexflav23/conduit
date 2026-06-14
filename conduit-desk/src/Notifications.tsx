import React, { useState, useEffect, useCallback } from 'react';
import { apiFetch } from './api';
import { asArray, tableState } from './state';
import { PageHead, Card, Chip, EmptyRow, LayerNote, SkeletonRow, Skeleton, useToast } from './kit/kit';
import { I } from './kit/icons';

// Conduit Desk — Notifications (doc 10 §B, P2.6): the in-app feed (the bell's full surface), the subscription
// model (who is notified of which events, above a materiality threshold), and delivery status across channels
// (in_app / email / webhook) — shadow-aware (outbound email/webhook are SUPPRESSED in the dual-run, not failed).
//
// The bell never cries wolf — materiality gates what reaches you; delivery is transparent — you can see what
// sent, what's pending, and what was suppressed. In-app first (no provider needed); email/push are the same
// content through a channel seam, layer-projected so a notification never leaks a layer the subscriber lacks.

type Props = { role: any; ctx: any; toast: (m: string, k?: string) => void };

const SUBTABS: [string, string][] = [['feed', 'Feed'], ['subs', 'Subscriptions'], ['delivery', 'Delivery status']];
const CHAN_CHIP: Record<string, string> = { in_app: 'accent', email: 'neutral', webhook: 'plum' };
const STATUS_CHIP: Record<string, string> = { sent: 'ok', delivered: 'ok', pending: 'warn', queued: 'warn', failed: 'danger', suppressed: 'neutral' };

export function Notifications({ role, ctx, toast }: Props) {
  const [tab, setTab] = useState<string>('feed');
  const [toastNode, fire] = useToast();
  const say = (m: string, k?: string) => { fire(m, (k as any) || 'ok'); toast(m, k); };
  return (
    <div className="page" style={{ maxWidth: 1100 }}>
      {toastNode}
      <PageHead
        crumb="In-app feed · subscriptions · delivery (doc 10 §B)"
        title="Notifications"
        sub={
          <span style={{ display: 'block', maxWidth: 760 }}>
            The bell never cries wolf — a materiality threshold gates what reaches you. Delivery is transparent: see what sent, what's pending, and what was suppressed in the shadow run.
          </span>
        }
      />
      <div className="seg" style={{ marginBottom: 18 }}>
        {SUBTABS.map(([k, l]) => (
          <button key={k} data-testid={'notif-tab-' + k} className={tab === k ? 'on' : ''} onClick={() => setTab(k)}>{l}</button>
        ))}
      </div>
      {tab === 'feed' && <Feed role={role} ctx={ctx} say={say} />}
      {tab === 'subs' && <Subs role={role} ctx={ctx} say={say} />}
      {tab === 'delivery' && <Delivery role={role} ctx={ctx} say={say} />}
    </div>
  );
}

// ----- Feed: the full in-app feed (the bell, expanded). Auto-loads on mount + when filter/ctx change. -----

function Feed({ role, ctx, say }: { role: any; ctx: any; say: (m: string, k?: string) => void }) {
  const [filter, setFilter] = useState<'all' | 'unread'>('all');
  const [res, setRes] = useState<{ status: number; json: any } | null>(null);

  const load = useCallback(() => {
    setRes(null);
    apiFetch(`/api/v1/notifications?filter=${filter}&market=${encodeURIComponent(ctx?.market || '')}`).then(setRes);
  }, [filter, ctx?.market]);

  useEffect(() => { load(); }, [load]);

  const rows = asArray<any>(res?.json?.notifications ?? res?.json);
  const unread = (res?.json && typeof res.json.unread === 'number') ? res.json.unread : rows.filter((n) => !n.read).length;
  const state = tableState(res, rows);

  const markRead = (id: string) =>
    apiFetch(`/api/v1/notifications/${encodeURIComponent(id)}/read`, { method: 'POST' }).then((r) => {
      if (r.status >= 400) return say('Could not mark read', 'err');
      load();
    });
  const markAll = () =>
    apiFetch('/api/v1/notifications/read-all', { method: 'POST' }).then((r) => {
      if (r.status >= 400) return say('Could not mark all read', 'err');
      say('All marked read'); load();
    });

  return (
    <Card style={{ padding: 0 }} className="tablewrap">
      <div className="loadbar" style={{ padding: '11px 16px', margin: 0, borderBottom: '1px solid var(--border)' }}>
        <div className="seg">
          {(['all', 'unread'] as const).map((f) => (
            <button key={f} className={filter === f ? 'on' : ''} onClick={() => setFilter(f)}>{f}</button>
          ))}
        </div>
        <div className="sp" />
        <span className="dim" style={{ fontSize: 12 }}>{state === 'loading' ? '…' : unread} unread</span>
        <button className="btn sm" onClick={markAll} disabled={state !== 'ready' || unread === 0}>Mark all read</button>
      </div>
      <div>
        {state === 'loading' && (
          <div style={{ padding: '14px 16px' }}><Skeleton lines={4} /></div>
        )}
        {state === 'forbidden' && (
          <div style={{ padding: '14px 16px' }}><LayerNote>Your feed is hidden — requires <b>volume</b> access to in-app notifications.</LayerNote></div>
        )}
        {state === 'error' && (
          <div className="banner danger" style={{ margin: 16 }}>{I.alert()}<div>Couldn't load your feed. Try again shortly.</div></div>
        )}
        {state === 'empty' && (
          <div className="dim" style={{ padding: 24, textAlign: 'center', fontSize: 13 }}>
            {filter === 'unread' ? "Nothing unread — you're all caught up." : 'No notifications yet.'}
          </div>
        )}
        {state === 'ready' && rows.map((n) => (
          <div
            key={n.id}
            className="row g12"
            onClick={() => !n.read && markRead(n.id)}
            style={{ padding: '14px 16px', borderBottom: '1px solid var(--line-soft)', cursor: n.read ? 'default' : 'pointer', background: n.read ? 'transparent' : 'var(--accent-subtle)', alignItems: 'flex-start' }}
          >
            <span style={{ width: 8, height: 8, borderRadius: 4, background: n.read ? 'transparent' : 'var(--accent)', flex: '0 0 8px', marginTop: 6 }} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="row between">
                <b style={{ fontSize: 13, fontWeight: n.read ? 500 : 600 }}>{n.subject}</b>
                <span className="dim" style={{ fontSize: 11 }}>{n.age || n.occurredAt || ''}</span>
              </div>
              {n.body && <div className="dim" style={{ fontSize: 12, marginTop: 2 }}>{n.body}</div>}
              <div className="row g6" style={{ marginTop: 7 }}>
                {n.eventType && <span className="mono dim" style={{ fontSize: 10 }}>{n.eventType}</span>}
                {n.channel && <span className={'chip ' + (CHAN_CHIP[n.channel] || 'neutral')} style={{ fontSize: 9.5, padding: '0 7px' }}>{n.channel}</span>}
                {n.status && <span className={'chip ' + (STATUS_CHIP[n.status] || 'neutral')} style={{ fontSize: 9.5, padding: '0 7px' }}>{n.status}</span>}
              </div>
            </div>
          </div>
        ))}
      </div>
    </Card>
  );
}

// ----- Subscriptions (admin): subscriber × channel × event_types × scope × materiality × active toggle. -----

function Subs({ role, ctx, say }: { role: any; ctx: any; say: (m: string, k?: string) => void }) {
  const [res, setRes] = useState<{ status: number; json: any } | null>(null);

  const load = useCallback(() => {
    setRes(null);
    apiFetch(`/api/v1/notifications/subscriptions?market=${encodeURIComponent(ctx?.market || '')}`).then(setRes);
  }, [ctx?.market]);

  useEffect(() => { load(); }, [load]);

  const rows = asArray<any>(res?.json?.subscriptions ?? res?.json);
  const state = tableState(res, rows);
  const me = role?.token || role?.name;

  const toggle = (s: any) =>
    apiFetch(`/api/v1/notifications/subscriptions/${encodeURIComponent(s.id)}`, { method: 'PATCH', body: JSON.stringify({ active: !s.active }) }).then((r) => {
      if (r.status >= 400) return say('Could not update subscription', 'err');
      say(s.active ? 'Subscription paused' : 'Subscription activated'); load();
    });
  const setMat = (s: any, v: number) =>
    apiFetch(`/api/v1/notifications/subscriptions/${encodeURIComponent(s.id)}`, { method: 'PATCH', body: JSON.stringify({ materiality: v }) }).then((r) => {
      if (r.status < 400) load();
    });

  if (state === 'forbidden') {
    return (
      <Card title="Subscriptions" icon={I.bell}>
        <LayerNote>Subscription management is hidden — requires <b>admin / finance</b> access.</LayerNote>
      </Card>
    );
  }

  return (
    <Card title="Subscriptions" icon={I.bell} aux="who is notified of which events, above what materiality" style={{ padding: 0 }} className="tablewrap">
      <table className="tbl">
        <thead>
          <tr>
            <th>Subscriber</th><th>Channel</th><th>Event types</th><th>Scope</th>
            <th style={{ width: 190 }}>Materiality (min change)</th><th>Active</th>
          </tr>
        </thead>
        <tbody>
          {state === 'loading' && <SkeletonRow cols={6} />}
          {state === 'error' && <EmptyRow cols={6}>Couldn't load subscriptions.</EmptyRow>}
          {state === 'empty' && <EmptyRow cols={6}>No subscriptions configured.</EmptyRow>}
          {state === 'ready' && rows.map((s) => {
            const isSelf = me != null && (s.subscriber === me || s.subscriberId === me);
            return (
              <tr key={s.id}>
                <td><b>{s.subscriber}</b></td>
                <td><span className={'chip ' + (CHAN_CHIP[s.channel] || 'neutral')}>{s.channel}</span></td>
                <td><div className="row g6 wrap">{asArray<string>(s.eventTypes ?? s.event_types).map((e) => <span key={e} className="mono dim" style={{ fontSize: 10 }}>{e}</span>)}</div></td>
                <td className="dim">{s.scope || 'all markets'}</td>
                <td>
                  <div className="row g8" style={{ alignItems: 'center' }}>
                    <input
                      type="range" min={0} max={50} step={5} value={s.materiality ?? 0}
                      onChange={(e) => setMat(s, parseInt(e.target.value, 10))}
                      style={{ flex: 1, accentColor: 'var(--accent)' }}
                    />
                    <span className="num" style={{ fontSize: 12, minWidth: 34 }}>{s.materiality ?? 0}%</span>
                  </div>
                </td>
                <td>
                  <button
                    onClick={() => !isSelf && toggle(s)}
                    disabled={isSelf}
                    title={isSelf ? 'Maker-checker — you cannot toggle your own subscription' : undefined}
                    className={'chip ' + (s.active ? 'ok' : 'neutral')}
                    style={{ cursor: isSelf ? 'not-allowed' : 'pointer', padding: '4px 10px', opacity: isSelf ? 0.55 : 1 }}
                  >
                    <span className="d" />{s.active ? 'active' : 'paused'}
                  </button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <div className="layer-note" style={{ padding: '10px 16px' }}>
        {I.shield()}A notification never leaks a layer the subscriber can't see — the materiality threshold keeps the bell calm, surfacing only changes that matter.
      </div>
    </Card>
  );
}

// ----- Delivery status: the relay worklist for external channels — shadow-aware (suppressed, not failed). -----

function Delivery({ role, ctx, say }: { role: any; ctx: any; say: (m: string, k?: string) => void }) {
  const [res, setRes] = useState<{ status: number; json: any } | null>(null);

  const load = useCallback(() => {
    setRes(null);
    apiFetch(`/api/v1/notifications/deliveries?market=${encodeURIComponent(ctx?.market || '')}`).then(setRes);
  }, [ctx?.market]);

  useEffect(() => { load(); }, [load]);

  const rows = asArray<any>(res?.json?.deliveries ?? res?.json);
  const state = tableState(res, rows);
  const shadow = !!(res?.json && res.json.shadow);

  const retry = (n: any) =>
    apiFetch(`/api/v1/notifications/deliveries/${encodeURIComponent(n.id)}/retry`, { method: 'POST' }).then((r) => {
      if (r.status >= 400) return say('Retry failed', 'err');
      say('Re-queued for delivery'); load();
    });

  if (state === 'forbidden') {
    return (
      <Card title="Delivery status" icon={I.download}>
        <LayerNote>Delivery status is hidden — requires <b>admin / finance</b> access.</LayerNote>
      </Card>
    );
  }

  return (
    <>
      {shadow && (
        <div className="banner info" style={{ marginBottom: 14 }}>
          {I.layers()}
          <div>
            <span className="bb">Shadow run.</span> Outbound email &amp; webhook are <span className="bb">suppressed</span>, not failed — the relay records a <span className="mono">shadow_action</span> of what it would have sent. Cutover flips this to live delivery.
          </div>
        </div>
      )}
      <Card title="Delivery status" icon={I.download} aux="the relay worklist · external channels only" style={{ padding: 0 }} className="tablewrap">
        <table className="tbl">
          <thead><tr><th>Notification</th><th>Channel</th><th>Subject</th><th>Status</th><th></th></tr></thead>
          <tbody>
            {state === 'loading' && <SkeletonRow cols={5} />}
            {state === 'error' && <EmptyRow cols={5}>Couldn't load the delivery worklist.</EmptyRow>}
            {state === 'empty' && <EmptyRow cols={5}>No external deliveries.</EmptyRow>}
            {state === 'ready' && rows.map((n) => (
              <tr key={n.id}>
                <td className="mono dim" style={{ fontSize: 11 }}>{n.id}</td>
                <td><span className={'chip ' + (CHAN_CHIP[n.channel] || 'neutral')}>{n.channel}</span></td>
                <td>{n.subject}</td>
                <td><Chip s={STATUS_CHIP[n.status] || 'neutral'}>{n.status === 'suppressed' ? 'suppressed · shadow' : n.status}</Chip></td>
                <td>{n.status === 'failed' && <button className="btn sm" onClick={() => retry(n)}>Retry</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Card>
    </>
  );
}
