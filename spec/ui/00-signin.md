# 00 — Sign-in / session / view-as (`signin`)
Status: COVERED ✅ · Roles: everyone · Backend: Google ID-token verify (server-side), `dev:<id>` non-prod, Keycloak (P2.4)

## Purpose
The front door: a Google Workspace (`hd=hypervolt.co.uk`) sign-in, server-verified; the always-visible identity;
and the **view-as / role switcher** (a privileged user previews the desk as another role + its data layers).

## Layout
- Full-bleed branded sign-in card (the first thing every employee sees — make it worthy); the official Google
  button, the surrounding card is ours. A subordinate "developers" divider with the `dev:<id>` door (non-prod).
- In-app: a **session chip** (email) top-right; the role/view-as menu (current role · its layers · switch).

## Components
Branded auth `Card`, Google button, session chip, the role-switch menu showing each user's title + layer count.

## Data & layers
The token decodes to the principal; the chip shows the verified email (or "developer session"). View-as changes
the *rendered* layers (a preview), not the real grant.

## Actions & states
Sign in · sign out (one click, no confirm) · switch view-as. **Wrong-account error:** explain the fix ("use your
hypervolt.co.uk account") + retry. **Session expiry** (~1h): design the re-auth interrupt — keep context, re-prompt,
resume; do NOT lose form state.

## Design notes
The gate is the brand moment — gradient-rich, Apple-quality. The re-auth interrupt is the unglamorous but
critical bit: never lose the operator's work to an expired token.
