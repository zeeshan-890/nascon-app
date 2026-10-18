const { describeDb } = require('../helpers/db');
const { resetAll, as, token, pool } = require('../helpers/mobilekit');
const { uid, freshToken, asToken, foreign, mkLeague, playMatch } = require('../helpers/adminkit');

afterAll(() => pool.end());

const fieldsOf = (r) => r.body.error.details.fields;
const expectErr = (r, status, code) => {
  expect(r.status).toBe(status);
  expect(r.body.error.code).toBe(code);
  expect(typeof r.body.error.message).toBe('string');
  expect(r.body.error.details).toEqual(expect.any(Object));
};

const gen = (tok, id, body) => asToken(tok).post(`/api/v1/leagues/${id}/fixtures/build`, body);
const fixturesOf = async (tok, id) => (await asToken(tok).get(`/api/v1/leagues/${id}/fixtures`)).body;
const standingsOf = async (tok, id) => (await asToken(tok).get(`/api/v1/leagues/${id}/standings`)).body;
const FIXTURE_KEYS = [
  'away_name', 'away_score', 'away_team_id', 'home_name', 'home_score', 'home_team_id',
  'match_id', 'scheduled_at', 'status', 'venue_name', 'version',
];

// Plays every unfinished fixture; the home side wins 1-0 unless `result(match, i)` says otherwise.
async function playAll(tok, leagueId, result = () => [1, 0]) {
  const list = (await fixturesOf(tok, leagueId)).filter((m) => m.status !== 'completed');
  for (const [i, m] of list.entries()) {
    const [h, a] = result(m, i);
    await playMatch(tok, m, h, a);
  }
  return list.length;
}

