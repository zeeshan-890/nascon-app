const { describeDb } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');

const league = (o = {}) => ({
  league_id: 'lg-1', sport_id: 'football', teams: ['a', 'b', 'c', 'd'],
  fixtures: [{ match_id: 'fx-1', home: 'a', away: 'b' }, { match_id: 'fx-2', home: 'c', away: 'd' }],
  qualification: { spots: 2 },
  ...o,
});
const post = async (b) => (await api()).post('/audit/v1/leagues').send(b);
const q = async (sql, p) => (await pool.query(sql, p)).rows;
const cfg = async (id) => (await q('SELECT config FROM leagues WHERE league_id=$1', [id]))[0].config;
const count = async (t) => Number((await q(`SELECT count(*) AS c FROM ${t}`))[0].c);

describeDb('POST /audit/v1/leagues', () => {
  beforeEach(() => resetDb());
  afterAll(() => pool.end());

  describe('happy path', () => {
    test('201 with counts', async () => {
      const r = await post(league());
      expect(r.status).toBe(201);
      expect(r.body).toEqual({ league_id: 'lg-1', status: 'created', team_count: 4, fixture_count: 2 });
    });
    test('fixtures stored with origin fixture and version 0', async () => {
      await post(league());
      const rows = await q("SELECT * FROM matches WHERE league_id='lg-1' ORDER BY match_id");
      expect(rows).toHaveLength(2);
      rows.forEach((m) => expect(m).toMatchObject({ origin: 'fixture', version: 0, sport_id: 'football' }));
      expect(rows[0]).toMatchObject({ match_id: 'fx-1', home_team_id: 'a', away_team_id: 'b' });
    });
    test('league_teams rows in order', async () => {
      await post(league({ teams: ['z', 'm', 'a'], fixtures: [], qualification: { spots: 1 } }));
      const rows = await q("SELECT team_id FROM league_teams WHERE league_id='lg-1' ORDER BY position");
      expect(rows.map((r) => r.team_id)).toEqual(['z', 'm', 'a']);
    });
    test('empty fixtures allowed', async () => {
      const r = await post(league({ fixtures: [] }));
      expect(r.status).toBe(201);
      expect(r.body.fixture_count).toBe(0);
    });
    test('league can use a sport created moments earlier', async () => {
      const a = await api();
      const s = await a.post('/audit/v1/sports').send({
        sport_id: 'mystery', name: 'Mystery', scoring_model: 'event', periods: { count: 1, duration_minutes: 5 },
        scoring_events: { pt: { points: 1 } }, draw_allowed: false, overtime: { policy: 'none' },
        tie_break_order: ['scored'],
        roster: { min_squad_size: 1, max_squad_size: 3, required_positions: {} },
      });
      expect(s.status).toBe(201);
      const r = await post(league({ sport_id: 'mystery' }));
      expect(r.status).toBe(201);
      expect((await cfg('lg-1')).tie_break_order).toEqual(['scored', 'draw_lots']);
    });
  });

  describe('defaults', () => {
    test('points, tie-break, qualification and lots_seed defaults', async () => {
      await post(league());
      const c = await cfg('lg-1');
      expect(c.points).toEqual({ win: 3, draw: 1, loss: 0 });
      expect(c.qualification_spots).toBe(2);
      expect(c.tie_break_order).toContain('draw_lots');
      expect(c.tie_break_order[0]).toBe('h2h_points');
      expect(c.lots_seed).toBe(42);
    });
    test('lots_seed follows last reset seed', async () => {
      await resetDb({ seed: 7 });
      await post(league());
      expect((await cfg('lg-1')).lots_seed).toBe(7);
    });
    test('explicit lots_seed overrides', async () => {
      await resetDb({ seed: 7 });
      await post(league({ lots_seed: 99 }));
      expect((await cfg('lg-1')).lots_seed).toBe(99);
    });
    test('partial points fill the rest', async () => {
      await post(league({ points: { win: 5 } }));
      expect((await cfg('lg-1')).points).toEqual({ win: 5, draw: 1, loss: 0 });
    });
    test('explicit tie_break_order gets draw_lots appended', async () => {
      await post(league({ tie_break_order: ['scored', 'fair_play'] }));
      expect((await cfg('lg-1')).tie_break_order).toEqual(['scored', 'fair_play', 'draw_lots']);
    });
  });

  describe('conflicts and not found', () => {
    test('missing sport -> 404 SPORT_NOT_FOUND', async () => {
      const r = await post(league({ sport_id: 'nope' }));
      expect(r.status).toBe(404);
      expect(r.body.error.code).toBe('SPORT_NOT_FOUND');
      expect(await q("SELECT 1 FROM leagues WHERE league_id='lg-1'")).toHaveLength(0);
    });
    test('duplicate league -> 409 LEAGUE_EXISTS', async () => {
      expect((await post(league())).status).toBe(201);
      const r = await post(league({ fixtures: [] }));
      expect(r.status).toBe(409);
      expect(r.body.error.code).toBe('LEAGUE_EXISTS');
    });
    test('seeded league id -> 409 LEAGUE_EXISTS', async () => {
      const r = await post(league({ league_id: 'league-alpha-1' }));
      expect(r.status).toBe(409);
      expect(r.body.error.code).toBe('LEAGUE_EXISTS');
    });
    test('fixture colliding with existing match -> 409 MATCH_EXISTS and no partial rows', async () => {
      const a = await api();
      const ev = await a.post('/audit/v1/events').send({
        event_id: 'lg-e1', match_id: 'fx-2', type: 'match_init',
        payload: { sport_id: 'football', home_team_id: 'a', away_team_id: 'b' },
      });
      expect(ev.status).toBe(201);
      const r = await post(league());
      expect(r.status).toBe(409);
      expect(r.body.error.code).toBe('MATCH_EXISTS');
      expect(r.body.error.details.match_id).toBe('fx-2');
      expect(await q("SELECT 1 FROM leagues WHERE league_id='lg-1'")).toHaveLength(0);
      expect(await q("SELECT 1 FROM league_teams WHERE league_id='lg-1'")).toHaveLength(0);
      expect(await q("SELECT 1 FROM matches WHERE match_id='fx-1'")).toHaveLength(0);
      expect(await count('matches')).toBe(1);
    });
    test('400 takes precedence over 404 and 409', async () => {
      const r = await post(league({ sport_id: 'nope', league_id: 'league-alpha-1', teams: ['a'] }));
      expect(r.status).toBe(400);
    });
  });

  describe('validation', () => {
    const teams4 = ['a', 'b', 'c', 'd'];
    const cases = [
      ['fixture team not in teams', league({ fixtures: [{ match_id: 'f', home: 'a', away: 'zz' }] }), 'fixtures.0.away'],
      ['fixture home team not in teams', league({ fixtures: [{ match_id: 'f', home: 'zz', away: 'a' }] }), 'fixtures.0.home'],
      ['home equals away', league({ fixtures: [{ match_id: 'f', home: 'a', away: 'a' }] }), 'fixtures.0.away'],
      ['single team', league({ teams: ['a'], fixtures: [], qualification: { spots: 1 } }), 'teams'],
      ['41 teams', league({ teams: Array.from({ length: 41 }, (_, i) => `t${i}`), fixtures: [], qualification: { spots: 1 } }), 'teams'],
      ['duplicate teams', league({ teams: ['a', 'a', 'b'], fixtures: [] }), 'teams'],
      ['spots 0', league({ teams: teams4, qualification: { spots: 0 } }), 'qualification.spots'],
      ['spots 5 for 4 teams', league({ teams: teams4, qualification: { spots: 5 } }), 'qualification.spots'],
      ['points.win string', league({ points: { win: '3' } }), 'points.win'],
      ['duplicate match_ids in request', league({ fixtures: [{ match_id: 'f', home: 'a', away: 'b' }, { match_id: 'f', home: 'c', away: 'd' }] }), 'fixtures'],
      ['tie_break duplicate', league({ tie_break_order: ['scored', 'scored'] }), 'tie_break_order'],
      ['tie_break unknown token', league({ tie_break_order: ['coin'] }), 'tie_break_order.0'],
      ['missing qualification', (() => { const b = league(); delete b.qualification; return b; })(), 'qualification'],
      ['missing league_id', (() => { const b = league(); delete b.league_id; return b; })(), 'league_id'],
    ];
    test.each(cases)('%s -> 400', async (_n, body, field) => {
      const r = await post(body);
      expect(r.status).toBe(400);
      expect(r.body.error.code).toBe('VALIDATION_ERROR');
      expect(r.body.error.details.fields).toContain(field);
      expect(await q("SELECT 1 FROM leagues WHERE league_id='lg-1'")).toHaveLength(0);
    });
    test('several bad fields all listed', async () => {
      const r = await post(league({
        league_id: 'bad id!', points: { win: '3' }, qualification: { spots: 0 },
        fixtures: [{ match_id: 'f', home: 'a', away: 'a' }],
      }));
      expect(r.status).toBe(400);
      expect(r.body.error.details.fields).toEqual(expect.arrayContaining([
        'league_id', 'points.win', 'qualification.spots', 'fixtures.0.away',
      ]));
    });
    test('array body -> 400 [body]', async () => {
      const r = await post([league()]);
      expect(r.status).toBe(400);
      expect(r.body.error.details.fields).toEqual(['body']);
    });
  });
});
