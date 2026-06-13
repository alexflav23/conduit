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
