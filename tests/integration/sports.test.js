const { describeDb } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');

const eventSport = (o = {}) => ({
  sport_id: 'hockey', name: 'Hockey', scoring_model: 'event',
  periods: { count: 4, duration_minutes: 15 },
  scoring_events: { goal: { points: 1 } },
  draw_allowed: true, overtime: { policy: 'shootout', duration_minutes: 5 },
  tie_break_order: ['h2h_points', 'overall_diff', 'draw_lots'],
  roster: { min_squad_size: 11, max_squad_size: 18, required_positions: { gk: 1 } },
  ...o,
});
const inningsSport = (o = {}) => ({
  sport_id: 'cricket10', name: 'Cricket 10', scoring_model: 'innings',
  periods: { innings_per_team: 1, overs_per_innings: 10, balls_per_over: 6, wickets_per_innings: 10 },
  scoring_events: { dot: { runs: 0, legal_ball: true, wicket: false }, wide: { runs: 1, legal_ball: false, wicket: false } },
  draw_allowed: false, overtime: { policy: 'super_over' },
  roster: { min_squad_size: 11, max_squad_size: 15, required_positions: {} },
  ...o,
});
const roundSport = (o = {}) => ({
  sport_id: 'valorant', name: 'Valorant', scoring_model: 'round',
  periods: { best_of: 3 },
  scoring_events: { round_win: { rounds: 1 } },
  draw_allowed: false, overtime: { policy: 'golden_score' },
  roster: { min_squad_size: 5, max_squad_size: 7, required_positions: {} },
  ...o,
});

const post = async (b) => (await api()).post('/audit/v1/sports').send(b);
const stored = async (id) => {
  const { rows } = await pool.query('SELECT config FROM sports WHERE sport_id=$1', [id]);
  return rows[0] ? rows[0].config : undefined;
};

