// The single authenticated API client every screen goes through. Unlike the legacy `apiFetch` (which swallowed
// errors into a {status,json} envelope), `request` THROWS a typed ApiError on any non-2xx, so React Query's
// error/loading states work without each view hand-rolling status checks. The bearer token is read live from
// the session, so callers never thread it.

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: unknown,
    message?: string,
  ) {
    super(message ?? `request failed (${status})`);
    this.name = 'ApiError';
  }
  /** true when the viewer's role/layer makes this resource absent — the desk collapses, never zeroes. */
  get forbidden(): boolean {
    return this.status === 401 || this.status === 403;
  }
  /** true when the endpoint isn't built yet (an unbacked screen) — render an honest "not available" state. */
  get notImplemented(): boolean {
    return this.status === 404;
  }
}

export function authToken(): string {
  return (typeof sessionStorage !== 'undefined' && sessionStorage.getItem('conduit_token')) || '';
}

/** GET/POST/etc. against the API. Resolves to parsed JSON on 2xx; throws ApiError otherwise. */
export async function request<T = unknown>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: {
      Authorization: `Bearer ${authToken()}`,
      'Content-Type': 'application/json',
      ...(init.headers || {}),
    },
  });
  const text = await res.text();
  const body = text ? safeJson(text) : null;
  if (!res.ok) {
    let message: string | undefined;
    if (body && typeof body === 'object' && 'message' in body) {
      message = String((body as Record<string, unknown>).message);
    }
    throw new ApiError(res.status, body, message);
  }
  return body as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}
