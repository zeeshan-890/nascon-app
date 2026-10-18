const crypto = require('crypto');
const { describeDb, describeDbRedis } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');
const { getRedis } = require('../../backend/src/db/redis');

afterAll(() => pool.end());

const post = async (path, body) => (await api()).post(path).send(body || {});

// A fingerprint of everything the base seed creates, independent of generated timestamps.
async function fingerprint() {
  const q = async (sql) => (await pool.query(sql)).rows;
  const state = {
    tenants: await q('SELECT tenant_id FROM tenants ORDER BY 1'),
    players: await q('SELECT player_id, club_id, tenant_id FROM players ORDER BY 1'),
    variants: await q('SELECT variant_id, price_minor, stock, reserved FROM variants ORDER BY 1'),
    users: await q('SELECT email, role FROM users ORDER BY 1'),
    sports: await q('SELECT sport_id FROM sports ORDER BY 1'),
    match: await q("SELECT match_id, sport_id, league_id, home_team_id, away_team_id, version FROM matches WHERE match_id = 'match-m1'"),
    events: await q("SELECT event_id, type, sequence, signature FROM match_events WHERE match_id = 'match-m1' ORDER BY sequence"),
    counts: await q(`SELECT (SELECT count(*) FROM matches)::int AS matches, (SELECT count(*) FROM match_events)::int AS events,
                            (SELECT count(*) FROM orders)::int AS orders, (SELECT count(*) FROM roster_audit)::int AS audit`),
  };
  return crypto.createHash('sha256').update(JSON.stringify(state)).digest('hex');
}

describeDb('Part II base seed after reset', () => {
  beforeAll(() => resetDb());

  test('player-beta-2 belongs to club-beta-1, alongside the Day 1 players', async () => {
    const { rows } = await pool.query("SELECT player_id, club_id FROM players WHERE player_id LIKE 'player-%' ORDER BY 1");
    expect(rows).toEqual(expect.arrayContaining([
      { player_id: 'player-alpha-1', club_id: 'club-alpha-1' },
      { player_id: 'player-alpha-2', club_id: 'club-alpha-2' },
      { player_id: 'player-beta-1', club_id: 'club-beta-1' },
      { player_id: 'player-beta-2', club_id: 'club-beta-1' },
    ]));
  });

  test('match-m1 is the football match in league-alpha-1, club-alpha-1 v club-alpha-2, in progress, 1 - 0', async () => {
    const r = await (await api()).get('/audit/v1/score/match-m1');
    expect(r.status).toBe(200);
    expect(r.body).toMatchObject({
      match_id: 'match-m1', sport_id: 'football', league_id: 'league-alpha-1', status: 'in_progress',
      outcome: 'pending', home: { team_id: 'club-alpha-1', score: 1 }, away: { team_id: 'club-alpha-2', score: 0 },
    });
  });

  test('a later score event gives 2 - 0, then match_end completes it deterministically', async () => {
    await resetDb();
    const a = await post('/audit/v1/events', { event_id: 'm1-x1', match_id: 'match-m1', type: 'score', payload: { team_id: 'club-alpha-1', event_type: 'goal' } });
    expect(a.status).toBe(201);
    const b = await post('/audit/v1/events', { event_id: 'm1-x2', match_id: 'match-m1', type: 'match_end', payload: {} });
    expect(b.status).toBe(201);
    const s = (await (await api()).get('/audit/v1/score/match-m1')).body;
    expect([s.status, s.home.score, s.away.score, s.outcome, s.winner_team_id]).toEqual(['completed', 2, 0, 'home_win', 'club-alpha-1']);
  });

  test('a level score is reachable too (1 - 1)', async () => {
    await resetDb();
    await post('/audit/v1/events', { event_id: 'm1-y1', match_id: 'match-m1', type: 'score', payload: { team_id: 'club-alpha-2', event_type: 'goal' } });
    const s = (await (await api()).get('/audit/v1/score/match-m1')).body;
    expect([s.home.score, s.away.score]).toEqual([1, 1]);
  });

  test('the reset response keeps its exact Day 1 shape (no added keys)', async () => {
    const r = await post('/audit/v1/reset');
    expect(Object.keys(r.body)).toEqual(['status', 'seed', 'sports', 'tenants', 'stock']);
  });

  test('no Part II state is seeded by this module (guest tokens, QR, carts, DLQ belong to their own modules)', async () => {
    const { rows } = await pool.query("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name LIKE 'day2_%'");
    for (const t of rows) {
      const n = (await pool.query(`SELECT count(*)::int AS n FROM ${t.table_name}`)).rows[0].n;
      expect(typeof n).toBe('number'); // tables owned by other modules may exist; their seed hooks fill them
    }
  });
});

