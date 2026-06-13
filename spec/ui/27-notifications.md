# 27 — Notifications (`notifications`)
Status: MISSING (model + relay built; channels seam-ready) · Roles: all (own in-app); admin (subscriptions) · Backend: NotificationRepo (subscriptions/notifications), NotificationDelivery relay, the shell bell

## Purpose
The notifications surface (doc 10 §B, P2.6): the **in-app feed** (the bell), the **subscription** model (who is
notified of which events, with a materiality threshold), and the **delivery** status across channels (in_app /
email / webhook) — shadow-aware (outbound muted in the dual-run).

## Layout
- The **bell** (shell): unread count + a dropdown of recent in-app notifications (subject · body · age · read).
- **Notifications page**: the full feed (filter by event type / status), each with its subscription + channel.
- **Subscriptions** (admin): create/edit a subscription — subscriber (user/stakeholder), channel, event_types,
  scope (market), **materiality** (min change %), active toggle.
- **Delivery status**: per external notification — pending / sent / failed (the relay's worklist); in shadow,
  email/webhook show as suppressed (a `shadow_action` was recorded).

## Components
The shell bell + dropdown, a feed table with read/unread + status `Chip`s, a subscription editor form,
a delivery-status panel, channel chips (in_app/email/webhook), materiality slider.

## Data & layers
Notification content carries the event's data (layer-appropriate — a notification never leaks a layer the
subscriber can't see). In-app is `volume`; the underlying figures honour the subscriber's layers.

## Actions & states
Mark read, create/edit subscription, see delivery status. *Shadow:* email/webhook are muted (show "suppressed —
shadow run," not failed). *Failed:* a channel failure is visible (not a silent drop) with a retry path.

## Design notes
The hero is a **calm, trustworthy feed** — the bell never cries wolf (materiality threshold), and delivery is
transparent (you can see what sent, what's pending, what was suppressed in the shadow run). In-app first
(no provider needed); email/push are the same content through a channel seam.
