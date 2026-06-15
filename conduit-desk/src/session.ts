// Pure session helper (doc 27 §0), free of StyleX/React so it unit-tests in isolation. Token lifecycle (sign-in,
// storage, silent renew) is owned by react-oidc-context now; all that lives here is the identity label.

// The session-chip label: a dev token reads as a developer session; an OIDC/JWT bearer surfaces its email claim;
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
