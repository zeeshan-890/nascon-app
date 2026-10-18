// Real host disconnect: the room socket of the active host drops and the next participant is promoted within 1.5 s.
const WebSocket = require('../../backend/node_modules/ws');
const { describeDbRedis } = require('../helpers/db');
const { boot, resetAll, pool } = require('../helpers/mobilekit');
const { createHttpServer } = require('../../backend/src/httpServer');
const { createHub } = require('../../backend/src/realtime/hub');
const request = require('../../backend/node_modules/supertest');

const P = ['player-alpha-1', 'player-alpha-2', 'player-beta-1', 'player-beta-2'];
let server;
let hub;
let port;
let app;

describeDbRedis('co-stream host disconnect', () => {
  beforeAll(async () => {
    await resetAll();
    app = await boot();
    server = createHttpServer(app);
    await new Promise((r) => server.listen(0, '127.0.0.1', r));
    port = server.address().port;
    hub = createHub(server);
  });
  afterAll(async () => {
    if (hub) await hub.close();
    if (server) await new Promise((r) => server.close(r));
    await pool.end();
  });

  const open = (path) => new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}${path}`);
    const frames = [];
    ws.on('message', (m) => frames.push(JSON.parse(m.toString())));
    ws.on('open', () => resolve({ ws, frames }));
    ws.on('error', reject);
  });
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  it('promotes the next participant within 1.5 s, five runs, then terminates when nobody is left', async () => {
    const times = [];
    for (let i = 0; i < 5; i += 1) {
      const sid = `cs-session-hd-${i}`;
      const created = await request(app).post('/audit/v1/costream/session')
        .send({ session_id: sid, host_player_id: P[0], participants: P, layout: 'grid_4' });
      expect(created.status).toBe(201);
      const { ws, frames } = await open(`/ws/costream/${sid}?player_id=${P[0]}`);
      await sleep(100);
      expect(frames.length).toBeGreaterThan(0); // snapshot of a session created over HTTP
      const watcher = await open(`/ws/costream/${sid}`);
      await sleep(50);
      const t0 = Date.now();
      ws.close();
      let got;
      while (Date.now() - t0 < 1500) {
        got = (await request(app).get(`/api/v1/costream/${sid}`)).body;
        if (got.active_host === P[1]) break;
        await sleep(20);
      }
      times.push(Date.now() - t0);
      expect(got.active_host).toBe(P[1]);
      expect(got).toMatchObject({ handoff_occurred: true, participant_count: 3, status: 'active', composite_playback_url: created.body.composite_playback_url, chat_room_id: created.body.chat_room_id });
      expect(got.signature).not.toBe(created.body.signature);
      await sleep(100);
      expect(watcher.frames.some((f) => JSON.stringify(f).includes('host.handoff'))).toBe(true);
      watcher.ws.close();
    }
    expect(Math.max(...times)).toBeLessThan(1500);
    // eslint-disable-next-line no-console
    console.log('handoff ms', times.join(','));

    const solo = await request(app).post('/audit/v1/costream/session')
      .send({ session_id: 'cs-session-hd-solo', host_player_id: P[0], participants: [P[0]], layout: 'solo' });
    expect(solo.status).toBe(201);
    const { ws } = await open(`/ws/costream/cs-session-hd-solo?player_id=${P[0]}`);
    await sleep(100);
    ws.close();
    await sleep(400);
    const end = (await request(app).get('/api/v1/costream/cs-session-hd-solo')).body;
    expect(end).toMatchObject({ status: 'terminated', active_host: null, participant_count: 0 });
  });
});