describeDb('POST /leagues/:id/fixtures/build', () => {
  let admin;
  beforeAll(async () => {
    await resetAll();
    admin = await token('admin');
  });

  test('round robin: persisted, shown in the fixture list with the mobile DTO, replay is 409', async () => {
    const lg = (await mkLeague()).league_id;
    const r = await gen(admin, lg, { format: 'round_robin' });
    expect(r.status).toBe(201);
    expect(r.body).toMatchObject({ league_id: lg, format: 'round_robin', replaced: false, match_count: 6, skipped: { byes: 0, voided: 0, pending: 0 } });
    expect(r.body.matches).toHaveLength(6);
    expect(r.body.matches[0]).toMatchObject({
      match_id: `${lg}:RR-L1-R1-M1`, round: 1, bracket_slot: { stage: 'round_robin', leg: 1 },
    });

    const list = await fixturesOf(admin, lg);
    expect(list).toHaveLength(6);
    expect(Object.keys(list[0]).sort()).toEqual(FIXTURE_KEYS);
    expect(list[0]).toMatchObject({ status: 'upcoming', home_score: 0, away_score: 0, version: 0, scheduled_at: null, venue_name: null });
    expect(list.map((m) => m.match_id)).toEqual(r.body.matches.map((m) => m.match_id)); // ordered by round
    expect(list[0].home_name).toBe(list[0].home_team_id);
    const { rows } = await pool.query(`SELECT origin, round, version FROM matches WHERE league_id = $1`, [lg]);
    expect(rows).toHaveLength(6);
    expect(rows.every((x) => x.origin === 'fixture' && x.version === 0)).toBe(true);
    expect(rows.map((x) => x.round).sort()).toEqual([1, 1, 2, 2, 3, 3]);

    const again = await gen(admin, lg, { format: 'round_robin' });
    expectErr(again, 409, 'FIXTURES_EXIST');
    expect(again.body.error.details.match_count).toBe(6);
    expect(await fixturesOf(admin, lg)).toHaveLength(6);
  });

  test('round robin end to end: events change the fixtures and the standings', async () => {
    const lg = (await mkLeague()).league_id;
    await gen(admin, lg, { format: 'round_robin' });
    expect(await playAll(admin, lg)).toBe(6);
    const fixtures = await fixturesOf(admin, lg);
    expect(fixtures.every((m) => m.status === 'completed' && m.home_score === 1 && m.away_score === 0 && m.version === 3)).toBe(true);
    const st = await standingsOf(admin, lg);
    expect(st.table).toHaveLength(4);
    expect(st.table.every((t) => t.played === 3)).toBe(true);
    expect(st.table.reduce((n, t) => n + t.points, 0)).toBe(18);
    expect(st.table.reduce((n, t) => n + t.won, 0)).toBe(6);
    expect(st.table.reduce((n, t) => n + t.scored, 0)).toBe(6);
    expect(st.table[0].rank).toBe(1);
    // replace is refused once events exist
    const locked = await gen(admin, lg, { format: 'round_robin', replace: true });
    expectErr(locked, 409, 'FIXTURES_EXIST');
    expect(locked.body.error.details.has_events).toBe(true);
    expect(await fixturesOf(admin, lg)).toHaveLength(6);
  });

  test('an organizer generates, scores and reads standings for a league it created', async () => {
    const body = { league_id: uid('lg'), sport_id: 'football', name: 'Org League', kind: 'league', teams: ['a', 'b', 'c'] };
    const created = await asToken(await freshToken('organizer')).post('/api/v1/leagues', body);
    expect(created.status).toBe(201);
    const org = await freshToken('organizer');
    const r = await gen(org, body.league_id, { format: 'round_robin' });
    expect(r.status).toBe(201);
    expect(r.body.match_count).toBe(3);
    expect(r.body.skipped.byes).toBe(3);
    await playAll(org, body.league_id, (m, i) => (i === 0 ? [2, 2] : [0, 1]));
    const st = await standingsOf(org, body.league_id);
    expect(st.table.map((t) => t.played)).toEqual([2, 2, 2]);
    expect(st.table.reduce((n, t) => n + t.drawn, 0)).toBe(2);
  });

  test('two legs, forfeits and voided matches follow the generator', async () => {
    const lg = (await mkLeague()).league_id;
    const two = await gen(admin, lg, { format: 'round_robin', params: { legs: 2 } });
    expect(two.status).toBe(201);
    expect(two.body.match_count).toBe(12);
    expect(two.body.matches.filter((m) => m.bracket_slot.leg === 2)).toHaveLength(6);
    const voided = (await mkLeague()).league_id;
    const wd = await gen(admin, voided, { format: 'round_robin', params: { withdrawals: [{ team_id: 't4', after_round: 0 }], withdrawal_policy: 'void_all' } });
    expect(wd.status).toBe(201);
    expect(wd.body.skipped.voided).toBe(3); // t4's matches are voided and not stored
    expect(wd.body.match_count).toBe(3);
    const forfeit = (await mkLeague()).league_id;
    const ff = await gen(admin, forfeit, { format: 'round_robin', params: { withdrawals: [{ team_id: 't4', after_round: 1 }] } });
    expect(ff.status).toBe(201);
    expect(ff.body.matches.filter((m) => m.bracket_slot.status === 'forfeit').length).toBeGreaterThan(0);
    expect(ff.body.matches.find((m) => m.bracket_slot.status === 'forfeit').bracket_slot.awarded_to).toEqual(expect.any(String));
  });

  test('swiss: round one pairings are persisted, the bye is skipped, and it flows into standings', async () => {
    const lg = (await mkLeague({ teams: ['a', 'b', 'c', 'd', 'e'] })).league_id;
    const r = await gen(admin, lg, { format: 'swiss' });
    expect(r.status).toBe(201);
    expect(r.body.match_count).toBe(2);
    expect(r.body.skipped.byes).toBe(1);
    expect(r.body.matches.every((m) => m.round === 1 && m.bracket_slot.stage === 'swiss')).toBe(true);
    expect(r.body.matches[0].match_id).toBe(`${lg}:SW-R1-M1`);
    await playAll(admin, lg);
    const st = await standingsOf(admin, lg);
    expect(st.table.filter((t) => t.played === 1)).toHaveLength(4);
    expect(st.table.filter((t) => t.won === 1)).toHaveLength(2);
  });

  test('single elimination: only playable matches are stored, with bracket slots; results reach standings', async () => {
    const lg = (await mkLeague({ teams: ['a', 'b', 'c', 'd', 'e', 'f'] })).league_id;
    const r = await gen(admin, lg, { format: 'single_elim', params: { third_place_match: true } });
    expect(r.status).toBe(201);
    expect(r.body.match_count).toBe(2); // 4v5 and 3v6; seeds 1 and 2 have byes
    expect(r.body.skipped.byes).toBe(2);
    expect(r.body.skipped.pending).toBeGreaterThan(0);
    const first = r.body.matches[0];
    expect(first).toMatchObject({ round: 1, home_team_id: 'd', away_team_id: 'e' });
    expect(r.body.matches[1]).toMatchObject({ round: 1, home_team_id: 'c', away_team_id: 'f' });
    expect(first.bracket_slot).toMatchObject({
      stage: 'single_elim', bracket: 'main', index: 2, home_seed: 4, away_seed: 5,
      winner_to: { match_id: `${lg}:R2M1`, slot: 'away' }, loser_to: null,
    });
    await playAll(admin, lg);
    const st = await standingsOf(admin, lg);
    expect(st.table.filter((t) => t.played === 1)).toHaveLength(4);
  });

  test('double elimination: round one of the winners bracket is stored and playable', async () => {
    const lg = (await mkLeague()).league_id;
    const r = await gen(admin, lg, { format: 'double_elim' });
    expect(r.status).toBe(201);
    expect(r.body.match_count).toBe(2);
    expect(r.body.matches[0].bracket_slot).toMatchObject({ stage: 'double_elim', bracket: 'winners', index: 1 });
    expect(r.body.matches[0].bracket_slot.loser_to).toEqual({ match_id: `${lg}:LR1M1`, slot: 'home' });
    expect(await playAll(admin, lg)).toBe(2);
    const st = await standingsOf(admin, lg);
    expect(st.table.filter((t) => t.played === 1)).toHaveLength(4);
  });

  test('replace: true regenerates while no match has events', async () => {
    const lg = (await mkLeague()).league_id;
    await gen(admin, lg, { format: 'swiss' });
    expect(await fixturesOf(admin, lg)).toHaveLength(2);
    const r = await gen(admin, lg, { format: 'round_robin', replace: true });
    expect(r.status).toBe(201);
    expect(r.body.replaced).toBe(true);
    const list = await fixturesOf(admin, lg);
    expect(list).toHaveLength(6);
    expect(list.every((m) => m.match_id.includes(':RR-'))).toBe(true);
    // replace on an empty league is a plain generate
    const empty = (await mkLeague()).league_id;
    expect((await gen(admin, empty, { format: 'round_robin', replace: true })).body.replaced).toBe(false);
  });

  test('validation (400) comes before 404 and 409', async () => {
    const lg = (await mkLeague({ teams: ['a', 'b'] })).league_id;
    expect(fieldsOf(await gen(admin, lg, { format: 'nope' }))).toEqual(['format']);
    expect(fieldsOf(await gen(admin, lg, {}))).toEqual(['format']);
    expect(fieldsOf(await gen(admin, lg, 'x'))).toEqual(['body']);
    expect(fieldsOf(await gen(admin, lg, { format: 'swiss', params: [1], replace: 'yes' }))).toEqual(['params', 'replace']);
    expect(fieldsOf(await gen(admin, lg, { format: 'round_robin', params: { legs: 3 } }))).toEqual(['params.legs']);
    expect(fieldsOf(await gen(admin, lg, { format: 'double_elim' }))).toEqual(['teams']); // double elimination needs 3 teams
    expect((await fixturesOf(admin, lg))).toHaveLength(0);
    const hy = (await mkLeague({ kind: 'hybrid', teams: ['a', 'b', 'c', 'd'], hybrid: { groups: 2, advance_per_group: 1 } })).league_id;
    expect(fieldsOf(await gen(admin, hy, { format: 'swiss' }))).toEqual(['format']);
  });

  test('404, bad id, roles, scope, tenant and authentication', async () => {
    expectErr(await gen(admin, 'nope', { format: 'round_robin' }), 404, 'LEAGUE_NOT_FOUND');
    expect(fieldsOf(await gen(admin, 'bad%20id', { format: 'round_robin' }))).toEqual(['league_id']);
    const lg = (await mkLeague()).league_id;
    for (const role of ['manager', 'player', 'viewer']) {
      expectErr(await as(role).post(`/api/v1/leagues/${lg}/fixtures/build`, { format: 'round_robin' }), 403, 'FORBIDDEN');
    }
    // an organizer who does not own the league
    expectErr(await as('organizer').post(`/api/v1/leagues/${lg}/fixtures/build`, { format: 'round_robin' }), 403, 'FORBIDDEN');
    expectErr(await as('organizer').post('/api/v1/leagues/league-alpha-2/fixtures/build', { format: 'round_robin' }), 403, 'FORBIDDEN');
    // the organizer of league-alpha-1 is allowed, and that league already has fixtures
    expectErr(await as('organizer').post('/api/v1/leagues/league-alpha-1/fixtures/build', { format: 'round_robin' }), 409, 'FIXTURES_EXIST');
    expectErr(await asToken(foreign()).post('/api/v1/leagues/league-alpha-1/fixtures/build', { format: 'round_robin' }), 403, 'FORBIDDEN');
    expectErr(await asToken(null).post(`/api/v1/leagues/${lg}/fixtures/build`, { format: 'round_robin' }), 401, 'UNAUTHENTICATED');
    expect(await fixturesOf(admin, lg)).toHaveLength(0);
    // the seeded league's fixtures are untouched
    expect(await fixturesOf(admin, 'league-alpha-1')).toHaveLength(6);
  });

  test('a stored id longer than 64 characters is a validation error on league_id', async () => {
    const long = `L${'x'.repeat(58)}`;
    const lg = (await mkLeague({ league_id: long })).league_id;
    expect(fieldsOf(await gen(admin, lg, { format: 'round_robin' }))).toEqual(['league_id']);
  });
});

