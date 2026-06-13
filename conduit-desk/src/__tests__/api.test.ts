import { describe, it, expect, vi, beforeEach } from 'vitest';
import { getProofLaws, runProofControl, getProofTrialBalance } from '../api';

// doc 29 F: the api client's response-shape contract — every call returns { status, json }, an empty body
// decodes to null (not a throw), the bearer token rides every request, and the verb/path are correct.
function mockFetch(status: number, body: string) {
  const fn = vi.fn().mockResolvedValue({ status, text: () => Promise.resolve(body) });
  // @ts-expect-error test stub
  global.fetch = fn;
  return fn;
}

describe('api client — the { status, json } contract', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('GET parses the JSON body and carries the bearer token', async () => {
    const fn = mockFetch(200, JSON.stringify({ laws: [{ id: 'L1' }] }));
    const r = await getProofLaws('dev:ceo');
    expect(r.status).toBe(200);
    expect(r.json.laws[0].id).toBe('L1');
    const [path, init] = fn.mock.calls[0];
    expect(path).toBe('/api/v1/proof/laws');
    expect(init.method).toBe('GET');
    expect(init.headers.Authorization).toBe('Bearer dev:ceo');
  });

  it('an empty body decodes to null rather than throwing', async () => {
    mockFetch(204, '');
    const r = await getProofLaws('dev:ceo');
    expect(r.status).toBe(204);
    expect(r.json).toBeNull();
  });

  it('a 403 still returns a structured result (the wall, not an exception)', async () => {
    mockFetch(403, JSON.stringify({ error: 'forbidden', message: 'requires view:proof_center' }));
    const r = await getProofLaws('dev:agent');
    expect(r.status).toBe(403);
    expect(r.json.error).toBe('forbidden');
  });

  it('POST sends the method and the path interpolates the code', async () => {
    const fn = mockFetch(200, JSON.stringify({ result: 'pass' }));
    await runProofControl('dev:ceo', 'CTRL-LINEAGE-CLOSURE');
    const [path, init] = fn.mock.calls[0];
    expect(path).toBe('/api/v1/proof/controls/CTRL-LINEAGE-CLOSURE/run');
    expect(init.method).toBe('POST');
  });

  it('path params interpolate (trial-balance entity id)', async () => {
    const fn = mockFetch(200, JSON.stringify({ balanced: true }));
    await getProofTrialBalance('dev:ceo', 'e-123');
    expect(fn.mock.calls[0][0]).toBe('/api/v1/proof/trial-balance/e-123');
  });
});
