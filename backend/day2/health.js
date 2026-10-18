// GET /health: 200 {"status":"ok"} only when Postgres, Redis, the baseline seed and (if MEDIA_HOST is set)
// the media server are reachable. Each check has a hard 1 s timeout; the verdict is cached for ~2 s.
const net = require('net');
const express = require('express');
const config = require('../src/config');
const pool = require('../src/db/pool');
const { getRedis } = require('../src/db/redis');
const { send, errorBody } = require('../src/http/respond');

const CHECK_TIMEOUT_MS = 1000;
const CACHE_MS = 2000;

function withTimeout(promise, ms = CHECK_TIMEOUT_MS) {
  let timer;
  const timeout = new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('timeout')), ms); });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

const defaultChecks = {
  postgres: async () => { await pool.query('SELECT 1'); },
  seed: async () => {
    const { rowCount } = await pool.query('SELECT 1 FROM tenants LIMIT 1');
    if (!rowCount) throw new Error('not seeded');
  },
  redis: async () => {
    const redis = getRedis();
    if (redis.status === 'wait') await redis.connect();
    if ((await redis.ping()) !== 'PONG') throw new Error('bad ping');
  },
  // Enabled only when MEDIA_HOST is set: a TCP connect to the HLS port of the media server.
  media: async () => {
    const host = process.env.MEDIA_HOST;
    if (!host) return;
    const port = Number(process.env.MEDIA_HEALTH_PORT) || 8888;
    await new Promise((resolve, reject) => {
      const socket = net.connect({ host, port });
      socket.once('connect', () => { socket.destroy(); resolve(); });
      socket.once('error', (err) => { socket.destroy(); reject(err); });
    });
  },
};

function createHealth({ checks = defaultChecks, now = Date.now, ready = () => config.state.ready, mediaEnabled = () => Boolean(process.env.MEDIA_HOST) } = {}) {
  const router = express.Router();
  let cached = null;

  async function evaluate() {
    const names = ['postgres', 'seed', 'redis'];
    if (mediaEnabled()) names.push('media');
    const results = await Promise.all(names.map(async (name) => {
      try { await withTimeout(Promise.resolve().then(checks[name])); return null; } catch (e) { return name; }
    }));
    // a dead database makes the seed check meaningless; report only the root cause
    const failing = results.filter(Boolean).filter((n) => !(n === 'seed' && results.includes('postgres')));
    return failing;
  }

  router.get('/health', async (req, res) => {
    try {
      if (!ready()) return send(res, 503, errorBody('NOT_READY', 'Service starting'));
      if (!cached || now() - cached.at > CACHE_MS) {
        const failing = await evaluate();
        cached = { at: now(), failing };
      }
      if (cached.failing.length === 0) return send(res, 200, { status: 'ok' });
      return send(res, 503, { status: 'degraded', failing: cached.failing });
    } catch (e) {
      return send(res, 503, { status: 'degraded', failing: ['health'] });
    }
  });

  router.resetCache = () => { cached = null; };
  return router;
}

module.exports = { createHealth, withTimeout, defaultChecks };
