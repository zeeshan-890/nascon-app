const { describeDb } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');
const { uid, mk, getScore } = require('../helpers/evtkit');
const rk = require('../helpers/rosterkit');
const { sign } = require('../../backend/src/domain/crypto');
const offlineSync = require('../../backend/src/services/offlineSyncService');

afterAll(() => pool.end());

let tick = 0;
const at = () => `2026-03-01T00:${String(Math.floor(tick / 60) % 60).padStart(2, '0')}:${String(tick++ % 60).padStart(2, '0')}Z`;
const sync = async (device_id, actions) => (await api()).post('/audit/v1/offline-sync').send({ device_id, actions });
const ev = (match_id, type, payload, base_version, over = {}) => {
  const event_id = uid('e');
  return { action_id: uid('act'), type: 'score_event', queued_at: at(), base_version, payload: { event_id, match_id, type, payload }, ...over };
};
const tr = (body, base_version, over = {}) => ({
  action_id: uid('act'), type: 'roster_transfer', queued_at: at(), base_version, payload: body, ...over,
});
const init = (match_id, extra = {}) => ev(match_id, 'match_init', { sport_id: 'football', home_team_id: 'H', away_team_id: 'A', ...extra }, 0);
const codes = (r) => r.body.results.map((x) => x.error_code);

describe('offline-sync envelope', () => {
  test('400 with field paths, before any DB work', async () => {
    const r = await sync('dev', [{ action_id: 'a', type: 'x', queued_at: 'bad', base_version: -1, payload: 1 }]);
    expect(r.status).toBe(400);
    expect(r.body.error.code).toBe('VALIDATION_ERROR');
    expect(r.body.error.details.fields).toEqual(['actions.0.type', 'actions.0.queued_at', 'actions.0.base_version', 'actions.0.payload']);
    expect((await sync('dev', [])).status).toBe(400);
    expect((await sync('', [ev('m', 'match_start', {}, 0)])).status).toBe(400);
    expect((await sync('dev', Array.from({ length: 101 }, () => ev('m', 'match_start', {}, 0)))).status).toBe(400);
  });
});

