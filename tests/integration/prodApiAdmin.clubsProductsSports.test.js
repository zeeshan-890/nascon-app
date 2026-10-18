const { describeDb } = require('../helpers/db');
const { resetAll, as, http, pool } = require('../helpers/mobilekit');
const { uid, asToken, foreign, ROLES } = require('../helpers/adminkit');

afterAll(() => pool.end());

const fieldsOf = (r) => r.body.error.details.fields;
const expectErr = (r, status, code) => {
  expect(r.status).toBe(status);
  expect(r.body.error.code).toBe(code);
  expect(typeof r.body.error.message).toBe('string');
  expect(r.body.error.details).toEqual(expect.any(Object));
};

describeDb('PATCH /clubs/:id (FR-2.1)', () => {
  beforeAll(() => resetAll());

  const profile = {
    name: 'Alpha FC',
    logo_url: 'https://cdn.example.test/alpha.png',
    venue: { name: 'Alpha Arena', lat: 31.52, lng: 74.35 },
    socials: { instagram: '@alphafc', x: '@alpha' },
    staff: [{ name: 'Ayesha Khan', role: 'coach' }, { name: 'Bilal Ahmed', role: 'physio' }],
  };

  test('the club manager updates its own club and gets the GET /clubs/:id shape back', async () => {
    const before = (await as('manager').get('/api/v1/clubs/club-alpha-1')).body;
    const r = await as('manager').patch('/api/v1/clubs/club-alpha-1', profile);
    expect(r.status).toBe(200);
    expect(Object.keys(r.body).sort()).toEqual(Object.keys(before).sort());
    expect(r.body).toMatchObject({ club_id: 'club-alpha-1', name: 'Alpha FC', logo_url: profile.logo_url, venue: profile.venue, socials: profile.socials });
    expect(r.body.staff).toEqual([{ name: 'Ayesha Khan', role: 'coach' }, { name: 'Bilal Ahmed', role: 'physio' }]);
    expect(r.body.roster).toEqual(before.roster); // untouched
    expect(r.body.stats).toEqual(before.stats);
    const again = await as('viewer').get('/api/v1/clubs/club-alpha-1');
    expect(again.body).toEqual(r.body); // cache was invalidated
    expect((await as('viewer').get('/api/v1/clubs')).body.find((c) => c.club_id === 'club-alpha-1')).toMatchObject({ name: 'Alpha FC', logo_url: profile.logo_url });
  });

  test('partial update keeps the other fields; repeating the same patch is idempotent', async () => {
    const one = await as('manager').patch('/api/v1/clubs/club-alpha-1', { name: 'Alpha United' });
    expect(one.body).toMatchObject({ name: 'Alpha United', venue: profile.venue, socials: profile.socials });
    expect(one.body.staff).toHaveLength(2);
    const two = await as('manager').patch('/api/v1/clubs/club-alpha-1', { name: 'Alpha United' });
    expect(two.body).toEqual(one.body);
    const cleared = await as('manager').patch('/api/v1/clubs/club-alpha-1', { staff: [], socials: {} });
    expect(cleared.body.staff).toEqual([]);
    expect(cleared.body.socials).toEqual({});
  });

  test('the super admin may update any club', async () => {
    const r = await as('admin').patch('/api/v1/clubs/club-beta-1', { name: 'Beta Club Renamed', staff: [{ name: 'Z', role: 'manager' }] });
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({ club_id: 'club-beta-1', name: 'Beta Club Renamed', tenant_id: 'tenant-beta' });
  });

  test('validation lists every bad field', async () => {
    const r = await as('manager').patch('/api/v1/clubs/club-alpha-1', {
      name: '', logo_url: 'javascript:alert(1)', venue: { name: '', lat: 91, lng: 'x' }, socials: { a: 1 },
      staff: [{ name: 'A', role: 'goalkeeper' }, { name: '', role: 'coach' }],
    });
    expectErr(r, 400, 'VALIDATION_ERROR');
    expect(fieldsOf(r)).toEqual(expect.arrayContaining([
      'name', 'logo_url', 'venue.name', 'venue.lat', 'venue.lng', 'socials.a', 'staff.0.role', 'staff.1.name',
    ]));
    expect(fieldsOf(await as('manager').patch('/api/v1/clubs/club-alpha-1', {}))).toEqual(['body']);
    expect(fieldsOf(await as('manager').patch('/api/v1/clubs/club-alpha-1', 'x'))).toEqual(['body']);
    const dup = { staff: [{ name: 'A', role: 'coach' }, { name: 'A', role: 'coach' }] };
    expect(fieldsOf(await as('manager').patch('/api/v1/clubs/club-alpha-1', dup))).toEqual(['staff.1']);
    const tooMany = Object.fromEntries(Array.from({ length: 21 }, (_, i) => [`k${i}`, 'v']));
    expect(fieldsOf(await as('manager').patch('/api/v1/clubs/club-alpha-1', { socials: tooMany }))).toEqual(['socials']);
    // nothing was applied
    expect((await as('viewer').get('/api/v1/clubs/club-alpha-1')).body.name).toBe('Alpha United');
  });

  test('404, malformed id, roles, other clubs, tenant isolation, authentication', async () => {
    expectErr(await as('admin').patch('/api/v1/clubs/nope', { name: 'x' }), 404, 'NOT_FOUND');
    expect(fieldsOf(await as('admin').patch('/api/v1/clubs/bad%20id', { name: 'x' }))).toEqual(['club_id']);
    for (const role of ['organizer', 'player', 'viewer']) {
      expectErr(await as(role).patch('/api/v1/clubs/club-alpha-1', { name: 'x' }), 403, 'FORBIDDEN');
    }
    // a manager of another club of the same tenant, and of another tenant
    expectErr(await as('manager').patch('/api/v1/clubs/club-alpha-2', { name: 'x' }), 403, 'FORBIDDEN');
    expectErr(await as('manager').patch('/api/v1/clubs/club-beta-1', { name: 'x' }), 403, 'FORBIDDEN');
    expectErr(await asToken(foreign('club_manager')).patch('/api/v1/clubs/club-alpha-1', { name: 'x' }), 403, 'FORBIDDEN');
    expectErr(await asToken(null).patch('/api/v1/clubs/club-alpha-1', { name: 'x' }), 401, 'UNAUTHENTICATED');
    expect((await as('viewer').get('/api/v1/clubs/club-alpha-2')).body.name).toBe('Alpha Club 2');
  });
});

