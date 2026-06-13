import { describe, it, expect } from 'vitest';
import { sessionEmail } from '../session';

// doc 29 F / doc 27 §0: the session chip decodes the identity from the token — a dev token, a real Google
// JWT (email claim), and a malformed token must each resolve to a sane label, never throw.
describe('sessionEmail — the session chip label', () => {
  it('a dev token reads as a developer session', () => {
    expect(sessionEmail('dev:agent-e2e')).toBe('developer session');
  });

  it('a Google JWT surfaces the email claim', () => {
    const header = btoa(JSON.stringify({ alg: 'RS256' }));
    const payload = btoa(JSON.stringify({ email: 'flavian@hypervolt.co.uk', hd: 'hypervolt.co.uk' }));
    expect(sessionEmail(`${header}.${payload}.sig`)).toBe('flavian@hypervolt.co.uk');
  });

  it('a JWT without an email claim falls back to "signed in"', () => {
    const tok = `${btoa('{}')}.${btoa(JSON.stringify({ sub: '123' }))}.sig`;
    expect(sessionEmail(tok)).toBe('signed in');
  });

  it('a malformed token never throws — it resolves to a label', () => {
    expect(sessionEmail('not-a-jwt')).toBe('developer session'); // 1 part, not 3 → dev label
    expect(sessionEmail('a.b.c')).toBe('signed in'); // 3 parts but undecodable → caught
  });
});
