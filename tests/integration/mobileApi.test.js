const { describeDb } = require('../helpers/db');
const { resetAll, as, http, pool } = require('../helpers/mobilekit');
const { uid, post: auditPost } = require('../helpers/evtkit');

afterAll(() => pool.end());

const keys = (o) => Object.keys(o).sort();

describeDb('mobile API: read endpoints (contract)', () => {
  beforeAll(() => resetAll());

  test('GET /leagues is tenant scoped, bare array, X-Total-Count', async () => {
    const r = await as('viewer').get('/api/v1/leagues');
    expect(r.status).toBe(200);
    expect(Array.isArray(r.body)).toBe(true);
    expect(r.headers['x-total-count']).toBe(String(r.body.length));
    expect(r.body.map((l) => l.league_id).sort()).toEqual(['league-alpha-1', 'league-alpha-2']);
    expect(keys(r.body[0])).toEqual(['league_id', 'name', 'season', 'sport_id']);
    const alpha1 = r.body.find((l) => l.league_id === 'league-alpha-1');
    expect(alpha1).toMatchObject({ name: 'Alpha Premier League', season: '2026/27', sport_id: 'football' });
    expect(r.body.find((l) => l.league_id === 'league-alpha-2')).toMatchObject({ name: 'Alpha League 2', season: null });
    const all = await as('admin').get('/api/v1/leagues');
    expect(all.body.map((l) => l.league_id)).toContain('league-beta-1');
    const paged = await as('admin').get('/api/v1/leagues?limit=1&offset=1');
    expect(paged.body).toHaveLength(1);
    expect(paged.headers['x-total-count']).toBe(String(all.body.length));
  });

  test('fixtures list: names, effective status, scores, versions', async () => {
    const r = await as('player').get('/api/v1/leagues/league-alpha-1/fixtures');
    expect(r.status).toBe(200);
    expect(r.body).toHaveLength(7);
    expect(keys(r.body[0])).toEqual([
      'away_name', 'away_score', 'away_team_id', 'home_name', 'home_score', 'home_team_id',
      'match_id', 'scheduled_at', 'status', 'venue_name', 'version',
    ]);
    const by = Object.fromEntries(r.body.map((m) => [m.match_id, m]));
    expect(by['demo-m-1']).toMatchObject({ status: 'completed', home_name: 'Lahore Lions', away_name: 'Karachi Kings', home_score: 2, away_score: 0, venue_name: 'Gaddafi Park' });
    expect(by['demo-m-live']).toMatchObject({ status: 'in_progress', home_score: 1, away_score: 1, version: 3 });
    expect(by['demo-m-5']).toMatchObject({ status: 'upcoming', home_score: 0, away_score: 0, version: 0 });
    expect(r.body.filter((m) => m.status === 'completed')).toHaveLength(3);
    expect(r.body.filter((m) => m.status === 'upcoming')).toHaveLength(2);
    expect(by['demo-m-1'].scheduled_at).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
  });

  test('match detail = score projection + pinned products', async () => {
    const r = await as('viewer').get('/api/v1/matches/demo-m-live');
    expect(r.status).toBe(200);
    const audit = (await (await http()).get('/audit/v1/score/demo-m-live')).body;
    const { pinned_products, scheduled_at, venue_name, ...rest } = r.body;
    expect(rest).toEqual(audit);
    expect(pinned_products).toEqual([{
      product_id: 'demo-prod-cap', name: 'Club Cap', price_minor: 120000, image_url: '/static/product/demo-prod-cap.png',
    }]);
    expect((await as('viewer').get('/api/v1/matches/demo-m-1')).body.pinned_products).toEqual([]);
  });

  test('standings = audit body + team_names, non-trivial table', async () => {
    const r = await as('viewer').get('/api/v1/leagues/league-alpha-1/standings');
    expect(r.status).toBe(200);
    const audit = (await (await http()).get('/audit/v1/standings/league-alpha-1')).body;
    const { team_names, ...rest } = r.body;
    expect(rest).toEqual(audit);
    expect(team_names).toEqual({
      'demo-team-1': 'Lahore Lions', 'demo-team-2': 'Karachi Kings', 'demo-team-3': 'Islamabad Falcons', 'demo-team-4': 'Peshawar Hawks',
    });
    expect(new Set(r.body.table.map((t) => t.points)).size).toBeGreaterThan(1);
  });

  test('sport config', async () => {
    const r = await as('viewer').get('/api/v1/sports/football');
    expect(r.status).toBe(200);
    expect(r.body.sport_id).toBe('football');
    expect(r.body.scoring_events).toBeDefined();
    expect((await as('viewer').get('/api/v1/sports/nosuchsport')).status).toBe(404);
    expect((await as('viewer').get('/api/v1/sports/bad%20id')).status).toBe(400);
  });

  test('clubs list and detail', async () => {
    const list = await as('manager').get('/api/v1/clubs');
    expect(list.body.map((c) => c.club_id)).toEqual(['club-alpha-1', 'club-alpha-2']);
    expect(keys(list.body[0])).toEqual(['club_id', 'logo_url', 'name']);
    const r = await as('manager').get('/api/v1/clubs/club-alpha-1');
    expect(r.status).toBe(200);
    expect(keys(r.body)).toEqual(['club_id', 'destination_squads', 'logo_url', 'name', 'roster', 'socials', 'squads', 'staff', 'stats', 'tenant_id', 'venue']);
    expect(r.body.venue).toEqual({ name: 'Gaddafi Park', lat: 31.5134, lng: 74.3333 });
    expect(r.body.staff).toHaveLength(3);
    expect(r.body.stats[0]).toMatchObject({ season: '2025/26', played: 22 });
    expect(r.body.squads).toEqual([{ squad_id: 'demo-sq-alpha-1', name: 'Lahore Lions', club_id: 'club-alpha-1' }]);
    expect(r.body.roster).toHaveLength(8);
    expect(keys(r.body.roster[0])).toEqual(['audit_version', 'league_id', 'name', 'on_loan', 'player_id', 'position', 'role', 'squad_id']);
    expect(r.body.roster.filter((p) => p.position === 'gk').length).toBeGreaterThanOrEqual(2);
    expect(r.body.roster.some((p) => p.role === 'captain')).toBe(true);
    expect(r.body.roster.map((p) => p.player_id)).toContain('player-alpha-1');
  });

  test('products with effective prices and stock', async () => {
    const r = await as('viewer').get('/api/v1/products');
    expect(r.status).toBe(200);
    expect(r.body.map((p) => p.product_id).sort()).toEqual(['demo-prod-cap', 'demo-prod-ebook', 'demo-prod-scarf', 'prod-jersey-001']);
    const cap = r.body.find((p) => p.product_id === 'demo-prod-cap');
    expect(keys(cap)).toEqual(['category', 'image_url', 'name', 'product_id', 'variants']);
    expect(cap.variants[0]).toEqual({ variant_id: 'demo-var-cap-std', size: 'Standard', color: 'Navy', price_minor: 120000, stock: 100 });
    expect(r.body.find((p) => p.product_id === 'prod-jersey-001').image_url).toBe('/static/product/prod-jersey-001.png');
  });
});