describeDb('hybrid leagues: group stage, knockout seeding, promotion and relegation', () => {
  let admin;
  const hybrid = (extra = {}) => mkLeague({
    kind: 'hybrid', name: 'Hybrid', teams: ['h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'h7', 'h8'], hybrid: { groups: 2, advance_per_group: 2 }, ...extra,
  });
  beforeAll(async () => {
    await resetAll();
    admin = await token('admin');
  });

  test('group stage is one round robin per group, the knockout needs finished groups', async () => {
    const lg = (await hybrid()).league_id;
    const g = await gen(admin, lg, { format: 'round_robin' });
    expect(g.status).toBe(201);
    expect(g.body.match_count).toBe(12);
    const groups = g.body.matches.map((m) => m.bracket_slot.group);
    expect(groups.filter((x) => x === 1)).toHaveLength(6);
    expect(groups.filter((x) => x === 2)).toHaveLength(6);
    const g1 = new Set(['h1', 'h3', 'h5', 'h7']);
    for (const m of g.body.matches) {
      expect(g1.has(m.home_team_id)).toBe(m.bracket_slot.group === 1);
      expect(g1.has(m.away_team_id)).toBe(m.bracket_slot.group === 1);
    }
    const early = await asToken(admin).post(`/api/v1/leagues/${lg}/knockout`, {});
    expectErr(early, 409, 'STANDINGS_UNRESOLVED');
    expect(early.body.error.details).toMatchObject({ reason: 'matches_pending', pending: 12 });
    expect(await fixturesOf(admin, lg)).toHaveLength(12);
  });

  test('knockout is seeded from the top of each group, persisted, and not replayable', async () => {
    const lg = (await hybrid()).league_id;
    await gen(admin, lg, { format: 'round_robin' });
    await playAll(admin, lg, (m, i) => [1 + (i % 3), 0]);
    const r = await asToken(admin).post(`/api/v1/leagues/${lg}/knockout`);
    expect(r.status).toBe(201);
    expect(r.body).toMatchObject({ league_id: lg, bracket_size: 4 });
    expect(r.body.seeds).toHaveLength(4);
    expect(r.body.seeds.map((s) => s.rank)).toEqual([1, 1, 2, 2]);
    expect(r.body.seeds.map((s) => s.seed)).toEqual([1, 2, 3, 4]);
    for (const tier of [r.body.seeds.slice(0, 2), r.body.seeds.slice(2)]) expect(tier.map((s) => s.group).sort()).toEqual([1, 2]);
    const g1 = new Set(['h1', 'h3', 'h5', 'h7']);
    for (const s of r.body.seeds) expect(g1.has(s.team_id)).toBe(s.group === 1);
    expect(new Set(r.body.seeds.map((s) => s.team_id)).size).toBe(4);

    expect(r.body.rounds).toHaveLength(2);
    expect(r.body.rounds[0].matches.map((m) => m.match_id)).toEqual([`${lg}:KO-R1M1`, `${lg}:KO-R1M2`]);
    expect(r.body.rounds[0].matches[0]).toMatchObject({
      home: r.body.seeds[0].team_id, away: r.body.seeds[3].team_id, winner_to: { match_id: `${lg}:KO-R2M1`, slot: 'home' },
    });
    expect(r.body.matches).toHaveLength(2);
    expect(r.body.matches[0].bracket_slot).toMatchObject({ stage: 'knockout', bracket: 'main' });

    const list = await fixturesOf(admin, lg);
    expect(list).toHaveLength(14);
    expect(list.filter((m) => m.match_id.includes(':KO-'))).toHaveLength(2);
    expect(Object.keys(list[0]).sort()).toEqual(FIXTURE_KEYS);

    expectErr(await asToken(admin).post(`/api/v1/leagues/${lg}/knockout`, {}), 409, 'KNOCKOUT_EXISTS');
    // knockout matches are not part of the group table, even after they are played
    await playAll(admin, lg);
    const st = await standingsOf(admin, lg);
    expect(st.table.every((t) => t.played === 3)).toBe(true);
    expect((await fixturesOf(admin, lg)).filter((m) => m.status === 'completed')).toHaveLength(14);
    // the group layout is locked
    expectErr(await asToken(admin).patch(`/api/v1/leagues/${lg}`, { hybrid: { groups: 1, advance_per_group: 4 } }), 409, 'FIXTURES_EXIST');
  });

  test('a plain league seeds a bracket from its table (qualification spots); third place option', async () => {
    const lg = (await mkLeague({ qualification_spots: 4 })).league_id;
    await gen(admin, lg, { format: 'round_robin' });
    await playAll(admin, lg, (m, i) => [i % 2 === 0 ? 2 : 1, i % 3 === 0 ? 1 : 0]);
    const st = await standingsOf(admin, lg);
    const r = await asToken(admin).post(`/api/v1/leagues/${lg}/knockout`, { third_place_match: true });
    expect(r.status).toBe(201);
    expect(r.body.seeds.map((s) => s.team_id)).toEqual(st.table.map((t) => t.team_id));
    expect(r.body.seeds.every((s) => s.group === 1)).toBe(true);
    expect(r.body.rounds.flatMap((x) => x.matches).some((m) => m.match_id === `${lg}:KO-TP`)).toBe(true);
    expect(r.body.matches).toHaveLength(2);
  });

  test('knockout errors: body, kind, nothing played, groups too small', async () => {
    const lg = (await mkLeague()).league_id;
    expect(fieldsOf(await asToken(admin).post(`/api/v1/leagues/${lg}/knockout`, { third_place_match: 'x' }))).toEqual(['third_place_match']);
    expect(fieldsOf(await asToken(admin).post(`/api/v1/leagues/${lg}/knockout`, [1]))).toEqual(['body']);
    const none = await asToken(admin).post(`/api/v1/leagues/${lg}/knockout`, {});
    expectErr(none, 409, 'STANDINGS_UNRESOLVED');
    expect(none.body.error.details.reason).toBe('no_group_matches');
    const division = (await mkLeague({ kind: 'division' })).league_id;
    expectErr(await asToken(admin).post(`/api/v1/leagues/${division}/knockout`, {}), 409, 'KNOCKOUT_UNSUPPORTED');
    // two qualifiers make a final only; one qualifier is no bracket
    const small = (await mkLeague({ teams: ['a', 'b'], qualification_spots: 2 })).league_id;
    await gen(admin, small, { format: 'round_robin' });
    await playAll(admin, small);
    expect((await asToken(admin).post(`/api/v1/leagues/${small}/knockout`, {})).status).toBe(201);
    const solo = (await mkLeague({ teams: ['a', 'b', 'c'], qualification_spots: 1 })).league_id;
    await gen(admin, solo, { format: 'round_robin' });
    await playAll(admin, solo);
    expectErr(await asToken(admin).post(`/api/v1/leagues/${solo}/knockout`, {}), 409, 'STANDINGS_UNRESOLVED'); // one qualifier is no bracket
  });

  test('knockout access: roles, scope, tenant, 404, auth', async () => {
    const lg = (await hybrid()).league_id;
    const url = `/api/v1/leagues/${lg}/knockout`;
    for (const role of ['manager', 'player', 'viewer']) expectErr(await as(role).post(url, {}), 403, 'FORBIDDEN');
    expectErr(await as('organizer').post(url, {}), 403, 'FORBIDDEN');
    expectErr(await asToken(foreign()).post('/api/v1/leagues/league-alpha-1/knockout', {}), 403, 'FORBIDDEN');
    expectErr(await asToken(null).post(url, {}), 401, 'UNAUTHENTICATED');
    expectErr(await asToken(admin).post('/api/v1/leagues/nope/knockout', {}), 404, 'LEAGUE_NOT_FOUND');
    // the organizer of the league may seed (here: nothing is played yet)
    expectErr(await as('organizer').post('/api/v1/leagues/league-alpha-1/knockout', {}), 409, 'STANDINGS_UNRESOLVED');
  });

  test('promotion and relegation are computed from the final table', async () => {
    const lg = (await mkLeague({
      tenant_id: 'tenant-alpha', teams: ['p1', 'p2', 'p3', 'p4', 'p5'], promotion: { count: 1, to: 'upper' }, relegation: { count: 2, to: 'lower' },
    })).league_id;
    const empty = await as('viewer').get(`/api/v1/leagues/${lg}/promotion-relegation`);
    expect(empty.status).toBe(200);
    expect(empty.body.final).toBe(false);
    await gen(admin, lg, { format: 'round_robin' });
    const part = await as('viewer').get(`/api/v1/leagues/${lg}/promotion-relegation`);
    expect(part.body.final).toBe(false);
    await playAll(admin, lg, (m, i) => [1 + (i % 2), i % 3 === 0 ? 1 : 0]);
    const r = await as('viewer').get(`/api/v1/leagues/${lg}/promotion-relegation`);
    expect(r.status).toBe(200);
    const st = await standingsOf(admin, lg);
    expect(r.body.final).toBe(true);
    expect(r.body.promotion).toEqual({ count: 1, to: 'upper', teams: [{ team_id: st.table[0].team_id, rank: 1 }] });
    expect(r.body.relegation).toEqual({
      count: 2, to: 'lower', teams: [{ team_id: st.table[3].team_id, rank: 4 }, { team_id: st.table[4].team_id, rank: 5 }],
    });
    // not configured: both null
    const plain = await as('viewer').get('/api/v1/leagues/league-alpha-1/promotion-relegation');
    expect(plain.body).toMatchObject({ league_id: 'league-alpha-1', final: false, promotion: null, relegation: null });
  });

  test('promotion-relegation access: every tenant role reads; foreign tenant, 404, 400, 401', async () => {
    for (const role of ['admin', 'organizer', 'manager', 'player', 'viewer']) {
      expect((await as(role).get('/api/v1/leagues/league-alpha-1/promotion-relegation')).status).toBe(200);
    }
    expectErr(await asToken(foreign()).get('/api/v1/leagues/league-alpha-1/promotion-relegation'), 403, 'FORBIDDEN');
    expectErr(await asToken(admin).get('/api/v1/leagues/nope/promotion-relegation'), 404, 'LEAGUE_NOT_FOUND');
    expect(fieldsOf(await asToken(admin).get('/api/v1/leagues/bad%20id/promotion-relegation'))).toEqual(['league_id']);
    expectErr(await asToken(null).get('/api/v1/leagues/league-alpha-1/promotion-relegation'), 401, 'UNAUTHENTICATED');
  });
});
