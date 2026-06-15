import React from 'react';
import { I } from './kit/icons';
import { sessionEmail } from './session';

// re-exported so callers keep importing the session helper from './SignIn'.
export { sessionEmail };

// D1 — Sign in (spec/ui/00-signin.md): the front door, real-data era. The ONLY way in is Keycloak (estate IdP)
// via the OIDC auth-code flow — `signIn()` redirects to the realm login, which presents Google Workspace
// (hd-gated) federation; the realm mints proper access + refresh tokens, so the desk never babysits token
// lifetime. No pre-login developer doors: impersonation is a post-login feature (the in-app "view as" switcher),
// because the desk shows real data. The gate is the brand moment: gradient-rich, dark Hypervolt.
export function SignIn({ signIn, error }: { signIn: () => void; error?: string }) {
  return (
    <div className="signin" data-testid="signin-page">
      <div className="panel">
        <div className="bolt">{I.bolt({ size: 28 })}</div>
        <h2 className="hv-gradient-text">CONDUIT</h2>
        <div className="sub">Hypervolt&rsquo;s system of record &mdash; staff only</div>

        <button className="btn primary" data-testid="signin-sso" onClick={signIn}
          style={{ width: '100%', justifyContent: 'center', margin: '14px 0 8px', padding: '11px 0' }}>
          {I.arrowR({ size: 16 })} Sign in with Google
        </button>

        {error ? (
          <div className="banner danger" style={{ marginTop: 14, textAlign: 'left' }} data-testid="signin-error">
            {error}
          </div>
        ) : null}

        <div className="dim" style={{ fontSize: 11.5, marginTop: 18, lineHeight: 1.5, textAlign: 'left' }}>
          Sign-in requires a <b>hypervolt.co.uk</b> Google Workspace account, brokered through Keycloak; the
          server verifies the realm + domain on every request. Access inside is role-based &mdash; a new account
          sees nothing until granted a role.
        </div>
      </div>
    </div>
  );
}
