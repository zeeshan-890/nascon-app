const { describeDb } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');
const { uid, mk, createSport, expectError } = require('../helpers/evtkit');
const { lotsKey } = require('../../backend/src/domain/crypto');

afterAll(() => pool.end());

const standings = async (id) => (await api()).get(`/audit/v1/standings/${id}`);

async function makeLeague(league_id, sport_id, teams, fixtures, extra = {}) {
  const r = await (await api()).post('/audit/v1/leagues').send({
    league_id, sport_id, teams, fixtures, qualification: { spots: 1 }, lots_seed: 42, ...extra,
  });
  if (r.status !== 201) throw new Error(`league create failed ${r.status} ${JSON.stringify(r.body)}`);
  return league_id;
}

// Plays a fixture to completion: `plays` is a list of [team, event_type] events.
async function play(match_id, sport, home, away, plays, { end = true } = {}) {
  const m = mk(match_id, sport, home, away);
  expect((await m.start()).status).toBe(201);
  for (const [team, key] of plays) expect((await m.score(team, key)).status).toBe(201);
  if (end) expect((await m.end()).status).toBe(201);
  return m;
}

const row = (body, id) => body.table.find((t) => t.team_id === id);

describeDb('standings: errors', () => {
  beforeAll(() => resetDb());

  test('bad id is 400 and unknown league is 404', async () => {
    expectError(await standings('bad id!'), 400, 'VALIDATION_ERROR');
    expectError(await standings('no-such-league'), 404, 'LEAGUE_NOT_FOUND');
  });

  test('a league with no fixtures: zeros, ordered by lots', async () => {
    const lg = uid('lg');
    await makeLeague(lg, 'football', ['A', 'B', 'C'], []);
    const r = await standings(lg);
    expect(r.status).toBe(200);
    expect(r.body.table.map((t) => t.team_id)).toEqual(['A', 'B', 'C'].sort((a, b) => (lotsKey(42, lg, a) < lotsKey(42, lg, b) ? -1 : 1)));
    expect(r.body.table.every((t) => t.points === 0 && t.diff === 0)).toBe(true);
    expect(r.body.h2h.A.B).toEqual({ played: 0, points: 0, scored: 0, conceded: 0 });
  });
});

