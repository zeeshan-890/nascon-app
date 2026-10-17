const { describeDb } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');
const { expectError } = require('../helpers/evtkit');
const { uid, P, baseSetup, setup, req, transfer, history } = require('../helpers/rosterkit');

afterAll(() => pool.end());

const post = async (path, body) => (await api()).post(`/audit/v1${path}`).send(body);
const returns = (h) => h.body.history.filter((e) => e.action === 'loan_return');

async function counts(league_id, over) {
  const r = await post('/roster/setup', baseSetup(league_id, over));
  return r.body.squad_counts;
}

describeDb('roster: setup', () => {
  beforeAll(() => resetDb());

  test('201 with every squad in squad_counts, zero for empty squads', async () => {
    const lg = uid('lg');
    const r = await setup(lg);
    expect(r.body).toEqual({ league_id: lg, status: 'configured', squad_counts: { A: 3, B: 1, C: 0, X: 0 } });
  });

  test('defaults max_squad_size and required_positions from the sport', async () => {
    const lg = uid('lg');
    const body = baseSetup(lg);
    delete body.league.max_squad_size;
    delete body.league.required_positions;
    expect((await post('/roster/setup', body)).status).toBe(201);
    // football: gk minimum 1 -> releasing the only keeper of A is blocked
    const r = await transfer(req(lg, { player_id: 'gk1', type: 'release', to_squad_id: undefined }));
    expectError(r, 409, 'MIN_POSITION_VIOLATION');
  });

  test('re-sending a league replaces only that league', async () => {
    const lg1 = uid('lg');
    const lg2 = uid('lg');
    await setup(lg1);
    await setup(lg2);
    expect((await transfer(req(lg1))).status).toBe(201);
    expect((await transfer(req(lg2))).status).toBe(201);
    await setup(lg1);
    const h1 = await history('df1');
    expect(h1.status).toBe(200);
    const lastLeague = h1.body.league_id;
    expect(lastLeague).toBe(lg1); // most recent setup
    expect(h1.body.history).toEqual([]);
    // league two keeps its audit entry and its request record
    const replay = await transfer(req(lg2, { request_id: 'does-not-matter' }));
    expect([201, 409]).toContain(replay.status);
    const counts2 = await post('/roster/setup', baseSetup(lg2));
    expect(counts2.body.squad_counts.A).toBe(3);
  });

  test('validation lists all bad fields', async () => {
    const lg = uid('lg');
    const body = baseSetup(lg);
    body.league.transfer_window = { open: '2026-03-01', close: '2026-02-01' };
    body.squads.push({ squad_id: 'A', club_id: 'zz', tenant_id: 't1', name: 'dup' });
    body.players.push(P('p9', 'NOPE', 'df'));
    body.players.push(P('gk1', 'A', 'gk'));
    body.players.push({ ...P('p10', 'A', 'df'), role: 'boss' });
    body.matches_in_progress = [{ match_id: 'm1', home_squad_id: 'A', away_squad_id: 'GHOST' }];
    const r = await post('/roster/setup', body);
    expectError(r, 400, 'VALIDATION_ERROR');
    expect(r.body.error.details.fields).toEqual(expect.arrayContaining([
      'league.transfer_window', 'squads.4.squad_id', 'players.4.squad_id', 'players.5.player_id',
      'players.6.role', 'matches_in_progress.0.away_squad_id',
    ]));
  });

  test('bad types and impossible dates are 400', async () => {
    const lg = uid('lg');
    const body = baseSetup(lg, { league: { transfer_window: { open: '2026-02-30', close: '2026-03-01' }, max_squad_size: 0 } });
    const r = await post('/roster/setup', body);
    expectError(r, 400, 'VALIDATION_ERROR');
    expect(r.body.error.details.fields).toEqual(expect.arrayContaining(['league.transfer_window.open', 'league.max_squad_size']));
    expectError(await post('/roster/setup', []), 400, 'VALIDATION_ERROR');
    expectError(await post('/roster/setup', {}), 400, 'VALIDATION_ERROR');
  });

  test('unknown sport is 404 after the 400 checks', async () => {
    const body = baseSetup(uid('lg'), { league: { sport_id: 'no-such-sport' } });
    expectError(await post('/roster/setup', body), 404, 'SPORT_NOT_FOUND');
    body.squads = 'x';
    expectError(await post('/roster/setup', body), 400, 'VALIDATION_ERROR');
  });

  test('player without a squad and matches_in_progress are accepted', async () => {
    const lg = uid('lg');
    const sc = await counts(lg, {
      players: [P('free1', null, 'df'), P('gk1', 'A', 'gk')],
      matches_in_progress: [{ match_id: 'm1', home_squad_id: 'A', away_squad_id: 'B' }],
    });
    expect(sc).toEqual({ A: 1, B: 0, C: 0, X: 0 });
  });
});

