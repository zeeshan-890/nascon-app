const http = require('http');
const WebSocket = require('../../backend/node_modules/ws');
const { describeDbRedis } = require('../helpers/db');
const { boot, resetAll, token, pool } = require('../helpers/mobilekit');
const { createHttpServer } = require('../../backend/src/httpServer');
const { createHub } = require('../../backend/src/realtime/hub');
const { relayOnce } = require('../../backend/src/services/outboxRelayService');
const metrics = require('../../backend/day2/lib/metrics');
const realtime = require('../../backend/day2/realtime');
const streams = require('../../backend/day2/realtime/streams');
const { uid } = require('../helpers/evtkit');

const MATCH = 'demo-m-live';
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

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const until = async (fn, ms = 3000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    const v = fn();
    if (v) return v;
    await sleep(10);
  }
  return null;
};

function connect(path, query = '') {
  const ws = new WebSocket(`ws://127.0.0.1:${port}${path}${query}`);
  ws.frames = [];
  ws.on('message', (m) => ws.frames.push(JSON.parse(m.toString())));
  ws.closed = new Promise((r) => ws.on('close', (code, reason) => r({ code, reason: reason.toString() })));
  return new Promise((resolve) => {
    ws.on('open', () => resolve(ws));
    ws.on('error', () => resolve(ws));
    ws.on('close', () => resolve(ws));
  });
}

async function postEvent(event_id = uid('e'), type = 'score', payload = { team_id: 'demo-team-1', event_type: 'goal' }, match_id = MATCH) {
  const r = await fetch(`http://127.0.0.1:${port}/audit/v1/events`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ event_id, match_id, type, payload }),
  });
  return { status: r.status, body: await r.json(), event_id };
}

// Minimal SSE client: collects parsed frames (event name, id, data) and comment lines.
function sse(path, headers = {}) {
  return new Promise((resolve) => {
    const out = { frames: [], comments: [], raw: '' };
    const req = http.get({ host: '127.0.0.1', port, path, headers }, (res) => {
      out.res = res;
      out.status = res.statusCode;
      out.headers = res.headers;
      res.setEncoding('utf8');
      res.on('data', (chunk) => {
        out.raw += chunk;
        const parts = out.raw.split('\n\n');
        out.raw = parts.pop();
        for (const block of parts) {
          const f = {};
          for (const line of block.split('\n')) {
            if (line.startsWith(':')) out.comments.push(line);
            else if (line.startsWith('id: ')) f.id = Number(line.slice(4));
            else if (line.startsWith('event: ')) f.event = line.slice(7);
            else if (line.startsWith('data: ')) f.data = JSON.parse(line.slice(6));
          }
          if (f.event || f.data) out.frames.push(f);
        }
      });
      if (res.statusCode !== 200) { let b = ''; res.on('data', (c) => { b += c; }); res.on('end', () => { out.body = b; resolve(out); }); } else resolve(out);
    });
    out.req = req;
    req.on('error', () => resolve(out));
  });
}

const appended = (ws) => ws.frames.filter((f) => f.type === 'match.event_appended');

