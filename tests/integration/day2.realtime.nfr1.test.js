// NFR-1 probe: score events appended through POST /audit/v1/events must reach 95% of 25 concurrent
// guest clients on ws://<host>/ws/matches/{id} in under 500 ms (p95), and arrive in order.
//   in-process (default):   jest tests/integration/day2.realtime.nfr1.test.js
//   against a running stack: NFR1_BASE_URL=http://localhost:8080 jest tests/integration/day2.realtime.nfr1.test.js
const WebSocket = require('../../backend/node_modules/ws');
const { describeDbRedis } = require('../helpers/db');

const REMOTE = process.env.NFR1_BASE_URL || '';
const CLIENTS = 25;
const EVENTS = Number(process.env.NFR1_EVENTS || 20);
const MATCH = 'demo-m-live';
const describeProbe = REMOTE ? describe : describeDbRedis;

let server;
let hub;
let pool;
let base = REMOTE.replace(/\/$/, '');

beforeAll(async () => {
  if (REMOTE || process.env.DB_AVAILABLE !== '1' || process.env.REDIS_AVAILABLE !== '1') return;
  const { boot } = require('../helpers/mobilekit');
  ({ pool } = require('../helpers/mobilekit'));
  const { createHttpServer } = require('../../backend/src/httpServer');
  const { createHub } = require('../../backend/src/realtime/hub');
  server = createHttpServer(await boot());
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
  hub = createHub(server);
});

afterAll(async () => {
  if (hub) await hub.close();
  if (server) await new Promise((r) => server.close(r));
  if (pool) await pool.end();
});

const post = (path, body) => fetch(`${base}${path}`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const pct = (sorted, p) => sorted[Math.min(sorted.length - 1, Math.floor(p * sorted.length))];

describeProbe('NFR-1: 25 concurrent clients, p95 < 500 ms', () => {
  test('score events reach 95% of clients in under 500 ms, in order, with no loss', async () => {
    expect((await post('/audit/v1/reset', {})).status).toBe(200);
    const wsBase = base.replace(/^http/, 'ws');
    const socks = [];
    for (let i = 0; i < CLIENTS; i += 1) {
      const ws = new WebSocket(`${wsBase}/ws/matches/${MATCH}`);
      ws.seen = new Map();
      ws.order = [];
      ws.on('message', (m) => {
        const f = JSON.parse(m.toString());
        if (f.type === 'snapshot') ws.snapshot = f;
        if (f.type === 'match.event_appended' && !ws.seen.has(f.data.event_id)) {
          ws.seen.set(f.data.event_id, performance.now());
          ws.order.push(f.data.event_id);
        }
      });
      socks.push(ws);
    }
    await Promise.all(socks.map((ws) => new Promise((r, j) => { ws.on('open', r); ws.on('error', j); })));
    const end = Date.now() + 5000;
    while (socks.some((ws) => !ws.snapshot) && Date.now() < end) await sleep(10);
    expect(socks.every((ws) => ws.snapshot)).toBe(true);

    const sent = [];
    const afterResponse = []; // receive time relative to the HTTP response
    const fromRequest = []; // receive time relative to the request being sent
    const stamp = Date.now().toString(36);
    for (let i = 0; i < EVENTS; i += 1) {
      const event_id = `nfr1-${stamp}-${i}`;
      const t0 = performance.now();
      const r = await post('/audit/v1/events', { event_id, match_id: MATCH, type: 'score', payload: { team_id: 'demo-team-1', event_type: 'goal' } });
      const t1 = performance.now();
      expect(r.status).toBe(201);
      sent.push({ event_id, t0, t1 });
      await sleep(60);
    }
    await sleep(800);
    let missing = 0;
    for (const { event_id, t0, t1 } of sent) {
      for (const ws of socks) {
        if (!ws.seen.has(event_id)) { missing += 1; continue; }
        fromRequest.push(ws.seen.get(event_id) - t0);
        afterResponse.push(ws.seen.get(event_id) - t1);
      }
    }
    const a = [...fromRequest].sort((x, y) => x - y);
    const b = [...afterResponse].sort((x, y) => x - y);
    const report = {
      clients: CLIENTS, events: EVENTS, missing,
      from_request_ms: { p50: +pct(a, 0.5).toFixed(1), p95: +pct(a, 0.95).toFixed(1), max: +a[a.length - 1].toFixed(1) },
      after_response_ms: { p50: +pct(b, 0.5).toFixed(1), p95: +pct(b, 0.95).toFixed(1), max: +b[b.length - 1].toFixed(1) },
    };
    // eslint-disable-next-line no-console
    console.log(`NFR-1 ${JSON.stringify(report)}`);
    expect(missing).toBe(0);
    expect(pct(a, 0.95)).toBeLessThan(500);
    const ids = sent.map((s) => s.event_id);
    for (const ws of socks) expect(ws.order).toEqual(ids);
    socks.forEach((ws) => ws.close());
  }, 60000);
});
