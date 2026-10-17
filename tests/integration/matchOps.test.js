const { describeDb } = require('../helpers/db');
const { resetAll, as, token, pool } = require('../helpers/mobilekit');
const { uid } = require('../helpers/evtkit');

afterAll(() => pool.end());

const fixtureOf = async (matchId) => (await as('viewer').get('/api/v1/leagues/league-alpha-1/fixtures')).body.find((m) => m.match_id === matchId);
const ev = (match_id, type, payload = {}) => ({ event_id: uid('e'), match_id, type, payload });

describeDb('match status machine (API)', () => {
  beforeAll(() => resetAll());

  test('postpone blocks scoring, shows in fixtures, and can be undone', async () => {
    const id = 'demo-m-6';
    const r = await as('organizer').patch(`/api/v1/matches/${id}/status`, { status: 'postponed' });
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({ match_id: id, status: 'postponed' });
    expect((await fixtureOf(id)).status).toBe('postponed');
    expect((await as('viewer').get(`/api/v1/matches/${id}`)).body.status).toBe('postponed');
    const blocked = await as('organizer').post(`/api/v1/matches/${id}/events`, ev(id, 'match_start'));
    expect(blocked.status).toBe(409);
    expect(blocked.body.error.code).toBe('MATCH_NOT_IN_PROGRESS');
    expect((await as('organizer').patch(`/api/v1/matches/${id}/status`, { status: 'upcoming' })).status).toBe(200);
    expect((await fixtureOf(id)).status).toBe('upcoming');
  });

  test('start and end append lifecycle events; terminal states are final', async () => {
    const id = 'demo-m-5';
    const start = await as('organizer').patch(`/api/v1/matches/${id}/status`, { status: 'in_progress' });
    expect(start.status).toBe(200);
    expect(await fixtureOf(id)).toMatchObject({ status: 'in_progress', version: 1 });
    const end = await as('organizer').patch(`/api/v1/matches/${id}/status`, { status: 'completed' });
    expect(end.status).toBe(200);
    expect(await fixtureOf(id)).toMatchObject({ status: 'completed', version: 2 });
    const again = await as('organizer').patch(`/api/v1/matches/${id}/status`, { status: 'upcoming' });
    expect(again.status).toBe(409);
    expect(again.body.error).toMatchObject({ code: 'ILLEGAL_TRANSITION', details: { from: 'completed', to: 'upcoming' } });
  });

  test('abandon and cancel set the administrative status', async () => {
    expect((await as('organizer').patch('/api/v1/matches/demo-m-live/status', { status: 'abandoned' })).status).toBe(200);
    expect((await fixtureOf('demo-m-live')).status).toBe('abandoned');
    expect((await as('organizer').patch('/api/v1/matches/demo-m-live/status', { status: 'in_progress' })).status).toBe(409);
    await pool.query(`INSERT INTO matches (match_id, sport_id, league_id, home_team_id, away_team_id, origin) VALUES ('c-up', 'football', 'league-alpha-1', 'x', 'y', 'fixture')`);
    expect((await as('organizer').patch('/api/v1/matches/c-up/status', { status: 'cancelled' })).status).toBe(200);
    expect((await as('admin').patch('/api/v1/matches/c-up/status', { status: 'upcoming' })).status).toBe(409);
  });

  test('a tie that match_end refuses is returned as is', async () => {
    await pool.query(`INSERT INTO sports (sport_id, name, scoring_model, config) VALUES ('nodraw', 'No Draw', 'event',
      '{"sport_id":"nodraw","name":"No Draw","scoring_model":"event","scoring_events":{"pt":{"points":1}},"draw_allowed":false,"overtime":{"policy":"none"},"tie_break_order":[],"periods":{"count":1,"duration_minutes":10}}'::jsonb)
      ON CONFLICT DO NOTHING`);
    await pool.query(`INSERT INTO matches (match_id, sport_id, league_id, home_team_id, away_team_id, origin) VALUES ('nd-1', 'nodraw', 'league-alpha-1', 'x', 'y', 'fixture')`);
    expect((await as('organizer').patch('/api/v1/matches/nd-1/status', { status: 'in_progress' })).status).toBe(200);
    const r = await as('organizer').patch('/api/v1/matches/nd-1/status', { status: 'completed' });
    expect(r.status).toBe(409);
    expect(r.body.error.code).toBe('TIE_NOT_ALLOWED');
  });

  test('validation, authorization, existence', async () => {
    expect((await as('organizer').patch('/api/v1/matches/demo-m-6/status', { status: 'weird' })).status).toBe(400);
    expect((await as('organizer').patch('/api/v1/matches/demo-m-6/status', {})).body.error.details.fields).toEqual(['status']);
    expect((await as('viewer').patch('/api/v1/matches/demo-m-6/status', { status: 'postponed' })).status).toBe(403);
    expect((await as('manager').patch('/api/v1/matches/demo-m-6/status', { status: 'postponed' })).status).toBe(403);
    expect((await as('organizer').patch('/api/v1/matches/nope/status', { status: 'postponed' })).status).toBe(404);
    expect((await as('organizer').patch('/api/v1/matches/bad%20id/status', { status: 'postponed' })).status).toBe(400);
  });
});

