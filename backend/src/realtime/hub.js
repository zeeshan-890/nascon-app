const { WebSocketServer } = require('ws');
const Redis = require('ioredis');
const config = require('../config');
const logger = require('../logger');
const pool = require('../db/pool');
const { Id } = require('../http/validators');
const { authorize, findPolicy } = require('../domain/rbac/engine');

const MAX_SUBSCRIPTIONS = 50;
const MAX_PAYLOAD = 4096;
const MAX_MSG_PER_SEC = 20;
const PING_MS = 25000;

const TOPIC = /^(match|league):(.+)$/;

// Resolves the tenant a topic belongs to: undefined = unknown topic, {open:true} = readable by all.
async function resolveTopic(kind, id) {
  if (!Id.safeParse(id).success) return undefined;
  if (kind === 'league') {
    const { rows } = await pool.query('SELECT league_id, tenant_id FROM leagues WHERE league_id = $1', [id]);
    return rows[0];
  }
  const { rows } = await pool.query(
    `SELECT m.match_id, m.league_id, l.tenant_id FROM matches m
     LEFT JOIN leagues l ON l.league_id = m.league_id WHERE m.match_id = $1`,
    [id],
  );
  if (!rows[0]) return undefined;
  return rows[0].league_id ? rows[0] : { ...rows[0], open: true };
}

function createHub(server, { policies } = {}) {
  const wss = new WebSocketServer({ server, path: '/ws', maxPayload: MAX_PAYLOAD });
  const topics = new Map(); // topic -> Set<ws>
  let subscriber = null;

  const send = (ws, obj) => {
    if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
  };

  function addToTopic(topic, ws) {
    if (!topics.has(topic)) topics.set(topic, new Set());
    topics.get(topic).add(ws);
  }

  function removeFromTopic(topic, ws) {
    const set = topics.get(topic);
    if (!set) return;
    set.delete(ws);
    if (!set.size) topics.delete(topic);
  }

  async function allowed(principal, action, resource) {
    const list = policies ? await policies() : await require('../services/rbacService').ensurePolicies();
    if (!findPolicy(principal.role, action, list)) return false;
    return resource.open || authorize(principal, action, resource, list).allowed;
  }

  async function subscribe(ws, topic) {
    const err = (code) => send(ws, { type: 'error', topic, code });
    const m = typeof topic === 'string' ? TOPIC.exec(topic) : null;
    if (!m) return err('NOT_FOUND');
    if (ws.topics.has(topic)) return undefined;
    if (ws.topics.size >= MAX_SUBSCRIPTIONS) return err('TOO_MANY_SUBSCRIPTIONS');
    const resource = await resolveTopic(m[1], m[2]);
    if (!resource) return err('NOT_FOUND');
    const action = m[1] === 'league' ? 'league.read' : 'match.read';
    if (!(await allowed(ws.principal, action, resource))) return err('FORBIDDEN');
    if (ws.topics.size >= MAX_SUBSCRIPTIONS || ws.readyState !== ws.OPEN) return undefined;
    ws.topics.add(topic);
    addToTopic(topic, ws);
    return send(ws, { type: 'subscribed', topic });
  }

  function onMessage(ws, raw) {
    const now = Date.now();
    ws.recent = ws.recent.filter((t) => now - t < 1000);
    ws.recent.push(now);
    if (ws.recent.length > MAX_MSG_PER_SEC) {
      ws.close(1008, 'rate limit');
      return;
    }
    let msg;
    try {
      msg = JSON.parse(raw.toString());
    } catch (e) {
      return;
    }
    if (!msg || typeof msg !== 'object') return;
    if (typeof msg.subscribe === 'string') {
      subscribe(ws, msg.subscribe).catch((e) => logger.warn({ err: e.message }, 'ws subscribe failed'));
    } else if (typeof msg.unsubscribe === 'string') {
      ws.topics.delete(msg.unsubscribe);
      removeFromTopic(msg.unsubscribe, ws);
    }
  }

  wss.on('connection', (ws, req) => {
    let principal = null;
    try {
      const url = new URL(req.url, 'http://localhost');
      principal = require('../services/authService').verifyAccessToken(url.searchParams.get('token'));
    } catch (e) {
      principal = null;
    }
    if (!principal) {
      ws.close(4401, 'unauthorized');
      return;
    }
    ws.principal = principal;
    ws.topics = new Set();
    ws.recent = [];
    ws.alive = true;
    ws.on('pong', () => { ws.alive = true; });
    ws.on('message', (raw) => onMessage(ws, raw));
    ws.on('error', () => {});
    ws.on('close', () => {
      for (const t of ws.topics) removeFromTopic(t, ws);
      ws.topics.clear();
    });
  });

  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (ws.alive === false) {
        ws.terminate();
      } else {
        ws.alive = false;
        try { ws.ping(); } catch (e) { /* closing */ }
      }
    }
  }, PING_MS);
  heartbeat.unref();

  // One dedicated subscriber connection per API process; Redis being down only disables pushes.
  function startSubscriber() {
    subscriber = new Redis(config.redisUrl, {
      maxRetriesPerRequest: null,
      retryStrategy: (n) => Math.min(n * 500, 5000),
    });
    subscriber.on('error', () => {});
    subscriber.on('ready', () => {
      subscriber.psubscribe('rt:*').catch(() => {});
    });
    subscriber.on('pmessage', (pattern, channel, message) => {
      const set = topics.get(channel.slice(3));
      if (!set) return;
      for (const ws of set) if (ws.readyState === ws.OPEN) ws.send(message);
    });
  }
  startSubscriber();

  function close() {
    clearInterval(heartbeat);
    if (subscriber) subscriber.disconnect();
    for (const ws of wss.clients) ws.terminate();
    return new Promise((resolve) => wss.close(() => resolve()));
  }

  return { wss, close, topics };
}

module.exports = { createHub, MAX_SUBSCRIPTIONS, MAX_PAYLOAD, MAX_MSG_PER_SEC };
