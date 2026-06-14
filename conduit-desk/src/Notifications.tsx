import React, { useState } from 'react';
import { useApi } from './lib/query';
import { ApiError } from './lib/client';
import { marketId } from './api';
import { PageHead, Card, Chip, LayerNote, Skeleton, useToast } from './kit/kit';
import { I } from './kit/icons';

// Conduit Desk — Notifications (doc 10 §B, P2.6): the in-app feed (the bell's full surface), the subscription
// model (who is notified of which events, above a materiality threshold), and delivery status across channels.
//
// Backend ground truth: the only route that serves notification data is GET /api/v1/h6q/notifications — the
// forward-visibility feed (doc 12 §2.6), permission-gated on view:pipeline_coverage. There is NO HTTP route for
// a read/unread model, subscription administration, or a delivery worklist (the relay's pendingForDelivery is an
// internal ConnectionIO, not exposed). So the Feed wires to that real path; Subscriptions and Delivery status
// honestly render "Not available in this environment yet" rather than calling guessed endpoints.

type Props = { role: any; ctx: any; toast: (m: string, k?: string) => void };

// Shape returned by NotificationRepo.recent (api/.../H6QRoutes.scala `notifications`).
type NotifRow = {
  id: string;
  subscription?: string;
  channel?: string;
  event_type?: string;
  subject?: string;
  body?: string | null;
  status?: string;
  created_at?: string;
};

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
      {tab === 'subs' && <NotAvailable which="Subscription management (subscriber × channel × event types × materiality)" />}
      {tab === 'delivery' && <NotAvailable which="The external-channel delivery worklist (email / webhook relay)" />}
    </div>
  );
}

// ----- Feed: the forward-visibility feed, GET /api/v1/h6q/notifications (permission view:pipeline_coverage). -----

function Feed({ role, ctx, say }: { role: any; ctx: any; say: (m: string, k?: string) => void }) {
  void role; void say;
  const market = ctx?.market ? marketId(ctx.market) : '';
  const q = useApi<NotifRow[]>(['notifications', 'feed', market], '/api/v1/h6q/notifications');

  const rows: NotifRow[] = Array.isArray(q.data) ? q.data : [];

  return (
    <Card style={{ padding: 0 }} className="tablewrap">
      <div className="loadbar" style={{ padding: '11px 16px', margin: 0, borderBottom: '1px solid var(--border)' }}>
        <span className="dim" style={{ fontSize: 12 }}>Forward-visibility feed</span>
        <div className="sp" />
        <span className="dim" style={{ fontSize: 12 }}>{q.isLoading ? '…' : rows.length} items</span>
      </div>
      <div>
        {q.isLoading && (
          <div style={{ padding: '14px 16px' }}><Skeleton lines={4} /></div>
        )}
        {q.isError && q.error.forbidden && (
          <div style={{ padding: '14px 16px' }}>
            <LayerNote>Your feed is hidden — requires <b>view:pipeline_coverage</b> access to forward-visibility notifications.</LayerNote>
          </div>
        )}
        {q.isError && !q.error.forbidden && q.error.notImplemented && (
          <div style={{ padding: '14px 16px' }}><NotAvailable which="The in-app notification feed" /></div>
        )}
        {q.isError && !q.error.forbidden && !q.error.notImplemented && (
          <div className="banner danger" style={{ margin: 16 }}>{I.alert()}<div>Couldn't load your feed. Try again shortly.</div></div>
        )}
        {!q.isLoading && !q.isError && rows.length === 0 && (
          <div className="dim" style={{ padding: 24, textAlign: 'center', fontSize: 13 }}>No notifications yet.</div>
        )}
        {!q.isLoading && !q.isError && rows.map((n) => (
          <div
            key={n.id}
            className="row g12"
            style={{ padding: '14px 16px', borderBottom: '1px solid var(--line-soft)', alignItems: 'flex-start' }}
          >
            <span style={{ width: 8, height: 8, borderRadius: 4, background: 'var(--accent)', flex: '0 0 8px', marginTop: 6 }} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div className="row between">
                <b style={{ fontSize: 13, fontWeight: 600 }}>{n.subject}</b>
                <span className="dim" style={{ fontSize: 11 }}>{n.created_at || ''}</span>
              </div>
              {n.body && <div className="dim" style={{ fontSize: 12, marginTop: 2 }}>{n.body}</div>}
              <div className="row g6" style={{ marginTop: 7 }}>
                {n.event_type && <span className="mono dim" style={{ fontSize: 10 }}>{n.event_type}</span>}
                {n.subscription && <span className="dim" style={{ fontSize: 10 }}>{n.subscription}</span>}
                {n.channel && <span className={'chip ' + (CHAN_CHIP[n.channel] || 'neutral')} style={{ fontSize: 9.5, padding: '0 7px' }}>{n.channel}</span>}
                {n.status && <Chip s={STATUS_CHIP[n.status] || 'neutral'}>{n.status === 'suppressed' ? 'suppressed · shadow' : n.status}</Chip>}
              </div>
            </div>
          </div>
        ))}
      </div>
    </Card>
  );
}

// ----- The honest panel for surfaces with no backend route in this deployment. -----

function NotAvailable({ which }: { which: string }) {
  return (
    <div
      data-testid="notif-not-available"
      style={{ padding: '28px 18px', textAlign: 'center', color: 'var(--muted)', border: '1px dashed var(--border)', borderRadius: 10, background: 'var(--bg-2)' }}
    >
      <div style={{ marginBottom: 6, color: 'var(--faint)' }}>{I.wifiOff({ size: 22 })}</div>
      <div style={{ fontFamily: 'var(--font-disp)', fontSize: 18, fontWeight: 600, color: 'var(--text)' }}>Not available in this environment yet</div>
      <div className="dim" style={{ fontSize: 12, marginTop: 4 }}>{which} isn't built in this deployment.</div>
    </div>
  );
}