describeDb('schedule conflicts (API)', () => {
  const T = Date.UTC(2026, 10, 3, 12, 0, 0); // Tuesday 2026-11-03 12:00 UTC
  const iso = (offsetH) => new Date(T + offsetH * 3600e3).toISOString().replace(/\.\d{3}Z$/, 'Z');
  const insert = (id, home, away, offsetH, venue, extra = '') => pool.query(
    `INSERT INTO matches (match_id, sport_id, league_id, home_team_id, away_team_id, origin, scheduled_at, venue_id) VALUES ($1, 'football', 'league-alpha-1', $2, $3, 'fixture', $4, $5)${extra}`,
    [id, home, away, offsetH === null ? null : iso(offsetH), venue],
  );
  const check = (id, offsetH, venue) => as('organizer').post(`/api/v1/matches/${id}/schedule/check`, { scheduled_at: iso(offsetH), venue_id: venue });
  const types = (r) => r.body.conflicts.map((c) => c.type);

  beforeAll(async () => {
    await resetAll();
    await insert('s-subject', 'ta', 'tb', null, null);
    await insert('s-venue', 'tc', 'td', 0, 'demo-venue-1');
    await insert('s-team', 'ta', 'te', 40, 'demo-venue-2');
  });

  test('venue double booking', async () => {
    const r = await check('s-subject', 1, 'demo-venue-1');
    expect(r.status).toBe(200);
    expect(r.body.conflicts).toEqual([{ type: 'venue_double_booking', entity_ids: ['demo-venue-1'], match_ids: ['s-venue'] }]);
    expect(types(await check('s-subject', 3, 'demo-venue-1'))).toEqual([]);
  });

  test('team overlap, min rest, and several at once', async () => {
    const overlap = await check('s-subject', 40.5, 'demo-venue-1');
    expect(overlap.body.conflicts).toEqual([{ type: 'team_overlap', entity_ids: ['ta'], match_ids: ['s-team'] }]);
    const rest = await check('s-subject', 50, 'demo-venue-1');
    expect(rest.body.conflicts).toEqual([{ type: 'min_rest', entity_ids: ['ta'], match_ids: ['s-team'] }]);
    const many = await check('s-subject', 0.5, 'demo-venue-1');
    expect(types(many)).toEqual(['venue_double_booking']);
    await insert('s-both', 'ta', 'tz', 0, 'demo-venue-2');
    const both = await check('s-subject', 0, 'demo-venue-1');
    expect(types(both)).toEqual(['venue_double_booking', 'team_overlap']);
  });

  test('player overlap uses squads whose id equals the team id', async () => {
    await pool.query(`INSERT INTO registrations (league_id, squad_id, player_id, role) VALUES
      ('lg-x1', 'pa', 'shared-p', 'starter'), ('lg-x2', 'pq', 'shared-p', 'starter')`);
    await insert('p-other', 'pq', 'pz', 200, 'demo-venue-2');
    await insert('p-subject', 'pa', 'pw', null, null);
    const r = await check('p-subject', 200, 'demo-venue-1');
    expect(r.body.conflicts).toEqual([{ type: 'player_overlap', entity_ids: ['shared-p'], match_ids: ['p-other'] }]);
  });

  test('venue unavailable outside opening hours', async () => {
    await pool.query(`INSERT INTO venue_hours (venue_id, weekday, opens, closes) VALUES ('demo-venue-2', 2, '09:00', '18:00')`);
    await insert('h-subject', 'ha', 'hb', null, null);
    expect(types(await check('h-subject', 300, 'demo-venue-2'))).toEqual(['venue_unavailable']);
    // Tuesday 2026-11-03 12:00 UTC is inside 09:00-18:00
    const inside = await as('organizer').post('/api/v1/matches/h-subject/schedule/check', { scheduled_at: iso(0), venue_id: 'demo-venue-2' });
    expect(types(inside)).not.toContain('venue_unavailable');
  });

  test('check does not save; PATCH saves a clean slot and rejects conflicts without saving', async () => {
    const before = (await pool.query(`SELECT scheduled_at, venue_id FROM matches WHERE match_id = 's-subject'`)).rows[0];
    await check('s-subject', 1, 'demo-venue-1');
    expect((await pool.query(`SELECT scheduled_at, venue_id FROM matches WHERE match_id = 's-subject'`)).rows[0]).toEqual(before);

    const bad = await as('organizer').patch('/api/v1/matches/s-subject/schedule', { scheduled_at: iso(1), venue_id: 'demo-venue-1' });
    expect(bad.status).toBe(409);
    expect(bad.body.error.code).toBe('SCHEDULE_CONFLICT');
    expect(bad.body.error.details.conflicts[0].type).toBe('venue_double_booking');
    expect((await pool.query(`SELECT scheduled_at FROM matches WHERE match_id = 's-subject'`)).rows[0].scheduled_at).toBeNull();

    const ok = await as('organizer').patch('/api/v1/matches/s-subject/schedule', { scheduled_at: iso(400), venue_id: 'demo-venue-1' });
    expect(ok.status).toBe(200);
    expect(ok.body).toEqual({ match_id: 's-subject', scheduled_at: iso(400), venue_id: 'demo-venue-1', conflicts: [] });
    const fixture = (await as('viewer').get('/api/v1/leagues/league-alpha-1/fixtures')).body.find((m) => m.match_id === 's-subject');
    expect(fixture).toMatchObject({ scheduled_at: iso(400), venue_name: 'Gaddafi Park' });
  });

  test('rescheduling a match does not conflict with itself; validation and RBAC', async () => {
    expect((await as('organizer').patch('/api/v1/matches/s-venue/schedule', { scheduled_at: iso(0), venue_id: 'demo-venue-1' })).status).toBe(200);
    expect((await as('organizer').patch('/api/v1/matches/s-venue/schedule', { scheduled_at: 'tomorrow' })).status).toBe(400);
    expect((await as('organizer').patch('/api/v1/matches/s-venue/schedule', { scheduled_at: iso(0), venue_id: 'ghost-venue' })).body.error.code).toBe('VENUE_NOT_FOUND');
    await pool.query(`INSERT INTO venues (venue_id, tenant_id, name) VALUES ('beta-venue', 'tenant-beta', 'Beta')`);
    expect((await as('organizer').patch('/api/v1/matches/s-venue/schedule', { scheduled_at: iso(0), venue_id: 'beta-venue' })).status).toBe(403);
    expect((await as('viewer').patch('/api/v1/matches/s-venue/schedule', { scheduled_at: iso(0) })).status).toBe(403);
    expect((await as('organizer').post('/api/v1/matches/nope/schedule/check', { scheduled_at: iso(0) })).status).toBe(404);
  });
});

