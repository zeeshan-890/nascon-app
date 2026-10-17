const { z } = require('zod');
const v = require('../../backend/src/http/validators');

const ok = (s, x) => s.safeParse(x).success;

describe('Id', () => {
  test('edges', () => {
    expect(ok(v.Id, 'a'.repeat(64))).toBe(true);
    expect(ok(v.Id, 'a'.repeat(65))).toBe(false);
    expect(ok(v.Id, '')).toBe(false);
    expect(ok(v.Id, 'a|b')).toBe(false);
    expect(ok(v.Id, "x'; DROP TABLE")).toBe(false);
    expect(ok(v.Id, 'evt-1.a:b_c')).toBe(true);
    expect(ok(v.Id, 5)).toBe(false);
  });
});

describe('simple string ids', () => {
  test('SportId / EventKey', () => {
    expect(ok(v.SportId, 'ab')).toBe(true);
    expect(ok(v.SportId, 'a')).toBe(false);
    expect(ok(v.SportId, 'AB')).toBe(false);
    expect(ok(v.EventKey, 'red_card')).toBe(true);
    expect(ok(v.EventKey, 'red-card')).toBe(false);
    expect(ok(v.EventKey, '')).toBe(false);
  });
});

describe('ints and bool', () => {
  test('Int', () => {
    expect(ok(v.Int, 3)).toBe(true);
    expect(ok(v.Int, -3)).toBe(true);
    expect(ok(v.Int, '3')).toBe(false);
    expect(ok(v.Int, 3.5)).toBe(false);
    expect(ok(v.Int, true)).toBe(false);
    expect(ok(v.Int, 2 ** 60)).toBe(false);
  });
  test('PosInt / NonNegInt', () => {
    expect(ok(v.PosInt, 1)).toBe(true);
    expect(ok(v.PosInt, 0)).toBe(false);
    expect(ok(v.NonNegInt, 0)).toBe(true);
    expect(ok(v.NonNegInt, -1)).toBe(false);
  });
  test('Bool strict', () => {
    expect(ok(v.Bool, false)).toBe(true);
    expect(ok(v.Bool, 'true')).toBe(false);
    expect(ok(v.Bool, 1)).toBe(false);
  });
});

describe('dates', () => {
  test('IsoDate', () => {
    expect(ok(v.IsoDate, '2028-02-29')).toBe(true);
    expect(ok(v.IsoDate, '2026-02-29')).toBe(false);
    expect(ok(v.IsoDate, '2026-02-30')).toBe(false);
    expect(ok(v.IsoDate, '2000-02-29')).toBe(true);
    expect(ok(v.IsoDate, '1900-02-29')).toBe(false);
    expect(ok(v.IsoDate, '2026-13-01')).toBe(false);
    expect(ok(v.IsoDate, '2026-1-01')).toBe(false);
  });
  test('IsoTimestamp', () => {
    expect(ok(v.IsoTimestamp, '2026-05-01T10:20:30Z')).toBe(true);
    expect(ok(v.IsoTimestamp, '2026-05-01T10:20:30.123Z')).toBe(false);
    expect(ok(v.IsoTimestamp, '2026-02-30T10:20:30Z')).toBe(false);
    expect(ok(v.IsoTimestamp, '2026-05-01T24:00:00Z')).toBe(false);
    expect(ok(v.IsoTimestamp, '2026-05-01T10:60:00Z')).toBe(false);
    expect(ok(v.IsoTimestamp, '2026-05-01 10:20:30Z')).toBe(false);
  });
  test('nowIso format', () => {
    expect(v.nowIso()).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
    expect(ok(v.IsoTimestamp, v.nowIso())).toBe(true);
  });
});

describe('Text / PlainObject', () => {
  test('Text', () => {
    expect(ok(v.Text(3), 'abc')).toBe(true);
    expect(ok(v.Text(3), 'abcd')).toBe(false);
    expect(ok(v.Text(3), '')).toBe(false);
  });
  test('PlainObject', () => {
    expect(ok(v.PlainObject, {})).toBe(true);
    expect(ok(v.PlainObject, null)).toBe(false);
    expect(ok(v.PlainObject, [])).toBe(false);
    expect(ok(v.PlainObject, 'x')).toBe(false);
    expect(v.isPlainObject({ a: 1 })).toBe(true);
    expect(v.isPlainObject([])).toBe(false);
    expect(v.isPlainObject(null)).toBe(false);
  });
});

describe('issuesToFields / parseOr400', () => {
  const schema = z.object({
    a: v.Id,
    n: v.Int,
    fixtures: z.array(z.object({ home: v.Id })),
  });

  test('collects multiple, dotted indexes', () => {
    const r = schema.safeParse({ a: '', n: 'x', fixtures: [{ home: 'ok' }, { home: '|' }] });
    expect(v.issuesToFields(r.error)).toEqual(['a', 'n', 'fixtures.1.home']);
    expect(v.issuesToFields(r.error, 'body')).toEqual(['body.a', 'body.n', 'body.fixtures.1.home']);
  });
  test('dedupes and maps empty path to body', () => {
    const r = z.string().safeParse(5);
    expect(v.issuesToFields(r.error)).toEqual(['body']);
    const r2 = v.Text(1).safeParse('');
    const dup = new z.ZodError([...r2.error.issues, ...r2.error.issues]);
    expect(v.issuesToFields(dup)).toEqual(['body']);
  });
  test('parseOr400 returns parsed data and strips unknown keys', () => {
    expect(v.parseOr400(schema, { a: 'x', n: 1, fixtures: [], extra: 1 })).toEqual({ a: 'x', n: 1, fixtures: [] });
  });
  test('parseOr400 lists all bad fields', () => {
    let err;
    try { v.parseOr400(schema, { a: '|', n: 1.5, fixtures: [{}] }); } catch (e) { err = e; }
    expect(err.status).toBe(400);
    expect(err.code).toBe('VALIDATION_ERROR');
    expect(err.message).toBe('Request validation failed');
    expect(err.details.fields).toEqual(['a', 'n', 'fixtures.0.home']);
  });
  test('requireObjectBody', () => {
    const b = { x: 1 };
    expect(v.requireObjectBody(b)).toBe(b);
    for (const bad of [null, [], 'x', 5, undefined]) {
      let err;
      try { v.requireObjectBody(bad); } catch (e) { err = e; }
      expect(err.status).toBe(400);
      expect(err.details).toEqual({ fields: ['body'] });
    }
  });
});