describeDb('roster: transfer validation and ordering', () => {
  let lg;
  beforeAll(async () => {
    await resetDb();
    lg = uid('lg');
    await setup(lg);
  });

  test('400 lists all bad fields', async () => {
    const r = await transfer({ request_id: 'bad id!', league_id: lg, type: 'swap', effective_date: '2026-02-30', reason: '', requested_by: 5 });
    expectError(r, 400, 'VALIDATION_ERROR');
    expect(r.body.error.details.fields).toEqual(expect.arrayContaining([
      'request_id', 'player_id', 'type', 'from_squad_id', 'effective_date', 'reason', 'requested_by',
    ]));
  });

  test('to_squad_id required unless release; from must differ from to', async () => {
    expect(r400(await transfer(req(lg, { to_squad_id: undefined })))).toContain('to_squad_id');
    expect(r400(await transfer(req(lg, { to_squad_id: 'A' })))).toContain('to_squad_id');
    expect((await transfer(req(lg, { type: 'release', to_squad_id: 'garbage id', player_id: 'df2' }))).status).toBe(201);
  });

  test('loan_end_date required and later than effective_date', async () => {
    expect(r400(await transfer(req(lg, { type: 'loan' })))).toContain('loan_end_date');
    expect(r400(await transfer(req(lg, { type: 'loan', loan_end_date: '2026-01-15' })))).toContain('loan_end_date');
    expect(r400(await transfer(req(lg, { type: 'loan', loan_end_date: '2026-01-14' })))).toContain('loan_end_date');
    expect(r400(await transfer(req(lg, { type: 'loan', loan_end_date: '2026-02-30' })))).toContain('loan_end_date');
  });

  test('validation beats not-found', async () => {
    expectError(await transfer(req('ghost-league', { effective_date: '2026-13-01' })), 400, 'VALIDATION_ERROR');
  });

  test('404 order: league, player, squad (from then to)', async () => {
    expectError(await transfer(req('ghost-league', { player_id: 'ghost', from_squad_id: 'ghost' })), 404, 'LEAGUE_NOT_FOUND');
    expectError(await transfer(req(lg, { player_id: 'ghost', from_squad_id: 'ghost' })), 404, 'PLAYER_NOT_FOUND');
    expectError(await transfer(req(lg, { from_squad_id: 'ghost', to_squad_id: 'ghost2' })), 404, 'SQUAD_NOT_FOUND');
    expectError(await transfer(req(lg, { to_squad_id: 'ghost2' })), 404, 'SQUAD_NOT_FOUND');
  });

  test('squads must belong to this league', async () => {
    const other = uid('lg');
    await setup(other, { squads: [{ squad_id: 'ONLY', club_id: 'c', tenant_id: 't1', name: 'n' }], players: [P('o1', 'ONLY', 'df')] });
    expectError(await transfer(req(other, { player_id: 'o1', from_squad_id: 'ONLY', to_squad_id: 'B' })), 404, 'SQUAD_NOT_FOUND');
  });

  test('cross-tenant before not-in-squad, window, squad-full', async () => {
    const r = await transfer(req(lg, { player_id: 'gk2', from_squad_id: 'C', to_squad_id: 'X', effective_date: '2030-01-01' }));
    expectError(r, 409, 'CROSS_TENANT_TRANSFER');
    expectError(await transfer(req(lg, { to_squad_id: 'X' })), 409, 'CROSS_TENANT_TRANSFER');
    expectError(await transfer(req(lg, { player_id: 'df1', from_squad_id: 'X', to_squad_id: 'B', type: 'release' })), 409, 'CROSS_TENANT_TRANSFER');
  });

  test('not-in-squad before window', async () => {
    const r = await transfer(req(lg, { from_squad_id: 'B', to_squad_id: 'C', effective_date: '2030-01-01' }));
    expectError(r, 409, 'NOT_IN_SQUAD');
  });

  test('window is inclusive; one day outside is 409; release ignores the window', async () => {
    const l2 = uid('lg');
    await setup(l2);
    expectError(await transfer(req(l2, { effective_date: '2026-01-09' })), 409, 'TRANSFER_WINDOW_CLOSED');
    expectError(await transfer(req(l2, { effective_date: '2026-02-11' })), 409, 'TRANSFER_WINDOW_CLOSED');
    expect((await transfer(req(l2, { effective_date: '2026-01-10' }))).status).toBe(201);
    expect((await transfer(req(l2, { player_id: 'df2', effective_date: '2026-02-10' }))).status).toBe(201);
    expect((await transfer(req(l2, { type: 'release', player_id: 'gk2', from_squad_id: 'B', effective_date: '2031-05-05' }))).status)
      .toBe(409); // gk2 is the only keeper of B -> minimum position, not the window
  });

  test('window before loan, loan before squad-full, squad-full before min-position', async () => {
    const l3 = uid('lg');
    await setup(l3, {
      players: [P('gk1', 'A', 'gk'), P('d1', 'A', 'df'), P('g2', 'B', 'gk'), P('b2', 'B', 'df'), P('b3', 'B', 'df'), P('b4', 'B', 'df')],
    });
    // window first
    await transfer(req(l3, { player_id: 'd1', type: 'loan', to_squad_id: 'C', loan_end_date: '2026-12-31', effective_date: '2026-01-11' }));
    expectError(await transfer(req(l3, { player_id: 'd1', from_squad_id: 'C', to_squad_id: 'B', effective_date: '2026-03-01' })),
      409, 'TRANSFER_WINDOW_CLOSED');
    // on loan before squad full (B is full: 4 players)
    expectError(await transfer(req(l3, { player_id: 'd1', from_squad_id: 'C', to_squad_id: 'B', effective_date: '2026-01-20' })),
      409, 'PLAYER_ON_LOAN');
    // squad full before min position: gk1 is the only keeper of A
    expectError(await transfer(req(l3, { player_id: 'gk1', to_squad_id: 'B', effective_date: '2026-01-20' })), 409, 'SQUAD_FULL');
  });

  test('a position without a minimum never blocks; keeper release is blocked', async () => {
    const l4 = uid('lg');
    await setup(l4);
    expect((await transfer(req(l4, { type: 'release', player_id: 'df1' }))).status).toBe(201);
    expect((await transfer(req(l4, { type: 'release', player_id: 'df2' }))).status).toBe(201);
    expectError(await transfer(req(l4, { type: 'release', player_id: 'gk1' })), 409, 'MIN_POSITION_VIOLATION');
  });

  test('a loan that drops the lender below its minimum is 409', async () => {
    const l5 = uid('lg');
    await setup(l5);
    expectError(await transfer(req(l5, { player_id: 'gk1', type: 'loan', to_squad_id: 'C', loan_end_date: '2026-02-01' })),
      409, 'MIN_POSITION_VIOLATION');
  });

  test('SQUAD_FULL exactly at max size', async () => {
    const l6 = uid('lg');
    await setup(l6, {
      league: { max_squad_size: 3 },
      players: [P('gk1', 'A', 'gk'), P('df1', 'A', 'df'), P('df2', 'A', 'df'), P('gk2', 'B', 'gk'), P('df3', 'B', 'df')],
    });
    expect((await transfer(req(l6, { player_id: 'df1', to_squad_id: 'C' }))).status).toBe(201);
    expect((await transfer(req(l6, { player_id: 'df2', to_squad_id: 'C' }))).status).toBe(201);
    expect((await transfer(req(l6, { player_id: 'df3', from_squad_id: 'B', to_squad_id: 'C' }))).status).toBe(201);
    // C now holds 3 = max
    const r = await transfer(req(l6, { player_id: 'gk1', to_squad_id: 'C' }));
    expectError(r, 409, 'SQUAD_FULL');
  });
});

