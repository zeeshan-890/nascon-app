const jwt = require('../../backend/node_modules/jsonwebtoken');
const config = require('../../backend/src/config');
const { describeDb } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');

const ROLES = ['super_admin', 'league_organizer', 'club_manager', 'player', 'viewer'];
const T = true;
const F = false;

describeDb('RBAC matrix + probes', () => {
  let tokens;
  let ctx;
  beforeAll(async () => {
    await resetDb();
    const r = await (await api()).post('/audit/v1/rbac-matrix').send({});
    tokens = r.body.tokens;
    ctx = r.body.context;
  });
  afterAll(() => pool.end());

  const call = async (method, path, token, body) => {
    let req = (await api())[method](path);
    if (token) req = req.set('Authorization', `Bearer ${token}`);
    return body === undefined ? req : req.send(body);
  };

  test('rbac-matrix exact shape and claims', async () => {
    const r = await (await api()).post('/audit/v1/rbac-matrix').send({});
    expect(r.status).toBe(200);
    expect(Object.keys(r.body).sort()).toEqual(['context', 'tokens']);
    expect(Object.keys(r.body.tokens)).toEqual(ROLES);
    expect(r.body.context).toEqual({
      tenant_id: 'tenant-alpha', foreign_tenant_id: 'tenant-beta',
      own_league_id: 'league-alpha-1', other_league_id: 'league-alpha-2', foreign_league_id: 'league-beta-1',
      own_club_id: 'club-alpha-1', other_club_id: 'club-alpha-2', foreign_club_id: 'club-beta-1',
      own_player_id: 'player-alpha-1', other_player_id: 'player-alpha-2', foreign_player_id: 'player-beta-1',
    });
    const claims = Object.fromEntries(ROLES.map((role) => [role, jwt.verify(r.body.tokens[role], config.jwtSecret, { algorithms: ['HS256'] })]));
    expect(claims.super_admin.role).toBe('super_admin');
    expect(claims.super_admin.tenant_id).toBeNull();
    for (const role of ROLES.slice(1)) expect(claims[role].tenant_id).toBe('tenant-alpha');
    expect(claims.league_organizer.league_ids).toEqual(['league-alpha-1']);
    expect(claims.club_manager.club_id).toBe('club-alpha-1');
    expect(claims.player.player_id).toBe('player-alpha-1');
    expect(claims.player.club_id).toBe('club-alpha-1');
    for (const c of Object.values(claims)) {
      expect(c.exp - c.iat).toBe(43200);
      expect(typeof c.jti).toBe('string');
    }
  });

  test.each([
    ['garbage', 'not json at all {{{'],
    ['empty', ''],
    ['array', '[1,2]'],
    ['object', '{}'],
  ])('rbac-matrix ignores the body (%s)', async (name, raw) => {
    const r = await (await api()).post('/audit/v1/rbac-matrix').set('Content-Type', 'application/json').send(raw);
    expect(r.status).toBe(200);
    expect(Object.keys(r.body.tokens)).toEqual(ROLES);
  });

  // [label, method, path builder, expected 200 per role]
  const PROBES = [
    ['P1 tenants', 'post', () => '/audit/v1/rbac/tenants', [T, F, F, F, F]],
    ['P2 own', 'post', (c) => `/audit/v1/rbac/leagues/${c.own_league_id}/fixtures`, [T, T, F, F, F]],
    ['P2 other', 'post', (c) => `/audit/v1/rbac/leagues/${c.other_league_id}/fixtures`, [T, F, F, F, F]],
    ['P2 foreign', 'post', (c) => `/audit/v1/rbac/leagues/${c.foreign_league_id}/fixtures`, [T, F, F, F, F]],
    ['P3 own', 'post', (c) => `/audit/v1/rbac/clubs/${c.own_club_id}/roster`, [T, F, T, F, F]],
    ['P3 other', 'post', (c) => `/audit/v1/rbac/clubs/${c.other_club_id}/roster`, [T, F, F, F, F]],
    ['P3 foreign', 'post', (c) => `/audit/v1/rbac/clubs/${c.foreign_club_id}/roster`, [T, F, F, F, F]],
    ['P4 own', 'post', (c) => `/audit/v1/rbac/clubs/${c.own_club_id}/products`, [T, F, T, F, F]],
    ['P4 other', 'post', (c) => `/audit/v1/rbac/clubs/${c.other_club_id}/products`, [T, F, F, F, F]],
    ['P4 foreign', 'post', (c) => `/audit/v1/rbac/clubs/${c.foreign_club_id}/products`, [T, F, F, F, F]],
    ['P5 own', 'patch', (c) => `/audit/v1/rbac/players/${c.own_player_id}`, [T, F, T, T, F]],
    ['P5 other', 'patch', (c) => `/audit/v1/rbac/players/${c.other_player_id}`, [T, F, F, F, F]],
    ['P5 foreign', 'patch', (c) => `/audit/v1/rbac/players/${c.foreign_player_id}`, [T, F, F, F, F]],
    ['P6 own', 'get', (c) => `/audit/v1/rbac/clubs/${c.own_club_id}`, [T, T, T, T, T]],
    ['P6 other', 'get', (c) => `/audit/v1/rbac/clubs/${c.other_club_id}`, [T, T, T, T, T]],
    ['P6 foreign', 'get', (c) => `/audit/v1/rbac/clubs/${c.foreign_club_id}`, [T, F, F, F, F]],
    ['P7 config', 'get', () => '/audit/v1/rbac/platform/config', [T, F, F, F, F]],
  ];

  const cells = [];
  for (const [label, method, path, exp] of PROBES) ROLES.forEach((role, i) => cells.push([label, role, method, path, exp[i]]));

  test.each(cells)('%s as %s', async (label, role, method, path, allowed) => {
    const r = await call(method, path(ctx), tokens[role]);
    if (allowed) {
      expect(r.status).toBe(200);
      expect(r.body).toEqual({ authorized: true });
    } else {
      expect(r.status).toBe(403);
      expect(r.body).toEqual({ error: { code: 'FORBIDDEN', message: expect.any(String), details: {} } });
    }
  });

  describe('401 cases on P7', () => {
    const expected401 = { error: { code: 'UNAUTHENTICATED', message: expect.any(String), details: {} } };
    const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
    const now = Math.floor(Date.now() / 1000);
    const cases = {
      'no header': () => ({}),
      'Basic scheme': () => ({ Authorization: 'Basic abc' }),
      'empty bearer': () => ({ Authorization: 'Bearer ' }),
      'bearer only': () => ({ Authorization: 'Bearer' }),
      'non-JWT string': () => ({ Authorization: 'Bearer hello' }),
      'tampered super admin': () => {
        const [h, , s] = tokens.viewer.split('.');
        const p = JSON.parse(Buffer.from(tokens.viewer.split('.')[1], 'base64url'));
        return { Authorization: `Bearer ${h}.${b64({ ...p, role: 'super_admin' })}.${s}` };
      },
      'tampered signature': () => ({ Authorization: `Bearer ${tokens.super_admin.slice(0, -3)}abc` }),
      'alg none': () => ({ Authorization: `Bearer ${b64({ alg: 'none', typ: 'JWT' })}.${b64({ sub: 'x', role: 'super_admin', exp: now + 999 })}.` }),
      'alg None': () => ({ Authorization: `Bearer ${b64({ alg: 'None', typ: 'JWT' })}.${b64({ sub: 'x', role: 'super_admin', exp: now + 999 })}.` }),
      expired: () => ({ Authorization: `Bearer ${jwt.sign({ sub: 'user-admin', role: 'super_admin', exp: now - 60 }, config.jwtSecret, { algorithm: 'HS256' })}` }),
      'missing exp': () => ({ Authorization: `Bearer ${jwt.sign({ sub: 'user-admin', role: 'super_admin' }, config.jwtSecret, { algorithm: 'HS256' })}` }),
      'other secret': () => ({ Authorization: `Bearer ${jwt.sign({ sub: 'user-admin', role: 'super_admin' }, 'other', { algorithm: 'HS256', expiresIn: 60 })}` }),
      HS384: () => ({ Authorization: `Bearer ${jwt.sign({ sub: 'user-admin', role: 'super_admin' }, config.jwtSecret, { algorithm: 'HS384', expiresIn: 60 })}` }),
      'unknown role': () => ({ Authorization: `Bearer ${jwt.sign({ sub: 'user-admin', role: 'god' }, config.jwtSecret, { algorithm: 'HS256', expiresIn: 60 })}` }),
    };
    test.each(Object.keys(cases))('%s -> identical 401', async (name) => {
      const r = await (await api()).get('/audit/v1/rbac/platform/config').set(cases[name]());
      expect(r.status).toBe(401);
      expect(r.body).toEqual(expected401);
    });
    test('lowercase bearer scheme is accepted', async () => {
      const r = await (await api()).get('/audit/v1/rbac/platform/config').set('Authorization', `bearer ${tokens.super_admin}`);
      expect(r.status).toBe(200);
    });
    test('every probe route requires authentication', async () => {
      for (const [, method, path] of PROBES) {
        const r = await call(method, path(ctx), null);
        expect(r.status).toBe(401);
      }
    });
  });

  describe('resource handling', () => {
    test('super admin on missing ids -> 404 with per-resource codes', async () => {
      const t = tokens.super_admin;
      const l = await call('post', '/audit/v1/rbac/leagues/nope/fixtures', t);
      expect(l.status).toBe(404);
      expect(l.body.error.code).toBe('LEAGUE_NOT_FOUND');
      const c = await call('get', '/audit/v1/rbac/clubs/nope', t);
      expect(c.status).toBe(404);
      expect(c.body.error).toEqual({ code: 'NOT_FOUND', message: 'Club not found', details: {} });
      const p = await call('patch', '/audit/v1/rbac/players/nope', t);
      expect(p.status).toBe(404);
      expect(p.body.error.code).toBe('PLAYER_NOT_FOUND');
      expect((await call('post', '/audit/v1/rbac/clubs/nope/roster', t)).status).toBe(404);
      expect((await call('post', '/audit/v1/rbac/clubs/nope/products', t)).status).toBe(404);
    });

    test('other roles: missing id -> 404 (after policy check), foreign existing -> 403', async () => {
      expect((await call('get', '/audit/v1/rbac/clubs/club-zzz', tokens.viewer)).status).toBe(404);
      expect((await call('post', '/audit/v1/rbac/leagues/league-zzz/fixtures', tokens.league_organizer)).body.error.code).toBe('LEAGUE_NOT_FOUND');
      expect((await call('patch', '/audit/v1/rbac/players/player-zzz', tokens.player)).body.error.code).toBe('PLAYER_NOT_FOUND');
      expect((await call('get', `/audit/v1/rbac/clubs/${ctx.foreign_club_id}`, tokens.viewer)).status).toBe(403);
    });

    test('role without policy -> 403 before resource lookup or id validation', async () => {
      for (const [method, path, role] of [
        ['post', '/audit/v1/rbac/leagues/bad%20id!/fixtures', 'viewer'],
        ['post', '/audit/v1/rbac/clubs/bad%20id!/roster', 'player'],
        ['patch', '/audit/v1/rbac/players/bad%20id!', 'viewer'],
        ['post', '/audit/v1/rbac/leagues/missing/fixtures', 'viewer'],
      ]) {
        const r = await call(method, path, tokens[role]);
        expect(r.status).toBe(403);
      }
    });

    test('allowed role with malformed id -> 400 VALIDATION_ERROR; injection ids never 5xx', async () => {
      for (const id of ['bad%20id!', encodeURIComponent("x'; DROP TABLE clubs;--"), 'a'.repeat(65), '%00', '%E0%A4%A']) {
        const r = await call('get', `/audit/v1/rbac/clubs/${id}`, tokens.viewer);
        expect(r.status).toBe(400);
        expect(r.body.error.code).toBe('VALIDATION_ERROR');
        const a = await call('get', `/audit/v1/rbac/clubs/${id}`, tokens.super_admin);
        expect(a.status).toBeLessThan(500);
      }
      const { rows } = await pool.query('SELECT count(*)::int AS n FROM clubs');
      expect(rows[0].n).toBe(3);
    });
  });

  describe('bodies are never read', () => {
    test('malformed JSON + no token -> 401, not 400', async () => {
      const r = await (await api()).post('/audit/v1/rbac/tenants').set('Content-Type', 'application/json').send('{bad');
      expect(r.status).toBe(401);
    });
    test('valid token + malformed / non-object / NUL / deep body -> normal result', async () => {
      const deep = `${'['.repeat(40)}${']'.repeat(40)}`;
      for (const raw of ['{bad', '[1,2,3]', '"str"', '42', '', '{"a":"\\u0000"}', deep]) {
        const ok = await (await api()).post('/audit/v1/rbac/tenants').set('Authorization', `Bearer ${tokens.super_admin}`).set('Content-Type', 'application/json').send(raw);
        expect(ok.status).toBe(200);
        expect(ok.body).toEqual({ authorized: true });
        const no = await (await api()).post('/audit/v1/rbac/tenants').set('Authorization', `Bearer ${tokens.viewer}`).set('Content-Type', 'application/json').send(raw);
        expect(no.status).toBe(403);
      }
    });
    test('viewer calling P2 with malformed id and malformed body -> 403', async () => {
      const r = await (await api()).post('/audit/v1/rbac/leagues/bad%20id!/fixtures').set('Authorization', `Bearer ${tokens.viewer}`).set('Content-Type', 'application/json').send('{bad');
      expect(r.status).toBe(403);
    });
    test('size limit still gives 413', async () => {
      const big = JSON.stringify({ pad: 'x'.repeat(1.2 * 1024 * 1024) });
      const r = await (await api()).post('/audit/v1/rbac/tenants').set('Authorization', `Bearer ${tokens.super_admin}`).set('Content-Type', 'application/json').send(big);
      expect(r.status).toBe(413);
      expect(r.body.error.code).toBe('PAYLOAD_TOO_LARGE');
    });
  });

  test('probes do not mutate state', async () => {
    const tables = ['tenants', 'clubs', 'players', 'leagues', 'users', 'role_policies', 'products', 'variants', 'orders', 'matches', 'match_events', 'refresh_tokens'];
    const count = async () => {
      const out = {};
      for (const t of tables) out[t] = (await pool.query(`SELECT count(*)::int AS n FROM ${t}`)).rows[0].n;
      return out;
    };
    const before = await count();
    for (const [, method, path] of PROBES) {
      for (const role of ROLES) await call(method, path(ctx), tokens[role], { name: 'mutate', stock: 0 });
    }
    expect(await count()).toEqual(before);
  });

  test('tokens minted before a reset remain valid after it (stateless)', async () => {
    await resetDb();
    const r = await call('get', '/audit/v1/rbac/platform/config', tokens.super_admin);
    expect(r.status).toBe(200);
    // policies are refreshed after reset: matrix still correct
    expect((await call('post', '/audit/v1/rbac/tenants', tokens.viewer)).status).toBe(403);
    expect((await call('get', `/audit/v1/rbac/clubs/${ctx.other_club_id}`, tokens.viewer)).status).toBe(200);
  });
});
