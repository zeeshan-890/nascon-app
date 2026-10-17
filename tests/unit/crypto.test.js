const { canonical, signWith, sign, lotsKey } = require('../../backend/src/domain/crypto');

describe('canonical', () => {
  test('sorts keys, no whitespace', () => {
    expect(canonical({ team_id: 't1', event_type: 'goal' })).toBe('{"event_type":"goal","team_id":"t1"}');
  });
  test('nested objects and arrays', () => {
    expect(canonical({ b: [3, { z: 1, a: null }, true, false], a: {} })).toBe('{"a":{},"b":[3,{"a":null,"z":1},true,false]}');
  });
  test('empty object, negative ints', () => {
    expect(canonical({})).toBe('{}');
    expect(canonical({ n: -5, m: 0 })).toBe('{"m":0,"n":-5}');
  });
  test('non-ASCII not escaped', () => {
    expect(canonical({ reason: 'گول', e: '😀' })).toBe('{"e":"😀","reason":"گول"}');
  });
  test('code point ordering: emoji after \\uFFFF', () => {
    expect(canonical({ '\u{1F600}': 1, '￿': 2 })).toBe('{"￿":2,"\u{1F600}":1}');
  });
  test('prefix ordering and undefined handling', () => {
    expect(canonical({ ab: 1, a: 2, u: undefined })).toBe('{"a":2,"ab":1}');
    expect(canonical(undefined)).toBe('null');
    expect(canonical([undefined])).toBe('[null]');
  });
  test('escapes quotes in strings', () => {
    expect(canonical({ k: 'a"b' })).toBe('{"k":"a\\"b"}');
  });
});

describe('sign', () => {
  const payload = { team_id: 't1', event_type: 'goal' };
  test('known vector', () => {
    expect(signWith('demo-salt', 'evt-1', payload)).toBe('972a8b558690b19121cc5bea84978f85e8eb15a3735018db36616bab1d7ec501');
  });
  test('sign uses config salt (demo-salt)', () => {
    expect(sign('evt-1', payload)).toBe('972a8b558690b19121cc5bea84978f85e8eb15a3735018db36616bab1d7ec501');
  });
  test('python reference with non-ASCII reason', () => {
    expect(signWith('demo-salt', 'e-ar-1', { reason: 'گول', compensates: 'e1' }))
      .toBe('54d59df2ffe0b1e89ce312fdb44ce10dcdd16ed6fe6fa7f6af06af24a8c3009a');
  });
  test('different salt differs', () => {
    expect(signWith('other', 'evt-1', payload)).not.toBe(signWith('demo-salt', 'evt-1', payload));
  });
});

describe('lotsKey', () => {
  test('vectors', () => {
    expect(lotsKey(42, 'lg-1', 't1')).toBe('0613316991ce0ac31e88d6003e3ff4d5e21bb072ac5f81b42c0cdc6598453293');
    expect(lotsKey(42, 'lg-1', 't2')).toBe('e3e3a14e22727f936e1f288cf5d02a8a9362b1da13c5432725fc10b2c7955dd9');
    expect(lotsKey(42, 'lg-1', 't3')).toBe('c8f28e3c2f1eb6c4f636b4f57050e3362a2b39c07fda9b61742a26dd20397028');
  });
});