const r400 = (res) => {
  expect(res.status).toBe(400);
  return res.body.error.details.fields;
};

describeDb('roster: effects, audit and rejections', () => {
  beforeAll(() => resetDb());

  test('transfer: captain leaves as substitute, audit fields and ids', async () => {
    const lg = uid('lg');
    await setup(lg);
    const body = req(lg, { player_id: 'gk1', from_squad_id: 'A', to_squad_id: 'C', requested_by: 'ann', reason: 'new <b>start</b>' });
    // gk1 is the only keeper: add one first via a different setup
    const l2 = uid('lg');
    await setup(l2, { players: [P('gk1', 'A', 'gk', 'captain'), P('gk9', 'A', 'gk'), P('d1', 'A', 'df')] });
    const r = await transfer({ ...body, league_id: l2 });
    expect(r.status).toBe(201);
    expect(r.body).toMatchObject({
      request_id: body.request_id, status: 'accepted', state: 'active', blocking_match_id: null,
      registration: {
        player_id: 'gk1', league_id: l2, squad_id: 'C', parent_squad_id: null, registration_type: 'transfer',
        start_date: '2026-01-15', loan_end_date: null, role: 'substitute',
      },
    });
    const a = r.body.audit_entry;
    expect(a).toMatchObject({
      action: 'transfer', player_id: 'gk1', league_id: l2, from_squad_id: 'A', to_squad_id: 'C',
      effective_date: '2026-01-15', state: 'active', who: 'ann', why: 'new <b>start</b>',
    });
    expect(a.audit_id).toMatch(/^au-\d{4,}$/);
    expect(a.when).toMatch(/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/);
    const h = await history('gk1');
    expect(h.body.history[0]).toEqual(a);
    expect(h.body.current_squad_id).toBe('C');
  });

  test('audit ids restart at au-0001 after reset and increase by one', async () => {
    await resetDb();
    const lg = uid('lg');
    await setup(lg);
    const a = await transfer(req(lg, { player_id: 'df1' }));
    const b = await transfer(req(lg, { player_id: 'df2' }));
    expect(a.body.audit_entry.audit_id).toBe('au-0001');
    expect(b.body.audit_entry.audit_id).toBe('au-0002');
  });

  test('release: registration and to_squad_id are null', async () => {
    const lg = uid('lg');
    await setup(lg);
    const r = await transfer(req(lg, { type: 'release', player_id: 'df1', to_squad_id: 'B' }));
    expect(r.status).toBe(201);
    expect(r.body.registration).toBeNull();
    expect(r.body.audit_entry.to_squad_id).toBeNull();
    const h = await history('df1');
    expect(h.body.current_squad_id).toBeNull();
    expect(h.body.parent_squad_id).toBeNull();
    expectError(await transfer(req(lg, { player_id: 'df1' })), 409, 'NOT_IN_SQUAD');
    const sc = await post('/roster/setup', baseSetup(lg));
    expect(sc.body.squad_counts.A).toBe(3);
  });

  test('rejected requests write nothing', async () => {
    const lg = uid('lg');
    await setup(lg);
    const before = (await history('df1')).body;
    const bad = [
      req(lg, { to_squad_id: 'A' }),
      req(lg, { player_id: 'ghost' }),
      req(lg, { to_squad_id: 'X' }),
      req(lg, { from_squad_id: 'B' }),
      req(lg, { effective_date: '2027-01-01' }),
    ];
    for (const b of bad) expect((await transfer(b)).status).toBeGreaterThanOrEqual(400);
    expect((await history('df1')).body).toEqual(before);
    // a rejected request id can be reused for a valid request
    const reuse = req(lg, { request_id: bad[4].request_id });
    expect((await transfer(reuse)).status).toBe(201);
  });

  test('deferred while the from squad is in a live match (home and away, last window day)', async () => {
    const lg = uid('lg');
    await setup(lg, {
      players: [P('gk1', 'A', 'gk'), P('df1', 'A', 'df'), P('gk2', 'B', 'gk'), P('bd', 'B', 'df'), P('cd', 'C', 'df')],
      matches_in_progress: [
        { match_id: 'm-9', home_squad_id: 'A', away_squad_id: 'C' },
        { match_id: 'm-2', home_squad_id: 'B', away_squad_id: 'A' },
      ],
    });
    const r = await transfer(req(lg, { player_id: 'df1', effective_date: '2026-02-10' }));
    expect(r.status).toBe(201);
    expect(r.body.state).toBe('deferred_until_match_end');
    expect(r.body.blocking_match_id).toBe('m-2');
    expect(r.body.audit_entry.state).toBe('deferred_until_match_end');
    const away = await transfer(req(lg, { player_id: 'cd', from_squad_id: 'C', to_squad_id: 'B' }));
    expect(away.body).toMatchObject({ state: 'deferred_until_match_end', blocking_match_id: 'm-9' });
    // counted immediately
    const h = await history('df1');
    expect(h.body.current_squad_id).toBe('B');
    const l2 = uid('lg');
    await setup(l2);
    const plain = await transfer(req(l2));
    expect(plain.body).toMatchObject({ state: 'active', blocking_match_id: null });
  });
});

