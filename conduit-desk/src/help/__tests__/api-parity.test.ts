import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import { CHAPTERS } from '../content';

// Chapter↔API parity (spec 38 §5b, M-Help.apiparity): every operation a chapter claims to document (apiOps) must
// exist in the committed OpenAPI spec — which is itself generated from the live Tapir endpoints. So a chapter
// can't cite an endpoint the API doesn't expose, and if an endpoint's path changes the spec regenerates and this
// fails until the chapter is fixed. The spec is api/openapi.json (regenerate via `sbt "api/runMain …GenerateOpenApi"`).
const norm = (p: string) => p.replace(/\{[^}]+\}/g, '{}'); // ignore param NAMES, compare shapes

const spec = JSON.parse(readFileSync('../api/openapi.json', 'utf8')) as { paths: Record<string, Record<string, unknown>> };
const specOps = new Set<string>();
for (const [path, methods] of Object.entries(spec.paths)) {
  for (const m of Object.keys(methods)) specOps.add(`${m.toUpperCase()} ${norm(path)}`);
}

describe('manual chapter ↔ OpenAPI parity', () => {
  it('every chapter apiOp exists in the generated spec', () => {
    const missing: string[] = [];
    for (const c of CHAPTERS) {
      for (const op of c.apiOps ?? []) {
        const [method, path] = op.split(' ');
        if (!specOps.has(`${method} ${norm(path)}`)) missing.push(`${c.id}: ${op}`);
      }
    }
    expect(missing, `apiOps not found in api/openapi.json:\n${missing.join('\n')}`).toEqual([]);
  });

  it('the spec is non-trivial (sanity)', () => {
    expect(Object.keys(spec.paths).length).toBeGreaterThan(100);
  });
});