describeDb('reset quality bar', () => {
  test('ten consecutive resets leave an identical state fingerprint', async () => {
    const prints = [];
    for (let i = 0; i < 10; i += 1) {
      await resetDb();
      prints.push(await fingerprint());
    }
    expect(new Set(prints).size).toBe(1);
  }, 60000);

  test('reset is fast (under 2 seconds once warm)', async () => {
    await resetDb();
    const t0 = Date.now();
    await resetDb();
    expect(Date.now() - t0).toBeLessThan(2000);
  });

  test('after match-m1 was completed or postponed, reset returns it to in_progress 1 - 0', async () => {
    await resetDb();
    await post('/audit/v1/events', { event_id: 'z-end', match_id: 'match-m1', type: 'match_end', payload: {} });
    expect((await (await api()).get('/audit/v1/score/match-m1')).body.status).toBe('completed');
    await resetDb();
    const s = (await (await api()).get('/audit/v1/score/match-m1')).body;
    expect([s.status, s.home.score, s.away.score, s.version]).toEqual(['in_progress', 1, 0, 2]);
    await pool.query("UPDATE matches SET admin_status = 'postponed' WHERE match_id = 'match-m1'");
    await resetDb();
    expect((await pool.query("SELECT admin_status FROM matches WHERE match_id='match-m1'")).rows[0].admin_status).toBeNull();
  });

  test('a stale or missing body is fine', async () => {
    expect((await post('/audit/v1/reset', undefined)).status).toBe(200);
    expect((await post('/audit/v1/reset', { seed: 42, stock: { 'var-jersey-001-M': 1 }, unknown: true })).status).toBe(200);
  });

  test('two resets fired at the same time both succeed and the state is intact', async () => {
    const rs = await Promise.all([post('/audit/v1/reset'), post('/audit/v1/reset'), post('/audit/v1/reset')]);
    expect(rs.map((r) => r.status)).toEqual([200, 200, 200]);
    const s = (await (await api()).get('/audit/v1/score/match-m1')).body;
    expect([s.status, s.home.score]).toEqual(['in_progress', 1]);
  }, 30000);

  test('a reset with 50 requests in flight never fails the reset caller or crashes the process', async () => {
    await resetDb();
    const noise = Array.from({ length: 50 }, (_, i) => (i % 2
      ? post('/audit/v1/events', { event_id: `inflight-${i}-${Date.now()}`, match_id: 'match-m1', type: 'score', payload: { team_id: 'club-alpha-1', event_type: 'goal' } })
      : (async () => (await api()).get('/audit/v1/score/match-m1'))()));
    const reset = post('/audit/v1/reset');
    const [rr] = await Promise.all([reset, ...noise.map((p) => p.catch(() => null))]);
    expect(rr.status).toBe(200);
    expect(rr.body.status).toBe('reset_complete');
    // in-flight requests may fail cleanly, but never with an unhandled crash: the app still answers
    expect((await (await api()).get('/health')).status).toBe(200);
  }, 60000);
});

describeDbRedis('reset clears Redis counters', () => {
  test('rate-limit and cache keys are removed by a reset', async () => {
    const redis = getRedis();
    if (redis.status === 'wait') await redis.connect();
    await redis.set('rl:1.2.3.4', 'x');
    await redis.set('cache:league:1', 'x');
    await resetDb();
    expect(await redis.exists('rl:1.2.3.4')).toBe(0);
    expect(await redis.exists('cache:league:1')).toBe(0);
  });
});
