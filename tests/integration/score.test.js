const { describeDb } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');
const { uid, post, getScore, mk, expectError } = require('../helpers/evtkit');
const { sign } = require('../../backend/src/domain/crypto');

afterAll(() => pool.end());

async function makeLeague(sport_id, fixtures, teams = ['H', 'A', 'C']) {
  const league_id = uid('lg');
  const r = await (await api()).post('/audit/v1/leagues').send({
    league_id, sport_id, teams, fixtures, qualification: { spots: 1 },
  });
  if (r.status !== 201) throw new Error(`league create failed ${r.status} ${JSON.stringify(r.body)}`);
  return league_id;
}

describeDb('score: sequences and versions', () => {
  beforeAll(() => resetDb());

  test('league fixture: no match_init needed, first event match_start gets sequence 1', async () => {
    const match_id = uid('fx');
    await makeLeague('football', [{ match_id, home: 'H', away: 'A' }]);
    const m = mk(match_id, 'football');
    const st = await m.start();
    expect(st.status).toBe(201);
    expect(st.body.sequence).toBe(1);
    expect(st.body.match_version).toBe(1);
    const g = await m.score('H', 'goal');
    expect(g.body.sequence).toBe(2);
    const s = (await m.get()).body;
    expect(s.version).toBe(2);
    expect(s.status).toBe('in_progress');
    expect(s.league_id).not.toBeNull();
    expect(s.events.map((e) => e.sequence)).toEqual([1, 2]);
  });

  test('score version equals event count incl. compensations', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    const g = await m.score('H', 'goal');
    await m.score('A', 'goal');
    await m.comp(g.body.event_id);
    await m.end();
    const s = (await m.get()).body;
    expect(s.version).toBe(6);
    expect(s.events).toHaveLength(6);
    expect(s.events.map((e) => e.sequence)).toEqual([1, 2, 3, 4, 5, 6]);
  });

  test('rejected events do not consume a sequence', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    expect((await m.score('Z', 'goal')).status).toBe(400);
    expect((await m.start()).status).toBe(409);
    const g = await m.score('H', 'goal');
    expect(g.body.sequence).toBe(3);
    expect((await m.get()).body.version).toBe(3);
  });

  test('replay does not bump version', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    const id = uid('e');
    await m.score('H', 'goal', id);
    await m.score('H', 'goal', id);
    expect((await m.get()).body.version).toBe(3);
  });
});

describeDb('score: endpoint', () => {
  beforeAll(() => resetDb());

  test('bad match id format -> 400', async () => {
    const agent = await api();
    for (const id of ['a%20b', 'x'.repeat(65), 'a%2Fb', 'caf%C3%A9']) {
      const r = await agent.get(`/audit/v1/score/${id}`);
      expectError(r, 400, 'VALIDATION_ERROR');
    }
  });

  test('64-char id is valid format (404 when unknown)', async () => {
    expectError(await getScore('x'.repeat(64)), 404, 'MATCH_NOT_FOUND');
  });

  test('unknown match -> 404 MATCH_NOT_FOUND', async () => {
    expectError(await getScore(uid('ghost')), 404, 'MATCH_NOT_FOUND');
  });

  test('fixture with no events (non-innings) -> upcoming/pending, null wickets/balls/overs', async () => {
    const match_id = uid('fx');
    const lg = await makeLeague('football', [{ match_id, home: 'H', away: 'A' }]);
    const res = await getScore(match_id);
    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      match_id, sport_id: 'football', league_id: lg, status: 'upcoming', outcome: 'pending',
      winner_team_id: null, tiebreak_winner_team_id: null,
      home: { team_id: 'H', score: 0, wickets: null, balls: null, overs: null, fair_play: 0 },
      away: { team_id: 'A', score: 0, wickets: null, balls: null, overs: null, fair_play: 0 },
      version: 0, events: [],
    });
  });

  test('innings fixture with no events -> wickets/balls ints and overs "0.0"', async () => {
    const match_id = uid('fx');
    await makeLeague('cricket', [{ match_id, home: 'H', away: 'A' }]);
    const s = (await getScore(match_id)).body;
    for (const side of [s.home, s.away]) {
      expect(side.wickets).toBe(0);
      expect(side.balls).toBe(0);
      expect(side.overs).toBe('0.0');
      expect(side.score).toBe(0);
    }
    expect(s.version).toBe(0);
    expect(s.events).toEqual([]);
  });

  test('match created via match_init without league has league_id null', async () => {
    const m = mk(uid('m'), 'football');
    await m.init();
    expect((await m.get()).body.league_id).toBeNull();
  });

  test('response key order is exact', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    await m.score('H', 'goal');
    const s = (await m.get()).body;
    expect(Object.keys(s)).toEqual([
      'match_id', 'sport_id', 'league_id', 'status', 'outcome', 'winner_team_id',
      'tiebreak_winner_team_id', 'home', 'away', 'version', 'events',
    ]);
    expect(Object.keys(s.home)).toEqual(['team_id', 'score', 'wickets', 'balls', 'overs', 'fair_play']);
    expect(Object.keys(s.events[0])).toEqual([
      'sequence', 'event_id', 'type', 'payload', 'signature', 'compensated', 'compensated_by',
    ]);
  });

  test('events listed in sequence order with payloads', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    await m.score('H', 'goal');
    const s = (await m.get()).body;
    expect(s.events.map((e) => e.type)).toEqual(['match_init', 'match_start', 'score']);
    expect(s.events[2].payload).toEqual({ team_id: 'H', event_type: 'goal' });
    expect(s.events[1].payload).toEqual({});
    expect(s.events[0].payload).toEqual({ sport_id: 'football', home_team_id: 'H', away_team_id: 'A' });
  });
});