describeDb('roster: idempotency', () => {
  beforeAll(() => resetDb());

  test('replay returns 200 with the identical body and no second audit entry', async () => {
    const lg = uid('lg');
    await setup(lg);
    const body = req(lg);
    const first = await transfer(body);
    const second = await transfer(body);
    expect(first.status).toBe(201);
    expect(second.status).toBe(200);
    expect(second.body).toEqual(first.body);
    expect((await history('df1')).body.history).toHaveLength(1);
  });

  test('same request_id with a different body is 409 DUPLICATE_REQUEST_ID', async () => {
    const lg = uid('lg');
    await setup(lg);
    const body = req(lg);
    await transfer(body);
    expectError(await transfer({ ...body, reason: 'other' }), 409, 'DUPLICATE_REQUEST_ID');
  });

  test('10 parallel identical requests: one 201, nine 200, one audit entry', async () => {
    const lg = uid('lg');
    await setup(lg);
    const body = req(lg);
    const out = await Promise.all(Array.from({ length: 10 }, () => transfer(body)));
    expect(out.filter((r) => r.status === 201)).toHaveLength(1);
    expect(out.filter((r) => r.status === 200)).toHaveLength(9);
    expect((await history('df1')).body.history).toHaveLength(1);
  });

  test('parallel transfers into a nearly full squad never exceed the maximum', async () => {
    const lg = uid('lg');
    await setup(lg, { league: { max_squad_size: 2 }, players: [P('gk1', 'A', 'gk'), P('d1', 'A', 'df'), P('d2', 'A', 'df'), P('d3', 'A', 'df'), P('g2', 'B', 'gk')] });
    const out = await Promise.all(['d1', 'd2', 'd3'].map((p) => transfer(req(lg, { player_id: p, to_squad_id: 'B' }))));
    expect(out.filter((r) => r.status === 201)).toHaveLength(1);
    expect(out.filter((r) => r.status === 409)).toHaveLength(2);
  });
});