describeDb('pins and order administration (API)', () => {
  beforeAll(() => resetAll());

  test('pin and unpin; tenant and role rules', async () => {
    const m = 'demo-m-5';
    const pin = await as('organizer').post(`/api/v1/matches/${m}/pins`, { product_id: 'demo-prod-scarf' });
    expect(pin.status).toBe(201);
    expect(pin.body).toEqual({ match_id: m, product_id: 'demo-prod-scarf', pinned: true });
    expect((await as('viewer').get(`/api/v1/matches/${m}`)).body.pinned_products.map((p) => p.product_id)).toEqual(['demo-prod-scarf']);
    expect((await as('organizer').post(`/api/v1/matches/${m}/pins`, { product_id: 'demo-prod-scarf' })).status).toBe(201);
    await pool.query(`INSERT INTO products (product_id, tenant_id, club_id, category, name) VALUES ('beta-p', 'tenant-beta', 'club-beta-1', 'x', 'Beta')`);
    expect((await as('organizer').post(`/api/v1/matches/${m}/pins`, { product_id: 'beta-p' })).status).toBe(403);
    expect((await as('organizer').post(`/api/v1/matches/${m}/pins`, { product_id: 'ghost' })).body.error.code).toBe('PRODUCT_NOT_FOUND');
    expect((await as('organizer').post(`/api/v1/matches/${m}/pins`, {})).status).toBe(400);
    expect((await as('viewer').post(`/api/v1/matches/${m}/pins`, { product_id: 'demo-prod-cap' })).status).toBe(403);
    const del = await as('organizer').delete(`/api/v1/matches/${m}/pins/demo-prod-scarf`);
    expect(del.status).toBe(200);
    expect(del.body.pinned).toBe(false);
    expect((await as('viewer').get(`/api/v1/matches/${m}`)).body.pinned_products).toEqual([]);
    expect((await as('viewer').delete(`/api/v1/matches/${m}/pins/demo-prod-cap`)).status).toBe(403);
    expect((await as('organizer').delete(`/api/v1/matches/${m}/pins/bad%20id`)).status).toBe(400);
  });

  test('manager moves an order through the state machine and refunds it (stock returns)', async () => {
    const order_id = uid('ord');
    const buy = await as('viewer').post('/api/v1/checkout', { order_id, items: [{ variant_id: 'demo-var-scarf-std', quantity: 3 }], payment: { token: 'tok_success' } });
    expect(buy.status).toBe(201);
    const stock = async () => (await as('viewer').get('/api/v1/products')).body.find((p) => p.product_id === 'demo-prod-scarf').variants[0].stock;
    expect(await stock()).toBe(97);

    expect((await as('viewer').patch(`/api/v1/orders/${order_id}/status`, { status: 'packed' })).status).toBe(403);
    expect((await as('organizer').post(`/api/v1/orders/${order_id}/refund`)).status).toBe(403);
    const packed = await as('manager').patch(`/api/v1/orders/${order_id}/status`, { status: 'packed' });
    expect(packed.status).toBe(200);
    expect(packed.body).toEqual({ order_id, from: 'paid', to: 'packed' });
    const illegal = await as('manager').patch(`/api/v1/orders/${order_id}/status`, { status: 'delivered' });
    expect(illegal.status).toBe(409);
    expect(illegal.body.error.code).toBe('ILLEGAL_TRANSITION');
    expect((await as('manager').patch(`/api/v1/orders/${order_id}/status`, { status: 'bogus' })).status).toBe(400);
    const refund = await as('manager').post(`/api/v1/orders/${order_id}/refund`);
    expect(refund.status).toBe(200);
    expect(refund.body.to).toBe('refunded');
    expect(await stock()).toBe(100);
    expect((await as('viewer').get(`/api/v1/orders/${order_id}`)).body.status).toBe('refunded');
    expect((await as('manager').post('/api/v1/orders/missing/refund')).status).toBe(404);
  });
});

