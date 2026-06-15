// OIDC wiring (estate-standard Keycloak). react-oidc-context + oidc-client-ts own the whole token lifecycle —
// PKCE auth-code flow, storage, and silent renew off the refresh token (no hand-rolled timers or hardcoded
// expiry buffers). The bearer the API client sends is the OIDC access token, with a `dev:<id>` override kept for
// the non-prod quick-doors the e2e suite + role-preview use.
import type { AuthProviderProps } from 'react-oidc-context';
import { WebStorageStateStore } from 'oidc-client-ts';

const env = (import.meta as any).env ?? {};
const KEYCLOAK_URL: string = env.VITE_KEYCLOAK_URL ?? 'http://localhost:8083';
const KEYCLOAK_REALM: string = env.VITE_KEYCLOAK_REALM ?? 'conduit';
const KEYCLOAK_CLIENT_ID: string = env.VITE_KEYCLOAK_CLIENT_ID ?? 'conduit-desk';

export const oidcConfig: AuthProviderProps = {
  authority: `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}`,
  client_id: KEYCLOAK_CLIENT_ID,
  redirect_uri: window.location.origin + '/',
  post_logout_redirect_uri: window.location.origin + '/',
  scope: 'openid email profile',
  // Silent renew via the refresh token — the library schedules it off the token's own expiry, not a magic number.
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  // Strip ?code&state from the URL after the redirect callback so the address bar stays clean.
  onSigninCallback: () => window.history.replaceState({}, document.title, window.location.pathname),
};

// The current API bearer: a dev override if present, else the live OIDC access token (kept in a module var the
// shell updates whenever react-oidc-context yields a new user, so the plain `request()` client can read it).
let oidcAccessToken = '';
export function setOidcToken(token: string | undefined): void {
  oidcAccessToken = token ?? '';
}

const DEV_TOKEN_KEY = 'conduit_dev_token';
export function devToken(): string {
  try {
    return sessionStorage.getItem(DEV_TOKEN_KEY) || '';
  } catch {
    return '';
  }
}
export function setDevToken(token: string): void {
  try {
    if (token) sessionStorage.setItem(DEV_TOKEN_KEY, token);
    else sessionStorage.removeItem(DEV_TOKEN_KEY);
  } catch {
    /* storage unavailable */
  }
}

export function currentToken(): string {
  return devToken() || oidcAccessToken;
}