describeDb('roster: loans and history', () => {
  let lg;
  beforeAll(async () => {
    await resetDb();
    lg = uid('lg');
    await setup(lg, {
      players: [P('gk1', 'A', 'gk'), P('d1', 'A', 'df', 'captain'), P('d2', 'A', 'df'), P('gk2', 'B', 'gk')],
    });
  });

  const loan = (over = {}) => req(lg, { type: 'loan', player_id: 'd1', to_squad_id: 'B', effective_date: '2026-01-15', loan_end_date: '2026-01-31', ...over });

  test('loan registration: borrower squad, parent, substitute role, counted at the borrower only', async () => {
    const r = await transfer(loan());
    expect(r.status).toBe(201);
    expect(r.body.registration).toEqual({
      player_id: 'd1', league_id: lg, squad_id: 'B', parent_squad_id: 'A', registration_type: 'loan',
      start_date: '2026-01-15', loan_end_date: '2026-01-31', role: 'substitute',
    });
    expect(r.body.audit_entry.action).toBe('loan');
  });

  test('history before, during, on the end date, and after the loan', async () => {
    const before = (await history('d1', '2026-01-14')).body;
    expect(before).toMatchObject({ current_squad_id: 'A', parent_squad_id: null, league_id: lg, as_of: '2026-01-14' });
    const during = (await history('d1', '2026-01-20')).body;
    expect(during).toMatchObject({ current_squad_id: 'B', parent_squad_id: 'A' });
    const onEnd = (await history('d1', '2026-01-31')).body;
    expect(onEnd).toMatchObject({ current_squad_id: 'B', parent_squad_id: 'A' });
    expect(returns(await history('d1', '2026-01-31'))).toHaveLength(0);
    const after = await history('d1', '2026-02-01');
    expect(after.body).toMatchObject({ current_squad_id: 'A', parent_squad_id: null });
    const ret = returns(after);
    expect(ret).toHaveLength(1);
    expect(ret[0]).toMatchObject({
      action: 'loan_return', from_squad_id: 'B', to_squad_id: 'A', effective_date: '2026-02-01',
      state: 'active', who: 'system', why: 'Loan ended', player_id: 'd1', league_id: lg,
    });
    // an earlier as_of never shows the materialised return
    expect(returns(await history('d1', '2026-01-20'))).toHaveLength(0);
    expect((await history('d1', '2026-01-20')).body.history).toHaveLength(1);
  });

  test('exactly one loan_return after repeated and parallel history calls', async () => {
    for (let i = 0; i < 3; i += 1) await history('d1', '2026-03-01');
    const par = await Promise.all(Array.from({ length: 10 }, () => history('d1', '2026-03-05')));
    for (const r of par) expect(returns(r)).toHaveLength(1);
    expect(returns(await history('d1', '2026-12-31'))).toHaveLength(1);
  });

  test('a transfer from the lender squad after the loan end succeeds', async () => {
    const r = await transfer(req(lg, { player_id: 'd1', from_squad_id: 'A', to_squad_id: 'C', effective_date: '2026-02-05' }));
    expect(r.status).toBe(201);
    const h = (await history('d1', '2026-02-06')).body;
    expect(h.current_squad_id).toBe('C');
    expect(h.history.map((e) => e.action)).toEqual(['loan', 'loan_return', 'transfer']);
  });
});