describeDb('score: signatures', () => {
  beforeAll(() => resetDb());

  test('signature at append equals sign(event_id, payload)', async () => {
    const m = mk(uid('m'), 'football');
    await m.init();
    const payload = { team_id: 'H', event_type: 'goal' };
    const id = uid('e');
    await m.start();
    const r = await m.send('score', payload, id);
    expect(r.status).toBe(201);
    expect(r.body.signature).toBe(sign(id, payload));
    expect(r.body.signature).toMatch(/^[0-9a-f]{64}$/);
  });

  test('signatures listed in /score equal the append responses', async () => {
    const m = mk(uid('m'), 'football');
    const res = [await m.init(), await m.start(), await m.score('H', 'goal'), await m.discipline('A', -1)];
    const s = (await m.get()).body;
    expect(s.events.map((e) => e.signature)).toEqual(res.map((r) => r.body.signature));
    expect(s.events.map((e) => e.event_id)).toEqual(res.map((r) => r.body.event_id));
    for (const e of s.events) expect(e.signature).toBe(sign(e.event_id, e.payload));
  });

  test('payload with extra inner keys is signed including them', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    const payload = { team_id: 'H', event_type: 'goal', minute: 12, note: { b: 1, a: [1, 2] } };
    const id = uid('e');
    const r = await m.send('score', payload, id);
    expect(r.status).toBe(201);
    expect(r.body.signature).toBe(sign(id, payload));
    expect(r.body.signature).not.toBe(sign(id, { team_id: 'H', event_type: 'goal' }));
    const e = (await m.get()).body.events.find((x) => x.event_id === id);
    expect(e.payload).toEqual(payload);
    expect(e.signature).toBe(r.body.signature);
  });

  test('non-ASCII reason known-answer vector', () => {
    expect(sign('e-ar-1', { compensates: 'e1', reason: 'گول' }))
      .toBe('54d59df2ffe0b1e89ce312fdb44ce10dcdd16ed6fe6fa7f6af06af24a8c3009a');
  });

  test('real compensation with Arabic reason is signed per spec', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    const g = await m.score('H', 'goal');
    const id = uid('e-ar');
    const payload = { compensates: g.body.event_id, reason: 'گول' };
    const r = await m.send('compensation', payload, id);
    expect(r.status).toBe(201);
    expect(r.body.signature).toBe(sign(id, payload));
    const e = (await m.get()).body.events.find((x) => x.event_id === id);
    expect(e.payload.reason).toBe('گول');
    expect(e.signature).toBe(sign(id, payload));
  });

  test('signature independent of payload key order', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    const id = uid('e');
    const r = await post({ event_id: id, match_id: m.match_id, type: 'score', payload: { event_type: 'goal', team_id: 'H' } });
    expect(r.body.signature).toBe(sign(id, { team_id: 'H', event_type: 'goal' }));
  });
});