describeDb('offline-sync: score events', () => {
  beforeAll(() => resetDb());

  test('out-of-order queue is sorted; results keep request order; signature matches', async () => {
    const m = uid('m');
    const a1 = init(m);
    const a2 = ev(m, 'match_start', {}, 1);
    const a3 = ev(m, 'score', { team_id: 'H', event_type: 'goal' }, 2);
    // request order: a3, a1, a2 (queued order a1 < a2 < a3)
    const r = await sync(uid('dev'), [a3, a1, a2]);
    expect(r.status).toBe(200);
    expect(r.body.results.map((x) => x.action_id)).toEqual([a3.action_id, a1.action_id, a2.action_id]);
    expect(r.body.results.map((x) => x.processed_order)).toEqual([3, 1, 2]);
    expect(r.body.results.map((x) => x.status)).toEqual(['applied', 'applied', 'applied']);
    expect(r.body.results.map((x) => x.resolution)).toEqual(['appended', 'appended', 'appended']);
    expect(r.body.results.map((x) => x.server_version)).toEqual([3, 1, 2]);
    expect(r.body.results[0].signature).toBe(sign(a3.payload.event_id, a3.payload.payload));
    expect(r.body.summary).toEqual({ applied: 3, rejected: 0, duplicate: 0 });
    const score = (await getScore(m)).body;
    expect(score.version).toBe(3);
    expect(score.events.map((e) => e.sequence)).toEqual([1, 2, 3]);
  });

  test('equal queued_at ties keep array order', async () => {
    const m = uid('m');
    const q = '2026-03-02T00:00:00Z';
    const list = [init(m), ev(m, 'match_start', {}, 1), ev(m, 'score', { team_id: 'H', event_type: 'goal' }, 2)].map((a) => ({ ...a, queued_at: q }));
    const r = await sync(uid('dev'), list);
    expect(r.body.results.map((x) => x.processed_order)).toEqual([1, 2, 3]);
    expect(r.body.summary.applied).toBe(3);
  });

  test('base_version: lower rebases, higher is rejected', async () => {
    const m = uid('m');
    const dev = uid('dev');
    await sync(dev, [init(m), ev(m, 'match_start', {}, 1)]);
    const low = ev(m, 'score', { team_id: 'A', event_type: 'goal' }, 0);
    const high = ev(m, 'score', { team_id: 'A', event_type: 'goal' }, 9);
    const exact = ev(m, 'score', { team_id: 'H', event_type: 'goal' }, 3);
    const r = await sync(dev, [low, high, exact]);
    expect(r.body.results[0]).toMatchObject({ status: 'applied', resolution: 'rebased_append', server_version: 3 });
    expect(r.body.results[1]).toMatchObject({ status: 'rejected', resolution: 'server_wins', error_code: 'INVALID_BASE_VERSION', server_version: 3, signature: null });
    expect(r.body.results[2]).toMatchObject({ status: 'applied', resolution: 'appended', server_version: 4 });
  });

  test('rule rejections surface their codes; failures do not roll back others', async () => {
    const m = uid('m');
    const other = uid('m');
    await mk(other, 'football').begin();
    const idle = uid('m');
    await mk(idle, 'football').init();
    const target = (await mk(other, 'football').score('H', 'goal')).body;
    const bad = [
      ev(idle, 'score', { team_id: 'H', event_type: 'goal' }, 1), // not started
      ev(m, 'match_start', {}, 0), // unknown match
      ev(other, 'compensation', { compensates: uid('none'), reason: 'x' }, 3),
      ev(other, 'score', { team_id: 'H', event_type: 'not_a_real_event' }, 3),
      ev(idle, 'compensation', { compensates: target.event_id, reason: 'x' }, 1),
    ];
    const ok = ev(other, 'score', { team_id: 'A', event_type: 'goal' }, 3);
    const r = await sync(uid('dev'), [...bad, ok]);
    expect(codes(r)).toEqual(['MATCH_NOT_IN_PROGRESS', 'MATCH_NOT_FOUND', 'EVENT_NOT_FOUND', 'UNKNOWN_EVENT_TYPE', 'INVALID_COMPENSATION', null]);
    expect(r.body.results[1].server_version).toBe(0);
    expect(r.body.results[5].status).toBe('applied');
    expect(r.body.summary).toEqual({ applied: 1, rejected: 5, duplicate: 0 });
  });

  test('payload problems are per-action VALIDATION_ERROR; unknown ids beat bad base versions', async () => {
    const r = await sync(uid('dev'), [
      { action_id: uid('a'), type: 'score_event', queued_at: at(), base_version: 0, payload: { nope: 1 } },
      ev(uid('ghost'), 'match_start', {}, 50),
      ev(uid('m'), 'match_init', { sport_id: 'no-such-sport', home_team_id: 'H', away_team_id: 'A' }, 7),
      ev(uid('m'), 'match_init', { sport_id: 'football', home_team_id: 'H', away_team_id: 'A', league_id: uid('nolg') }, 7),
    ]);
    expect(codes(r)).toEqual(['VALIDATION_ERROR', 'MATCH_NOT_FOUND', 'SPORT_NOT_FOUND', 'LEAGUE_NOT_FOUND']);
    expect(r.body.results.map((x) => x.server_version)).toEqual([0, 0, 0, 0]);
  });

  test('duplicates: identical event, repeated action_id, DUPLICATE_EVENT_ID, whole re-send', async () => {
    const m = uid('m');
    const dev = uid('dev');
    const a1 = init(m);
    const a2 = ev(m, 'match_start', {}, 1);
    const sameAction = { ...a2, queued_at: at() };
    const r1 = await sync(dev, [a1, a2, sameAction]);
    expect(r1.body.results.map((x) => x.status)).toEqual(['applied', 'applied', 'duplicate']);
    expect(r1.body.results[2]).toMatchObject({ resolution: 'skipped_duplicate', error_code: null, server_version: 2 });

    // re-sending the entire request
    const r2 = await sync(dev, [a1, a2]);
    expect(r2.body.results.map((x) => x.status)).toEqual(['duplicate', 'duplicate']);
    expect(r2.body.summary).toEqual({ applied: 0, rejected: 0, duplicate: 2 });
    expect((await getScore(m)).body.version).toBe(2);

    // same event already present (different action, other device) -> duplicate, same id different body -> rejected
    const same = { ...a2, action_id: uid('act') };
    const clash = { ...a2, action_id: uid('act'), payload: { ...a2.payload, payload: { x: 1 } } };
    const r3 = await sync(uid('dev'), [same, ev(m, 'match_start', {}, 2, { payload: { ...a2.payload, type: 'match_end', payload: {} } })]);
    expect(r3.body.results[0].status).toBe('duplicate');
    const r4 = await sync(uid('dev'), [{ ...clash, payload: { event_id: a2.payload.event_id, match_id: m, type: 'score', payload: { team_id: 'H', event_type: 'goal' } } }]);
    expect(codes(r4)).toEqual(['DUPLICATE_EVENT_ID']);

    // a previously rejected action re-sent is a duplicate too
    const rej = ev(uid('ghost'), 'match_start', {}, 0);
    expect(codes(await sync(dev, [rej]))).toEqual(['MATCH_NOT_FOUND']);
    expect((await sync(dev, [rej])).body.results[0]).toMatchObject({ status: 'duplicate', error_code: null });
  });

  test('two devices scoring one match produce a gap-free log; parallel same-device requests serialize', async () => {
    const m = uid('m');
    await mk(m, 'football').begin();
    const dev = uid('dev');
    const batch = (n) => Array.from({ length: n }, () => ev(m, 'score', { team_id: 'H', event_type: 'goal' }, 0));
    const responses = await Promise.all([
      sync(dev, batch(5)), sync(dev, batch(5)), sync(uid('devb'), batch(5)), sync(uid('devc'), batch(5)),
    ]);
    for (const r of responses) expect(r.body.summary.applied).toBe(5);
    const score = (await getScore(m)).body;
    expect(score.version).toBe(22);
    expect(score.events.map((e) => e.sequence)).toEqual(Array.from({ length: 22 }, (_, i) => i + 1));
  });
});