describeDb('GET /products/:id (FR-2.3)', () => {
  beforeAll(() => resetAll());

  test('returns the list item shape with live variant stock for every role', async () => {
    const list = (await as('viewer').get('/api/v1/products')).body;
    expect(list.length).toBeGreaterThan(0);
    for (const role of ROLES) {
      for (const item of list) {
        const r = await as(role).get(`/api/v1/products/${item.product_id}`);
        expect(r.status).toBe(200);
        expect(r.body).toEqual(item);
      }
    }
    const jersey = (await as('viewer').get('/api/v1/products/prod-jersey-001')).body;
    expect(jersey.variants.length).toBeGreaterThan(0);
    expect(Object.keys(jersey).sort()).toEqual(['category', 'image_url', 'name', 'product_id', 'variants']);
    expect(Object.keys(jersey.variants[0]).sort()).toEqual(['color', 'price_minor', 'size', 'stock', 'variant_id']);
  });

  test('stock is live while the list may be cached', async () => {
    const variant = (await as('viewer').get('/api/v1/products/prod-jersey-001')).body.variants.find((v) => v.variant_id === 'var-jersey-001-L');
    await pool.query(`UPDATE variants SET stock = 77 WHERE variant_id = 'var-jersey-001-L'`);
    const after = (await as('viewer').get('/api/v1/products/prod-jersey-001')).body.variants.find((v) => v.variant_id === 'var-jersey-001-L');
    expect(variant.stock).not.toBe(77);
    expect(after.stock).toBe(77);
    // a scheduled price is applied like in the list
    await pool.query(`INSERT INTO price_schedules (variant_id, price_minor, effective_from) VALUES ('var-jersey-001-L', 1234, now() - interval '1 hour')`);
    const priced = (await as('viewer').get('/api/v1/products/prod-jersey-001')).body.variants.find((v) => v.variant_id === 'var-jersey-001-L');
    expect(priced.price_minor).toBe(1234);
  });

  test('404, malformed id, tenant isolation, authentication', async () => {
    expectErr(await as('viewer').get('/api/v1/products/nope'), 404, 'PRODUCT_NOT_FOUND');
    expect(fieldsOf(await as('viewer').get('/api/v1/products/bad%20id'))).toEqual(['product_id']);
    expectErr(await asToken(foreign()).get('/api/v1/products/prod-jersey-001'), 403, 'FORBIDDEN');
    expectErr(await asToken(null).get('/api/v1/products/prod-jersey-001'), 401, 'UNAUTHENTICATED');
    expect((await as('admin').get('/api/v1/products/prod-jersey-001')).status).toBe(200);
  });
});

