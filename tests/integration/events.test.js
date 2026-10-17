const { describeDb } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');
const { uid, post, createSport, mk, expectError } = require('../helpers/evtkit');
afterAll(() => pool.end());

describeDb('events: state machine', () => {
  beforeAll(() => resetDb());

  test('score before match_start -> 409 MATCH_NOT_IN_PROGRESS', async () => {
    const m = mk(uid('m'), 'football');
    expect((await m.init()).status).toBe(201);
    expectError(await m.score('H', 'goal'), 409, 'MATCH_NOT_IN_PROGRESS');
  });

  test('second match_start -> 409 ILLEGAL_TRANSITION', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    expectError(await m.start(), 409, 'ILLEGAL_TRANSITION');
  });

  test('match_end while upcoming -> 409 ILLEGAL_TRANSITION', async () => {
    const m = mk(uid('m'), 'football');
    await m.init();
    expectError(await m.end(), 409, 'ILLEGAL_TRANSITION');
  });

  test('score after match_end -> 409 MATCH_NOT_IN_PROGRESS', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    expect((await m.end()).status).toBe(201);
    expectError(await m.score('H', 'goal'), 409, 'MATCH_NOT_IN_PROGRESS');
  });

  test('match_end twice -> 409 ILLEGAL_TRANSITION', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    await m.end();
    expectError(await m.end(), 409, 'ILLEGAL_TRANSITION');
  });
});

describeDb('events: draws and tiebreaks', () => {
  beforeAll(() => resetDb());

  test('basketball level score -> match_end 409 TIE_NOT_ALLOWED', async () => {
    const m = mk(uid('m'), 'basketball');
    await m.begin();
    await m.score('H', 'two_pointer');
    await m.score('A', 'two_pointer');
    expectError(await m.end(), 409, 'TIE_NOT_ALLOWED');
    expect((await m.get()).body.status).toBe('in_progress');
  });

  test('basketball 0-0 also TIE_NOT_ALLOWED', async () => {
    const m = mk(uid('m'), 'basketball');
    await m.begin();
    expectError(await m.end(), 409, 'TIE_NOT_ALLOWED');
  });

  test.each([['H', 'home_win'], ['A', 'away_win']])('tiebreak winner %s -> %s', async (team, outcome) => {
    const m = mk(uid('m'), 'basketball');
    await m.begin();
    await m.score('H', 'free_throw');
    await m.score('A', 'free_throw');
    expect((await m.tiebreak(team)).status).toBe(201);
    expect((await m.end()).status).toBe(201);
    const s = (await m.get()).body;
    expect(s.status).toBe('completed');
    expect(s.outcome).toBe(outcome);
    expect(s.winner_team_id).toBe(team);
    expect(s.tiebreak_winner_team_id).toBe(team);
  });

  test('compensating the tiebreak after match_end -> undecided, winner null, still completed', async () => {
    const m = mk(uid('m'), 'basketball');
    await m.begin();
    await m.score('H', 'free_throw');
    await m.score('A', 'free_throw');
    const tb = await m.tiebreak('H');
    await m.end();
    const c = await m.comp(tb.body.event_id);
    expect(c.status).toBe(201);
    const s = (await m.get()).body;
    expect(s.status).toBe('completed');
    expect(s.outcome).toBe('undecided');
    expect(s.winner_team_id).toBeNull();
    expect(s.tiebreak_winner_team_id).toBeNull();
  });

  test('football level score -> draw', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    await m.score('H', 'goal');
    await m.score('A', 'goal');
    await m.end();
    const s = (await m.get()).body;
    expect(s.outcome).toBe('draw');
    expect(s.winner_team_id).toBeNull();
  });

  test('football decisive -> away_win', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    await m.score('A', 'goal');
    await m.end();
    const s = (await m.get()).body;
    expect(s.outcome).toBe('away_win');
    expect(s.winner_team_id).toBe('A');
  });
});