describeDb('offline-sync: roster transfers', () => {
  beforeAll(() => resetDb());

  test('applied, rebased, base too high, duplicates and not-found codes', async () => {
    const lg = uid('lg');
    await rk.setup(lg);
    const dev = uid('dev');
    const t1 = tr(rk.req(lg, { player_id: 'df1', from_squad_id: 'A', to_squad_id: 'B' }), 0);
    const r1 = await sync(dev, [t1]);
    expect(r1.body.results[0]).toMatchObject({ status: 'applied', resolution: 'appended', server_version: 1, signature: null });

    const back = tr(rk.req(lg, { player_id: 'df1', from_squad_id: 'B', to_squad_id: 'A' }), 0);
    const tooHigh = tr(rk.req(lg, { player_id: 'df2', from_squad_id: 'A', to_squad_id: 'B' }), 4);
    const dup = { ...tr(t1.payload, 0), action_id: uid('act') };
    const r2 = await sync(dev, [back, tooHigh, dup]);
    expect(r2.body.results[0]).toMatchObject({ status: 'applied', resolution: 'rebased_append', server_version: 2 });
    expect(r2.body.results[1]).toMatchObject({ error_code: 'INVALID_BASE_VERSION', server_version: 0 });
    expect(r2.body.results[2]).toMatchObject({ status: 'duplicate', server_version: 2 });

    const miss = await sync(dev, [
      tr(rk.req(lg, { league_id: uid('nolg') }), 9),
      tr(rk.req(lg, { player_id: 'ghost' }), 9),
      tr(rk.req(lg, { from_squad_id: 'ZZ' }), 9),
      tr(rk.req(lg, { to_squad_id: 'ZZ' }), 9),
    ]);
    expect(codes(miss)).toEqual(['LEAGUE_NOT_FOUND', 'PLAYER_NOT_FOUND', 'SQUAD_NOT_FOUND', 'SQUAD_NOT_FOUND']);
    expect(miss.body.results.map((x) => x.server_version)).toEqual([0, 0, 2, 2]);

    const bad = await sync(dev, [{ action_id: uid('a'), type: 'roster_transfer', queued_at: at(), base_version: 0, payload: { type: 'transfer' } }]);
    expect(codes(bad)).toEqual(['VALIDATION_ERROR']);
  });

  test('rule rejections: window, squad full, not in squad, cross tenant, min position, loan', async () => {
    const lg = uid('lg');
    await rk.setup(lg);
    const dev = uid('dev');
    const r = await sync(dev, [
      tr(rk.req(lg, { effective_date: '2026-06-01' }), 0), // window closed
      tr(rk.req(lg, { player_id: 'gk2', from_squad_id: 'A', to_squad_id: 'B' }), 0), // not in squad
      tr(rk.req(lg, { player_id: 'gk1', from_squad_id: 'A', type: 'release', to_squad_id: undefined }), 0), // last keeper
      tr(rk.req(lg, { player_id: 'df1', from_squad_id: 'A', to_squad_id: 'X' }), 0), // other tenant
    ]);
    expect(codes(r)).toEqual(['TRANSFER_WINDOW_CLOSED', 'NOT_IN_SQUAD', 'MIN_POSITION_VIOLATION', 'CROSS_TENANT_TRANSFER']);

    const loanLg = uid('lg');
    await rk.setup(loanLg);
    const loan = tr(rk.req(loanLg, { player_id: 'df2', type: 'loan', from_squad_id: 'A', to_squad_id: 'B', loan_end_date: '2026-02-05' }), 0);
    const onLoan = tr(rk.req(loanLg, { player_id: 'df2', from_squad_id: 'B', to_squad_id: 'C', effective_date: '2026-01-20' }), 1);
    const r2 = await sync(dev, [loan, onLoan]);
    expect(r2.body.results[0].status).toBe('applied');
    expect(r2.body.results[1].error_code).toBe('PLAYER_ON_LOAN');

    const fullLg = uid('lg');
    await rk.setup(fullLg, { league: { max_squad_size: 1 } });
    const r3 = await sync(dev, [tr(rk.req(fullLg, { player_id: 'df1', from_squad_id: 'A', to_squad_id: 'B' }), 0)]);
    expect(codes(r3)).toEqual(['SQUAD_FULL']);
  });

  test('mixed batch: failing action does not affect the others', async () => {
    const lg = uid('lg');
    await rk.setup(lg);
    const m = uid('m');
    const r = await sync(uid('dev'), [
      tr(rk.req(lg, { player_id: 'ghost' }), 0),
      init(m),
      tr(rk.req(lg), 0),
    ]);
    expect(r.body.summary).toEqual({ applied: 2, rejected: 1, duplicate: 0 });
  });
});

