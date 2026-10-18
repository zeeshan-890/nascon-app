// Unauthenticated read-only WebSocket channels: /ws/matches/{match_id} and /ws/costream/{session_id}.
// Mounted on the shared HTTP server through the Day 1 hub's upgrade dispatcher (see src/realtime/hub.js).
const { WebSocketServer } = require('ws');
const logger = require('../../src/logger');
const { open } = require('./streams');
const gauge = require('./gauge');

const MAX_PAYLOAD = 4096;
const MAX_SUBSCRIPTIONS = 10; // per connection (the path's own channel counts as one)
const MAX_MSG_PER_SEC = 20;
const MAX_BUFFERED = 1024 * 1024; // bytes queued on one socket before it is dropped as a slow consumer
const PING_MS = 25000;

const PATHS = [
  { re: /^\/ws\/matches\/([^/]+)\/?$/, kind: 'match', label: 'match' },
  { re: /^\/ws\/costream\/([^/]+)\/?$/, kind: 'room', label: 'session' },
];
// Verbs that would mutate state if applied; refused for guests, not supported on the socket for members.
const WRITE_VERB = /^(chat|message|msg|send|post|say|reply|score|event|follow|unfollow|like|react|vote|kick|mute|ban|flag|report|handoff|host|join|leave|update|create|delete|set|add|remove)/i;

const stats = { slowDropped: 0 };

function memberOf(token) {
  if (!token || token.startsWith('guest_')) return false;
  try {
    return !!require('../../src/services/authService').verifyAccessToken(token);
  } catch (e) {
    return false;
  }
}

function tokenOf(req, url) {
  const q = url.searchParams.get('token');
  if (q) return q;
  const auth = req.headers.authorization;
  const m = typeof auth === 'string' ? /^\s*bearer\s+(\S+)/i.exec(auth) : null;
  return m ? m[1] : '';
}

