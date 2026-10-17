const crypto = require('crypto');
const jwt = require('../../../backend/node_modules/jsonwebtoken');
const config = require('../../../backend/src/config');
const { verifyAccessToken, signAccessToken } = require('../../../backend/src/services/authService');

const secret = config.jwtSecret;
const b64 = (o) => Buffer.from(typeof o === 'string' ? o : JSON.stringify(o)).toString('base64url');
const claims = (over = {}) => ({
  sub: 'user-viewer', role: 'viewer', tenant_id: 'tenant-alpha', league_ids: [], club_id: null, player_id: null,
  jti: 'j', ...over,
});
const sign = (payload, opts = {}, key = secret) => jwt.sign(payload, key, { algorithm: 'HS256', expiresIn: 3600, ...opts });

describe('verifyAccessToken', () => {
  test('valid token yields the principal', () => {
    const t = signAccessToken({ user_id: 'user-player', role: 'player', tenant_id: 'tenant-alpha', league_ids: [], club_id: 'club-alpha-1', player_id: 'player-alpha-1' });
    expect(verifyAccessToken(t)).toEqual({
      user_id: 'user-player', role: 'player', tenant_id: 'tenant-alpha', league_ids: [], club_id: 'club-alpha-1', player_id: 'player-alpha-1',
    });
  });

  test('minted tokens carry the documented claims and a 12 h expiry', () => {
    const t = signAccessToken({ user_id: 'u', role: 'viewer', tenant_id: 'tenant-alpha' });
    const c = jwt.decode(t);
    expect(Object.keys(c).sort()).toEqual(['club_id', 'exp', 'iat', 'jti', 'league_ids', 'player_id', 'role', 'sub', 'tenant_id']);
    expect(c.exp - c.iat).toBe(43200);
    expect(jwt.decode(t, { complete: true }).header.alg).toBe('HS256');
    expect(jwt.decode(signAccessToken({ user_id: 'u', role: 'viewer' })).jti).not.toBe(c.jti);
  });

  test('malformed claim types are normalised', () => {
    const t = sign(claims({ league_ids: ['a', 5, null], club_id: 7, tenant_id: 3, player_id: {} }));
    expect(verifyAccessToken(t)).toEqual({
      user_id: 'user-viewer', role: 'viewer', tenant_id: null, league_ids: ['a'], club_id: null, player_id: null,
    });
    expect(verifyAccessToken(sign(claims({ league_ids: 'x' }))).league_ids).toEqual([]);
  });

  test('expired', () => {
    expect(verifyAccessToken(jwt.sign({ ...claims(), exp: Math.floor(Date.now() / 1000) - 10 }, secret, { algorithm: 'HS256' }))).toBeNull();
  });

  test('missing exp', () => {
    expect(verifyAccessToken(jwt.sign(claims(), secret, { algorithm: 'HS256' }))).toBeNull();
  });

  test('tampered payload keeps old signature', () => {
    const [h, , s] = sign(claims()).split('.');
    const forged = `${h}.${b64(claims({ role: 'super_admin', exp: Math.floor(Date.now() / 1000) + 3600 }))}.${s}`;
    expect(verifyAccessToken(forged)).toBeNull();
  });

  test('tampered signature', () => {
    const t = sign(claims());
    const flipped = t.slice(0, -2) + (t.endsWith('AA') ? 'BB' : 'AA');
    expect(verifyAccessToken(flipped)).toBeNull();
    expect(verifyAccessToken(`${t.split('.').slice(0, 2).join('.')}.`)).toBeNull();
  });

  test.each(['none', 'None', 'NONE', 'nOnE'])('alg %s', (alg) => {
    const payload = claims({ role: 'super_admin', exp: Math.floor(Date.now() / 1000) + 3600 });
    expect(verifyAccessToken(`${b64({ alg, typ: 'JWT' })}.${b64(payload)}.`)).toBeNull();
    expect(verifyAccessToken(`${b64({ alg, typ: 'JWT' })}.${b64(payload)}.abc`)).toBeNull();
  });

  test('HS384 and HS512 signed with the right secret are rejected', () => {
    expect(verifyAccessToken(jwt.sign(claims(), secret, { algorithm: 'HS384', expiresIn: 3600 }))).toBeNull();
    expect(verifyAccessToken(jwt.sign(claims(), secret, { algorithm: 'HS512', expiresIn: 3600 }))).toBeNull();
  });

  test('RS256 header (key confusion attempt)', () => {
    const payload = claims({ exp: Math.floor(Date.now() / 1000) + 3600 });
    const signingInput = `${b64({ alg: 'RS256', typ: 'JWT' })}.${b64(payload)}`;
    const sig = crypto.createHmac('sha256', secret).update(signingInput).digest('base64url');
    expect(verifyAccessToken(`${signingInput}.${sig}`)).toBeNull();
  });

  test('wrong secret', () => {
    expect(verifyAccessToken(sign(claims(), {}, 'another-secret'))).toBeNull();
  });

  test('garbage, empty and non-string', () => {
    for (const v of ['', 'garbage', 'a.b', 'a.b.c', 'a.b.c.d', '...', undefined, null, 5, {}, 'x'.repeat(9000)]) {
      expect(verifyAccessToken(v)).toBeNull();
    }
  });

  test('unknown role, missing role, missing sub', () => {
    expect(verifyAccessToken(sign(claims({ role: 'root' })))).toBeNull();
    expect(verifyAccessToken(sign(claims({ role: undefined })))).toBeNull();
    expect(verifyAccessToken(sign(claims({ role: '__proto__' })))).toBeNull();
    expect(verifyAccessToken(sign(claims({ sub: undefined })))).toBeNull();
    expect(verifyAccessToken(sign(claims({ sub: '' })))).toBeNull();
  });

  test('explicit secret argument is honoured', () => {
    const t = sign(claims(), {}, 'k2');
    expect(verifyAccessToken(t, 'k2')).not.toBeNull();
    expect(verifyAccessToken(t)).toBeNull();
  });
});
