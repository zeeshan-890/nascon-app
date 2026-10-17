const { describeDb } = require('../helpers/db');
const { resetDb, pool } = require('../helpers/app');
const { uid, post, mk, expectError } = require('../helpers/evtkit');

afterAll(() => pool.end());

describeDb('events: concurrency', () => {
  beforeAll(() => resetDb());

  test('10 parallel identical posts -> one 201, nine 200, version incremented once', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    const body = { event_id: uid('e'), match_id: m.match_id, type: 'score', payload: { team_id: 'H', event_type: 'goal' } };
    const rs = await Promise.all(Array.from({ length: 10 }, () => post(body)));
    expect(rs.filter((r) => r.status === 201)).toHaveLength(1);
    expect(rs.filter((r) => r.status === 200)).toHaveLength(9);
    const texts = new Set(rs.map((r) => r.text));
    expect(texts.size).toBe(1);
    const s = (await m.get()).body;
    expect(s.version).toBe(3);
    expect(s.home.score).toBe(1);
  });

  test('20 parallel distinct score events -> all 201, sequences 2..22 gap-free, version 22', async () => {
    const m = mk(uid('m'), 'football');
    await m.init();
    await m.start();
    // sequences 1 and 2 are init/start; scores should take 3..22
    const rs = await Promise.all(Array.from({ length: 20 }, (_, i) => m.score(i % 2 ? 'A' : 'H', 'goal')));
    expect(rs.map((r) => r.status)).toEqual(Array(20).fill(201));
    const seqs = rs.map((r) => r.body.sequence).sort((a, b) => a - b);
    expect(seqs).toEqual(Array.from({ length: 20 }, (_, i) => i + 3));
    const s = (await m.get()).body;
    expect(s.version).toBe(22);
    expect(s.events.map((e) => e.sequence)).toEqual(Array.from({ length: 22 }, (_, i) => i + 1));
    expect(s.home.score).toBe(10);
    expect(s.away.score).toBe(10);
  });

  test('init+start then 20 parallel distinct score events: spec sequences 2..22 reading (events after match_start)', async () => {
    // With init(1) + start(2), 20 scores occupy 3..22; the version is 22.
    const m = mk(uid('m'), 'football');
    await m.begin();
    const rs = await Promise.all(Array.from({ length: 20 }, () => m.score('H', 'goal')));
    for (const r of rs) expect(r.status).toBe(201);
    expect(Math.max(...rs.map((r) => r.body.match_version))).toBe(22);
    expect((await m.get()).body.version).toBe(22);
  });

  test('two parallel compensations of same target -> one 201, one 409 INVALID_COMPENSATION', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    const g = await m.score('H', 'goal');
    const [a, b] = await Promise.all([m.comp(g.body.event_id, 'r1'), m.comp(g.body.event_id, 'r2')]);
    const statuses = [a.status, b.status].sort();
    expect(statuses).toEqual([201, 409]);
    const loser = a.status === 409 ? a : b;
    expectError(loser, 409, 'INVALID_COMPENSATION');
    const s = (await m.get()).body;
    expect(s.home.score).toBe(0);
    expect(s.events.filter((e) => e.type === 'compensation')).toHaveLength(1);
    expect(s.version).toBe(4);
  });

  test('10 parallel identical match_init -> one 201, rest 200', async () => {
    const m = mk(uid('m'), 'football');
    const body = {
      event_id: uid('init'), match_id: m.match_id, type: 'match_init',
      payload: { sport_id: 'football', home_team_id: 'H', away_team_id: 'A' },
    };
    const rs = await Promise.all(Array.from({ length: 10 }, () => post(body)));
    expect(rs.filter((r) => r.status === 201)).toHaveLength(1);
    expect(rs.filter((r) => r.status === 200)).toHaveLength(9);
    expect(new Set(rs.map((r) => r.text)).size).toBe(1);
    expect((await m.get()).body.version).toBe(1);
  });

  test('parallel match_init with different event_ids for same match -> one 201, rest 409 MATCH_EXISTS', async () => {
    const m = mk(uid('m'), 'football');
    const rs = await Promise.all(Array.from({ length: 6 }, () => m.init()));
    expect(rs.filter((r) => r.status === 201)).toHaveLength(1);
    for (const r of rs.filter((x) => x.status !== 201)) expectError(r, 409, 'MATCH_EXISTS');
  });
});

describeDb('events: append-only', () => {
  beforeAll(() => resetDb());

  test('UPDATE on match_events is rejected', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    await expect(pool.query("UPDATE match_events SET type='x'")).rejects.toThrow(/append-only/);
  });

  test('DELETE on match_events is rejected', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    await expect(pool.query('DELETE FROM match_events')).rejects.toThrow(/append-only/);
  });

  test('events are intact after the rejected mutations', async () => {
    const m = mk(uid('m'), 'football');
    await m.begin();
    await pool.query("UPDATE match_events SET type='x'").catch(() => {});
    await pool.query('DELETE FROM match_events').catch(() => {});
    expect((await m.get()).body.events.map((e) => e.type)).toEqual(['match_init', 'match_start']);
  });
});