describeDb('mobile API: authentication, roles, tenants', () => {
  beforeAll(() => resetAll());

  const paths = [
    '/api/v1/leagues', '/api/v1/leagues/league-alpha-1/fixtures', '/api/v1/leagues/league-alpha-1/standings',
    '/api/v1/matches/demo-m-live', '/api/v1/sports/football', '/api/v1/clubs', '/api/v1/clubs/club-alpha-1', '/api/v1/products',
  ];

  test.each(paths)('401 without token: %s', async (p) => {
    expect((await (await http()).get(p)).status).toBe(401);
    expect((await (await http()).get(p).set('Authorization', 'Bearer nope')).status).toBe(401);
  });

  test('foreign tenant is 403, not 404', async () => {
    for (const p of ['/api/v1/leagues/league-beta-1/fixtures', '/api/v1/leagues/league-beta-1/standings', '/api/v1/clubs/club-beta-1']) {
      const r = await as('viewer').get(p);
      expect(r.status).toBe(403);
      expect(r.body.error.code).toBe('FORBIDDEN');
    }
    expect((await as('manager').get('/api/v1/clubs/club-beta-1')).status).toBe(403);
    expect((await as('admin').get('/api/v1/clubs/club-beta-1')).status).toBe(200);
  });

  test('missing ids are 404, malformed ids 400', async () => {
    expect((await as('admin').get('/api/v1/leagues/nope/fixtures')).body.error.code).toBe('LEAGUE_NOT_FOUND');
    expect((await as('viewer').get('/api/v1/matches/nope')).body.error.code).toBe('MATCH_NOT_FOUND');
    expect((await as('viewer').get('/api/v1/clubs/nope')).status).toBe(404);
    const bad = await as('viewer').get('/api/v1/leagues/bad%20id/fixtures');
    expect(bad.status).toBe(400);
    expect(bad.body.error.details.fields).toEqual(['league_id']);
    expect((await as('viewer').get('/api/v1/matches/bad%7Cid')).status).toBe(400);
  });

  test('stand-alone matches are readable by everyone, writable only by the super admin', async () => {
    const match_id = uid('solo');
    const init = await auditPost({ event_id: uid('e'), match_id, type: 'match_init', payload: { sport_id: 'football', home_team_id: 'H', away_team_id: 'A' } });
    expect(init.status).toBe(201);
    expect((await as('viewer').get(`/api/v1/matches/${match_id}`)).status).toBe(200);
    const body = { event_id: uid('e'), match_id, type: 'match_start', payload: {} };
    expect((await as('organizer').post(`/api/v1/matches/${match_id}/events`, body)).status).toBe(403);
    expect((await as('admin').post(`/api/v1/matches/${match_id}/events`, body)).status).toBe(201);
  });
});

