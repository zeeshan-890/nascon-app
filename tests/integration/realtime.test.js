const WebSocket = require('../../backend/node_modules/ws');
const { describeDbRedis } = require('../helpers/db');
const { boot, resetAll, as, token, pool } = require('../helpers/mobilekit');
const { createHttpServer } = require('../../backend/src/httpServer');
const { createHub } = require('../../backend/src/realtime/hub');
const { relayOnce } = require('../../backend/src/services/outboxRelayService');
const { uid } = require('../helpers/evtkit');

let server;
let hub;
let port;

beforeAll(async () => {
  if (process.env.DB_AVAILABLE !== '1' || process.env.REDIS_AVAILABLE !== '1') return;
  await resetAll();
  server = createHttpServer(await boot());
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  port = server.address().port;
  hub = createHub(server);
});

afterAll(async () => {
  if (hub) await hub.close();
  if (server) await new Promise((r) => server.close(r));
  await pool.end();
});

// Opens a socket and collects every frame; resolves once the connection is open (or closed).
function connect(tok) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?token=${encodeURIComponent(tok)}`);
  ws.frames = [];
  ws.on('message', (m) => ws.frames.push(JSON.parse(m.toString())));
  ws.closed = new Promise((r) => ws.on('close', (code, reason) => r({ code, reason: reason.toString() })));
  return new Promise((resolve) => {
    ws.on('open', () => resolve(ws));
    ws.on('error', () => resolve(ws));
    ws.on('close', () => resolve(ws));
  });
}

const until = async (fn, ms = 2000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const v = fn();
    if (v) return v;
    await new Promise((r) => setTimeout(r, 20));
  }
  return null;
};

const pump = async () => {
  // The worker's relay loop, driven by hand in tests.
  for (let i = 0; i < 3; i += 1) await relayOnce();
};

describeDbRedis('websocket hub', () => {
  test('bad, missing or expired token: handshake completes, then close 4401', async () => {
    for (const t of ['', 'garbage']) {
      const ws = await connect(t);
      expect((await ws.closed).code).toBe(4401);
    }
  });

  test('subscribe to a match and a league; REST event pushes both messages', async () => {
    const ws = await connect(await token('viewer'));
    ws.send(JSON.stringify({ subscribe: 'match:demo-m-live' }));
    ws.send(JSON.stringify({ subscribe: 'league:league-alpha-1' }));
    await until(() => ws.frames.length >= 2);
    expect(ws.frames).toEqual([{ type: 'subscribed', topic: 'match:demo-m-live' }, { type: 'subscribed', topic: 'league:league-alpha-1' }]);
    await pump();
    ws.frames.length = 0;

    const event_id = uid('e');
    const r = await as('organizer').post('/api/v1/matches/demo-m-live/events', {
      event_id, match_id: 'demo-m-live', type: 'score', payload: { team_id: 'demo-team-1', event_type: 'goal' },
    });
    expect(r.status).toBe(201);
    await pump();
    const appended = await until(() => ws.frames.find((f) => f.type === 'match.event_appended'));
    const standings = await until(() => ws.frames.find((f) => f.type === 'standings.updated'));
    expect(appended).toEqual({
      type: 'match.event_appended', match_id: 'demo-m-live',
      data: { match_id: 'demo-m-live', event_id, sequence: r.body.sequence, version: r.body.sequence, event_type: 'score' },
    });
    expect(standings).toEqual({ type: 'standings.updated', data: { league_id: 'league-alpha-1' } });
    expect(ws.frames.filter((f) => f.type === 'match.event_appended')).toHaveLength(1);

    ws.send(JSON.stringify({ unsubscribe: 'match:demo-m-live' }));
    await new Promise((r2) => setTimeout(r2, 100));
    ws.frames.length = 0;
    await as('organizer').post('/api/v1/matches/demo-m-live/events', {
      event_id: uid('e'), match_id: 'demo-m-live', type: 'score', payload: { team_id: 'demo-team-4', event_type: 'goal' },
    });
    await pump();
    await new Promise((r2) => setTimeout(r2, 200));
    expect(ws.frames.find((f) => f.type === 'match.event_appended')).toBeUndefined();
    ws.close();
  });

  test('status changes are pushed with the new status', async () => {
    const ws = await connect(await token('organizer'));
    const match_id = 'demo-m-5';
    ws.send(JSON.stringify({ subscribe: `match:${match_id}` }));
    await until(() => ws.frames.length >= 1);
    await as('organizer').post(`/api/v1/matches/${match_id}/events`, { event_id: uid('e'), match_id, type: 'match_start', payload: {} });
    await pump();
    const changed = await until(() => ws.frames.find((f) => f.type === 'match.status_changed'));
    expect(changed).toEqual({ type: 'match.status_changed', match_id, data: { match_id, status: 'in_progress' } });
    ws.close();
  });

  test('denied and unknown topics get an error frame; the socket stays open', async () => {
    await pool.query(
      `INSERT INTO matches (match_id, sport_id, league_id, home_team_id, away_team_id, origin) VALUES ('beta-live', 'football', 'league-beta-1', 'x', 'y', 'fixture')
       ON CONFLICT DO NOTHING`,
    );
    const ws = await connect(await token('viewer'));
    for (const topic of ['match:beta-live', 'league:league-beta-1', 'match:nope', 'league:nope', 'weird:topic', 'match:bad id']) {
      ws.send(JSON.stringify({ subscribe: topic }));
      await new Promise((r) => setTimeout(r, 30));
    }
    await until(() => ws.frames.length >= 6);
    expect(ws.frames).toEqual([
      { type: 'error', topic: 'match:beta-live', code: 'FORBIDDEN' },
      { type: 'error', topic: 'league:league-beta-1', code: 'FORBIDDEN' },
      { type: 'error', topic: 'match:nope', code: 'NOT_FOUND' },
      { type: 'error', topic: 'league:nope', code: 'NOT_FOUND' },
      { type: 'error', topic: 'weird:topic', code: 'NOT_FOUND' },
      { type: 'error', topic: 'match:bad id', code: 'NOT_FOUND' },
    ]);
    expect(ws.readyState).toBe(WebSocket.OPEN);
    ws.close();
  });

  test('malformed frames are ignored; more than 20 messages a second closes with 1008', async () => {
    const ws = await connect(await token('viewer'));
    ws.send('not json');
    ws.send('[1,2,3]');
    ws.send('"str"');
    ws.send(JSON.stringify({ hello: 'world' }));
    await new Promise((r) => setTimeout(r, 100));
    expect(ws.readyState).toBe(WebSocket.OPEN);
    expect(ws.frames).toEqual([]);
    for (let i = 0; i < 25; i += 1) ws.send(JSON.stringify({ unsubscribe: 'match:x' }));
    expect((await ws.closed).code).toBe(1008);
  });

  test('oversized frames are refused by the 4 KB limit', async () => {
    const ws = await connect(await token('viewer'));
    ws.send(JSON.stringify({ subscribe: `match:${'a'.repeat(5000)}` }));
    expect((await ws.closed).code).toBe(1009);
  });

  test('at most 50 subscriptions per socket', async () => {
    await pool.query(
      `INSERT INTO leagues (league_id, tenant_id, sport_id, kind, name, config)
       SELECT 'ws-lg-' || n, 'tenant-alpha', 'football', 'league', 'ws', '{}'::jsonb FROM generate_series(1, 52) n ON CONFLICT DO NOTHING`,
    );
    const ws = await connect(await token('viewer'));
    for (let batch = 0; batch < 6; batch += 1) {
      for (let i = 1; i <= 10 && batch * 10 + i <= 52; i += 1) {
        ws.send(JSON.stringify({ subscribe: `league:ws-lg-${batch * 10 + i}` }));
      }
      await new Promise((r) => setTimeout(r, 600));
    }
    await until(() => ws.frames.length >= 52, 3000);
    expect(ws.frames.filter((f) => f.type === 'subscribed')).toHaveLength(50);
    expect(ws.frames.filter((f) => f.code === 'TOO_MANY_SUBSCRIPTIONS')).toHaveLength(2);
    ws.close();
  }, 15000);
});
