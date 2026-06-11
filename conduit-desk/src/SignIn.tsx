import React, { useEffect, useRef, useState } from 'react';
import * as stylex from '@stylexjs/stylex';
import { colors } from './styles/tokens.stylex';

// D1 — Sign in (doc 20 D1, doc 27 §0): Google Workspace domain-gated entry (hypervolt.co.uk), enforced
// server-side (GoogleTokenVerifier). The Google ID token becomes the bearer for every API call. The dev
// door (dev:<id>) only exists where the backend runs non-prod — it is the same field the e2e suites use.

declare global {
  interface Window {
    google?: { accounts: { id: { initialize: (c: object) => void; renderButton: (el: HTMLElement, opts: object) => void; disableAutoSelect: () => void } } };
  }
}

const styles = stylex.create({
  page: { minHeight: '80vh', display: 'flex', alignItems: 'center', justifyContent: 'center' },
  card: { backgroundColor: colors.surface, border: `1px solid ${colors.border}`, borderRadius: '16px', padding: '2.5rem 3rem', maxWidth: '420px', textAlign: 'center' },
  logo: { fontSize: '1.6rem', fontWeight: 800, marginBottom: '0.25rem' },
  accent: { color: colors.accent },
  sub: { color: colors.muted, fontSize: '0.85rem', marginBottom: '1.5rem' },
  googleSlot: { display: 'flex', justifyContent: 'center', minHeight: '44px', marginBottom: '1rem' },
  error: { color: '#e76e6e', fontSize: '0.8rem', marginTop: '0.75rem' },
  divider: { color: colors.muted, fontSize: '0.7rem', margin: '1rem 0 0.5rem', textTransform: 'uppercase', letterSpacing: '0.08em' },
  devInput: { backgroundColor: colors.bg, color: colors.text, border: `1px solid ${colors.border}`, borderRadius: '8px', padding: '0.5rem 0.7rem', fontSize: '0.9rem', width: '100%' },
  note: { color: colors.muted, fontSize: '0.72rem', marginTop: '1.25rem', lineHeight: 1.5 },
});

const GOOGLE_CLIENT_ID = (import.meta as any).env?.VITE_GOOGLE_CLIENT_ID as string | undefined;

export function SignIn({ onToken }: { onToken: (token: string) => void }) {
  const slot = useRef<HTMLDivElement>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!GOOGLE_CLIENT_ID) return;
    const init = () => {
      if (!window.google || !slot.current) return;
      window.google.accounts.id.initialize({
        client_id: GOOGLE_CLIENT_ID,
        hosted_domain: 'hypervolt.co.uk',
        callback: (resp: { credential?: string }) => {
          if (resp.credential) onToken(resp.credential);
          else setError('Sign-in failed — use your hypervolt.co.uk account.');
        },
      });
      window.google.accounts.id.renderButton(slot.current, { theme: 'filled_black', size: 'large', width: 280 });
    };
    if (window.google) init();
    else {
      const s = document.createElement('script');
      s.src = 'https://accounts.google.com/gsi/client';
      s.async = true;
      s.onload = init;
      document.head.appendChild(s);
    }
  }, [onToken]);

  return (
    <div {...stylex.props(styles.page)} data-testid="signin-page">
      <div {...stylex.props(styles.card)}>
        <div {...stylex.props(styles.logo)}><span {...stylex.props(styles.accent)}>Conduit</span></div>
        <div {...stylex.props(styles.sub)}>Hypervolt’s system of record — staff only</div>
        {GOOGLE_CLIENT_ID ? (
          <div ref={slot} {...stylex.props(styles.googleSlot)} data-testid="signin-google" />
        ) : null}
        {error ? <div {...stylex.props(styles.error)} data-testid="signin-error">{error}</div> : null}
        <div {...stylex.props(styles.divider)}>{GOOGLE_CLIENT_ID ? 'or — developers' : 'developers'}</div>
        <input
          {...stylex.props(styles.devInput)}
          data-testid="token"
          placeholder="dev:<keycloak_id> (non-prod only)"
          onChange={(e) => { if (e.target.value.trim()) onToken(e.target.value.trim()); }}
        />
        <div {...stylex.props(styles.note)}>
          Sign-in requires a <b>hypervolt.co.uk</b> Google Workspace account; the server verifies the domain on
          every request. Access inside is role-based — a new account sees nothing until granted a role.
        </div>
      </div>
    </div>
  );
}

export function sessionEmail(token: string): string {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return 'developer session';
    return JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/'))).email ?? 'signed in';
  } catch {
    return 'signed in';
  }
}

export function signOutGoogle() {
  try { window.google?.accounts.id.disableAutoSelect(); } catch { /* gsi not loaded */ }
}