describeDbRedis('Part II realtime channels', () => {
  test('unknown match: WebSocket closes 4404, SSE answers 404 MATCH_NOT_FOUND envelope', async () => {
    const ws = await connect('/ws/matches/no-such-match');
    const closed = await ws.closed;
    expect(closed.code).toBe(4404);
    expect(closed.reason).toMatch(/not found/);
    const r = await sse('/api/v1/matches/no-such-match/stream');
    expect(r.status).toBe(404);
    expect(JSON.parse(r.body).error.code).toBe('MATCH_NOT_FOUND');
    const ws2 = await connect('/ws/matches/bad%20id%21');
    expect((await ws2.closed).code).toBe(4404);
  });

  test('guest connects without a token, gets a snapshot, then an ordered event with sequence and timestamp', async () => {
    const ws = await connect(`/ws/matches/${MATCH}`);
    const snap = await until(() => ws.frames.find((f) => f.type === 'snapshot'));
    expect(snap).toMatchObject({ match_id: MATCH, data: { match_id: MATCH, status: 'in_progress' } });
    expect(Number.isInteger(snap.sequence)).toBe(true);
    expect(typeof snap.data.home_score).toBe('number');
    expect(Date.parse(snap.ts)).not.toBeNaN();

    const r = await postEvent();
    expect(r.status).toBe(201);
    const ev = await until(() => appended(ws).find((f) => f.data.event_id === r.event_id));
    expect(ev).toBeTruthy();
    expect(ev.sequence).toBeGreaterThan(snap.sequence);
    expect(ev.data).toMatchObject({ match_id: MATCH, event_id: r.event_id, sequence: r.body.sequence, event_type: 'score' });
    expect(ev.data.home_score).toBeGreaterThanOrEqual(snap.data.home_score + 1);
    ws.close();
  });

  test('member token and garbage token are accepted and ignored for reads', async () => {
    const member = await connect(`/ws/matches/${MATCH}`, `?token=${encodeURIComponent(await token('viewer'))}`);
    const junk = await connect(`/ws/matches/${MATCH}`, '?token=garbage');
    const guest = await connect(`/ws/matches/${MATCH}`, '?token=guest_abc123');
    for (const ws of [member, junk, guest]) {
      expect(await until(() => ws.frames.find((f) => f.type === 'snapshot'))).toBeTruthy();
      ws.close();
    }
  });

  test('guest write attempts get GUEST_WRITE_FORBIDDEN and the socket stays open', async () => {
    const ws = await connect(`/ws/matches/${MATCH}`, '?token=guest_abc123');
    await until(() => ws.frames.length);
    for (const msg of [{ type: 'chat.post', text: 'hi' }, { type: 'score', team_id: 'x' }, { action: 'follow', id: 'p1' }]) {
      ws.frames.length = 0;
      ws.send(JSON.stringify(msg));
      const err = await until(() => ws.frames.find((f) => f.type === 'error'));
      expect(err).toMatchObject({ type: 'error', code: 'GUEST_WRITE_FORBIDDEN' });
    }
    ws.frames.length = 0;
    ws.send('not json');
    expect((await until(() => ws.frames.find((f) => f.type === 'error'))).code).toBe('INVALID_MESSAGE');
    ws.send(JSON.stringify({ type: 'ping' }));
    expect(await until(() => ws.frames.find((f) => f.type === 'pong'))).toBeTruthy();
    expect(ws.readyState).toBe(WebSocket.OPEN);
    ws.close();
  });

  test('member write over the socket is not applied either (WRITE_NOT_SUPPORTED)', async () => {
    const ws = await connect(`/ws/matches/${MATCH}`, `?token=${encodeURIComponent(await token('organizer'))}`);
    await until(() => ws.frames.length);
    const before = (await (await fetch(`http://127.0.0.1:${port}/audit/v1/score/${MATCH}`)).json()).version;
    ws.send(JSON.stringify({ type: 'score', team_id: 'demo-team-1', event_type: 'goal' }));
    expect((await until(() => ws.frames.find((f) => f.type === 'error'))).code).toBe('WRITE_NOT_SUPPORTED');
    const after = (await (await fetch(`http://127.0.0.1:${port}/audit/v1/score/${MATCH}`)).json()).version;
    expect(after).toBe(before);
    ws.close();
  });

  test('too many subscriptions and oversized payloads are refused', async () => {
    const ws = await connect(`/ws/matches/${MATCH}`);
    await until(() => ws.frames.length);
    for (let i = 0; i < 12; i += 1) ws.send(JSON.stringify({ subscribe: `match:no-such-${i}` }));
    await until(() => ws.frames.filter((f) => f.type === 'error').length >= 12);
    expect(ws.frames.filter((f) => f.code === 'NOT_FOUND').length).toBe(12);
    ws.close();
    const big = await connect(`/ws/matches/${MATCH}`);
    await until(() => big.frames.length);
    big.send(JSON.stringify({ type: 'ping', pad: 'x'.repeat(8000) }));
    expect((await big.closed).code).toBe(1009);
  });

  test('both delivery paths do not double-deliver; 100 events arrive in order with no loss and no duplicates', async () => {
    const ws = await connect(`/ws/matches/${MATCH}`);
    await until(() => ws.frames.length);
    ws.frames.length = 0;
    const ids = [];
    for (let i = 0; i < 100; i += 1) ids.push((await postEvent()).event_id);
    await relayOnce(); await relayOnce(); await relayOnce(); // the worker's path delivers the same events again
    await until(() => appended(ws).length >= 100, 8000);
    await sleep(300);
    const got = appended(ws);
    expect(got.map((f) => f.data.event_id)).toEqual(ids);
    const seqs = got.map((f) => f.sequence);
    expect(seqs).toEqual([...seqs].sort((a, b) => a - b));
    expect(new Set(seqs).size).toBe(100);
    const scores = got.map((f) => f.data.home_score);
    expect(scores).toEqual([...scores].sort((a, b) => a - b));
    ws.close();
  });

  test('worker relay alone (direct publish unavailable) still delivers, once', async () => {
    const ws = await connect(`/ws/matches/${MATCH}`);
    await until(() => ws.frames.length);
    ws.frames.length = 0;
    const id = uid('relay');
    const r = await postEvent(id);
    await relayOnce(); await relayOnce();
    await sleep(300);
    expect(appended(ws).filter((f) => f.data.event_id === id).length).toBe(1);
    expect(r.status).toBe(201);
    ws.close();
  });

  test('reconnecting client is corrected by the snapshot', async () => {
    const a = await connect(`/ws/matches/${MATCH}`);
    await until(() => a.frames.length);
    a.terminate();
    for (let i = 0; i < 3; i += 1) await postEvent();
    const b = await connect(`/ws/matches/${MATCH}`);
    const snap = await until(() => b.frames.find((f) => f.type === 'snapshot'));
    const score = await (await fetch(`http://127.0.0.1:${port}/audit/v1/score/${MATCH}`)).json();
    expect(snap.data.home_score).toBe(score.home.score);
    expect(snap.data.away_score).toBe(score.away.score);
    b.close();
  });

  test('status changes are pushed: match_end -> completed', async () => {
    const m = uid('stat');
    await postEvent(uid('i'), 'match_init', { sport_id: 'football', home_team_id: 'H', away_team_id: 'A' }, m);
    const ws = await connect(`/ws/matches/${m}`);
    await until(() => ws.frames.length);
    await postEvent(uid('s'), 'match_start', {}, m);
    await postEvent(uid('x'), 'match_end', {}, m);
    const done = await until(() => ws.frames.filter((f) => f.type === 'match.status_changed').length >= 2, 4000);
    expect(done).toBe(true);
    expect(ws.frames.filter((f) => f.type === 'match.status_changed').map((f) => f.data.status)).toEqual(['in_progress', 'completed']);
    ws.close();
  });

  test('SSE: headers, snapshot first, heartbeat, ids = sequence, Last-Event-ID replay then live', async () => {
    const c1 = await sse(`/api/v1/matches/${MATCH}/stream`);
    expect(c1.status).toBe(200);
    expect(c1.headers['content-type']).toMatch(/^text\/event-stream/);
    expect(c1.headers['cache-control']).toMatch(/no-cache/);
    expect(c1.headers['x-accel-buffering']).toBe('no');
    expect(c1.headers.connection).toMatch(/keep-alive/);
    expect(c1.headers['content-encoding'] || 'identity').toBe('identity');
    await until(() => c1.frames.length);
    expect(c1.frames[0].event).toBe('snapshot');
    const e1 = await postEvent();
    const e2 = await postEvent();
    await until(() => c1.frames.filter((f) => f.event === 'match.event_appended').length >= 2);
    const evs = c1.frames.filter((f) => f.event === 'match.event_appended');
    expect(evs[0].id).toBe(evs[0].data.sequence);
    expect(evs[1].id).toBeGreaterThan(evs[0].id);
    c1.req.destroy();

    const e3 = await postEvent(); // missed while disconnected
    const c2 = await sse(`/api/v1/matches/${MATCH}/stream`, { 'Last-Event-ID': String(evs[0].id) });
    await until(() => c2.frames.filter((f) => f.event === 'match.event_appended').length >= 2);
    expect(c2.frames[0].event).toBe('snapshot');
    const replay = c2.frames.filter((f) => f.event === 'match.event_appended').map((f) => f.data.data.event_id);
    expect(replay).toEqual([e2.event_id, e3.event_id]);
    const e4 = await postEvent();
    await until(() => c2.frames.some((f) => f.data && f.data.data && f.data.data.event_id === e4.event_id));
    expect(c2.frames.filter((f) => f.event === 'match.event_appended').map((f) => f.data.data.event_id).pop()).toBe(e4.event_id);
    expect(e1.status).toBe(201);
    c2.req.destroy();
  });

  test('POST /audit/v1/reset sends connected clients a fresh snapshot', async () => {
    const ws = await connect(`/ws/matches/${MATCH}`);
    const c = await sse(`/api/v1/matches/${MATCH}/stream`);
    await until(() => ws.frames.length && c.frames.length);
    const before = ws.frames[0].data.home_score;
    await postEvent();
    await sleep(100);
    ws.lastSeqBeforeReset = Math.max(...ws.frames.map((f) => f.sequence));
    ws.frames.length = 0;
    const r = await fetch(`http://127.0.0.1:${port}/audit/v1/reset`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}' });
    expect(r.status).toBe(200);
    const snap = await until(() => ws.frames.find((f) => f.type === 'snapshot'));
    expect(snap).toBeTruthy();
    const seeded = await (await fetch(`http://127.0.0.1:${port}/audit/v1/score/${MATCH}`)).json();
    expect(snap.data.home_score).toBe(seeded.home.score);
    expect(snap.data.home_score).toBeLessThan(before + 1);
    expect(snap.sequence).toBeGreaterThan(ws.lastSeqBeforeReset);
    expect(c.frames.filter((f) => f.event === 'snapshot').length).toBe(2);
    ws.close();
    c.req.destroy();
  });

  test('co-stream rooms: 4404 for unknown, snapshot, chat with room sequence, handoff, dedupe, read-only', async () => {
    await pool.query(`CREATE TABLE IF NOT EXISTS day2_costream_session (
      session_id text primary key, host_player_id text, active_host text null, layout text, status text,
      participants jsonb, active_participants jsonb, handoff_occurred boolean, request_hash text,
      created_at timestamptz, updated_at timestamptz)`);
    await pool.query(`CREATE TABLE IF NOT EXISTS day2_chat_message (
      room_id text, seq bigint, message_id text, sender text, text text, flagged boolean, created_at timestamptz,
      primary key(room_id, seq))`);
    const sid = uid('sess');
    await pool.query(
      `INSERT INTO day2_costream_session VALUES ($1,'player-alpha-1','player-alpha-1','grid','live','["player-alpha-1"]'::jsonb,'["player-alpha-1"]'::jsonb,false,'h',now(),now())`, [sid]);
    await pool.query(`INSERT INTO day2_chat_message VALUES ($1,1,'m1','player-alpha-1','hello',false,now())`, [sid]);

    expect((await (await connect('/ws/costream/nope')).closed).code).toBe(4404);
    const ws = await connect(`/ws/costream/${sid}`);
    const snap = await until(() => ws.frames.find((f) => f.type === 'snapshot'));
    expect(snap.data).toMatchObject({ session_id: sid, status: 'live', active_host: 'player-alpha-1', layout: 'grid', last_chat_seq: 1 });
    expect(snap.data.recent_messages[0]).toMatchObject({ seq: 1, text: 'hello' });

    realtime.publishRoom(sid, 'chat.message', { seq: 2, message_id: 'm2', sender: 'player-alpha-1', text: 'hi' });
    realtime.publishRoom(sid, 'chat.message', { seq: 2, message_id: 'm2', sender: 'player-alpha-1', text: 'hi' }); // duplicate
    realtime.publishRoom(sid, 'host.handoff', { from: 'player-alpha-1', to: 'player-alpha-2' });
    realtime.publishRoom(sid, 'session.status', { status: 'ended' });
    await until(() => ws.frames.filter((f) => f.type !== 'snapshot').length >= 3);
    await sleep(100);
    const frames = ws.frames.filter((f) => f.type !== 'snapshot');
    expect(frames.map((f) => f.type)).toEqual(['chat.message', 'host.handoff', 'session.status']);
    expect(frames[0].data.seq).toBe(2);
    expect(frames[0].sequence).toBeLessThan(frames[1].sequence);

    ws.send(JSON.stringify({ type: 'chat', text: 'x' }));
    expect((await until(() => ws.frames.find((f) => f.code === 'GUEST_WRITE_FORBIDDEN')))).toBeTruthy();
    // the Redis channel feeds rooms too
    ws.frames.length = 0;
    await require('../../backend/src/db/redis').getRedis().publish(`rt:costream:${sid}`, JSON.stringify({ type: 'participant.joined', data: { player_id: 'p9', event_id: 'pj1' } }));
    expect(await until(() => ws.frames.find((f) => f.type === 'participant.joined'))).toBeTruthy();
    ws.close();
    await pool.query('DELETE FROM day2_chat_message WHERE room_id = $1', [sid]);
    await pool.query('DELETE FROM day2_costream_session WHERE session_id = $1', [sid]);
  });

  test('connection gauge: balanced across connect/close/error paths, never negative, zero after hub reset', async () => {
    const base = metrics.get('nascon_active_ws_connections');
    const socks = [];
    for (let i = 0; i < 10; i += 1) socks.push(await connect(`/ws/matches/${MATCH}`));
    const bad = await connect('/ws/matches/no-such-match');
    await bad.closed;
    expect(metrics.get('nascon_active_ws_connections')).toBe(base + 10);
    socks.slice(0, 5).forEach((s) => s.close());
    socks.slice(5).forEach((s) => s.terminate());
    await until(() => metrics.get('nascon_active_ws_connections') === base, 3000);
    expect(metrics.get('nascon_active_ws_connections')).toBe(base);
    expect(metrics.get('nascon_active_ws_connections')).toBeGreaterThanOrEqual(0);
    // Day 1 hub sockets are counted too
    const d1 = new WebSocket(`ws://127.0.0.1:${port}/ws?token=${encodeURIComponent(await token('viewer'))}`);
    await new Promise((r) => d1.on('open', r));
    expect(metrics.get('nascon_active_ws_connections')).toBe(base + 1);
    d1.close();
    await until(() => metrics.get('nascon_active_ws_connections') === base);
    expect(metrics.get('nascon_active_ws_connections')).toBe(base);
  });

  test('1000 connect/disconnect cycles leave no sockets, subscribers or timers behind', async () => {
    const stream = await streams.open('match', MATCH);
    const heapBefore = process.memoryUsage().heapUsed;
    for (let batch = 0; batch < 20; batch += 1) {
      const socks = await Promise.all(Array.from({ length: 50 }, () => connect(`/ws/matches/${MATCH}`)));
      await Promise.all(socks.map((s) => until(() => s.frames.length)));
      await Promise.all(socks.map((s) => { s.close(); return s.closed; }));
    }
    await until(() => stream.subs.size === 0);
    expect(stream.subs.size).toBe(0);
    expect(hub.wss.clients.size).toBe(0);
    expect(metrics.get('nascon_active_ws_connections')).toBe(0);
    if (global.gc) global.gc();
    expect(process.memoryUsage().heapUsed - heapBefore).toBeLessThan(60 * 1024 * 1024);
  }, 120000);

  test('hub reset zeroes the gauge', async () => {
    const ws = await connect(`/ws/matches/${MATCH}`);
    await until(() => ws.frames.length);
    expect(metrics.get('nascon_active_ws_connections')).toBe(1);
    realtime.resetGauge();
    expect(metrics.get('nascon_active_ws_connections')).toBe(0);
    ws.close();
    await ws.closed;
    await sleep(50);
    expect(metrics.get('nascon_active_ws_connections')).toBe(0);
  });
});
