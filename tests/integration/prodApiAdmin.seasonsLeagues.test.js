const { describeDb } = require('../helpers/db');
const { resetAll, as, pool } = require('../helpers/mobilekit');
const { uid, freshToken, asToken, foreign, mkLeague, ROLES } = require('../helpers/adminkit');

afterAll(() => pool.end());

const fieldsOf = (r) => r.body.error.details.fields;
const expectErr = (r, status, code) => {
  expect(r.status).toBe(status);
  expect(r.body.error.code).toBe(code);
  expect(typeof r.body.error.message).toBe('string');
  expect(r.body.error.details).toEqual(expect.any(Object));
};

const season = (extra = {}) => ({ name: 'Season 26', start_date: '2026-08-01', end_date: '2027-05-31', ...extra });

describeDb('seasons (FR-1.2)', () => {
  beforeAll(() => resetAll());

  test('create, read, list and patch as super admin', async () => {
    const id = uid('season');
    const created = await as('admin').post('/api/v1/seasons', season({ season_id: id }));
    expect(created.status).toBe(201);
    expect(created.body).toEqual({ season_id: id, tenant_id: null, name: 'Season 26', start_date: '2026-08-01', end_date: '2027-05-31' });
    expect((await as('viewer').get(`/api/v1/seasons/${id}`)).status).toBe(403); // global season, tenant user
    expect((await as('admin').get(`/api/v1/seasons/${id}`)).body).toEqual(created.body);

    const patched = await as('admin').patch(`/api/v1/seasons/${id}`, { name: 'Renamed', end_date: '2027-06-30' });
    expect(patched.status).toBe(200);
    expect(patched.body).toMatchObject({ season_id: id, name: 'Renamed', start_date: '2026-08-01', end_date: '2027-06-30' });
    expect((await as('admin').get(`/api/v1/seasons/${id}`)).body.name).toBe('Renamed');
  });

  test('an organizer creates seasons inside its own tenant; the id is generated when omitted', async () => {
    const r = await as('organizer').post('/api/v1/seasons', season({ name: 'Org Season' }));
    expect(r.status).toBe(201);
    expect(r.body.season_id).toMatch(/^season-/);
    expect(r.body.tenant_id).toBe('tenant-alpha');
    for (const role of ROLES) {
      const g = await as(role).get(`/api/v1/seasons/${r.body.season_id}`);
      expect(g.status).toBe(200);
    }
    const list = await as('viewer').get('/api/v1/seasons');
    expect(list.status).toBe(200);
    expect(list.body.map((s) => s.season_id)).toContain(r.body.season_id);
    expect(list.headers['x-total-count']).toBe(String(list.body.length));
  });

  test('replaying the same season_id with the same body is 200, a different body is 409', async () => {
    const id = uid('season');
    const first = await as('organizer').post('/api/v1/seasons', season({ season_id: id }));
    expect(first.status).toBe(201);
    const replay = await as('organizer').post('/api/v1/seasons', season({ season_id: id }));
    expect(replay.status).toBe(200);
    expect(replay.body).toEqual(first.body);
    expectErr(await as('organizer').post('/api/v1/seasons', season({ season_id: id, name: 'Other' })), 409, 'SEASON_EXISTS');
  });

  test('validation is 400 with details.fields', async () => {
    const bad = await as('admin').post('/api/v1/seasons', { season_id: 'bad id!', name: '', start_date: '2026-13-40', end_date: 5 });
    expectErr(bad, 400, 'VALIDATION_ERROR');
    expect(fieldsOf(bad).sort()).toEqual(['end_date', 'name', 'season_id', 'start_date']);
    const order = await as('admin').post('/api/v1/seasons', season({ start_date: '2027-01-01', end_date: '2026-01-01' }));
    expect(fieldsOf(order)).toEqual(['end_date']);
    expect(fieldsOf(await as('admin').post('/api/v1/seasons', [1]))).toEqual(['body']);
    expect(fieldsOf(await as('admin').post('/api/v1/seasons', {}))).toEqual(expect.arrayContaining(['name', 'start_date', 'end_date']));
  });

  test('patch validation: empty body, bad dates, merged date order', async () => {
    const id = uid('season');
    await as('admin').post('/api/v1/seasons', season({ season_id: id }));
    expect(fieldsOf(await as('admin').patch(`/api/v1/seasons/${id}`, {}))).toEqual(['body']);
    expect(fieldsOf(await as('admin').patch(`/api/v1/seasons/${id}`, { start_date: 'nope' }))).toEqual(['start_date']);
    expect(fieldsOf(await as('admin').patch(`/api/v1/seasons/${id}`, { end_date: '2026-01-01' }))).toEqual(['end_date']);
    expect(fieldsOf(await as('admin').patch(`/api/v1/seasons/${id}`, { start_date: '2028-01-01' }))).toEqual(['start_date']);
  });

  test('404 and malformed ids', async () => {
    expectErr(await as('admin').get('/api/v1/seasons/nope'), 404, 'SEASON_NOT_FOUND');
    expectErr(await as('admin').patch('/api/v1/seasons/nope', { name: 'x' }), 404, 'SEASON_NOT_FOUND');
    const badId = await as('admin').get('/api/v1/seasons/bad%20id');
    expectErr(badId, 400, 'VALIDATION_ERROR');
    expect(fieldsOf(badId)).toEqual(['season_id']);
  });

  test('roles: only super admin and organizer write; everybody reads', async () => {
    const id = uid('season');
    await as('organizer').post('/api/v1/seasons', season({ season_id: id }));
    for (const role of ['manager', 'player', 'viewer']) {
      expectErr(await as(role).post('/api/v1/seasons', season()), 403, 'FORBIDDEN');
      expectErr(await as(role).patch(`/api/v1/seasons/${id}`, { name: 'x' }), 403, 'FORBIDDEN');
    }
    expect((await as('organizer').patch(`/api/v1/seasons/${id}`, { name: 'by organizer' })).status).toBe(200);
    expect((await as('admin').patch(`/api/v1/seasons/${id}`, { name: 'by admin' })).status).toBe(200);
    for (const role of ROLES) expect((await as(role).get('/api/v1/seasons')).status).toBe(200);
  });

  test('unauthenticated is 401', async () => {
    const anon = asToken(null);
    expectErr(await anon.get('/api/v1/seasons'), 401, 'UNAUTHENTICATED');
    expectErr(await anon.post('/api/v1/seasons', season()), 401, 'UNAUTHENTICATED');
    expectErr(await anon.get('/api/v1/seasons/x'), 401, 'UNAUTHENTICATED');
    expectErr(await anon.patch('/api/v1/seasons/x', {}), 401, 'UNAUTHENTICATED');
  });

  test('tenant isolation', async () => {
    const id = uid('season');
    await as('organizer').post('/api/v1/seasons', season({ season_id: id }));
    const beta = asToken(foreign());
    expectErr(await beta.get(`/api/v1/seasons/${id}`), 403, 'FORBIDDEN');
    expectErr(await beta.patch(`/api/v1/seasons/${id}`, { name: 'x' }), 403, 'FORBIDDEN');
    expect((await beta.get('/api/v1/seasons')).body.map((s) => s.season_id)).not.toContain(id);
    // an organizer cannot create a season for another tenant, a super admin can name the tenant
    expectErr(await as('organizer').post('/api/v1/seasons', season({ tenant_id: 'tenant-beta' })), 403, 'FORBIDDEN');
    const forBeta = await as('admin').post('/api/v1/seasons', season({ tenant_id: 'tenant-beta' }));
    expect(forBeta.status).toBe(201);
    expect(forBeta.body.tenant_id).toBe('tenant-beta');
    expectErr(await as('viewer').get(`/api/v1/seasons/${forBeta.body.season_id}`), 403, 'FORBIDDEN');
    expectErr(await as('admin').post('/api/v1/seasons', season({ tenant_id: 'tenant-nope' })), 404, 'TENANT_NOT_FOUND');
  });

  test('list pagination is capped at 100', async () => {
    const one = await as('admin').get('/api/v1/seasons?limit=1');
    expect(one.body).toHaveLength(1);
    expect(Number(one.headers['x-total-count'])).toBeGreaterThan(1);
    const big = await as('admin').get('/api/v1/seasons?limit=100000');
    expect(big.body.length).toBeLessThanOrEqual(100);
  });
});

