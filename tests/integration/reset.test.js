const { describeDb } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');
const bcrypt = require('../../backend/node_modules/bcryptjs');

const DEFAULT = {
  status: 'reset_complete',
  seed: 42,
  sports: ['football', 'basketball', 'cricket', 'esports'],
  tenants: ['tenant-alpha', 'tenant-beta'],
  stock: { 'var-jersey-001-M': 1, 'var-jersey-001-L': 5 },
};
const q = async (sql, p) => (await pool.query(sql, p)).rows;
const post = async (body) => (await api()).post('/audit/v1/reset').send(body);
const count = async (t) => Number((await q(`SELECT count(*) AS c FROM ${t}`))[0].c);

async function snapshot() {
  const counts = {};
  for (const t of ['tenants', 'sports', 'users', 'variants', 'leagues', 'clubs', 'players', 'role_policies', 'match_events', 'matches']) {
    counts[t] = await count(t);
  }
  counts.seed = (await q("SELECT value FROM settings WHERE key='last_reset_seed'"))[0].value;
  return counts;
}

describeDb('reset', () => {
  afterAll(() => pool.end());

  describe('request handling', () => {
    test('no body gives the exact default response', async () => {
      const r = await (await api()).post('/audit/v1/reset');
      expect(r.status).toBe(200);
      expect(r.body).toEqual(DEFAULT);
    });
    test('empty object gives the default response', async () => {
      const r = await post({});
      expect(r.status).toBe(200);
      expect(r.body).toEqual(DEFAULT);
    });
    test('custom seed is echoed and stored', async () => {
      const r = await post({ seed: 7 });
      expect(r.status).toBe(200);
      expect(r.body).toEqual({ ...DEFAULT, seed: 7 });
      expect((await q("SELECT value FROM settings WHERE key='last_reset_seed'"))[0].value).toBe(7);
    });
    test('stock override merges with defaults and is persisted', async () => {
      const r = await post({ stock: { 'var-jersey-001-M': 0 } });
      expect(r.status).toBe(200);
      expect(r.body.stock).toEqual({ 'var-jersey-001-M': 0, 'var-jersey-001-L': 5 });
      const rows = await q('SELECT variant_id, stock FROM variants ORDER BY variant_id');
      expect(rows).toEqual([{ variant_id: 'var-jersey-001-L', stock: 5 }, { variant_id: 'var-jersey-001-M', stock: 0 }]);
    });
    test.each([['x'], [1.5], [null], [true]])('bad seed %p -> 400 with seed field', async (seed) => {
      const r = await post({ seed });
      expect(r.status).toBe(400);
      expect(r.body.error.code).toBe('VALIDATION_ERROR');
      expect(r.body.error.details.fields).toContain('seed');
    });
    test.each([[-1], ['2'], [1.5]])('bad stock value %p -> 400', async (v) => {
      const r = await post({ stock: { 'var-jersey-001-M': v } });
      expect(r.status).toBe(400);
      expect(r.body.error.code).toBe('VALIDATION_ERROR');
      expect(r.body.error.details.fields).toContain('stock.var-jersey-001-M');
    });
    test('stock that is not an object -> 400', async () => {
      const r = await post({ stock: [1] });
      expect(r.status).toBe(400);
      expect(r.body.error.details.fields).toContain('stock');
    });
    test('all bad fields listed together', async () => {
      const r = await post({ seed: 'x', stock: { 'var-jersey-001-M': -1, 'var-jersey-001-L': 'a' } });
      expect(r.status).toBe(400);
      expect(r.body.error.details.fields).toEqual(expect.arrayContaining(['seed', 'stock.var-jersey-001-M', 'stock.var-jersey-001-L']));
    });
    test('unknown variant -> 404 VARIANT_NOT_FOUND', async () => {
      const r = await post({ stock: { 'var-nope': 3 } });
      expect(r.status).toBe(404);
      expect(r.body.error.code).toBe('VARIANT_NOT_FOUND');
      expect(r.body.error.details.variant_id).toBe('var-nope');
    });
    test('validation 400 takes precedence over 404', async () => {
      const r = await post({ seed: 'x', stock: { 'var-nope': 3 } });
      expect(r.status).toBe(400);
    });
    test('array body -> 400 [body]', async () => {
      const r = await post([1, 2]);
      expect(r.status).toBe(400);
      expect(r.body.error.details.fields).toEqual(['body']);
    });
    test('string body -> 400 [body]', async () => {
      const r = await (await api()).post('/audit/v1/reset').set('Content-Type', 'application/json').send('"hello"');
      expect(r.status).toBe(400);
      expect(r.body.error.details.fields).toEqual(['body']);
    });
    test('failed validation does not wipe the data', async () => {
      await resetDb({ seed: 5 });
      await post({ seed: 'x' });
      await post({ stock: { 'var-nope': 1 } });
      expect((await q("SELECT value FROM settings WHERE key='last_reset_seed'"))[0].value).toBe(5);
    });
  });

  describe('state after reset', () => {
    beforeAll(async () => { await resetDb(); });

    test('reset twice is idempotent (response and DB state)', async () => {
      const a = await post({ seed: 11 });
      const s1 = await snapshot();
      const b = await post({ seed: 11 });
      const s2 = await snapshot();
      expect(b.body).toEqual(a.body);
      expect(s2).toEqual(s1);
      expect(s1).toMatchObject({ tenants: 2, sports: 4, users: 5, variants: 2, seed: 11 });
      await resetDb();
    });

    test('five demo users with correct roles and scope', async () => {
      const users = await q('SELECT * FROM users ORDER BY email');
      expect(users.map((u) => u.email)).toEqual([
        'admin@nascon.test', 'manager@nascon.test', 'organizer@nascon.test', 'player@nascon.test', 'viewer@nascon.test',
      ]);
      const by = Object.fromEntries(users.map((u) => [u.email, u]));
      expect(by['admin@nascon.test'].role).toBe('super_admin');
      expect(by['organizer@nascon.test'].role).toBe('league_organizer');
      expect(by['organizer@nascon.test'].league_ids).toEqual(['league-alpha-1']);
      expect(by['manager@nascon.test']).toMatchObject({ role: 'club_manager', club_id: 'club-alpha-1' });
      expect(by['player@nascon.test']).toMatchObject({ role: 'player', player_id: 'player-alpha-1' });
      expect(by['viewer@nascon.test'].role).toBe('viewer');
    });

    test('password hashes verify Nascon@26', async () => {
      const users = await q('SELECT password_hash FROM users');
      for (const u of users) {
        expect(await bcrypt.compare('Nascon@26', u.password_hash)).toBe(true);
        expect(await bcrypt.compare('wrong', u.password_hash)).toBe(false);
      }
    });

    test('leagues, clubs, players seeded', async () => {
      const leagues = await q('SELECT league_id, tenant_id FROM leagues ORDER BY league_id');
      expect(leagues).toEqual([
        { league_id: 'league-alpha-1', tenant_id: 'tenant-alpha' },
        { league_id: 'league-alpha-2', tenant_id: 'tenant-alpha' },
        { league_id: 'league-beta-1', tenant_id: 'tenant-beta' },
      ]);
      expect(await count('clubs')).toBe(3);
      const players = await q('SELECT player_id, club_id, tenant_id FROM players');
      expect(players).toHaveLength(3);
      players.forEach((p) => { expect(p.club_id).toBeTruthy(); expect(p.tenant_id).toBeTruthy(); });
    });

    test('discount and variant price', async () => {
      const d = await q('SELECT code, percent FROM discount_codes');
      expect(d).toEqual([{ code: 'WELCOME10', percent: 10 }]);
      const v = await q('SELECT price_minor, currency FROM variants');
      expect(v).toHaveLength(2);
      v.forEach((x) => expect({ ...x, price_minor: Number(x.price_minor) }).toEqual({ price_minor: 450000, currency: 'PKR' }));
    });

    test('sequences restart at 1', async () => {
      await resetDb();
      expect(Number((await q("SELECT nextval('audit_seq') AS n"))[0].n)).toBe(1);
      expect(Number((await q("SELECT nextval('payment_seq') AS n"))[0].n)).toBe(1);
      await q("SELECT nextval('audit_seq')");
      await resetDb();
      expect(Number((await q("SELECT nextval('audit_seq') AS n"))[0].n)).toBe(1);
      expect(Number((await q("SELECT nextval('payment_seq') AS n"))[0].n)).toBe(1);
    });

    test('appended event is removed by reset despite append-only trigger', async () => {
      const a = await api();
      const ev = await a.post('/audit/v1/events').send({
        event_id: 'rst-e1', match_id: 'rst-m1', type: 'match_init',
        payload: { sport_id: 'football', home_team_id: 'a', away_team_id: 'b' },
      });
      expect(ev.status).toBe(201);
      expect(await count('match_events')).toBe(1);
      const r = await post({});
      expect(r.status).toBe(200);
      expect(await count('match_events')).toBe(0);
      expect(await count('matches')).toBe(0);
    });

    test('custom sport is removed by reset', async () => {
      const a = await api();
      const s = await a.post('/audit/v1/sports').send({
        sport_id: 'rst-sport', name: 'R', scoring_model: 'event', periods: { count: 1, duration_minutes: 10 },
        scoring_events: { pt: { points: 1 } }, draw_allowed: true, overtime: { policy: 'none' },
        roster: { min_squad_size: 1, max_squad_size: 2, required_positions: {} },
      });
      expect(s.status).toBe(201);
      await post({});
      expect(await q("SELECT 1 FROM sports WHERE sport_id='rst-sport'")).toHaveLength(0);
      expect(await count('sports')).toBe(4);
    });

    test('reset takes under 10 s', async () => {
      const t = Date.now();
      const r = await post({});
      expect(r.status).toBe(200);
      expect(Date.now() - t).toBeLessThan(10000);
    });
  });
});
