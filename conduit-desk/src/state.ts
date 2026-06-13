// The shared data-table state machine (spec doc 27 §13 / doc 29 F). Every list view derives its render
// state from one API result, so the four states are handled in ONE place — loading / forbidden / error /
// empty / ready — instead of each component re-deriving them and crashing on a non-array error body (the
// class found during the screenshot capture: `.map` on error JSON).

export type TableState = 'loading' | 'forbidden' | 'error' | 'empty' | 'ready';

// The crash-class guard: anything that isn't an array becomes []. An error JSON ({error,message}) or null
// can never reach a `.map`.
export function asArray<T>(x: unknown): T[] {
  return Array.isArray(x) ? (x as T[]) : [];
}

export interface ApiResult {
  status: number;
  json: unknown;
}

// Derive the render state from an API result. `res === null` means not-yet-loaded.
export function tableState(res: ApiResult | null, rows: unknown): TableState {
  if (res === null) return 'loading';
  if (res.status === 401 || res.status === 403) return 'forbidden';
  if (res.status >= 400) return 'error';
  const arr = asArray(rows);
  return arr.length === 0 ? 'empty' : 'ready';
}
