const { describeDb } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');
const config = require('../../backend/src/config');

describeDb('health', () => {
  beforeAll(async () => { await resetDb(); });
  afterEach(() => { config.state.ready = true; });
  afterAll(() => pool.end());

  test('200 ok when ready', async () => {
    const r = await (await api()).get('/health');
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ status: 'ok' });
    expect(r.headers['content-type']).toMatch(/application\/json/);
  });

  test('503 NOT_READY when state.ready is false', async () => {
    const a = await api();
    config.state.ready = false;
    const r = await a.get('/health');
    expect(r.status).toBe(503);
    expect(r.body).toEqual({ error: { code: 'NOT_READY', message: 'Service starting', details: {} } });
    config.state.ready = true;
    expect((await a.get('/health')).status).toBe(200);
  });

  test('responds in under 200 ms', async () => {
    const a = await api();
    await a.get('/health');
    const t = Date.now();
    const r = await a.get('/health');
    expect(r.status).toBe(200);
    expect(Date.now() - t).toBeLessThan(200);
  });
});
