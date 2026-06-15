// The pure session helpers (doc 27 §0), kept free of StyleX/React so they unit-test in isolation: decode
// the identity label from the bearer token, and tear down the Google auto-select on sign-out.
// (Window.google is augmented project-wide by SignIn.tsx.)

// The session-chip label: a dev token reads as a developer session; a Google JWT surfaces its email claim;
// anything malformed resolves to "signed in" — never throws.
export function sessionEmail(token: string): string {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return 'developer session';
    return JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/'))).email ?? 'signed in';
  } catch {
    return 'signed in';
  }
}

export function signOutGoogle(): void {
  try {
    window.google?.accounts.id.disableAutoSelect();
  } catch {
    /* gsi not loaded */
  }
}

// A Google session is a 3-part JWT; a dev token (dev:<id>) is not — only the former expires + refreshes.
export function isGoogleToken(token: string): boolean {
  return token.split('.').length === 3 && !token.startsWith('dev:');
}

// The token's `exp` claim in epoch-ms, or null if absent/unparseable.
export function tokenExpMs(token: string): number | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const exp = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/'))).exp;
    return typeof exp === 'number' ? exp * 1000 : null;
  } catch {
    return null;
  }
}

export function isExpired(token: string, skewMs = 0): boolean {
  const exp = tokenExpMs(token);
  return exp != null && Date.now() >= exp - skewMs;
}

// Load the Google Identity Services client once (it persists on window once signed in), so a silent refresh can
// run from anywhere in the app — not only on the sign-in screen.
let gisLoading: Promise<void> | null = null;
export function ensureGis(): Promise<void> {
  if (window.google?.accounts?.id) return Promise.resolve();
  if (gisLoading) return gisLoading;
  gisLoading = new Promise<void>((resolve, reject) => {
    const existing = document.querySelector('script[src*="gsi/client"]') as HTMLScriptElement | null;
    const s = existing ?? document.createElement('script');
    s.addEventListener('load', () => resolve(), { once: true });
    s.addEventListener('error', () => reject(new Error('gsi load failed')), { once: true });
    if (!existing) { s.src = 'https://accounts.google.com/gsi/client'; s.async = true; document.head.appendChild(s); }
  });
  return gisLoading;
}

// Initialise GIS once with auto-select on, so prompt() can silently re-issue a fresh credential for an active
// Google session. `onCredential` receives each (re)issued ID token.
let gisInited = false;
export function initGoogleAuth(clientId: string, onCredential: (jwt: string) => void): void {
  const id = window.google?.accounts?.id;
  if (!id || gisInited) return;
  id.initialize({
    client_id: clientId,
    hosted_domain: 'hypervolt.co.uk',
    auto_select: true,
    callback: (resp: { credential?: string }) => { if (resp.credential) onCredential(resp.credential); },
  });
  gisInited = true;
}

// Ask GIS to silently re-issue a credential (no UI when the Google session is active + a single account is
// auto-selected). The credential arrives via the callback registered in initGoogleAuth.
export function promptGoogleRefresh(): void {
  try {
    window.google?.accounts.id.prompt();
  } catch {
    /* gsi not loaded */
  }
}