describeDb('roster add and player history (API)', () => {
  beforeAll(() => resetAll());

  const add = (over = {}) => ({
    request_id: uid('rq'), league_id: 'league-alpha-1', player_id: uid('np'), name: 'New Player', position: 'df',
    squad_id: 'demo-sq-alpha-1', role: 'substitute', effective_date: '2026-10-10', reason: 'trial', ...over,
  });

  test('adds a player: response shape, club roster, audit version, history', async () => {
    const body = add({ player_id: 'new-pl-1' });
    const r = await as('manager').post('/api/v1/clubs/club-alpha-1/roster/add', body);
    expect(r.status).toBe(201);
    expect(r.body).toMatchObject({
      request_id: body.request_id, status: 'accepted', state: 'active', blocking_match_id: null,
      registration: { player_id: 'new-pl-1', league_id: 'league-alpha-1', squad_id: 'demo-sq-alpha-1', registration_type: 'add', role: 'substitute' },
      audit_entry: { action: 'add', player_id: 'new-pl-1', to_squad_id: 'demo-sq-alpha-1', from_squad_id: null, effective_date: '2026-10-10', why: 'trial' },
    });
    const club = (await as('manager').get('/api/v1/clubs/club-alpha-1')).body;
    expect(club.roster).toHaveLength(9);
    expect(club.roster.find((p) => p.player_id === 'new-pl-1')).toMatchObject({ name: 'New Player', position: 'df', audit_version: 1 });

    const h = await as('manager').get('/api/v1/players/new-pl-1/history');
    expect(h.status).toBe(200);
    expect(h.body.history.map((e) => e.action)).toEqual(['add']);
    expect(h.body.current_squad_id).toBe('demo-sq-alpha-1');

    // the new player can be transferred like any other
    const tr = await as('manager').post('/api/v1/clubs/club-alpha-1/roster/transfer', {
      request_id: uid('rq'), league_id: 'league-alpha-1', player_id: 'new-pl-1', type: 'transfer', from_squad_id: 'demo-sq-alpha-1',
      to_squad_id: 'demo-sq-alpha-2', effective_date: '2026-10-11', reason: 'swap',
    });
    expect(tr.status).toBe(201);
  });

  test('idempotent by request_id; a different body with the same id is DUPLICATE_REQUEST_ID', async () => {
    const body = add();
    expect((await as('manager').post('/api/v1/clubs/club-alpha-1/roster/add', body)).status).toBe(201);
    const again = await as('manager').post('/api/v1/clubs/club-alpha-1/roster/add', body);
    expect(again.status).toBe(200);
    const clash = await as('manager').post('/api/v1/clubs/club-alpha-1/roster/add', { ...body, name: 'Other' });
    expect(clash.status).toBe(409);
    expect(clash.body.error.code).toBe('DUPLICATE_REQUEST_ID');
  });

  test('rules in order', async () => {
    const post = (b) => as('manager').post('/api/v1/clubs/club-alpha-1/roster/add', b);
    const bad = await post(add({ role: 'king', effective_date: 'x' }));
    expect(bad.status).toBe(400);
    expect(bad.body.error.details.fields).toEqual(expect.arrayContaining(['role', 'effective_date']));
    expect((await post(add({ league_id: 'nope-lg' }))).body.error.code).toBe('LEAGUE_NOT_FOUND');
    expect((await post(add({ squad_id: 'nope-sq' }))).body.error.code).toBe('SQUAD_NOT_FOUND');
    expect((await post(add({ squad_id: 'demo-sq-alpha-2' }))).status).toBe(403);
    expect((await as('manager').post('/api/v1/clubs/club-alpha-2/roster/add', add())).status).toBe(403);
    const dup = await post(add({ player_id: 'player-alpha-1' }));
    expect(dup.status).toBe(409);
    expect(dup.body.error.code).toBe('PLAYER_ALREADY_REGISTERED');
    expect((await post(add({ effective_date: '2026-12-01' }))).body.error.code).toBe('TRANSFER_WINDOW_CLOSED');
    expect((await as('viewer').post('/api/v1/clubs/club-alpha-1/roster/add', add())).status).toBe(403);
    expect((await as('organizer').post('/api/v1/clubs/club-alpha-1/roster/add', add())).status).toBe(403);
  });

  test('a full squad is SQUAD_FULL; a released player can be re-added', async () => {
    await pool.query(`UPDATE roster_leagues SET max_squad_size = 8 WHERE league_id = 'league-alpha-1'`);
    const full = await as('manager').post('/api/v1/clubs/club-alpha-1/roster/add', add());
    expect(full.status).toBe(409);
    expect(full.body.error.code).toBe('SQUAD_FULL');
    await pool.query(`UPDATE roster_leagues SET max_squad_size = 25 WHERE league_id = 'league-alpha-1'`);
    const rel = await as('manager').post('/api/v1/clubs/club-alpha-1/roster/transfer', {
      request_id: uid('rq'), league_id: 'league-alpha-1', player_id: 'demo-pl-1-8', type: 'release', from_squad_id: 'demo-sq-alpha-1',
      effective_date: '2026-10-12', reason: 'cut',
    });
    expect(rel.status).toBe(201);
    const readd = await as('manager').post('/api/v1/clubs/club-alpha-1/roster/add', add({ player_id: 'demo-pl-1-8', effective_date: '2026-10-13' }));
    expect(readd.status).toBe(201);
  });

  test('player history permissions', async () => {
    const ok = await as('player').get('/api/v1/players/player-alpha-1/history');
    expect(ok.status).toBe(200);
    expect(ok.body.player_id).toBe('player-alpha-1');
    expect((await as('player').get('/api/v1/players/player-alpha-2/history')).status).toBe(403);
    expect((await as('viewer').get('/api/v1/players/player-alpha-1/history')).status).toBe(403);
    expect((await as('manager').get('/api/v1/players/player-alpha-2/history')).status).toBe(403);
    expect((await as('manager').get('/api/v1/players/player-alpha-1/history')).status).toBe(200);
    expect((await as('admin').get('/api/v1/players/player-alpha-2/history?as_of=2026-10-01')).body.as_of).toBe('2026-10-01');
    expect((await as('admin').get('/api/v1/players/nobody/history')).status).toBe(404);
    expect((await as('admin').get('/api/v1/players/bad%20id/history')).status).toBe(400);
    expect((await as('admin').get('/api/v1/players/player-alpha-1/history?as_of=nope')).status).toBe(400);
  });
});

