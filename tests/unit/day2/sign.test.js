const {
  canonical, sign, signStreamKey, signCostream, signQr, signCartMerge, signModeration,
} = require('../../../backend/day2/lib/sign');
const { ApiError } = require('../../../backend/day2/lib/errors');

// Expected digests come from a one-off independent Python calculation
// (hmac + json.dumps(sort_keys=True, separators=(",", ":"), ensure_ascii=False)) with the public reference salt.
describe('Part II signatures match the independent reference', () => {
  test('stream key', () => {
    expect(signStreamKey({ action: 'rotate', player_id: 'player-alpha-1', status: 'active', stream_key: 'sk_live_9f83a1b2c3d4e5f6' }))
      .toBe('9bd054eb746844b4701c6f158209c315ef2b16f392efd733bdf0715940610289');
  });
  test('co-stream with a handoff', () => {
    expect(signCostream({ active_host: 'player-alpha-2', handoff_occurred: true, layout: 'grid_4', participant_count: 3, session_id: 'cs-session-101', status: 'active' }))
      .toBe('5211c8c6f8a8e7694c68aa64469ee012eb90b2def8a4d9bf43399f18566aeee4');
  });
  test('co-stream terminated with a null host', () => {
    expect(signCostream({ active_host: null, handoff_occurred: false, layout: 'solo', participant_count: 0, session_id: 'cs-session-102', status: 'terminated' }))
      .toBe('b5f07c77e2dcd5ce7ff5fa0ac06f8af80472f6bf26b506ff48a3bb2a1550dd78');
  });
  test('QR', () => {
    expect(signQr({ entity_id: 'match-m1', short_url: 'http://localhost:8080/q/m1-live', target_route: '/web/matches/match-m1/live' }))
      .toBe('9c5ce507faf71e175f9faa35b966909c78142735c39811fb0f55dbf4ece9437b');
  });
  test('cart merge', () => {
    expect(signCartMerge({ cart_merged: true, guest_token: 'guest_anon_tok_7721', registered_user_id: 'player-alpha-1', total_items: 1, write_rejection_code: 403 }))
      .toBe('152d6fcfffb561ffcd3ec2a3a2c5e5a818efffedeeef6bf610b2c7e46177ad15');
  });
  test('moderation', () => {
    expect(signModeration({ chat_room_id: 'chat-cs-session-101', flagged_count: 1, kicked_users: ['user-spammer'], queue_depth: 0 }))
      .toBe('265f5fb2db106eec3ee58c1c4b205caebc2163fca78c534a9fcfc2dcf88d1db8');
  });
  test('unicode, null and nested arrays', () => {
    expect(sign('e-uni-1', { reason: 'گول ⚽', z: null, a: [1, { b: 'é' }] }))
      .toBe('32b0fda1ce86381a81e47eedf876b1ace8bb5d7e95d286258d640534548b9521');
  });
  test('wrappers use the pipe separator, not a colon', () => {
    const payload = { entity_id: 'match-m1', short_url: 'u', target_route: 'r' };
    expect(signQr(payload)).toBe(sign('match-m1', payload));
    expect(signQr(payload)).not.toBe(sign('match-m1:', payload));
  });
  test('output is 64 lowercase hex characters', () => {
    expect(sign('x', {})).toMatch(/^[0-9a-f]{64}$/);
  });
});

describe('canonical', () => {
  test('sorts keys at every level by code point and keeps array order', () => {
    expect(canonical({ b: 1, a: { d: [3, 2, 1], c: null } })).toBe('{"a":{"c":null,"d":[3,2,1]},"b":1}');
    // U+FFFF sorts before an astral character even though UTF-16 order says otherwise
    expect(canonical({ '\u{1F600}': 1, '￿': 2 })).toBe('{"￿":2,"\u{1F600}":1}');
  });
  test('does not escape non-ASCII and prints no spaces', () => {
    expect(canonical({ k: 'گول' })).toBe('{"k":"گول"}');
  });
  test('booleans, null, negative and zero integers', () => {
    expect(canonical([true, false, null, -5, 0])).toBe('[true,false,null,-5,0]');
  });
  test('rejects non-integer numbers', () => {
    expect(() => canonical({ a: 1.5 })).toThrow(TypeError);
    expect(() => canonical({ a: NaN })).toThrow(TypeError);
    expect(() => canonical({ a: Infinity })).toThrow(TypeError);
  });
  test('rejects undefined values and unsupported types', () => {
    expect(() => canonical({ a: undefined })).toThrow(TypeError);
    expect(() => canonical(undefined)).toThrow(TypeError);
    expect(() => canonical({ a: () => 1 })).toThrow(TypeError);
    expect(() => canonical({ a: BigInt(1) })).toThrow(TypeError);
  });
  test('wrappers reject a missing field', () => {
    expect(() => signQr({ entity_id: 'x', short_url: 'y' })).toThrow(TypeError);
  });
});

describe('salt handling', () => {
  const saved = process.env.DAY2_SALT;
  afterEach(() => { process.env.DAY2_SALT = saved; });

  test('a missing or empty salt throws a 500 ApiError that never contains a salt value', () => {
    for (const bad of [undefined, '']) {
      if (bad === undefined) delete process.env.DAY2_SALT; else process.env.DAY2_SALT = bad;
      let err;
      try { sign('x', {}); } catch (e) { err = e; }
      expect(err).toBeInstanceOf(ApiError);
      expect(err.status).toBe(500);
      expect(err.message).not.toContain(saved);
    }
  });
  test('the salt is read at call time and used verbatim (no trimming)', () => {
    const a = sign('x', {});
    process.env.DAY2_SALT = ` ${saved} `;
    expect(sign('x', {})).not.toBe(a);
    process.env.DAY2_SALT = saved;
    expect(sign('x', {})).toBe(a);
  });
});
