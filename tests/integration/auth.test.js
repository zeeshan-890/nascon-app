const jwt = require('../../backend/node_modules/jsonwebtoken');
const config = require('../../backend/src/config');
const { describeDb } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');

const PASSWORD = 'Nascon@26';
const ACCOUNTS = [
  ['admin@nascon.test', 'super_admin'],
  ['organizer@nascon.test', 'league_organizer'],
  ['manager@nascon.test', 'club_manager'],
  ['player@nascon.test', 'player'],
  ['viewer@nascon.test', 'viewer'],
];

describeDb('auth endpoints', () => {
  const login = async (email, password = PASSWORD) => (await api()).post('/api/v1/auth/login').send({ email, password });
  const refresh = async (t) => (await api()).post('/api/v1/auth/refresh').send({ refresh_token: t });

  beforeAll(() => { process.env.LOGIN_LOCK_SECONDS = '2'; });
  beforeEach(() => resetDb());
  afterAll(async () => { delete process.env.LOGIN_LOCK_SECONDS; await pool.end(); });

  test.each(ACCOUNTS)('login %s as %s', async (email, role) => {
    const r = await login(email);
    expect(r.status).toBe(200);
    expect(r.headers['cache-control']).toBe('no-store');
    expect(r.body.expires_in).toBe(43200);
    expect(typeof r.body.refresh_token).toBe('string');
    expect(r.body.user.role).toBe(role);
    expect(Object.keys(r.body.user).sort()).toEqual(['club_id', 'email', 'league_ids', 'player_id', 'role', 'tenant_id', 'user_id']);
    const c = jwt.verify(r.body.access_token, config.jwtSecret, { algorithms: ['HS256'] });
    expect(c.role).toBe(role);
    if (role === 'super_admin') expect(r.body.tenant).toBeNull();
    else expect(r.body.tenant).toEqual({ tenant_id: 'tenant-alpha', name: 'Alpha Sports', logo_url: '/static/logo/tenant-alpha.png', primary_color: '#0B5FFF', secondary_color: '#FFB800' });
    expect(JSON.stringify(r.body)).not.toMatch(/password_hash|\$2[aby]\$/);
  });

  test('email lookup is case-insensitive', async () => {
    expect((await login('ADMIN@Nascon.Test')).status).toBe(200);
  });

  test('wrong password and unknown email give the same 401 body', async () => {
    const a = await login('viewer@nascon.test', 'wrong');
    const b = await login('nobody@nascon.test', 'wrong');
    expect(a.status).toBe(401);
    expect(b.status).toBe(401);
    expect(a.body).toEqual(b.body);
    expect(a.body).toEqual({ error: { code: 'UNAUTHENTICATED', message: 'Email or password is incorrect', details: {} } });
  });

  test.each([
    [{}], [{ email: 'a@b.c' }], [{ password: 'x' }], [{ email: 5, password: 'x' }], [{ email: 'a@b.c', password: '' }],
    [{ email: 'a@b.c', password: 'x'.repeat(201) }], [{ email: ['a'], password: 'x' }], [[1]],
  ])('validation %j -> 400', async (body) => {
    const r = await (await api()).post('/api/v1/auth/login').send(body);
    expect(r.status).toBe(400);
    expect(r.body.error.code).toBe('VALIDATION_ERROR');
  });

  test('injection in email is data', async () => {
    const r = await login("admin@nascon.test' OR '1'='1");
    expect(r.status).toBe(401);
  });

  test('5 failures lock the account; correct password refused during lock; works after', async () => {
    for (let i = 0; i < 5; i += 1) expect((await login('player@nascon.test', 'bad')).status).toBe(401);
    const locked = await login('player@nascon.test', PASSWORD);
    expect(locked.status).toBe(429);
    expect(locked.body.error.code).toBe('RATE_LIMITED');
    expect(Number(locked.headers['retry-after'])).toBeGreaterThanOrEqual(1);
    expect(locked.body.error.details.retry_after_seconds).toBe(Number(locked.headers['retry-after']));
    expect((await login('player@nascon.test', 'bad')).status).toBe(429);
    await new Promise((r) => setTimeout(r, 2300));
    expect((await login('player@nascon.test', PASSWORD)).status).toBe(200);
    // counter reset: a single failure no longer locks
    expect((await login('player@nascon.test', 'bad')).status).toBe(401);
    expect((await login('player@nascon.test', PASSWORD)).status).toBe(200);
  });

  test('success resets the failure counter', async () => {
    for (let i = 0; i < 4; i += 1) await login('viewer@nascon.test', 'bad');
    expect((await login('viewer@nascon.test')).status).toBe(200);
    for (let i = 0; i < 4; i += 1) expect((await login('viewer@nascon.test', 'bad')).status).toBe(401);
    expect((await login('viewer@nascon.test')).status).toBe(200);
  });

  test('reset clears lockouts', async () => {
    for (let i = 0; i < 5; i += 1) await login('manager@nascon.test', 'bad');
    expect((await login('manager@nascon.test')).status).toBe(429);
    await resetDb();
    expect((await login('manager@nascon.test')).status).toBe(200);
  });

  test('per IP+email throttle: 10 attempts -> 429', async () => {
    let last;
    const seen = [];
    for (let i = 0; i < 12; i += 1) { last = await login('ghost@nascon.test', 'bad'); seen.push(last.status); }
    expect(seen.slice(0, 10).every((s) => s === 401)).toBe(true);
    expect(seen.slice(10)).toEqual([429, 429]);
    expect(Number(last.headers['retry-after'])).toBeGreaterThanOrEqual(1);
    // another email is unaffected
    expect((await login('viewer@nascon.test')).status).toBe(200);
  });

  describe('refresh / logout', () => {
    test('rotation', async () => {
      const l = await login('viewer@nascon.test');
      const r1 = await refresh(l.body.refresh_token);
      expect(r1.status).toBe(200);
      expect(r1.headers['cache-control']).toBe('no-store');
      expect(Object.keys(r1.body).sort()).toEqual(['access_token', 'expires_in', 'refresh_token']);
      expect(r1.body.refresh_token).not.toBe(l.body.refresh_token);
      expect(jwt.decode(r1.body.access_token).role).toBe('viewer');
      const r2 = await refresh(r1.body.refresh_token);
      expect(r2.status).toBe(200);
    });

    test('only hashes are stored', async () => {
      const l = await login('viewer@nascon.test');
      const { rows } = await pool.query('SELECT token_hash FROM refresh_tokens');
      expect(rows).toHaveLength(1);
      expect(rows[0].token_hash).not.toBe(l.body.refresh_token);
      expect(rows[0].token_hash).toMatch(/^[0-9a-f]{64}$/);
      expect(Buffer.from(l.body.refresh_token, 'base64url')).toHaveLength(32);
    });

    test('reuse of an old refresh token -> 401 and the newer token is revoked too', async () => {
      const l = await login('viewer@nascon.test');
      const r1 = await refresh(l.body.refresh_token);
      const reuse = await refresh(l.body.refresh_token);
      expect(reuse.status).toBe(401);
      expect(reuse.body.error.code).toBe('UNAUTHENTICATED');
      expect((await refresh(r1.body.refresh_token)).status).toBe(401);
    });

    test('concurrent refresh with the same token: exactly one wins', async () => {
      const l = await login('viewer@nascon.test');
      const rs = await Promise.all([1, 2, 3, 4].map(() => refresh(l.body.refresh_token)));
      expect(rs.filter((r) => r.status === 200)).toHaveLength(1);
    });

    test('logout revokes; idempotent; unknown token still 200', async () => {
      const l = await login('viewer@nascon.test');
      const out = await (await api()).post('/api/v1/auth/logout').send({ refresh_token: l.body.refresh_token });
      expect(out.status).toBe(200);
      expect(out.body).toEqual({});
      expect((await refresh(l.body.refresh_token)).status).toBe(401);
      expect((await (await api()).post('/api/v1/auth/logout').send({ refresh_token: l.body.refresh_token })).status).toBe(200);
      expect((await (await api()).post('/api/v1/auth/logout').send({ refresh_token: 'unknown' })).status).toBe(200);
    });

    test('unknown / expired refresh token -> 401', async () => {
      expect((await refresh('nope')).status).toBe(401);
      const l = await login('viewer@nascon.test');
      await pool.query("UPDATE refresh_tokens SET expires_at = now() - interval '1 second'");
      expect((await refresh(l.body.refresh_token)).status).toBe(401);
    });

    test('validation', async () => {
      for (const body of [{}, { refresh_token: 5 }, { refresh_token: '' }, [1]]) {
        expect((await (await api()).post('/api/v1/auth/refresh').send(body)).status).toBe(400);
        expect((await (await api()).post('/api/v1/auth/logout').send(body)).status).toBe(400);
      }
    });
  });

  describe('GET /api/v1/me', () => {
    test('without / with token', async () => {
      const no = await (await api()).get('/api/v1/me');
      expect(no.status).toBe(401);
      expect(no.body.error.code).toBe('UNAUTHENTICATED');
      const l = await login('organizer@nascon.test');
      const r = await (await api()).get('/api/v1/me').set('Authorization', `Bearer ${l.body.access_token}`);
      expect(r.status).toBe(200);
      expect(r.headers['cache-control']).toBe('no-store');
      expect(r.body.user).toEqual({ user_id: 'user-organizer', email: 'organizer@nascon.test', role: 'league_organizer', tenant_id: 'tenant-alpha', league_ids: ['league-alpha-1'], club_id: null, player_id: null });
      expect(r.body.tenant.name).toBe('Alpha Sports');
      expect(JSON.stringify(r.body)).not.toMatch(/password|hash/i);
    });
    test('super admin has null tenant', async () => {
      const l = await login('admin@nascon.test');
      const r = await (await api()).get('/api/v1/me').set('Authorization', `Bearer ${l.body.access_token}`);
      expect(r.body.tenant).toBeNull();
    });
    test('a token minted before /reset still authenticates after it', async () => {
      const l = await login('viewer@nascon.test');
      await resetDb();
      const r = await (await api()).get('/api/v1/me').set('Authorization', `Bearer ${l.body.access_token}`);
      expect(r.status).toBe(200);
    });
    test('token for a user that no longer exists -> 401', async () => {
      const t = jwt.sign({ sub: 'ghost', role: 'viewer', tenant_id: 'tenant-alpha' }, config.jwtSecret, { algorithm: 'HS256', expiresIn: 60 });
      expect((await (await api()).get('/api/v1/me').set('Authorization', `Bearer ${t}`)).status).toBe(401);
    });
  });
});
