const { inspectBody, MAX_DEPTH, MAX_NODES } = require('../../../backend/src/domain/rbac/bodyWalk');

const nestArr = (n) => { let v = []; for (let i = 1; i < n; i += 1) v = [v]; return v; };
const nestObj = (n) => { let v = {}; for (let i = 1; i < n; i += 1) v = { a: v }; return v; };

describe('body walker', () => {
  test('limits', () => { expect(MAX_DEPTH).toBe(32); expect(MAX_NODES).toBe(10000); });
  test('depth 32 ok, 33 rejected (arrays and objects)', () => {
    expect(inspectBody(nestArr(32)).ok).toBe(true);
    expect(inspectBody(nestArr(33))).toEqual({ ok: false, reason: 'depth' });
    expect(inspectBody(nestObj(32)).ok).toBe(true);
    expect(inspectBody(nestObj(33))).toEqual({ ok: false, reason: 'depth' });
  });
  test('extremely deep body does not overflow the stack', () => {
    expect(inspectBody(nestArr(200000)).reason).toBe('depth');
  });
  test('NUL in value, key, array element, nested', () => {
    expect(inspectBody({ a: 'x\u0000y' })).toEqual({ ok: false, reason: 'nul' });
    expect(inspectBody({ 'k\u0000': 1 }).reason).toBe('nul');
    expect(inspectBody(['ok', 'b\u0000']).reason).toBe('nul');
    expect(inspectBody({ a: [{ b: { c: '\u0000' } }] }).reason).toBe('nul');
    expect(inspectBody('\u0000').reason).toBe('nul');
  });
  test('other control characters are not NUL', () => {
    expect(inspectBody({ a: 'tab\there\u0001\n' }).ok).toBe(true);
  });
  test('node count limit', () => {
    expect(inspectBody(new Array(9999).fill(1)).ok).toBe(true);
    expect(inspectBody(new Array(10000).fill(1))).toEqual({ ok: false, reason: 'nodes' });
    const wide = {};
    for (let i = 0; i < 10001; i += 1) wide[`k${i}`] = i;
    expect(inspectBody(wide).reason).toBe('nodes');
  });
  test('custom limits', () => {
    expect(inspectBody([[1]], { maxDepth: 1 }).reason).toBe('depth');
    expect(inspectBody([1, 2, 3], { maxNodes: 3 }).reason).toBe('nodes');
  });
  test('scalars, null and empty are fine; prototype keys are plain data', () => {
    for (const v of [null, 1, true, 'str', {}, []]) expect(inspectBody(v).ok).toBe(true);
    const body = JSON.parse('{"__proto__":{"admin":true},"constructor":{"prototype":{"x":1}},"prototype":1}');
    expect(inspectBody(body).ok).toBe(true);
    expect({}.admin).toBeUndefined();
  });
});