describeDb('standings: full flow with a seeded sport', () => {
  beforeAll(() => resetDb());

  test('3-way tie ends in drawing of lots; response shape', async () => {
    const lg = uid('lg');
    const [m1, m2, m3] = [uid('m'), uid('m'), uid('m')];
    await makeLeague(lg, 'football', ['A', 'B', 'C'], [
      { match_id: m1, home: 'A', away: 'B' }, { match_id: m2, home: 'B', away: 'C' }, { match_id: m3, home: 'C', away: 'A' },
    ], { qualification: { spots: 2 } });
    await play(m1, 'football', 'A', 'B', [['A', 'goal']]);
    await play(m2, 'football', 'B', 'C', [['B', 'goal']]);
    await play(m3, 'football', 'C', 'A', [['C', 'goal']]);
    const r = await standings(lg);
    expect(r.status).toBe(200);
    const expected = ['A', 'B', 'C'].sort((a, b) => (lotsKey(42, lg, a) < lotsKey(42, lg, b) ? -1 : 1));
    expect(r.body.table.map((t) => t.team_id)).toEqual(expected);
    expect(r.body.table.map((t) => t.resolved_by)).toEqual(['draw_lots', 'draw_lots', null]);
    expect(r.body).toMatchObject({ league_id: lg, sport_id: 'football', qualification_spots: 2 });
    expect(r.body.table[0]).toEqual({
      rank: 1, team_id: expected[0], played: 2, won: 1, drawn: 0, lost: 1, scored: 1, conceded: 1, diff: 0, points: 3,
      fair_play: 0, status: expect.any(String), resolved_by: 'draw_lots',
    });
    expect(r.body.h2h.A.B).toEqual({ played: 1, points: 3, scored: 1, conceded: 0 });
    expect(r.body.h2h.B.A).toEqual({ played: 1, points: 0, scored: 0, conceded: 1 });
  });

  test('edge case 5: compensating a goal after the match ended recomputes everything; /score keeps the log', async () => {
    const lg = uid('lg');
    const id = uid('m');
    await makeLeague(lg, 'football', ['A', 'B'], [{ match_id: id, home: 'A', away: 'B' }]);
    const m = mk(id, 'football', 'A', 'B');
    await m.start();
    const goal = await m.score('A', 'goal');
    await m.end();
    const before = (await standings(lg)).body;
    expect(before.table.map((t) => [t.team_id, t.points, t.status])).toEqual([['A', 3, 'clinched'], ['B', 0, 'eliminated']]);
    expect(before.h2h.A.B).toEqual({ played: 1, points: 3, scored: 1, conceded: 0 });

    expect((await m.comp(goal.body.event_id)).status).toBe(201);
    const after = (await standings(lg)).body;
    expect(after.table.map((t) => t.points)).toEqual([1, 1]);
    expect(after.table.every((t) => t.scored === 0 && t.drawn === 1)).toBe(true);
    expect(after.table[0].resolved_by).toBe('draw_lots');
    expect(after.h2h.A.B).toEqual({ played: 1, points: 1, scored: 0, conceded: 0 });
    expect(after.table.map((t) => t.status)).toEqual(['still_possible', 'still_possible']);

    const score = (await m.get()).body;
    expect(score.events.map((e) => e.type)).toEqual(['match_start', 'score', 'match_end', 'compensation']);
    expect(score.events[1]).toMatchObject({ compensated: true, compensated_by: expect.any(String) });
  });

  test('a compensated tiebreak winner makes the match undecided and remaining again', async () => {
    const lg = uid('lg');
    const id = uid('m');
    await makeLeague(lg, 'basketball', ['A', 'B'], [{ match_id: id, home: 'A', away: 'B' }]);
    const m = mk(id, 'basketball', 'A', 'B');
    await m.start();
    await m.score('A', 'free_throw');
    await m.score('B', 'free_throw');
    const tb = await m.tiebreak('B');
    await m.end();
    const decided = (await standings(lg)).body;
    expect(row(decided, 'B')).toMatchObject({ won: 1, points: 3, rank: 1 });
    await m.comp(tb.body.event_id);
    const undecided = (await standings(lg)).body;
    expect(undecided.table.every((t) => t.played === 0 && t.points === 0 && t.scored === 0)).toBe(true);
    expect(undecided.h2h.A.B.played).toBe(0);
    expect(undecided.table.every((t) => t.status === 'still_possible')).toBe(true);
  });

  test('fair play counts discipline points of played matches', async () => {
    const lg = uid('lg');
    const id = uid('m');
    await makeLeague(lg, 'football', ['A', 'B'], [{ match_id: id, home: 'A', away: 'B' }]);
    const m = mk(id, 'football', 'A', 'B');
    await m.start();
    await m.score('A', 'goal');
    await m.discipline('A', -2);
    await m.discipline('B', -1);
    await m.end();
    const r = (await standings(lg)).body;
    expect(row(r, 'A').fair_play).toBe(-2);
    expect(row(r, 'B').fair_play).toBe(-1);
  });

  test('custom points and an in-progress match staying remaining', async () => {
    const lg = uid('lg');
    const [m1, m2] = [uid('m'), uid('m')];
    await makeLeague(lg, 'football', ['A', 'B'], [{ match_id: m1, home: 'A', away: 'B' }, { match_id: m2, home: 'B', away: 'A' }], {
      points: { win: 2, draw: 1, loss: 0 },
    });
    await play(m1, 'football', 'A', 'B', [['A', 'goal']]);
    await play(m2, 'football', 'B', 'A', [['B', 'goal']], { end: false });
    const r = (await standings(lg)).body;
    expect(row(r, 'A')).toMatchObject({ played: 1, points: 2 });
    expect(row(r, 'B')).toMatchObject({ played: 1, points: 0 });
  });
});

