const express = require('../../../backend/node_modules/express');
const request = require('../../../backend/node_modules/supertest');
const { createGate, createLimiter, clientIp, LUA } = require('../../../backend/src/middleware/rateLimit');
const { errorHandler } = require('../../../backend/src/middleware/errorHandler');

function clock(start = 5000000) {
  let t = start;
  const now = () => t;
  now.advance = (ms) => { t += ms; };
  return now;
}

function appWith(limiter) {
  const app = express();
  app.use(limiter);
  app.get('/p', (req, res) => res.json({ pong: true }));
  app.use(errorHandler);
  return app;
}

describe('limiter middleware (in-memory path)', () => {
  test('first 100 ok, next 50 are 429 with Retry-After; recovers after the window', async () => {
    const now = clock();
    const limiter = createLimiter({ redis: null, now });
    const app = appWith(limiter);
    const statuses = [];
    let last;
    for (let i = 0; i < 150; i += 1) {
      last = await request(app).get('/p').set('X-Forwarded-For', `9.9.9.${i}`);
      statuses.push(last.status);
      if (i === 0) {
        expect(last.headers['x-ratelimit-limit']).toBe('100');
        expect(last.headers['x-ratelimit-remaining']).toBe('99');
      }
      now.advance(5);
    }
    expect(statuses.slice(0, 100).every((s) => s === 200)).toBe(true);
    expect(statuses.slice(100).every((s) => s === 429)).toBe(true);
    expect(last.headers['retry-after']).toMatch(/^\d+$/);
    expect(Number(last.headers['retry-after'])).toBeGreaterThanOrEqual(1);
    expect(last.headers['x-ratelimit-remaining']).toBe('0');
    expect(last.body).toEqual({
      error: { code: 'RATE_LIMITED', message: 'Too many requests. Try again later.', details: { retry_after_seconds: Number(last.headers['retry-after']) } },
    });
    now.advance(11000);
    expect((await request(app).get('/p')).status).toBe(200);
    limiter.gate.stop();
  });

  test('custom limit and window', async () => {
    const now = clock();
    const limiter = createLimiter({ redis: null, now, limit: 2, windowMs: 3000 });
    const app = appWith(limiter);
    expect((await request(app).get('/p')).status).toBe(200);
    expect((await request(app).get('/p')).status).toBe(200);
    const r = await request(app).get('/p');
    expect(r.status).toBe(429);
    expect(r.headers['retry-after']).toBe('3');
    limiter.gate.stop();
  });

  test('client ip ignores X-Forwarded-For and strips ::ffff:', () => {
    expect(clientIp({ socket: { remoteAddress: '::ffff:10.0.0.5' }, headers: { 'x-forwarded-for': '1.1.1.1' } })).toBe('10.0.0.5');
    expect(clientIp({ socket: { remoteAddress: '::1' } })).toBe('::1');
    expect(clientIp({ socket: {} })).toBe('unknown');
  });
});

describe('gate: Redis path and fallback', () => {
  test('uses the Lua script and parses allowed / denied replies', async () => {
    const calls = [];
    const replies = [[1, 99], [0, 2500]];
    const fake = { status: 'ready', eval: async (...args) => { calls.push(args); return replies.shift(); } };
    const gate = createGate({ prefix: 'rl:', limit: 100, windowMs: 10000, redis: () => fake });
    expect(await gate.hit('1.2.3.4')).toEqual({ allowed: true, remaining: 99, retryMs: 0 });
    expect(await gate.hit('1.2.3.4')).toEqual({ allowed: false, remaining: 0, retryMs: 2500 });
    expect(calls[0].slice(0, 4)).toEqual([LUA, 1, 'rl:1.2.3.4', 100]);
    expect(calls[0][4]).toBe(10000);
    expect(typeof calls[0][5]).toBe('string');
    expect(LUA).toMatch(/TIME/);
    expect(LUA).toMatch(/ZREMRANGEBYSCORE/);
    expect(LUA).toMatch(/PEXPIRE/);
    gate.stop();
  });

  test('connects a lazy client first', async () => {
    const fake = { status: 'wait', connect: jest.fn(async () => { fake.status = 'ready'; }), eval: async () => [1, 5] };
    const gate = createGate({ prefix: 'x:', limit: 10, windowMs: 1000, redis: () => fake });
    expect((await gate.hit('k')).allowed).toBe(true);
    expect(fake.connect).toHaveBeenCalledTimes(1);
    gate.stop();
  });

  test('Redis error falls back to memory and enforces the same limit', async () => {
    const fake = { status: 'ready', eval: jest.fn(async () => { throw new Error('boom'); }) };
    const now = clock();
    const gate = createGate({ prefix: 'rl:', limit: 3, windowMs: 1000, now, redis: () => fake });
    const out = [];
    for (let i = 0; i < 5; i += 1) out.push((await gate.hit('ip')).allowed);
    expect(out).toEqual([true, true, true, false, false]);
    expect(fake.eval).toHaveBeenCalledTimes(1); // circuit breaker skips Redis afterwards
    gate.stop();
  });

  test('Redis timeout falls back quickly', async () => {
    const fake = { status: 'ready', eval: () => new Promise(() => {}) };
    const gate = createGate({ prefix: 'rl:', limit: 1, windowMs: 1000, redis: () => fake });
    const t0 = Date.now();
    expect((await gate.hit('ip')).allowed).toBe(true);
    expect(Date.now() - t0).toBeLessThan(1500);
    expect((await gate.hit('ip')).allowed).toBe(false);
    gate.stop();
  });

  test('connect failure falls back', async () => {
    const fake = { status: 'wait', connect: async () => { throw new Error('refused'); } };
    const gate = createGate({ prefix: 'rl:', limit: 1, windowMs: 1000, redis: () => fake });
    expect((await gate.hit('ip')).allowed).toBe(true);
    expect((await gate.hit('ip')).allowed).toBe(false);
    gate.stop();
  });

  test('test-only window override via env', () => {
    process.env.RATE_LIMIT_WINDOW_MS = '1234';
    const l = createLimiter({ redis: null });
    expect(l.gate.windowMs).toBe(1234);
    l.gate.stop();
    delete process.env.RATE_LIMIT_WINDOW_MS;
    const d = createLimiter({ redis: null });
    expect(d.gate.windowMs).toBe(10000);
    d.gate.stop();
  });
});