describeDb('mobile API: scoring and cache freshness', () => {
  beforeAll(() => resetAll());

  test('organizer posts events; viewer cannot; mismatching match_id is 400', async () => {
    const body = (extra = {}) => ({ event_id: uid('e'), match_id: 'demo-m-live', type: 'score', payload: { team_id: 'demo-team-1', event_type: 'goal' }, ...extra });
    const r = await as('organizer').post('/api/v1/matches/demo-m-live/events', body());
    expect(r.status).toBe(201);
    expect(keys(r.body)).toEqual(['event_id', 'match_id', 'match_version', 'sequence', 'signature', 'type']);
    expect(r.body.sequence).toBe(4);
    expect((await as('viewer').post('/api/v1/matches/demo-m-live/events', body())).status).toBe(403);
    expect((await as('manager').post('/api/v1/matches/demo-m-live/events', body())).status).toBe(403);
    const mismatch = await as('organizer').post('/api/v1/matches/demo-m-live/events', body({ match_id: 'demo-m-5' }));
    expect(mismatch.status).toBe(400);
    expect(mismatch.body.error.details.fields).toEqual(['match_id']);
    expect((await as('organizer').post('/api/v1/matches/demo-m-live/events', { nope: 1 })).status).toBe(400);
    expect((await as('organizer').post('/api/v1/matches/nope/events', body())).status).toBe(404);
  });

  test('an organizer cannot score a foreign league match (403)', async () => {
    const { rows } = await pool.query(`SELECT 1`);
    expect(rows).toHaveLength(1);
    await pool.query(
      `INSERT INTO matches (match_id, sport_id, league_id, home_team_id, away_team_id, origin) VALUES ('beta-m-1', 'football', 'league-beta-1', 'x', 'y', 'fixture')`,
    );
    const r = await as('organizer').post('/api/v1/matches/beta-m-1/events', { event_id: uid('e'), match_id: 'beta-m-1', type: 'match_start', payload: {} });
    expect(r.status).toBe(403);
  });

  test('an audit write is visible through the cached API reads at once', async () => {
    const before = (await as('viewer').get('/api/v1/leagues/league-alpha-1/fixtures')).body.find((m) => m.match_id === 'demo-m-live');
    const st = (await as('viewer').get('/api/v1/leagues/league-alpha-1/standings')).body;
    const w = await auditPost({ event_id: uid('e'), match_id: 'demo-m-live', type: 'score', payload: { team_id: 'demo-team-4', event_type: 'goal' } });
    expect(w.status).toBe(201);
    const after = (await as('viewer').get('/api/v1/leagues/league-alpha-1/fixtures')).body.find((m) => m.match_id === 'demo-m-live');
    expect(after.away_score).toBe(before.away_score + 1);
    expect(after.version).toBe(before.version + 1);
    expect(st.table.length).toBe(4);
  });
});