describeDb('events: compensation', () => {
  beforeAll(() => resetDb());

  test('compensating a score drops the score and links events', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    const g = await m.score('H', 'goal');
    await m.score('H', 'goal');
    expect((await m.get()).body.home.score).toBe(2);
    const c = await m.comp(g.body.event_id, 'var');
    expect(c.status).toBe(201);
    const s = (await m.get()).body;
    expect(s.home.score).toBe(1);
    const orig = s.events.find((e) => e.event_id === g.body.event_id);
    expect(orig.compensated).toBe(true);
    expect(orig.compensated_by).toBe(c.body.event_id);
    const ce = s.events.find((e) => e.event_id === c.body.event_id);
    expect(ce).toBeDefined();
    expect(ce.type).toBe('compensation');
    expect(ce.compensated).toBe(false);
    expect(ce.compensated_by).toBeNull();
  });

  test('compensating discipline restores fair_play', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    const d = await m.discipline('H', -2);
    expect((await m.get()).body.home.fair_play).toBe(-2);
    expect((await m.comp(d.body.event_id)).status).toBe(201);
    expect((await m.get()).body.home.fair_play).toBe(0);
  });

  test('compensating twice -> 409 INVALID_COMPENSATION', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    const g = await m.score('H', 'goal');
    expect((await m.comp(g.body.event_id)).status).toBe(201);
    expectError(await m.comp(g.body.event_id), 409, 'INVALID_COMPENSATION');
  });

  test('compensating a match_start -> 409 INVALID_COMPENSATION', async () => {
    const m = mk(uid('m'), 'football');
    await m.init();
    const st = await m.start();
    expectError(await m.comp(st.body.event_id), 409, 'INVALID_COMPENSATION');
  });

  test('compensating an event of another match -> 409', async () => {
    const a = mk(uid('m'), 'football');
    const b = mk(uid('m'), 'football');
    await a.begin();
    await b.begin();
    const g = await a.score('H', 'goal');
    expectError(await b.comp(g.body.event_id), 409, 'INVALID_COMPENSATION');
    expect((await a.get()).body.home.score).toBe(1);
  });

  test('unknown target -> 404 EVENT_NOT_FOUND', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    expectError(await m.comp('nope-nope'), 404, 'EVENT_NOT_FOUND');
  });

  test('compensation on completed match -> 201 and status stays completed', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    const g = await m.score('H', 'goal');
    await m.end();
    const c = await m.comp(g.body.event_id);
    expect(c.status).toBe(201);
    const s = (await m.get()).body;
    expect(s.status).toBe('completed');
    expect(s.home.score).toBe(0);
    expect(s.outcome).toBe('draw');
  });
});

