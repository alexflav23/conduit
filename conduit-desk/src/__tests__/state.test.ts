import { describe, it, expect } from 'vitest';
import { asArray, tableState } from '../state';

// doc 29 F: the data-table's four states + the crash-class guard, in one place.
describe('asArray — the crash-class guard', () => {
  it('passes arrays through', () => expect(asArray([1, 2])).toEqual([1, 2]));
  it('turns an error body into [] (never reaches .map)', () =>
    expect(asArray({ error: 'forbidden', message: 'nope' })).toEqual([]));
  it('turns null/undefined into []', () => {
    expect(asArray(null)).toEqual([]);
    expect(asArray(undefined)).toEqual([]);
  });
});

describe('tableState — one derivation for every list view', () => {
  it('null result is loading', () => expect(tableState(null, undefined)).toBe('loading'));
  it('403/401 is forbidden (the wall, not a crash)', () => {
    expect(tableState({ status: 403, json: { error: 'forbidden' } }, null)).toBe('forbidden');
    expect(tableState({ status: 401, json: null }, null)).toBe('forbidden');
  });
  it('any other 4xx/5xx is error — even with a non-array body', () => {
    expect(tableState({ status: 500, json: { message: 'boom' } }, { message: 'boom' })).toBe('error');
    expect(tableState({ status: 422, json: { error: 'x' } }, { error: 'x' })).toBe('error');
  });
  it('200 with no rows is empty', () => expect(tableState({ status: 200, json: [] }, [])).toBe('empty'));
  it('200 with rows is ready', () => expect(tableState({ status: 200, json: [1] }, [1])).toBe('ready'));
});