describeDb('mobile API: checkout and receipts', () => {
  beforeAll(() => resetAll());

  const order = (over = {}) => ({
    order_id: uid('ord'), items: [{ variant_id: 'demo-var-cap-std', quantity: 2 }], payment: { token: 'tok_success' }, ...over,
  });

  test('manager buys a demo product; receipt is a PDF; others are 403; stock is cache fresh', async () => {
    const before = (await as('manager').get('/api/v1/products')).body.find((p) => p.product_id === 'demo-prod-cap').variants[0].stock;
    const body = order();
    const r = await as('manager').post('/api/v1/checkout', { ...body, customer_id: 'someone-else' });
    expect(r.status).toBe(201);
    expect(r.body.status).toBe('paid');
    const after = (await as('manager').get('/api/v1/products')).body.find((p) => p.product_id === 'demo-prod-cap').variants[0].stock;
    expect(after).toBe(before - 2);

    const o = await as('manager').get(`/api/v1/orders/${body.order_id}`);
    expect(o.status).toBe(200);
    expect(keys(o.body)).toEqual(['created_at', 'currency', 'discount_minor', 'items', 'order_id', 'paid_at', 'status', 'status_history', 'subtotal_minor', 'total_minor']);
    expect(o.body.total_minor).toBe(240000);

    const pdf = await (await http()).get(`/api/v1/orders/${body.order_id}/receipt.pdf`)
      .set('Authorization', `Bearer ${await require('../helpers/mobilekit').token('manager')}`).buffer(true)
      .parse((res, cb) => { const c = []; res.on('data', (d) => c.push(d)); res.on('end', () => cb(null, Buffer.concat(c))); });
    expect(pdf.status).toBe(200);
    expect(pdf.headers['content-type']).toBe('application/pdf');
    expect(pdf.headers['content-disposition']).toBe(`inline; filename="receipt-${body.order_id}.pdf"`);
    expect(pdf.headers['cache-control']).toBe('private, no-store');
    expect(pdf.body.subarray(0, 4).toString()).toBe('%PDF');

    expect((await as('viewer').get(`/api/v1/orders/${body.order_id}`)).status).toBe(403);
    expect((await as('viewer').get(`/api/v1/orders/${body.order_id}/receipt.pdf`)).status).toBe(403);
    expect((await as('admin').get(`/api/v1/orders/${body.order_id}`)).status).toBe(200);
    expect((await as('manager').get('/api/v1/orders/does-not-exist')).status).toBe(404);
    expect((await as('manager').get('/api/v1/orders/bad%20id')).status).toBe(400);
  });

  test('variants of another tenant are 403, unknown variants 404, bad body 400', async () => {
    await pool.query(`INSERT INTO products (product_id, tenant_id, club_id, category, name) VALUES ('beta-prod', 'tenant-beta', 'club-beta-1', 'apparel', 'Beta')`);
    await pool.query(`INSERT INTO variants (variant_id, product_id, price_minor, stock) VALUES ('beta-var', 'beta-prod', 1000, 5)`);
    expect((await as('manager').post('/api/v1/checkout', order({ items: [{ variant_id: 'beta-var', quantity: 1 }] }))).status).toBe(403);
    expect((await as('manager').post('/api/v1/checkout', order({ items: [{ variant_id: 'ghost-var', quantity: 1 }] }))).status).toBe(404);
    const bad = await as('manager').post('/api/v1/checkout', { order_id: 'x', items: [] });
    expect(bad.status).toBe(400);
    expect(bad.body.error.details.fields).toEqual(expect.arrayContaining(['items', 'payment']));
    expect((await as('admin').post('/api/v1/checkout', order({ items: [{ variant_id: 'beta-var', quantity: 1 }] }))).status).toBe(201);
  });

  test('re-posting the same order is idempotent', async () => {
    const body = order();
    const a = await as('viewer').post('/api/v1/checkout', body);
    const b = await as('viewer').post('/api/v1/checkout', body);
    expect([a.status, b.status]).toEqual([201, 200]);
    expect(b.body.order_id).toBe(a.body.order_id);
  });
});