describeDb('events: innings model', () => {
  beforeAll(() => resetDb());

  test('wide adds 1 run and 0 balls', async () => {
    const m = mk(uid('m'), 'cricket');
    await m.begin();
    expect((await m.score('H', 'wide')).status).toBe(201);
    const s = (await m.get()).body;
    expect(s.home.score).toBe(1);
    expect(s.home.balls).toBe(0);
    expect(s.home.overs).toBe('0.0');
  });

  test('7 legal balls -> overs "1.1"', async () => {
    const m = mk(uid('m'), 'cricket');
    await m.begin();
    for (let i = 0; i < 7; i += 1) expect((await m.score('H', 'dot_ball')).status).toBe(201);
    const s = (await m.get()).body;
    expect(s.home.balls).toBe(7);
    expect(s.home.overs).toBe('1.1');
    expect(s.away.overs).toBe('0.0');
  });

  test('10 wickets then score -> INNINGS_COMPLETE; compensating a wicket re-allows scoring', async () => {
    const m = mk(uid('m'), 'cricket');
    await m.begin();
    const ids = [];
    for (let i = 0; i < 10; i += 1) {
      const r = await m.score('H', 'wicket');
      expect(r.status).toBe(201);
      ids.push(r.body.event_id);
    }
    expect((await m.get()).body.home.wickets).toBe(10);
    expectError(await m.score('H', 'single'), 409, 'INNINGS_COMPLETE');
    expect((await m.score('A', 'single')).status).toBe(201);
    expect((await m.comp(ids[3])).status).toBe(201);
    expect((await m.get()).body.home.wickets).toBe(9);
    expect((await m.score('H', 'single')).status).toBe(201);
  });

  test('mystery innings sport: 13th legal ball in 2x6x1 -> 409', async () => {
    const sid = `inn-${Date.now().toString(36)}`;
    await createSport({
      sport_id: sid, scoring_model: 'innings',
      periods: { innings_per_team: 1, overs_per_innings: 2, balls_per_over: 6, wickets_per_innings: 10 },
      scoring_events: {
        hit: { runs: 1, legal_ball: true, wicket: false },
        wd: { runs: 1, legal_ball: false, wicket: false },
      },
    });
    const m = mk(uid('m'), sid);
    await m.begin();
    for (let i = 0; i < 12; i += 1) expect((await m.score('H', 'hit')).status).toBe(201);
    expect((await m.get()).body.home.overs).toBe('2.0');
    expectError(await m.score('H', 'hit'), 409, 'INNINGS_COMPLETE');
  });
});

describeDb('events: round model', () => {
  beforeAll(() => resetDb());

  test('esports best_of 3: 2 round_win by one team then any score -> SERIES_ALREADY_DECIDED', async () => {
    const m = mk(uid('m'), 'esports');
    await m.begin();
    await m.score('H', 'round_win');
    await m.score('H', 'round_win');
    expectError(await m.score('A', 'round_win'), 409, 'SERIES_ALREADY_DECIDED');
    expectError(await m.score('H', 'round_win'), 409, 'SERIES_ALREADY_DECIDED');
  });

  test('esports best_of 3: 2-1 series also decided', async () => {
    const m = mk(uid('m'), 'esports');
    await m.begin();
    expect((await m.score('H', 'round_win')).status).toBe(201);
    expect((await m.score('A', 'round_win')).status).toBe(201);
    expect((await m.score('H', 'round_win')).status).toBe(201);
    expectError(await m.score('A', 'round_win'), 409, 'SERIES_ALREADY_DECIDED');
  });

  test('best_of 5 mystery sport needs 3', async () => {
    const sid = `bo5-${Date.now().toString(36)}`;
    await createSport({
      sport_id: sid, scoring_model: 'round', periods: { best_of: 5 },
      scoring_events: { win: { rounds: 1 } }, draw_allowed: false,
    });
    const m = mk(uid('m'), sid);
    await m.begin();
    await m.score('H', 'win');
    await m.score('H', 'win');
    expect((await m.score('A', 'win')).status).toBe(201);
    expect((await m.score('H', 'win')).status).toBe(201);
    expectError(await m.score('A', 'win'), 409, 'SERIES_ALREADY_DECIDED');
  });

  test('compensating a round win re-opens the series', async () => {
    const m = mk(uid('m'), 'esports');
    await m.begin();
    await m.score('H', 'round_win');
    const w = await m.score('H', 'round_win');
    await m.comp(w.body.event_id);
    expect((await m.score('A', 'round_win')).status).toBe(201);
  });
});