describeDb('sync through the production route', () => {
  beforeAll(() => resetAll());

  test('an organizer syncing a foreign-league score gets FORBIDDEN for that action only', async () => {
    await pool.query(`INSERT INTO matches (match_id, sport_id, league_id, home_team_id, away_team_id, origin) VALUES ('beta-sync', 'football', 'league-beta-1', 'x', 'y', 'fixture')`);
    const actions = [
      { action_id: uid('a'), type: 'score_event', queued_at: '2026-03-01T00:00:01Z', base_version: 3, payload: { event_id: uid('e'), match_id: 'demo-m-live', type: 'score', payload: { team_id: 'demo-team-1', event_type: 'goal' } } },
      { action_id: uid('a'), type: 'score_event', queued_at: '2026-03-01T00:00:02Z', base_version: 0, payload: { event_id: uid('e'), match_id: 'beta-sync', type: 'match_start', payload: {} } },
    ];
    const r = await as('organizer').post('/api/v1/sync', { device_id: uid('dev'), actions });
    expect(r.status).toBe(200);
    expect(r.body.results.map((x) => [x.status, x.error_code])).toEqual([['applied', null], ['rejected', 'FORBIDDEN']]);
    expect((await fixtureOf('demo-m-live')).home_score).toBe(2);
    expect((await as('viewer').post('/api/v1/sync', { device_id: uid('dev'), actions: [actions[0]] })).body.results[0].error_code).toBe('FORBIDDEN');
    expect((await (await require('../helpers/mobilekit').http()).post('/api/v1/sync').send({})).status).toBe(401);
  });

  test('a manager syncs roster transfers for their own club only', async () => {
    const t = (from, to, player) => ({
      action_id: uid('a'), type: 'roster_transfer', queued_at: '2026-03-01T00:00:03Z', base_version: 0,
      payload: { request_id: uid('rq'), league_id: 'league-alpha-1', player_id: player, type: 'transfer', from_squad_id: from, to_squad_id: to, effective_date: '2026-10-12', reason: 'sync', requested_by: 'mgr' },
    });
    const r = await as('manager').post('/api/v1/sync', {
      device_id: uid('dev'), actions: [t('demo-sq-alpha-1', 'demo-sq-alpha-2', 'demo-pl-1-3'), t('demo-sq-alpha-2', 'demo-sq-alpha-1', 'demo-pl-2-3')],
    });
    expect(r.body.results.map((x) => [x.status, x.error_code])).toEqual([['applied', null], ['rejected', 'FORBIDDEN']]);
    expect(await token('manager')).toBeTruthy();
  });
});