describeDb('standings: mystery sports', () => {
  beforeAll(() => resetDb());

  test('event-model mystery sport end to end', async () => {
    const sport_id = uid('zap').toLowerCase();
    await createSport({
      sport_id, scoring_model: 'event', periods: { count: 2, duration_minutes: 10 }, scoring_events: { zap: { points: 5 } },
      draw_allowed: false,
    });
    const lg = uid('lg');
    const id = uid('m');
    await makeLeague(lg, sport_id, ['A', 'B'], [{ match_id: id, home: 'A', away: 'B' }]);
    await play(id, sport_id, 'A', 'B', [['A', 'zap'], ['A', 'zap'], ['B', 'zap']]);
    const r = (await standings(lg)).body;
    expect(r.sport_id).toBe(sport_id);
    expect(row(r, 'A')).toMatchObject({ rank: 1, won: 1, scored: 10, conceded: 5, diff: 5, points: 3 });
  });

  test('innings-model mystery sport: net run rate with an all-out innings', async () => {
    const sport_id = uid('inn').toLowerCase();
    await createSport({
      sport_id, scoring_model: 'innings',
      periods: { innings_per_team: 1, overs_per_innings: 2, balls_per_over: 3, wickets_per_innings: 2 },
      scoring_events: {
        hit: { runs: 1, legal_ball: true, wicket: false },
        out: { runs: 0, legal_ball: true, wicket: true },
      },
      draw_allowed: true,
    });
    const lg = uid('lg');
    const id = uid('m');
    await makeLeague(lg, sport_id, ['A', 'B'], [{ match_id: id, home: 'A', away: 'B' }]);
    const plays = [...Array(6).fill(['A', 'hit']), ...Array(3).fill(['B', 'hit']), ['B', 'out'], ['B', 'out']];
    await play(id, sport_id, 'A', 'B', plays);
    const r = (await standings(lg)).body;
    // A: 6 runs over 2 overs vs 3 runs over a full (all-out) 2 overs -> 3 - 1.5
    expect(row(r, 'A')).toMatchObject({ scored: 6, conceded: 3, diff: 1.5, won: 1 });
    expect(row(r, 'B').diff).toBe(-1.5);
  });

  test('round-model sport (seeded) uses the map differential', async () => {
    const lg = uid('lg');
    const id = uid('m');
    await makeLeague(lg, 'esports', ['A', 'B'], [{ match_id: id, home: 'A', away: 'B' }]);
    await play(id, 'esports', 'A', 'B', [['A', 'round_win'], ['B', 'round_win'], ['A', 'round_win']]);
    const r = (await standings(lg)).body;
    expect(row(r, 'A')).toMatchObject({ scored: 2, conceded: 1, diff: 1, points: 3 });
  });
});

describeDb('standings: scale', () => {
  beforeAll(() => resetDb());

  test('40-team double round robin of results answers in under a second', async () => {
    const lg = uid('lg');
    const teams = Array.from({ length: 40 }, (_, i) => `T${String(i).padStart(2, '0')}`);
    const fixtures = [];
    for (const h of teams) for (const a of teams) if (h !== a) fixtures.push({ match_id: `${lg}-${h}-${a}`, home: h, away: a });
    await makeLeague(lg, 'football', teams, fixtures, { qualification: { spots: 8 } });

    const rows = [];
    fixtures.forEach((f, i) => {
      const goals = (i * 7) % 3; // 0..2 goals for the home side, away side scores nothing
      const events = [['match_start', {}], ...Array.from({ length: goals }, () => ['score', { team_id: f.home, event_type: 'goal' }]), ['match_end', {}]];
      events.forEach(([type, payload], j) => rows.push([`${f.match_id}-e${j}`, f.match_id, j + 1, type, JSON.stringify(payload)]));
    });
    await pool.query(
      `INSERT INTO match_events (event_id, match_id, sequence, type, payload, payload_canonical, signature, request_canonical)
       SELECT e, m, s, t, p::jsonb, p, 'sig', 'req' FROM unnest($1::text[], $2::text[], $3::int[], $4::text[], $5::text[]) AS x(e, m, s, t, p)`,
      [rows.map((r) => r[0]), rows.map((r) => r[1]), rows.map((r) => r[2]), rows.map((r) => r[3]), rows.map((r) => r[4])]
    );

    const t0 = Date.now();
    const r = await standings(lg);
    const ms = Date.now() - t0;
    expect(r.status).toBe(200);
    expect(ms).toBeLessThan(1000);
    expect(r.body.table).toHaveLength(40);
    expect(r.body.table.every((t) => t.played === 78)).toBe(true);
    expect(Object.keys(r.body.h2h)).toHaveLength(40);
    expect(Object.keys(r.body.h2h.T00)).toHaveLength(39);
    expect(r.body.table.map((t) => t.rank)).toEqual(Array.from({ length: 40 }, (_, i) => i + 1));
    const pts = r.body.table.map((t) => t.points);
    expect([...pts].sort((a, b) => b - a)).toEqual(pts);
  });
});