describeDb('events: mystery sport end to end', () => {
  beforeAll(() => resetDb());

  test('kabaddi', async () => {
    const r = await createSport({
      sport_id: 'kabaddi', scoring_model: 'event', periods: { count: 2, duration_minutes: 20 },
      scoring_events: { raid_point: { points: 1 }, all_out: { points: 2 } }, draw_allowed: true,
    });
    expect(r.body.sport_id).toBe('kabaddi');
    const m = mk(uid('m'), 'kabaddi');
    await m.begin();
    expect((await m.score('H', 'raid_point')).status).toBe(201);
    expect((await m.score('H', 'all_out')).status).toBe(201);
    const s = (await m.get()).body;
    expect(s.sport_id).toBe('kabaddi');
    expect(s.home.score).toBe(3);
    expect(s.away.score).toBe(0);
    expectError(await m.score('H', 'dunk'), 400, 'UNKNOWN_EVENT_TYPE');
    expect((await m.get()).body.version).toBe(4);
  });

  test('duplicate sport -> 409 SPORT_EXISTS', async () => {
    const res = await (await api()).post('/audit/v1/sports').send({
      sport_id: 'kabaddi', name: 'k', scoring_model: 'event', periods: { count: 1, duration_minutes: 1 },
      scoring_events: { a: { points: 1 } }, draw_allowed: true, overtime: { policy: 'none' },
      roster: { min_squad_size: 1, max_squad_size: 2, required_positions: {} },
    });
    expectError(res, 409, 'SPORT_EXISTS');
  });
});

describeDb('events: idempotency', () => {
  beforeAll(() => resetDb());

  test('identical replay -> 200 with byte-identical body', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    const body = { event_id: uid('e'), match_id: m.match_id, type: 'score', payload: { team_id: 'H', event_type: 'goal' } };
    const a = await post(body);
    const b = await post(body);
    expect(a.status).toBe(201);
    expect(b.status).toBe(200);
    expect(b.text).toBe(a.text);
    expect((await m.get()).body.home.score).toBe(1);
  });

  test('replay with reordered keys is still identical', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    const id = uid('e');
    const a = await post({ event_id: id, match_id: m.match_id, type: 'score', payload: { team_id: 'H', event_type: 'goal' } });
    const b = await post({ payload: { event_type: 'goal', team_id: 'H' }, type: 'score', match_id: m.match_id, event_id: id });
    expect(b.status).toBe(200);
    expect(b.text).toBe(a.text);
  });

  test('replay still returns 200 after the state would make it illegal', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    const body = { event_id: uid('e'), match_id: m.match_id, type: 'score', payload: { team_id: 'H', event_type: 'goal' } };
    const a = await post(body);
    await m.end();
    const b = await post(body);
    expect(b.status).toBe(200);
    expect(b.text).toBe(a.text);
  });

  test('same event_id with different payload -> 409 DUPLICATE_EVENT_ID', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    const id = uid('e');
    expect((await m.score('H', 'goal', id)).status).toBe(201);
    expectError(await m.score('A', 'goal', id), 409, 'DUPLICATE_EVENT_ID');
    const s = (await m.get()).body;
    expect(s.home.score).toBe(1);
    expect(s.away.score).toBe(0);
  });

  test('same event_id sent to another match -> 409', async () => {
    const a = mk(uid('m'), 'football');
    const b = mk(uid('m'), 'football');
    await a.begin();
    await b.begin();
    const id = uid('e');
    await a.score('H', 'goal', id);
    expectError(await b.score('H', 'goal', id), 409, 'DUPLICATE_EVENT_ID');
  });

  test('replay of match_init -> 200 (not MATCH_EXISTS)', async () => {
    const m = mk(uid('m'), 'football');
    const id = uid('init');
    const a = await m.init(id);
    const b = await m.init(id);
    expect(a.status).toBe(201);
    expect(b.status).toBe(200);
    expect(b.text).toBe(a.text);
  });

  test('second match_init with new event_id -> 409 MATCH_EXISTS', async () => {
    const m = mk(uid('m'), 'football');
    await m.init();
    expectError(await m.init(), 409, 'MATCH_EXISTS');
  });

  test('create response shape and key order', async () => {
    const m = mk(uid('m'), 'football');
    const r = await m.init();
    expect(r.status).toBe(201);
    expect(Object.keys(r.body)).toEqual(['event_id', 'match_id', 'type', 'sequence', 'match_version', 'signature']);
    expect(r.body.sequence).toBe(1);
    expect(r.body.match_version).toBe(1);
    expect(r.body.type).toBe('match_init');
  });
});

