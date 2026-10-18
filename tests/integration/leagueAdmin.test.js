const { describeDb } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');

const PASSWORD = 'Nascon@26';
const BODY = { sport_id: 'football', name: 'Lahore Super Cup', format: 'round_robin', max_squad_size: 20 };

describeDb('league approval workflow', () => {
  const tokens = {};
  const as = async (who, method, path, body) => {
    const r = (await api())[method](path).set('Authorization', `Bearer ${tokens[who]}`);
    return body === undefined ? r : r.send(body);
  };

  beforeAll(async () => {
    await resetDb();
    for (const who of ['admin', 'organizer', 'viewer']) {
      const r = await (await api()).post('/api/v1/auth/login').send({ email: `${who}@nascon.test`, password: PASSWORD });
      tokens[who] = r.body.access_token;
    }
  });
  afterAll(() => pool.end());

  test('organizer creates a pending league that viewers cannot see until an admin approves it', async () => {
    const created = await as('organizer', 'post', '/api/v1/admin/leagues', BODY);
    expect(created.status).toBe(201);
    expect(created.body.status).toBe('pending');
    const id = created.body.league_id;

    const publicIds = async () => (await as('viewer', 'get', '/api/v1/leagues')).body.map((l) => l.league_id);
    expect(await publicIds()).not.toContain(id);
    expect((await as('organizer', 'get', '/api/v1/admin/leagues')).body.map((l) => l.league_id)).toContain(id);

    expect((await as('viewer', 'post', `/api/v1/admin/leagues/${id}/approve`)).status).toBe(403);
    expect((await as('organizer', 'post', `/api/v1/admin/leagues/${id}/approve`)).status).toBe(403);

    const approved = await as('admin', 'post', `/api/v1/admin/leagues/${id}/approve`);
    expect(approved.status).toBe(200);
    expect(approved.body.status).toBe('active');
    expect(await publicIds()).toContain(id);

    const again = await as('admin', 'post', `/api/v1/admin/leagues/${id}/approve`);
    expect(again.status).toBe(409);
    expect(again.body.error.code).toBe('ILLEGAL_TRANSITION');
  });

  test('a rejected league stays hidden from viewers', async () => {
    const id = (await as('organizer', 'post', '/api/v1/admin/leagues', BODY)).body.league_id;
    const rejected = await as('admin', 'post', `/api/v1/admin/leagues/${id}/reject`);
    expect(rejected.body.status).toBe('rejected');
    expect((await as('viewer', 'get', '/api/v1/leagues')).body.map((l) => l.league_id)).not.toContain(id);
  });

  test('super admin leagues are active at once; viewers cannot create or list admin leagues', async () => {
    const r = await as('admin', 'post', '/api/v1/admin/leagues', BODY);
    expect(r.status).toBe(201);
    expect(r.body.status).toBe('active');
    expect((await as('viewer', 'post', '/api/v1/admin/leagues', BODY)).status).toBe(403);
    expect((await as('viewer', 'get', '/api/v1/admin/leagues')).status).toBe(403);
  });

  test('validation and unknown ids', async () => {
    const bad = await as('organizer', 'post', '/api/v1/admin/leagues', { ...BODY, name: '' });
    expect(bad.status).toBe(400);
    expect(bad.body.error.details.fields).toContain('name');
    const nosport = await as('organizer', 'post', '/api/v1/admin/leagues', { ...BODY, sport_id: 'nope-sport' });
    expect(nosport.status).toBe(404);
    expect((await as('admin', 'post', '/api/v1/admin/leagues/does-not-exist/approve')).status).toBe(404);
  });

  test('an organizer cannot read or edit another organizer-less league', async () => {
    const other = await as('admin', 'post', '/api/v1/admin/leagues', BODY);
    expect((await as('organizer', 'get', `/api/v1/admin/leagues/${other.body.league_id}`)).status).toBe(403);
    expect((await as('organizer', 'patch', `/api/v1/admin/leagues/${other.body.league_id}`, BODY)).status).toBe(403);
  });
  describe('teams and generated fixtures', () => {
    let id;
    beforeAll(async () => {
      id = (await as('organizer', 'post', '/api/v1/admin/leagues', BODY)).body.league_id;
    });

    test('a pending league cannot take teams until it is approved', async () => {
      const r = await as('organizer', 'post', `/api/v1/leagues/${id}/teams`, { name: 'Lahore Lions' });
      expect(r.status).toBe(409);
      expect(r.body.error.code).toBe('LEAGUE_NOT_ACTIVE');
      await as('admin', 'post', `/api/v1/admin/leagues/${id}/approve`);
    });

    test('organizer adds teams; duplicates and viewers are refused; the table lists the teams', async () => {
      const names = ['Lahore Lions', 'Karachi Kings', 'Peshawar Hawks', 'Islamabad Falcons'];
      const teams = [];
      for (const name of names) {
        const r = await as('organizer', 'post', `/api/v1/leagues/${id}/teams`, { name });
        expect(r.status).toBe(201);
        teams.push(r.body.team_id);
      }
      expect((await as('organizer', 'post', `/api/v1/leagues/${id}/teams`, { name: 'lahore lions' })).body.error.code).toBe('DUPLICATE_TEAM');
      expect((await as('viewer', 'post', `/api/v1/leagues/${id}/teams`, { name: 'X' })).status).toBe(403);
      expect((await as('organizer', 'get', `/api/v1/leagues/${id}/teams`)).body.map((t) => t.name)).toEqual(names);
      const table = (await as('viewer', 'get', `/api/v1/leagues/${id}/standings`)).body.table;
      expect(table.map((r) => r.team_id).sort()).toEqual([...teams].sort());

      const gen = await as('organizer', 'post', `/api/v1/leagues/${id}/fixtures/generate`, {
        format: 'round_robin', teams, params: { legs: 1 }, save: true, start_date: '2026-10-24', match_days: [6, 7],
      });
      expect(gen.status).toBe(201);
      expect(gen.body.created).toBe(6);

      const fixtures = (await as('viewer', 'get', `/api/v1/leagues/${id}/fixtures`)).body;
      expect(fixtures).toHaveLength(6);
      expect(new Set(fixtures.map((f) => f.scheduled_at.slice(0, 10)))).toEqual(new Set(['2026-10-24', '2026-10-25', '2026-10-31']));

      const removal = await as('organizer', 'delete', `/api/v1/leagues/${id}/teams/${teams[0]}`);
      expect(removal.status).toBe(409);
      expect(removal.body.error.code).toBe('TEAM_HAS_FIXTURES');
    });

    test('generate rejects teams outside the league and viewers', async () => {
      const bad = await as('organizer', 'post', `/api/v1/leagues/${id}/fixtures/generate`, { format: 'round_robin', teams: ['nope', 'nada'] });
      expect(bad.status).toBe(400);
      expect(bad.body.error.details.fields).toContain('teams');
      expect((await as('viewer', 'post', `/api/v1/leagues/${id}/fixtures/generate`, { format: 'round_robin', teams: [] })).status).toBe(403);
    });
  });
});