function createWsServer() {
  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_PAYLOAD });
  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (ws.alive === false) { ws.terminate(); continue; }
      ws.alive = false;
      try { ws.ping(); } catch (e) { /* closing */ }
    }
  }, PING_MS);
  heartbeat.unref();

  const send = (ws, obj) => { if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj)); };

  function sinkFor(ws) {
    return {
      write(entry) {
        if (ws.readyState !== ws.OPEN) return false;
        if (ws.bufferedAmount > MAX_BUFFERED) { // slow consumer: drop it rather than buffer without bound
          stats.slowDropped += 1;
          ws.terminate();
          return false;
        }
        ws.send(entry.ws);
        return true;
      },
      close(code, reason) { try { ws.close(code, reason); } catch (e) { /* closed */ } },
    };
  }

  async function subscribeTo(ws, kind, id) {
    const key = `${kind}:${id}`;
    if (ws.sinks.has(key)) return true;
    if (ws.sinks.size >= MAX_SUBSCRIPTIONS) { send(ws, { type: 'error', code: 'TOO_MANY_SUBSCRIPTIONS', topic: key }); return false; }
    const stream = await open(kind, id);
    if (!stream) { send(ws, { type: 'error', code: 'NOT_FOUND', topic: key }); return false; }
    if (ws.readyState !== ws.OPEN) return false;
    const sink = sinkFor(ws);
    ws.sinks.set(key, { stream, sink });
    const ok = await stream.join(sink);
    if (!ok) ws.sinks.delete(key);
    return ok;
  }

  function leaveAll(ws) {
    for (const { stream, sink } of ws.sinks.values()) stream.leave(sink);
    ws.sinks.clear();
  }

  async function onMessage(ws, raw) {
    const now = Date.now();
    ws.recent = ws.recent.filter((t) => now - t < 1000);
    ws.recent.push(now);
    if (ws.recent.length > MAX_MSG_PER_SEC) { ws.close(1008, 'rate limit'); return; }
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch (e) { msg = null; }
    if (!msg || typeof msg !== 'object' || Array.isArray(msg)) { send(ws, { type: 'error', code: 'INVALID_MESSAGE' }); return; }
    const verb = [msg.type, msg.action, msg.op].find((v) => typeof v === 'string') || '';
    if (verb === 'ping') { send(ws, { type: 'pong', ts: new Date().toISOString() }); return; }
    if (verb === 'pong') return;
    if (typeof msg.subscribe === 'string' || typeof msg.unsubscribe === 'string') {
      const topic = msg.subscribe || msg.unsubscribe;
      const m = /^(match|room|costream):(.+)$/.exec(topic);
      if (!m) { send(ws, { type: 'error', code: 'NOT_FOUND', topic }); return; }
      const kind = m[1] === 'match' ? 'match' : 'room';
      if (typeof msg.unsubscribe === 'string') {
        const entry = ws.sinks.get(`${kind}:${m[2]}`);
        if (entry) { entry.stream.leave(entry.sink); ws.sinks.delete(`${kind}:${m[2]}`); }
        return;
      }
      if (await subscribeTo(ws, kind, m[2])) send(ws, { type: 'subscribed', topic });
      return;
    }
    if (verb === 'snapshot' || verb === 'resync') {
      await Promise.all([...ws.sinks.values()].map(({ stream, sink }) => stream.resendSnapshot(sink)));
      return;
    }
    if (WRITE_VERB.test(verb)) {
      // Guests are read-only: refuse, never apply, never close the connection (FR-4.3).
      if (!ws.member) send(ws, { type: 'error', code: 'GUEST_WRITE_FORBIDDEN', message: 'Guests have read-only access.' });
      else send(ws, { type: 'error', code: 'WRITE_NOT_SUPPORTED', message: 'Use the REST API for writes.' });
      return;
    }
    send(ws, { type: 'error', code: 'UNKNOWN_MESSAGE' });
  }

  // Returns true when the request was handled here (the caller must not touch the socket then).
  function handleUpgrade(req, socket, head) {
    let url;
    try { url = new URL(req.url, 'http://localhost'); } catch (e) { return false; }
    const route = PATHS.find((p) => p.re.test(url.pathname));
    if (!route) return false;
    let id;
    try { id = decodeURIComponent(route.re.exec(url.pathname)[1]); } catch (e) { id = ''; }
    const member = memberOf(tokenOf(req, url));
    wss.handleUpgrade(req, socket, head, (ws) => {
      ws.member = member;
      ws.sinks = new Map();
      ws.recent = [];
      ws.alive = true;
      gauge.track(ws);
      ws.on('pong', () => { ws.alive = true; });
      ws.on('error', () => {});
      ws.on('close', () => leaveAll(ws));
      // A room socket opened with ?player_id=<host> that drops hands the host role to the next participant.
      const hostId = route.kind === 'room' ? url.searchParams.get('player_id') : null;
      if (hostId) {
        ws.on('close', () => {
          require('../lib/costreamHandoff').promoteHost(id, hostId).catch((err) => logger.warn({ err: err.message }, 'host handoff failed'));
        });
      }
      // Messages are processed in arrival order, after the initial subscription has finished.
      let queue = subscribeTo(ws, route.kind, id).then((ok) => {
        if (!ok && ws.readyState === ws.OPEN) ws.close(4404, `${route.label} not found`);
      }).catch((err) => {
        logger.warn({ err: err.message }, 'ws subscribe failed');
        if (ws.readyState === ws.OPEN) ws.close(1011, 'internal error');
      });
      ws.on('message', (raw) => {
        queue = queue.then(() => onMessage(ws, raw)).catch(() => {});
      });
    });
    return true;
  }

  function close() {
    clearInterval(heartbeat);
    for (const ws of wss.clients) ws.terminate();
    wss.close();
  }

  return { wss, handleUpgrade, close };
}

module.exports = { createWsServer, MAX_PAYLOAD, MAX_SUBSCRIPTIONS, stats };