describeDb('events: validation and not-found', () => {
  beforeAll(() => resetDb());

  test('unknown match -> 404 MATCH_NOT_FOUND', async () => {
    const m = mk(uid('ghost'), 'football');
    expectError(await m.start(), 404, 'MATCH_NOT_FOUND');
  });

  test('match_init with unknown sport -> 404 SPORT_NOT_FOUND', async () => {
    expectError(await mk(uid('m'), 'no-such-sport').init(), 404, 'SPORT_NOT_FOUND');
  });

  test('match_init with unknown league -> 404 LEAGUE_NOT_FOUND', async () => {
    expectError(await mk(uid('m'), 'football').init(undefined, { league_id: 'no-league' }), 404, 'LEAGUE_NOT_FOUND');
  });

  test('match_init home == away -> 400', async () => {
    const m = mk(uid('m'), 'football', 'T', 'T');
    const r = await m.init();
    expectError(r, 400, 'VALIDATION_ERROR');
    expect(r.body.error.details.fields).toContain('payload.away_team_id');
  });

  test('team_id not in match -> 400 payload.team_id', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    for (const r of [await m.score('Z', 'goal'), await m.discipline('Z', -1), await m.tiebreak('Z')]) {
      expectError(r, 400, 'VALIDATION_ERROR');
      expect(r.body.error.details.fields).toEqual(['payload.team_id']);
    }
  });

  test('validation (400) takes precedence over state (409)', async () => {
    const m = mk(uid('m'), 'football');
    await m.init();
    expectError(await m.score('Z', 'goal'), 400, 'VALIDATION_ERROR');
  });

  test('discipline points as string -> 400', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    const r = await m.send('discipline', { team_id: 'H', points: '-2' });
    expectError(r, 400, 'VALIDATION_ERROR');
    expect(r.body.error.details.fields).toEqual(['payload.points']);
  });

  test('bad type -> 400', async () => {
    const r = await post({ event_id: uid('e'), match_id: 'whatever', type: 'bogus', payload: {} });
    expectError(r, 400, 'VALIDATION_ERROR');
    expect(r.body.error.details.fields).toContain('type');
  });

  test('missing event_id -> 400', async () => {
    const r = await post({ match_id: 'whatever', type: 'match_start', payload: {} });
    expectError(r, 400, 'VALIDATION_ERROR');
    expect(r.body.error.details.fields).toContain('event_id');
  });

  test('400 beats 404 for unknown match with invalid body', async () => {
    const r = await post({ event_id: uid('e'), match_id: uid('ghost'), type: 'discipline', payload: { team_id: 'H' } });
    expectError(r, 400, 'VALIDATION_ERROR');
  });

  test('non-object body (array / string) -> 400 fields [body]', async () => {
    const agent = await api();
    const arr = await agent.post('/audit/v1/events').send([1, 2]);
    expectError(arr, 400, 'VALIDATION_ERROR');
    expect(arr.body.error.details.fields).toEqual(['body']);
    const str = await agent.post('/audit/v1/events').set('Content-Type', 'application/json').send('"hello"');
    expectError(str, 400, 'VALIDATION_ERROR');
    expect(str.body.error.details.fields).toEqual(['body']);
  });

  test('payload not an object -> 400', async () => {
    const r = await post({ event_id: uid('e'), match_id: 'm', type: 'match_start', payload: 'x' });
    expectError(r, 400, 'VALIDATION_ERROR');
    expect(r.body.error.details.fields).toContain('payload');
  });

  test('empty compensation reason -> 400', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    const g = await m.score('H', 'goal');
    const r = await m.comp(g.body.event_id, '');
    expectError(r, 400, 'VALIDATION_ERROR');
    expect(r.body.error.details.fields).toEqual(['payload.reason']);
  });
});