describeDb('offline-sync: authorization callback (production route hook)', () => {
  beforeAll(() => resetDb());

  test('denied actions are rejected FORBIDDEN, others still processed', async () => {
    const lg = uid('lg');
    await rk.setup(lg, { players: [rk.P('gk1', 'A', 'gk'), rk.P('df1', 'A', 'df'), rk.P('gk2', 'B', 'gk'), rk.P('df3', 'B', 'df')] });
    const mine = uid('m');
    const foreign = uid('m');
    const seen = [];
    const authorize = async (ctx) => {
      seen.push(ctx);
      return ctx.match_id !== foreign && ctx.club_id !== 'ca';
    };
    const body = {
      device_id: uid('dev'),
      actions: [init(mine), init(foreign), tr(rk.req(lg, { from_squad_id: 'A' }), 0), tr(rk.req(lg, { player_id: 'df3', from_squad_id: 'B', to_squad_id: 'C' }), 0)],
    };
    const out = await offlineSync.sync(body, { authorize });
    expect(out.results.map((r) => r.error_code)).toEqual([null, 'FORBIDDEN', 'FORBIDDEN', null]);
    expect(out.summary).toEqual({ applied: 2, rejected: 2, duplicate: 0 });
    expect(seen).toContainEqual(expect.objectContaining({ type: 'roster_transfer', club_id: 'ca', league_id: lg }));
    expect((await getScore(foreign)).status).toBe(404);
  });
});