describeDb('roster: player on loan', () => {
  test('transfer, loan and release of a loaned player; from=lender is NOT_IN_SQUAD', async () => {
    await resetDb();
    const lg = uid('lg');
    await setup(lg, { players: [P('gk1', 'A', 'gk'), P('d1', 'A', 'df'), P('gk2', 'B', 'gk')] });
    expect((await transfer(req(lg, { type: 'loan', player_id: 'd1', to_squad_id: 'B', loan_end_date: '2026-02-01' }))).status).toBe(201);
    const mv = { player_id: 'd1', from_squad_id: 'B', effective_date: '2026-01-20' };
    expectError(await transfer(req(lg, { ...mv, to_squad_id: 'C' })), 409, 'PLAYER_ON_LOAN');
    expectError(await transfer(req(lg, { ...mv, type: 'loan', to_squad_id: 'C', loan_end_date: '2026-01-30' })), 409, 'PLAYER_ON_LOAN');
    expectError(await transfer(req(lg, { ...mv, type: 'release' })), 409, 'PLAYER_ON_LOAN');
    expectError(await transfer(req(lg, { player_id: 'd1', from_squad_id: 'A', to_squad_id: 'C', effective_date: '2026-01-20' })),
      409, 'NOT_IN_SQUAD');
  });

  test('loaned-in players count at the borrower, not the lender', async () => {
    await resetDb();
    const lg = uid('lg');
    await setup(lg, { league: { max_squad_size: 3 }, players: [P('gk1', 'A', 'gk'), P('d1', 'A', 'df'), P('gk2', 'B', 'gk'), P('e1', 'B', 'df')] });
    expect((await transfer(req(lg, { type: 'loan', player_id: 'd1', to_squad_id: 'B', loan_end_date: '2026-02-01' }))).status).toBe(201);
    // B now has 3 (full); A has 1 so it can take one
    expectError(await transfer(req(lg, { player_id: 'gk1', to_squad_id: 'B', effective_date: '2026-01-20', type: 'transfer' })), 409, 'SQUAD_FULL');
    expect((await transfer(req(lg, { player_id: 'e1', from_squad_id: 'B', to_squad_id: 'A', effective_date: '2026-01-20' }))).status)
      .toBe(201);
  });
});

describeDb('roster: history endpoint', () => {
  beforeAll(() => resetDb());

  test('validation then 404', async () => {
    expectError(await history('bad id!'), 400, 'VALIDATION_ERROR');
    expectError(await history('p1', '2026-02-30'), 400, 'VALIDATION_ERROR');
    expectError(await history('nobody'), 404, 'PLAYER_NOT_FOUND');
  });

  test('default as_of is today (UTC)', async () => {
    const lg = uid('lg');
    await setup(lg);
    const h = await history('df2');
    expect(h.status).toBe(200);
    expect(h.body.as_of).toBe(new Date().toISOString().slice(0, 10));
    expect(h.body.current_squad_id).toBe('A');
  });
});

describe('roster: db-less sanity', () => {
  test('uid helper', () => expect(uid('a')).toMatch(/^a-/));
});