describeDb('POST /audit/v1/sports', () => {
  beforeEach(() => resetDb());
  afterAll(() => pool.end());

  describe('valid', () => {
    test.each([['event', eventSport], ['innings', inningsSport], ['round', roundSport]])('%s model -> 201', async (_n, make) => {
      const body = make();
      const r = await post(body);
      expect(r.status).toBe(201);
      expect(r.body.sport_id).toBe(body.sport_id);
      expect(r.body.status).toBe('created');
      expect(r.body.config.scoring_model).toBe(body.scoring_model);
      expect(r.body.config.scoring_events).toEqual(body.scoring_events);
      expect(await stored(body.sport_id)).toEqual(r.body.config);
    });
    test('duplicate -> 409 SPORT_EXISTS', async () => {
      expect((await post(eventSport())).status).toBe(201);
      const r = await post(eventSport());
      expect(r.status).toBe(409);
      expect(r.body.error.code).toBe('SPORT_EXISTS');
    });
    test('seeded sport id is a duplicate', async () => {
      const r = await post(eventSport({ sport_id: 'football' }));
      expect(r.status).toBe(409);
      expect(r.body.error.code).toBe('SPORT_EXISTS');
    });
    test('draw_lots omitted from tie_break_order is appended', async () => {
      const r = await post(eventSport({ tie_break_order: ['h2h_points', 'scored'] }));
      expect(r.status).toBe(201);
      expect(r.body.config.tie_break_order).toEqual(['h2h_points', 'scored', 'draw_lots']);
      expect((await stored('hockey')).tie_break_order).toEqual(['h2h_points', 'scored', 'draw_lots']);
    });
    test('draw_lots listed earlier is moved to the end (always applied last)', async () => {
      const r = await post(eventSport({ tie_break_order: ['draw_lots', 'scored'] }));
      expect(r.body.config.tie_break_order).toEqual(['scored', 'draw_lots']);
    });
    test('missing tie_break_order defaults to a list containing draw_lots', async () => {
      const b = eventSport(); delete b.tie_break_order;
      const r = await post(b);
      expect(r.status).toBe(201);
      expect(r.body.config.tie_break_order).toContain('draw_lots');
    });
    test('unknown keys are stripped from stored config', async () => {
      const r = await post(eventSport({
        extra: 'x', periods: { count: 2, duration_minutes: 10, bogus: 1 },
        scoring_events: { goal: { points: 1, junk: true } },
        roster: { min_squad_size: 1, max_squad_size: 2, required_positions: {}, nope: 1 },
      }));
      expect(r.status).toBe(201);
      const c = await stored('hockey');
      expect(c.extra).toBeUndefined();
      expect(c.periods).toEqual({ count: 2, duration_minutes: 10 });
      expect(c.scoring_events.goal).toEqual({ points: 1 });
      expect(c.roster.nope).toBeUndefined();
      expect(r.body.config.extra).toBeUndefined();
    });
  });

  describe('invalid', () => {
    const noName = eventSport(); delete noName.name;
    const cases = [
      ['sport_id uppercase', eventSport({ sport_id: 'Football' }), 'sport_id'],
      ['sport_id too short', eventSport({ sport_id: 'x' }), 'sport_id'],
      ['sport_id 41 chars', eventSport({ sport_id: 'a'.repeat(41) }), 'sport_id'],
      ['sport_id injection', eventSport({ sport_id: "x'; DROP TABLE sports;--" }), 'sport_id'],
      ['sport_id number', eventSport({ sport_id: 5 }), 'sport_id'],
      ['name missing', noName, 'name'],
      ['scoring_model unknown', eventSport({ scoring_model: 'weird' }), 'scoring_model'],
      ['best_of 2', roundSport({ periods: { best_of: 2 } }), 'periods.best_of'],
      ['best_of 0', roundSport({ periods: { best_of: 0 } }), 'periods.best_of'],
      ['scoring_events empty', eventSport({ scoring_events: {} }), 'scoring_events'],
      ['scoring_events bad key', eventSport({ scoring_events: { 'Raid Point': { points: 1 } } }), 'scoring_events'],
      ['event points 0', eventSport({ scoring_events: { goal: { points: 0 } } }), 'scoring_events.goal.points'],
      ['innings missing legal_ball', inningsSport({ scoring_events: { dot: { runs: 0, wicket: false } } }), 'scoring_events.dot.legal_ball'],
      ['overtime policy penalties', eventSport({ overtime: { policy: 'penalties' } }), 'overtime.policy'],
      ['tie_break duplicate', eventSport({ tie_break_order: ['scored', 'scored'] }), 'tie_break_order'],
      ['tie_break unknown', eventSport({ tie_break_order: ['scored', 'coin_flip'] }), 'tie_break_order'],
      ['roster min > max', eventSport({ roster: { min_squad_size: 10, max_squad_size: 5, required_positions: {} } }), 'roster.min_squad_size'],
      ['draw_allowed string', eventSport({ draw_allowed: 'yes' }), 'draw_allowed'],
    ];
    test.each(cases)('%s -> 400', async (_n, body, field) => {
      const r = await post(body);
      expect(r.status).toBe(400);
      expect(r.body.error.code).toBe('VALIDATION_ERROR');
      expect(r.body.error.details.fields).toContain(field);
    });

    test('several bad fields all listed', async () => {
      const r = await post(eventSport({
        sport_id: 'BAD', name: '', draw_allowed: 1, overtime: { policy: 'x' },
        scoring_events: { goal: { points: 0 } }, tie_break_order: ['zzz'],
        roster: { min_squad_size: 10, max_squad_size: 5, required_positions: {} },
      }));
      expect(r.status).toBe(400);
      expect(r.body.error.details.fields).toEqual(expect.arrayContaining([
        'sport_id', 'name', 'draw_allowed', 'overtime.policy', 'scoring_events.goal.points', 'tie_break_order', 'roster.min_squad_size',
      ]));
    });

    test('injection attempt leaves sports table intact', async () => {
      await post(eventSport({ sport_id: "x'; DROP TABLE sports;--" }));
      const { rows } = await pool.query('SELECT count(*)::int AS c FROM sports');
      expect(rows[0].c).toBe(4);
    });

    test('invalid sport is not stored', async () => {
      await post(eventSport({ overtime: { policy: 'penalties' } }));
      expect(await stored('hockey')).toBeUndefined();
    });

    test('array body -> 400 [body]', async () => {
      const r = await post([eventSport()]);
      expect(r.status).toBe(400);
      expect(r.body.error.details.fields).toEqual(['body']);
    });

    test('400 takes precedence over 409', async () => {
      expect((await post(eventSport())).status).toBe(201);
      const r = await post(eventSport({ overtime: { policy: 'penalties' } }));
      expect(r.status).toBe(400);
      expect(r.body.error.details.fields).toContain('overtime.policy');
    });
  });
});
