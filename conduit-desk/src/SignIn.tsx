import React, { useEffect, useRef, useState } from 'react';
import { I } from './kit/icons';
import { sessionEmail, signOutGoogle } from './session';

// re-exported so callers keep importing the session helpers from './SignIn' (they live in ./session now,
// StyleX-free, so they unit-test in isolation — doc 29 F).
export { sessionEmail, signOutGoogle };

// D1 — Sign in (doc 20 D1, spec/ui/00-signin.md): Google Workspace domain-gated entry (hypervolt.co.uk),
// enforced server-side (GoogleTokenVerifier). The Google ID token becomes the bearer for every API call. The
// dev door (dev:<id>) only exists where the backend runs non-prod — the same field the e2e suites use.
// Ported to the desk kit's .signin chrome from the design bundle. testids preserved.

declare global {
  interface Window {
    google?: { accounts: { id: { initialize: (c: object) => void; renderButton: (el: HTMLElement, opts: object) => void; disableAutoSelect: () => void } } };
  }
}

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
    <div className="signin" data-testid="signin-page">
      <div className="panel">
        <div className="bolt">{I.bolt({ size: 28 })}</div>
        <h2>CONDUIT</h2>
        <div className="sub">Hypervolt’s system of record — staff only</div>
        {GOOGLE_CLIENT_ID ? <div ref={slot} style={{ display: 'flex', justifyContent: 'center', minHeight: 44, marginBottom: 16 }} data-testid="signin-google" /> : null}
        {error ? <div style={{ color: '#e76e6e', fontSize: 12.5, marginBottom: 10 }} data-testid="signin-error">{error}</div> : null}
        <div className="dim" style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.08em', margin: '6px 0 8px' }}>{GOOGLE_CLIENT_ID ? 'or — developers' : 'developers'}</div>
        <input
          className="fld"
          style={{ width: '100%' }}
          data-testid="token"
          placeholder="dev:<keycloak_id> (non-prod only)"
          onChange={(e) => { if (e.target.value.trim()) onToken(e.target.value.trim()); }}
        />
        <div className="dim" style={{ fontSize: 11.5, marginTop: 18, lineHeight: 1.5 }}>
          Sign-in requires a <b>hypervolt.co.uk</b> Google Workspace account; the server verifies the domain on
          every request. Access inside is role-based — a new account sees nothing until granted a role.
        </div>
      </div>
    </div>
  );
}