describeDb('leagues: create, read, patch (FR-1.2)', () => {
  beforeAll(() => resetAll());

  test('create a league with defaults and read it back', async () => {
    const lg = uid('lg');
    const r = await as('admin').post('/api/v1/leagues', {
      league_id: lg, sport_id: 'football', name: 'Premier', kind: 'league', teams: ['a', 'b', 'c', 'd'],
    });
    expect(r.status).toBe(201);
    expect(r.body).toMatchObject({
      league_id: lg, name: 'Premier', kind: 'league', sport_id: 'football', teams: ['a', 'b', 'c', 'd'],
      season_id: null, parent_league_id: null, tenant_id: null,
    });
    expect(r.body.config).toMatchObject({ points: { win: 3, draw: 1, loss: 0 }, qualification_spots: 1, lots_seed: 42 });
    expect(r.body.config.tie_break_order).toEqual(['h2h_points', 'h2h_diff', 'overall_diff', 'scored', 'fair_play', 'draw_lots']);
    const g = await as('admin').get(`/api/v1/leagues/${lg}`);
    expect(g.body).toEqual(r.body);
    // it shows up in the list and has a standings table with the four teams
    expect((await as('admin').get('/api/v1/leagues')).body.map((l) => l.league_id)).toContain(lg);
    expect((await as('admin').get(`/api/v1/leagues/${lg}/standings`)).body.table).toHaveLength(4);
  });

  test('every kind and every config key is stored in config', async () => {
    const seasonId = uid('season');
    await as('admin').post('/api/v1/seasons', season({ season_id: seasonId, tenant_id: 'tenant-alpha' }));
    const top = await mkLeague({ league_id: uid('div'), kind: 'division', name: 'Division 1', season_id: seasonId });
    expect(top.tenant_id).toBe('tenant-alpha'); // inherited from the season
    const lg = await mkLeague({
      kind: 'division',
      name: 'Division 2',
      season_id: seasonId,
      parent_league_id: top.league_id,
      teams: ['a', 'b', 'c', 'd', 'e', 'f'],
      points: { win: 2 },
      tie_break_order: ['overall_diff', 'scored'],
      qualification_spots: 3,
      lots_seed: 7,
      transfer_window: { open: '2026-09-01', close: '2026-10-01' },
      max_squad_size: 22,
      required_positions: { gk: 1, def: 3 },
      promotion: { count: 1, to: top.league_id },
      relegation: { count: 2, to: 'division-3' },
    });
    expect(lg).toMatchObject({ kind: 'division', season_id: seasonId, parent_league_id: top.league_id });
    expect(lg.config).toEqual({
      points: { win: 2, draw: 1, loss: 0 },
      tie_break_order: ['overall_diff', 'scored', 'draw_lots'],
      qualification_spots: 3,
      lots_seed: 7,
      transfer_window: { open: '2026-09-01', close: '2026-10-01' },
      max_squad_size: 22,
      required_positions: { gk: 1, def: 3 },
      promotion: { count: 1, to: top.league_id },
      relegation: { count: 2, to: 'division-3' },
    });
    expect(lg.promotion).toEqual({ count: 1, to: top.league_id });
    const listed = (await as('viewer').get('/api/v1/leagues')).body.find((l) => l.league_id === lg.league_id);
    expect(listed.season).toBe('Season 26');
    const ko = await mkLeague({ kind: 'knockout', name: 'Cup' });
    expect(ko.kind).toBe('knockout');
    const hy = await mkLeague({ kind: 'hybrid', name: 'Hybrid', teams: ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'], hybrid: { groups: 2, advance_per_group: 2 } });
    expect(hy.config.hybrid).toEqual({ groups: 2, advance_per_group: 2 });
  });

  test('replay: same body is 200 with the same league, a different body is 409', async () => {
    const body = { league_id: uid('lg'), sport_id: 'football', name: 'Replay', kind: 'league', teams: ['a', 'b', 'c'], qualification_spots: 2 };
    const first = await as('admin').post('/api/v1/leagues', body);
    expect(first.status).toBe(201);
    const again = await as('admin').post('/api/v1/leagues', body);
    expect(again.status).toBe(200);
    expect(again.body).toEqual(first.body);
    expectErr(await as('admin').post('/api/v1/leagues', { ...body, name: 'Different' }), 409, 'LEAGUE_EXISTS');
    expectErr(await as('admin').post('/api/v1/leagues', { ...body, teams: ['a', 'b'] }), 409, 'LEAGUE_EXISTS');
    expectErr(await as('admin').post('/api/v1/leagues', { ...body, league_id: 'league-alpha-1' }), 409, 'LEAGUE_EXISTS');
  });

  test('validation lists every bad field', async () => {
    const r = await as('admin').post('/api/v1/leagues', {
      league_id: 'bad id', sport_id: 5, name: '', kind: 'cup', teams: ['a', 'a'], season_id: 3,
      points: { win: 1.5 }, tie_break_order: ['nope'], qualification_spots: 0, lots_seed: 'x',
      transfer_window: { open: '2026-01-01' }, max_squad_size: 0, required_positions: { gk: -1 },
      promotion: { count: 0 }, relegation: 'x', hybrid: [],
    });
    expectErr(r, 400, 'VALIDATION_ERROR');
    expect(fieldsOf(r)).toEqual(expect.arrayContaining([
      'league_id', 'sport_id', 'name', 'kind', 'teams', 'season_id', 'points.win', 'tie_break_order.0', 'qualification_spots', 'lots_seed',
      'transfer_window.close', 'max_squad_size', 'required_positions.gk', 'promotion.count', 'promotion.to', 'relegation', 'hybrid',
    ]));
    expect(fieldsOf(await as('admin').post('/api/v1/leagues', 'x'))).toEqual(['body']);
    expect(fieldsOf(await as('admin').post('/api/v1/leagues', {}))).toEqual(expect.arrayContaining(['league_id', 'sport_id', 'name', 'kind', 'teams']));
  });

  test('cross-field rules', async () => {
    const base = { league_id: uid('lg'), sport_id: 'football', name: 'X', kind: 'league', teams: ['a', 'b', 'c', 'd'] };
    const bad = async (extra, field) => {
      const r = await as('admin').post('/api/v1/leagues', { ...base, ...extra });
      expectErr(r, 400, 'VALIDATION_ERROR');
      expect(fieldsOf(r)).toContain(field);
    };
    await bad({ qualification_spots: 5 }, 'qualification_spots');
    await bad({ tie_break_order: ['scored', 'scored'] }, 'tie_break_order');
    await bad({ transfer_window: { open: '2026-10-01', close: '2026-09-01' } }, 'transfer_window.close');
    await bad({ promotion: { count: 3, to: 'up' }, relegation: { count: 2, to: 'down' } }, 'relegation.count');
    await bad({ promotion: { count: 5, to: 'up' } }, 'promotion.count');
    await bad({ promotion: { count: 1, to: base.league_id } }, 'promotion.to');
    await bad({ relegation: { count: 1, to: base.league_id } }, 'relegation.to');
    await bad({ parent_league_id: base.league_id }, 'parent_league_id');
    await bad({ kind: 'hybrid' }, 'hybrid'); // hybrid needs its config
    await bad({ hybrid: { groups: 2, advance_per_group: 1 } }, 'hybrid'); // only for hybrid
    await bad({ kind: 'hybrid', hybrid: { groups: 3, advance_per_group: 1 } }, 'hybrid.groups'); // groups of fewer than 2
    await bad({ kind: 'hybrid', hybrid: { groups: 2, advance_per_group: 3 } }, 'hybrid.advance_per_group');
  });

  test('404s come after validation: sport, season, parent league', async () => {
    const base = { league_id: uid('lg'), sport_id: 'football', name: 'X', kind: 'league', teams: ['a', 'b'] };
    expectErr(await as('admin').post('/api/v1/leagues', { ...base, sport_id: 'no-such-sport' }), 404, 'SPORT_NOT_FOUND');
    expectErr(await as('admin').post('/api/v1/leagues', { ...base, season_id: 'no-season' }), 404, 'SEASON_NOT_FOUND');
    expectErr(await as('admin').post('/api/v1/leagues', { ...base, parent_league_id: 'no-league' }), 404, 'LEAGUE_NOT_FOUND');
    // 400 wins over 404
    expectErr(await as('admin').post('/api/v1/leagues', { ...base, sport_id: 'no-such-sport', name: '' }), 400, 'VALIDATION_ERROR');
  });

  test('roles for POST /leagues: super admin and organizer only', async () => {
    for (const role of ['manager', 'player', 'viewer']) {
      const r = await as(role).post('/api/v1/leagues', { league_id: uid('lg'), sport_id: 'football', name: 'X', kind: 'league', teams: ['a', 'b'] });
      expectErr(r, 403, 'FORBIDDEN');
    }
    expectErr(await asToken(null).post('/api/v1/leagues', {}), 401, 'UNAUTHENTICATED');
  });

  test('an organizer creates leagues in its tenant and owns them after the next login', async () => {
    const mine = uid('lg');
    const body = { league_id: mine, sport_id: 'football', name: 'Mine', kind: 'league', teams: ['a', 'b', 'c'] };
    const oldToken = await freshToken('organizer');
    const created = await asToken(oldToken).post('/api/v1/leagues', body);
    expect(created.status).toBe(201);
    expect(created.body.tenant_id).toBe('tenant-alpha');
    // the old token does not carry the new league yet
    expectErr(await asToken(oldToken).patch(`/api/v1/leagues/${mine}`, { name: 'Renamed' }), 403, 'FORBIDDEN');
    const renewed = await freshToken('organizer');
    expect((await asToken(renewed).patch(`/api/v1/leagues/${mine}`, { name: 'Renamed' })).body.name).toBe('Renamed');
    // replay by the same organizer does not duplicate the ownership
    expect((await asToken(renewed).post('/api/v1/leagues', { ...body, name: 'Renamed' })).status).toBe(200);
    const { rows } = await pool.query(`SELECT league_ids FROM users WHERE user_id = 'user-organizer'`);
    expect(rows[0].league_ids.filter((x) => x === mine)).toHaveLength(1);
  });

  test('tenant isolation on create: foreign season, foreign parent, foreign tenant_id', async () => {
    const betaSeason = (await as('admin').post('/api/v1/seasons', season({ tenant_id: 'tenant-beta' }))).body.season_id;
    const base = { league_id: uid('lg'), sport_id: 'football', name: 'X', kind: 'league', teams: ['a', 'b'] };
    expectErr(await as('organizer').post('/api/v1/leagues', { ...base, season_id: betaSeason }), 403, 'FORBIDDEN');
    expectErr(await as('organizer').post('/api/v1/leagues', { ...base, parent_league_id: 'league-beta-1' }), 403, 'FORBIDDEN');
    expectErr(await as('organizer').post('/api/v1/leagues', { ...base, tenant_id: 'tenant-beta' }), 403, 'FORBIDDEN');
    // a super admin naming a different tenant than the season's is a conflict
    expectErr(await as('admin').post('/api/v1/leagues', { ...base, season_id: betaSeason, tenant_id: 'tenant-alpha' }), 409, 'TENANT_MISMATCH');
    const ok = await as('admin').post('/api/v1/leagues', { ...base, season_id: betaSeason });
    expect(ok.status).toBe(201);
    expect(ok.body.tenant_id).toBe('tenant-beta');
    expectErr(await as('admin').post('/api/v1/leagues', { ...base, league_id: uid('lg'), parent_league_id: 'league-beta-1', tenant_id: 'tenant-alpha' }), 409, 'TENANT_MISMATCH');
  });

  test('GET /leagues/:id: every role of the tenant, 404, 400, 401, foreign tenant', async () => {
    for (const role of ROLES) {
      const r = await as(role).get('/api/v1/leagues/league-alpha-1');
      expect(r.status).toBe(200);
      expect(r.body).toMatchObject({ league_id: 'league-alpha-1', name: 'Alpha Premier League', sport_id: 'football', kind: 'league' });
      expect(r.body.teams).toHaveLength(4);
      expect(r.body.config.qualification_spots).toBe(2);
    }
    expectErr(await as('admin').get('/api/v1/leagues/nope'), 404, 'LEAGUE_NOT_FOUND');
    expect(fieldsOf(await as('admin').get('/api/v1/leagues/bad%20id'))).toEqual(['league_id']);
    expectErr(await asToken(null).get('/api/v1/leagues/league-alpha-1'), 401, 'UNAUTHENTICATED');
    expectErr(await asToken(foreign()).get('/api/v1/leagues/league-alpha-1'), 403, 'FORBIDDEN');
    expect((await as('admin').get('/api/v1/leagues/league-beta-1')).status).toBe(200);
  });

  test('PATCH /leagues/:id updates config, promotion and relegation (and clears them with null)', async () => {
    const lg = (await mkLeague({ teams: ['a', 'b', 'c', 'd', 'e', 'f'], points: { win: 3 } })).league_id;
    const r = await as('admin').patch(`/api/v1/leagues/${lg}`, {
      name: 'Patched',
      points: { draw: 2 },
      qualification_spots: 4,
      tie_break_order: ['scored'],
      lots_seed: 9,
      transfer_window: { open: '2026-09-01', close: '2026-09-30' },
      max_squad_size: 20,
      required_positions: { gk: 2 },
      promotion: { count: 2, to: 'upper' },
      relegation: { count: 2, to: 'lower' },
    });
    expect(r.status).toBe(200);
    expect(r.body.name).toBe('Patched');
    expect(r.body.config).toEqual({
      points: { win: 3, draw: 2, loss: 0 },
      tie_break_order: ['scored', 'draw_lots'],
      qualification_spots: 4,
      lots_seed: 9,
      transfer_window: { open: '2026-09-01', close: '2026-09-30' },
      max_squad_size: 20,
      required_positions: { gk: 2 },
      promotion: { count: 2, to: 'upper' },
      relegation: { count: 2, to: 'lower' },
    });
    expect((await as('admin').get(`/api/v1/leagues/${lg}`)).body).toEqual(r.body);
    const cleared = await as('admin').patch(`/api/v1/leagues/${lg}`, { promotion: null, relegation: null, transfer_window: null, max_squad_size: null });
    expect(cleared.status).toBe(200);
    expect(cleared.body.config.promotion).toBeUndefined();
    expect(cleared.body.config.relegation).toBeUndefined();
    expect(cleared.body.config.transfer_window).toBeUndefined();
    expect(cleared.body.config.max_squad_size).toBeUndefined();
    // the new qualification spots and points feed the standings (cache is invalidated)
    const st = (await as('admin').get(`/api/v1/leagues/${lg}/standings`)).body;
    expect(st.qualification_spots).toBe(4);
  });

  test('PATCH validation, 404 and the season link', async () => {
    const lg = (await mkLeague({ teams: ['a', 'b', 'c', 'd'] })).league_id;
    const api = as('admin');
    expect(fieldsOf(await api.patch(`/api/v1/leagues/${lg}`, {}))).toEqual(['body']);
    expect(fieldsOf(await api.patch(`/api/v1/leagues/${lg}`, 'x'))).toEqual(['body']);
    expect(fieldsOf(await api.patch(`/api/v1/leagues/${lg}`, { qualification_spots: 9 }))).toEqual(['qualification_spots']);
    expect(fieldsOf(await api.patch(`/api/v1/leagues/${lg}`, { promotion: { count: 3, to: 'u' }, relegation: { count: 3, to: 'd' } }))).toEqual(['relegation.count']);
    expect(fieldsOf(await api.patch(`/api/v1/leagues/${lg}`, { promotion: { count: 1, to: lg } }))).toEqual(['promotion.to']);
    expect(fieldsOf(await api.patch(`/api/v1/leagues/${lg}`, { hybrid: { groups: 2, advance_per_group: 1 } }))).toEqual(['hybrid']);
    expect(fieldsOf(await api.patch(`/api/v1/leagues/${lg}`, { tie_break_order: ['scored', 'scored'] }))).toEqual(['tie_break_order']);
    expectErr(await api.patch('/api/v1/leagues/nope', { name: 'x' }), 404, 'LEAGUE_NOT_FOUND');
    expectErr(await api.patch(`/api/v1/leagues/${lg}`, { season_id: 'nope' }), 404, 'SEASON_NOT_FOUND');
    const betaSeason = (await api.post('/api/v1/seasons', season({ tenant_id: 'tenant-beta' }))).body.season_id;
    expectErr(await api.patch('/api/v1/leagues/league-alpha-2', { season_id: betaSeason }), 403, 'FORBIDDEN');
    const alphaSeason = (await api.post('/api/v1/seasons', season({ tenant_id: 'tenant-alpha' }))).body.season_id;
    const linked = await api.patch('/api/v1/leagues/league-alpha-2', { season_id: alphaSeason });
    expect(linked.body.season_id).toBe(alphaSeason);
    expect((await api.patch('/api/v1/leagues/league-alpha-2', { season_id: null })).body.season_id).toBeNull();
  });

  test('PATCH roles: organizer of the league and super admin only; hybrid layout locks once fixtures exist', async () => {
    const body = { name: 'By role' };
    expect((await as('organizer').patch('/api/v1/leagues/league-alpha-1', body)).status).toBe(200);
    expectErr(await as('organizer').patch('/api/v1/leagues/league-alpha-2', body), 403, 'FORBIDDEN');
    for (const role of ['manager', 'player', 'viewer']) expectErr(await as(role).patch('/api/v1/leagues/league-alpha-1', body), 403, 'FORBIDDEN');
    expectErr(await asToken(foreign()).patch('/api/v1/leagues/league-alpha-1', body), 403, 'FORBIDDEN');
    expectErr(await asToken(null).patch('/api/v1/leagues/league-alpha-1', body), 401, 'UNAUTHENTICATED');
    const hy = await mkLeague({ kind: 'hybrid', teams: ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h'], hybrid: { groups: 2, advance_per_group: 2 } });
    expect((await as('admin').patch(`/api/v1/leagues/${hy.league_id}`, { hybrid: { groups: 4, advance_per_group: 1 } })).status).toBe(200);
    await as('admin').post(`/api/v1/leagues/${hy.league_id}/fixtures/build`, { format: 'round_robin' });
    expectErr(await as('admin').patch(`/api/v1/leagues/${hy.league_id}`, { hybrid: { groups: 2, advance_per_group: 1 } }), 409, 'FIXTURES_EXIST');
    expect((await as('admin').patch(`/api/v1/leagues/${hy.league_id}`, { name: 'Still editable' })).status).toBe(200);
  });
});
