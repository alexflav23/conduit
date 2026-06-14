import React, { useEffect, useRef, useState } from 'react';
import { I } from './kit/icons';
import { sessionEmail, signOutGoogle } from './session';

// re-exported so callers keep importing the session helpers from './SignIn' (they live in ./session now,
// StyleX-free, so they unit-test in isolation — doc 29 F).
export { sessionEmail, signOutGoogle };

// D1 — Sign in (spec/ui/00-signin.md): the front door. A Google Workspace (hd=hypervolt.co.uk) sign-in,
// server-verified (GoogleTokenVerifier); the Google ID token becomes the bearer for every API call. A
// subordinate "developers" door (dev:<id>) only where the backend runs non-prod — the field the e2e
// suites use. The gate is the brand moment: gradient-rich, dark Hypervolt. testids preserved.

declare global {
  interface Window {
    google?: { accounts: { id: { initialize: (c: object) => void; renderButton: (el: HTMLElement, opts: object) => void; disableAutoSelect: () => void } } };
  }
}

const GOOGLE_CLIENT_ID = (import.meta as any).env?.VITE_GOOGLE_CLIENT_ID as string | undefined;

// Non-prod quick-doors: the seed identities the dev backend recognises, surfaced as one-tap rows so an
// operator (or the e2e suite) can preview the desk as a role without typing the keycloak id. Each row's
// title + layer count mirrors the in-app role/view-as menu the spec describes.
const DEV_DOORS: { id: string; name: string; title: string; layers: number }[] = [
  { id: 'dev:ceo', name: 'CEO', title: 'Full access', layers: 6 },
  { id: 'dev:deal-desk', name: 'Deal Desk', title: 'Commercial · volume', layers: 2 },
  { id: 'dev:finance', name: 'Finance', title: 'Profitability · commercial · volume', layers: 3 },
  { id: 'dev:ops', name: 'Operations', title: 'Volume', layers: 1 },
];

export function SignIn({ onToken }: { onToken: (token: string) => void }) {
  const slot = useRef<HTMLDivElement>(null);
  const [error, setError] = useState('');
  const [dev, setDev] = useState('');

  useEffect(() => {
    if (!GOOGLE_CLIENT_ID) return;
    const init = () => {
      if (!window.google || !slot.current) return;
      window.google.accounts.id.initialize({
        client_id: GOOGLE_CLIENT_ID,
        hosted_domain: 'hypervolt.co.uk',
        callback: (resp: { credential?: string }) => {
          if (resp.credential) onToken(resp.credential);
          else setError('Sign-in failed — use your hypervolt.co.uk Google account.');
        },
      });
      window.google.accounts.id.renderButton(slot.current, { theme: 'filled_black', size: 'large', width: 300 });
    };
    if (window.google) init();
    else {
      const s = document.createElement('script');
      s.src = 'https://accounts.google.com/gsi/client';
      s.async = true;
      s.onload = init;
      s.onerror = () => setError('Could not reach Google sign-in. Check your connection and retry.');
      document.head.appendChild(s);
    }
  }, [onToken]);

  const submitDev = (raw: string) => {
    const t = raw.trim();
    if (!t) return;
    if (!t.startsWith('dev:')) {
      setError('Developer tokens start with dev: — e.g. dev:<keycloak_id>.');
      return;
    }
    setError('');
    onToken(t);
  };

  return (
    <div className="signin" data-testid="signin-page">
      <div className="panel">
        <div className="bolt">{I.bolt({ size: 28 })}</div>
        <h2 className="hv-gradient-text">CONDUIT</h2>
        <div className="sub">Hypervolt&rsquo;s system of record &mdash; staff only</div>

        {GOOGLE_CLIENT_ID ? (
          <div
            ref={slot}
            style={{ display: 'flex', justifyContent: 'center', minHeight: 44, marginBottom: 16 }}
            data-testid="signin-google"
          />
        ) : null}

        {error ? (
          <div className="banner danger" style={{ marginBottom: 14, textAlign: 'left' }} data-testid="signin-error">
            {error}
          </div>
        ) : null}

        <div
          className="dim"
          style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.08em', margin: '6px 0 12px' }}
        >
          {GOOGLE_CLIENT_ID ? 'or — developers' : 'developers'}
        </div>

        <div className="users">
          {DEV_DOORS.map((d) => (
            <div
              key={d.id}
              className={`u${dev === d.id ? ' on' : ''}`}
              data-testid={`signin-dev-${d.id.replace(/[^a-z]/g, '')}`}
              role="button"
              tabIndex={0}
              onClick={() => { setDev(d.id); submitDev(d.id); }}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { setDev(d.id); submitDev(d.id); } }}
            >
              <div className="av hv-gradient-bg">{d.name.slice(0, 2).toUpperCase()}</div>
              <div>
                <div className="nm">{d.name}</div>
                <div className="rl">{d.title} · {d.layers} layer{d.layers === 1 ? '' : 's'}</div>
              </div>
              <div className="ck">{I.arrowR({ size: 16 })}</div>
            </div>
          ))}
        </div>

        <div className="row g8" style={{ marginBottom: 4 }}>
          <input
            className="fld"
            style={{ flex: 1, minWidth: 0 }}
            data-testid="token"
            placeholder="dev:<keycloak_id> (non-prod only)"
            value={dev}
            onChange={(e) => { setDev(e.target.value); if (error) setError(''); }}
            onKeyDown={(e) => { if (e.key === 'Enter') submitDev(dev); }}
          />
          <button
            className="btn primary sm"
            data-testid="signin-dev-go"
            disabled={!dev.trim()}
            onClick={() => submitDev(dev)}
          >
            Enter
          </button>
        </div>

        <div className="dim" style={{ fontSize: 11.5, marginTop: 18, lineHeight: 1.5, textAlign: 'left' }}>
          Sign-in requires a <b>hypervolt.co.uk</b> Google Workspace account; the server verifies the domain on
          every request. Access inside is role-based &mdash; a new account sees nothing until granted a role.
        </div>
      </div>
    </div>
  );
}