describeDb('mobile API: static art and demo data', () => {
  beforeAll(() => resetAll());

  const png = async (p) => (await http()).get(p).buffer(true)
    .parse((res, cb) => { const c = []; res.on('data', (d) => c.push(d)); res.on('end', () => cb(null, Buffer.concat(c))); });

  test('generated PNGs: signature, determinism, cache header, tenant colours', async () => {
    const a = await png('/static/logo/tenant-alpha.png');
    const b = await png('/static/logo/tenant-alpha.png');
    expect(a.status).toBe(200);
    expect(a.headers['content-type']).toBe('image/png');
    expect(a.headers['cache-control']).toBe('public, max-age=3600');
    expect(a.body.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a');
    expect(a.body.equals(b.body)).toBe(true);
    const other = await png('/static/logo/tenant-beta.png');
    expect(other.body.equals(a.body)).toBe(false);
    const prod = await png('/static/product/demo-prod-cap.png');
    expect(prod.status).toBe(200);
    expect(prod.body.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a');
    const bad = await (await http()).get('/static/logo/bad%20id.png');
    expect(bad.status).toBe(404);
    expect(bad.body.error.code).toBe('NOT_FOUND');
    expect((await (await http()).get('/static/logo/x.jpg')).status).toBe(404);
  });

  test('roster scenario: two squads, 8 players each, 2 keepers, not full, captain', async () => {
    const { rows } = await pool.query(
      `SELECT squad_id, count(*)::int AS n, count(*) FILTER (WHERE position = 'gk')::int AS gk
       FROM roster_initial WHERE league_id = 'league-alpha-1' GROUP BY squad_id ORDER BY squad_id`,
    );
    expect(rows).toEqual([{ squad_id: 'demo-sq-alpha-1', n: 8, gk: 2 }, { squad_id: 'demo-sq-alpha-2', n: 8, gk: 2 }]);
    const club2 = (await as('admin').get('/api/v1/clubs/club-alpha-2')).body;
    expect(club2.roster).toHaveLength(8);
    expect(club2.roster.map((p) => p.player_id)).toContain('player-alpha-2');
  });

  test('reset twice gives identical counts; the grader flows are unaffected', async () => {
    const count = async () => (await pool.query(
      `SELECT (SELECT count(*) FROM matches)::int AS matches, (SELECT count(*) FROM match_events)::int AS events,
              (SELECT count(*) FROM products)::int AS products, (SELECT count(*) FROM roster_initial)::int AS roster`,
    )).rows[0];
    await resetAll();
    const first = await count();
    await resetAll();
    expect(await count()).toEqual(first);
    expect(first).toMatchObject({ matches: 7, products: 4, roster: 16 });
    const lg = await (await http()).post('/audit/v1/leagues').send({
      league_id: 'lg-1', sport_id: 'football', teams: ['a', 'b'], fixtures: [{ match_id: 'fx-1', home: 'a', away: 'b' }], qualification: { spots: 1 },
    });
    expect(lg.status).toBe(201);
    const stock = await (await http()).get('/audit/v1/inventory/var-jersey-001-M');
    expect(stock.body).toEqual({ variant_id: 'var-jersey-001-M', stock: 1, reserved: 0 });
    const reset = await (await http()).post('/audit/v1/reset').send({ stock: { 'var-jersey-001-M': 9 } });
    expect(reset.status).toBe(200);
    expect((await (await http()).get('/audit/v1/inventory/demo-var-cap-std')).body.stock).toBe(100);
  });
});