describeDb('GET /sports and POST /sports (FR-2.3 / sports API)', () => {
  beforeAll(() => resetAll());

  const sport = (id, extra = {}) => ({
    sport_id: id,
    name: 'Test Sport',
    scoring_model: 'event',
    periods: { count: 2, duration_minutes: 30 },
    scoring_events: { point: { points: 1 } },
    draw_allowed: true,
    overtime: { policy: 'none' },
    roster: { min_squad_size: 2, max_squad_size: 10, required_positions: {} },
    ...extra,
  });

  test('every role lists the sports; pagination is capped at 100 and counted in X-Total-Count', async () => {
    for (const role of ROLES) {
      const r = await as(role).get('/api/v1/sports');
      expect(r.status).toBe(200);
      expect(r.body.map((s) => s.sport_id)).toEqual(['basketball', 'cricket', 'esports', 'football']);
      expect(r.headers['x-total-count']).toBe('4');
      expect(r.body[0]).toMatchObject({ sport_id: 'basketball', scoring_model: 'event' });
    }
    const page = await as('viewer').get('/api/v1/sports?limit=2&offset=1');
    expect(page.body.map((s) => s.sport_id)).toEqual(['cricket', 'esports']);
    expect(page.headers['x-total-count']).toBe('4');
    expect((await as('viewer').get('/api/v1/sports?limit=100000')).body.length).toBeLessThanOrEqual(100);
    expectErr(await asToken(null).get('/api/v1/sports'), 401, 'UNAUTHENTICATED');
  });

  test('the super admin creates a sport: 201 with the normalised config, readable everywhere', async () => {
    const id = uid('sp');
    const r = await as('admin').post('/api/v1/sports', sport(id));
    expect(r.status).toBe(201);
    expect(r.body).toMatchObject({ sport_id: id, status: 'created' });
    expect(r.body.config).toMatchObject({ sport_id: id, scoring_model: 'event', draw_allowed: true });
    expect(r.body.config.tie_break_order).toEqual(['h2h_points', 'h2h_diff', 'overall_diff', 'scored', 'fair_play', 'draw_lots']);
    expect((await as('viewer').get(`/api/v1/sports/${id}`)).body).toEqual(r.body.config);
    expect((await as('viewer').get('/api/v1/sports')).body.map((s) => s.sport_id)).toContain(id);
    // the audit endpoint sees the same sport
    const audit = await (await http()).post('/audit/v1/sports').send(sport(id));
    expect(audit.status).toBe(409);
  });

  test('same normalisation as /audit/v1/sports', async () => {
    const id = uid('sp');
    const prod = await as('admin').post('/api/v1/sports', sport(id, { tie_break_order: ['scored'] }));
    const other = uid('sp');
    const audit = await (await http()).post('/audit/v1/sports').send(sport(other, { tie_break_order: ['scored'] }));
    expect(prod.body.config.tie_break_order).toEqual(audit.body.config.tie_break_order);
    expect({ ...prod.body.config, sport_id: 'x' }).toEqual({ ...audit.body.config, sport_id: 'x' });
  });

  test('replay: identical definition is 200, a different one is 409 SPORT_EXISTS', async () => {
    const id = uid('sp');
    const first = await as('admin').post('/api/v1/sports', sport(id));
    const again = await as('admin').post('/api/v1/sports', sport(id));
    expect(again.status).toBe(200);
    expect(again.body.config).toEqual(first.body.config);
    expectErr(await as('admin').post('/api/v1/sports', sport(id, { name: 'Changed' })), 409, 'SPORT_EXISTS');
    expectErr(await as('admin').post('/api/v1/sports', sport('football')), 409, 'SPORT_EXISTS');
  });

  test('validation: every bad field is listed', async () => {
    const total = async () => (await as('viewer').get('/api/v1/sports')).headers['x-total-count'];
    const count = await total();
    const r = await as('admin').post('/api/v1/sports', sport('A', { name: '', scoring_model: 'nope', draw_allowed: 'yes' }));
    expectErr(r, 400, 'VALIDATION_ERROR');
    expect(fieldsOf(r)).toEqual(expect.arrayContaining(['sport_id', 'name', 'scoring_model', 'draw_allowed']));
    expect(fieldsOf(await as('admin').post('/api/v1/sports', 'x'))).toEqual(['body']);
    expect(fieldsOf(await as('admin').post('/api/v1/sports', {}))).toEqual(expect.arrayContaining(['sport_id', 'name', 'scoring_model']));
    expect(await total()).toBe(count); // nothing was stored
  });

  test('only the super admin writes; the others get 403, no token 401', async () => {
    for (const role of ['organizer', 'manager', 'player', 'viewer']) {
      expectErr(await as(role).post('/api/v1/sports', sport(uid('sp'))), 403, 'FORBIDDEN');
    }
    expectErr(await asToken(foreign('league_organizer')).post('/api/v1/sports', sport(uid('sp'))), 403, 'FORBIDDEN');
    expectErr(await asToken(null).post('/api/v1/sports', sport(uid('sp'))), 401, 'UNAUTHENTICATED');
  });
});
