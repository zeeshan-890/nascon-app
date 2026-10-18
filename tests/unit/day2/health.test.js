const request = require('supertest');
const express = require('express');
const { createHealth, withTimeout } = require('../../../backend/day2/health');

const ok = async () => {};
const fail = async () => { throw new Error('internal secret detail postgres://user:pw@host'); };
const hang = () => new Promise(() => {});

function app(checks, opts = {}) {
  const a = express();
  const router = createHealth({ checks: { postgres: ok, seed: ok, redis: ok, media: ok, ...checks }, ready: () => true, ...opts });
  a.use(router);
  return { a, router };
}

describe('GET /health', () => {
  test('all dependencies up -> byte-exact {"status":"ok"} and 200', async () => {
    const r = await request(app({}).a).get('/health');
    expect(r.status).toBe(200);
    expect(r.text).toBe('{"status":"ok"}');
    expect(r.headers['content-type']).toMatch(/application\/json/);
  });

  test.each(['postgres', 'redis'])('%s down -> 503 degraded naming only that dependency, no internals', async (name) => {
    const r = await request(app({ [name]: fail }).a).get('/health');
    expect(r.status).toBe(503);
    expect(r.body.status).toBe('degraded');
    expect(r.body.failing).toContain(name);
    expect(r.text).not.toMatch(/secret|postgres:\/\/|pw@/);
  });

  test('a missing seed is reported as "seed"', async () => {
    const r = await request(app({ seed: fail }).a).get('/health');
    expect(r.status).toBe(503);
    expect(r.body).toEqual({ status: 'degraded', failing: ['seed'] });
  });

  test('when postgres is down the seed check is not blamed separately', async () => {
    const r = await request(app({ postgres: fail, seed: fail }).a).get('/health');
    expect(r.body.failing).toEqual(['postgres']);
  });

  test('media is checked only when enabled', async () => {
    expect((await request(app({ media: fail }, { mediaEnabled: () => false }).a).get('/health')).status).toBe(200);
    const down = await request(app({ media: fail }, { mediaEnabled: () => true }).a).get('/health');
    expect(down.status).toBe(503);
    expect(down.body.failing).toEqual(['media']);
  });

  test('not ready yet (booting) -> 503 NOT_READY envelope, checks not run', async () => {
    const spy = jest.fn(ok);
    const r = await request(app({ postgres: spy }, { ready: () => false }).a).get('/health');
    expect(r.status).toBe(503);
    expect(r.body.error.code).toBe('NOT_READY');
    expect(spy).not.toHaveBeenCalled();
  });

  test('a hanging dependency is cut off after ~1 s and never hangs the probe', async () => {
    const t0 = Date.now();
    const r = await request(app({ redis: hang }).a).get('/health');
    const ms = Date.now() - t0;
    expect(r.status).toBe(503);
    expect(r.body.failing).toEqual(['redis']);
    expect(ms).toBeGreaterThanOrEqual(900);
    expect(ms).toBeLessThan(2500);
  }, 10000);

  test('the verdict is cached for ~2 s', async () => {
    let t = 1000;
    const spy = jest.fn(ok);
    const { a } = app({ postgres: spy }, { now: () => t });
    await request(a).get('/health');
    await request(a).get('/health');
    await request(a).get('/health');
    expect(spy).toHaveBeenCalledTimes(1); // a probe flood does not reach the database
    t += 2500;
    await request(a).get('/health');
    expect(spy).toHaveBeenCalledTimes(2);
  });

  test('recovers on the next evaluation after a failure (no warm-up delay)', async () => {
    let t = 0;
    let up = false;
    const { a } = app({ redis: async () => { if (!up) throw new Error('x'); } }, { now: () => t });
    expect((await request(a).get('/health')).status).toBe(503);
    up = true;
    t += 2100;
    const r = await request(a).get('/health');
    expect(r.status).toBe(200);
    expect(r.text).toBe('{"status":"ok"}');
  });

  test('never throws even if a check throws synchronously', async () => {
    const r = await request(app({ postgres: () => { throw new Error('sync'); } }).a).get('/health');
    expect(r.status).toBe(503);
  });

  test('withTimeout resolves fast promises and rejects slow ones', async () => {
    await expect(withTimeout(Promise.resolve(5), 50)).resolves.toBe(5);
    await expect(withTimeout(hang(), 30)).rejects.toThrow('timeout');
  });
});
