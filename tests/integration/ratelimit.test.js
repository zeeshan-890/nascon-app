const express = require('../../backend/node_modules/express');
const request = require('../../backend/node_modules/supertest');
const createApp = require('../../backend/src/app');
const { createLimiter } = require('../../backend/src/middleware/rateLimit');
const { describeDb } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');

describeDb('rate limiting', () => {
  beforeAll(() => resetDb());
  afterAll(() => pool.end());

  test('ping is public: 200 {"pong":true} with rate headers', async () => {
    const r = await (await api()).get('/api/v1/ping');
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ pong: true });
    expect(r.headers['x-ratelimit-limit']).toBe('100');
    expect(r.headers['x-ratelimit-remaining']).toBeDefined();
  });

  // Each scenario gets its own app+limiter so counts are isolated (redis: null forces the in-memory path;
  // Redis is unavailable locally, so the default getRedis() path falls back to the same logic).
  const scenarios = [['in-memory (Redis disabled)', () => createLimiter({ redis: null })], ['default (Redis -> fallback)', () => createLimiter()]];

  describe.each(scenarios)('%s', (name, mk) => {
    test('150 quick pings: first 100 are 200, next 50 are 429 with Retry-After', async () => {
      const limiter = mk();
      const app = request(createApp({ limiter }));
      const statuses = [];
      let last;
      for (let i = 0; i < 150; i += 1) {
        last = await app.get('/api/v1/ping').set('X-Forwarded-For', `8.8.8.${i}`);
        statuses.push(last.status);
      }
      expect(statuses.filter((s) => s === 200)).toHaveLength(100);
      expect(statuses.slice(0, 100).every((s) => s === 200)).toBe(true);
      expect(statuses.slice(100).every((s) => s === 429)).toBe(true);
      expect(last.headers['retry-after']).toMatch(/^[1-9]\d*$/);
      expect(last.body.error.code).toBe('RATE_LIMITED');
      expect(last.body.error.details.retry_after_seconds).toBe(Number(last.headers['retry-after']));
      expect(last.headers['x-ratelimit-remaining']).toBe('0');
      limiter.gate.stop();
    });

    test('shortened window: recovers after the window passes', async () => {
      const limiter = createLimiter({ redis: name.startsWith('in-memory') ? null : undefined, limit: 5, windowMs: 600 });
      const app = request(createApp({ limiter }));
      const s = [];
      for (let i = 0; i < 8; i += 1) s.push((await app.get('/api/v1/ping')).status);
      expect(s).toEqual([200, 200, 200, 200, 200, 429, 429, 429]);
      await new Promise((r) => setTimeout(r, 700));
      expect((await app.get('/api/v1/ping')).status).toBe(200);
      limiter.gate.stop();
    });
  });

  test('fake clock: +11 s later the window is empty again', async () => {
    let t = 1000000;
    const limiter = createLimiter({ redis: null, now: () => t });
    const app = request(createApp({ limiter }));
    for (let i = 0; i < 150; i += 1) await app.get('/api/v1/ping');
    expect((await app.get('/api/v1/ping')).status).toBe(429);
    t += 11000;
    expect((await app.get('/api/v1/ping')).status).toBe(200);
    limiter.gate.stop();
  });

  test('X-Forwarded-For is ignored (cannot dodge or poison the limit)', async () => {
    const limiter = createLimiter({ redis: null, limit: 3 });
    const app = request(createApp({ limiter }));
    const s = [];
    for (let i = 0; i < 5; i += 1) s.push((await app.get('/api/v1/ping').set('X-Forwarded-For', `1.2.3.${i}`)).status);
    expect(s).toEqual([200, 200, 200, 429, 429]);
    limiter.gate.stop();
  });

  test('auth endpoints are under the limiter', async () => {
    const limiter = createLimiter({ redis: null, limit: 2 });
    const app = request(createApp({ limiter }));
    await app.post('/api/v1/auth/login').send({});
    await app.get('/api/v1/me');
    const r = await app.post('/api/v1/auth/login').send({ email: 'viewer@nascon.test', password: 'Nascon@26' });
    expect(r.status).toBe(429);
    limiter.gate.stop();
  });

  test('429 body is exact for unknown /api/v1 routes too (limiter runs first)', async () => {
    const limiter = createLimiter({ redis: null, limit: 1 });
    const app = request(createApp({ limiter }));
    expect((await app.get('/api/v1/nothing')).status).toBe(404);
    expect((await app.get('/api/v1/nothing')).status).toBe(429);
    limiter.gate.stop();
  });

  test('/audit/v1/events, /audit/v1/rbac-matrix and /health are never limited (300 requests each)', async () => {
    const limiter = createLimiter({ redis: null, limit: 5 });
    const server = createApp({ limiter }).listen(0, '127.0.0.1');
    await new Promise((r) => server.once('listening', r));
    server.setMaxListeners(0);
    const app = request(server);
    try {
      for (const [m, p] of [['post', '/audit/v1/events'], ['get', '/health'], ['post', '/audit/v1/rbac-matrix']]) {
        const results = [];
        for (let batch = 0; batch < 6; batch += 1) {
          results.push(...await Promise.all(Array.from({ length: 50 }, () => (m === 'post' ? app.post(p).send({}) : app.get(p)))));
        }
        expect(results).toHaveLength(300);
        expect(results.filter((r) => r.status === 429)).toHaveLength(0);
        expect(results.every((r) => r.headers['x-ratelimit-limit'] === undefined)).toBe(true);
      }
    } finally {
      await new Promise((r) => server.close(r));
      limiter.gate.stop();
    }
  });

  test('limiter failure never breaks the API', async () => {
    const bad = (req, res, next) => next();
    const app = express();
    app.use(bad);
    const limiter = createLimiter({ redis: () => { throw new Error('no redis'); } });
    const a = request(createApp({ limiter }));
    expect((await a.get('/api/v1/ping')).status).toBe(200);
    limiter.gate.stop();
  });
});
