// Contract checks the Kotlin app depends on that the older mobileApi suite does not pin down:
// club move destinations, order detail display fields, and realtime push latency of the worker.
const path = require('path');
const { spawn } = require('child_process');
const { describeDb, describeDbRedis } = require('../helpers/db');
const { resetAll, as, pool } = require('../helpers/mobilekit');
const { uid, post: auditPost } = require('../helpers/evtkit');

const Redis = require('../../backend/node_modules/ioredis');

afterAll(() => pool.end());

describeDb('mobile contract: club payload exposes move destinations', () => {
  beforeAll(() => resetAll());

  test('squads stay the club own squads; destination_squads lists other clubs of the league with head counts', async () => {
    const r = await as('manager').get('/api/v1/clubs/club-alpha-1');
    expect(r.status).toBe(200);
    expect(r.body.squads).toEqual([{ squad_id: 'demo-sq-alpha-1', name: 'Lahore Lions', club_id: 'club-alpha-1' }]);
    expect(r.body.destination_squads).toEqual([
      { squad_id: 'demo-sq-alpha-2', name: 'Karachi Kings', club_id: 'club-alpha-2', size: 8 },
    ]);
    // Without it the app's destination picker would be empty for the demo manager.
    const other = await as('manager').get('/api/v1/clubs/club-alpha-2');
    expect(other.body.destination_squads.map((s) => s.squad_id)).toEqual(['demo-sq-alpha-1']);
  });

  test('head counts follow a transfer and every roster row carries a numeric audit_version', async () => {
    const move = await as('manager').post('/api/v1/clubs/club-alpha-1/roster/transfer', {
      request_id: uid('rq'), league_id: 'league-alpha-1', player_id: 'demo-pl-1-4', type: 'transfer',
      from_squad_id: 'demo-sq-alpha-1', to_squad_id: 'demo-sq-alpha-2', effective_date: '2026-10-17', reason: 'contract', requested_by: 'user-manager',
    });
    expect([200, 201]).toContain(move.status);
    const r = await as('manager').get('/api/v1/clubs/club-alpha-1');
    expect(r.body.destination_squads[0].size).toBe(9);
    for (const p of r.body.roster) {
      expect(Number.isInteger(p.audit_version)).toBe(true);
      expect(typeof p.name).toBe('string');
    }
    const moved = (await as('manager').get('/api/v1/clubs/club-alpha-2')).body.roster.find((p) => p.player_id === 'demo-pl-1-4');
    // The queued roster action's base_version is this value; /sync reports the same number as server_version.
    expect(moved.audit_version).toBe(1);
  });
});

describeDb('mobile contract: order detail', () => {
  beforeAll(() => resetAll());

  test('items carry the product name, plus created_at and status_history', async () => {
    const order = {
      order_id: uid('ord'), customer_id: 'x', items: [{ variant_id: 'demo-var-cap-std', quantity: 1 }], payment: { token: 'tok_success' },
    };
    const paid = await as('viewer').post('/api/v1/checkout', order);
    expect(paid.status).toBe(201);
    const r = await as('viewer').get(`/api/v1/orders/${order.order_id}`);
    expect(r.status).toBe(200);
    expect(r.body.items).toEqual([{ variant_id: 'demo-var-cap-std', name: 'Club Cap', quantity: 1, unit_price_minor: 120000 }]);
    expect(r.body.created_at).toMatch(/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/);
    expect(r.body.paid_at).toMatch(/Z$/);
    expect(Array.isArray(r.body.status_history)).toBe(true);
    expect(r.body.status_history.length).toBeGreaterThan(0);
    for (const h of r.body.status_history) {
      expect(typeof h.to_status).toBe('string');
      expect(h.from_status === null || typeof h.from_status === 'string').toBe(true);
      expect(h.at).toMatch(/Z$/);
    }
    expect(r.body.status_history[r.body.status_history.length - 1].to_status).toBe('paid');
  });
});

describeDbRedis('mobile contract: realtime push latency', () => {
  let worker;
  let sub;

  beforeAll(async () => {
    await resetAll();
    worker = spawn(process.execPath, [path.join(__dirname, '../../backend/src/worker.js')], {
      env: { ...process.env, DAY1_SALT: process.env.DAY1_SALT || 'test-salt', LOG_LEVEL: 'silent' },
      stdio: 'ignore',
    });
    sub = new Redis(process.env.REDIS_URL);
    await sub.psubscribe('rt:match:*');
    await new Promise((r) => setTimeout(r, 3500)); // boot + backlog
  }, 20000);

  afterAll(async () => {
    if (worker) worker.kill();
    if (sub) sub.disconnect();
  });

  test('an appended event reaches the realtime channel well within one second (the blocking stream reader must not stall the relay)', async () => {
    const mid = uid('lat');
    const seen = new Map();
    sub.on('pmessage', (p, channel, msg) => {
      const o = JSON.parse(msg);
      if (o.match_id === mid) seen.set(o.type, Date.now());
    });
    await auditPost({ event_id: uid('i'), match_id: mid, type: 'match_init', payload: { sport_id: 'football', home_team_id: 'H', away_team_id: 'A' } });
    await auditPost({ event_id: uid('s'), match_id: mid, type: 'match_start', payload: {} });
    const worst = [];
    for (let i = 0; i < 4; i += 1) {
      const t0 = Date.now();
      seen.clear();
      const r = await auditPost({ event_id: uid('g'), match_id: mid, type: 'score', payload: { team_id: 'H', event_type: 'goal' } });
      expect(r.status).toBe(201);
      for (let w = 0; w < 40 && !seen.has('match.event_appended'); w += 1) await new Promise((x) => setTimeout(x, 50));
      expect(seen.has('match.event_appended')).toBe(true);
      worst.push(seen.get('match.event_appended') - t0);
      await new Promise((x) => setTimeout(x, 400));
    }
    expect(Math.max(...worst)).toBeLessThan(1000);
  }, 30000);
});
