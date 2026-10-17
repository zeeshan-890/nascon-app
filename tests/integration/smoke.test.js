const { describeDb } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');

describeDb('smoke', () => {
  afterAll(() => pool.end());

  test('reset then health', async () => {
    const r = await resetDb();
    expect(r.body).toEqual({
      status: 'reset_complete',
      seed: 42,
      sports: ['football', 'basketball', 'cricket', 'esports'],
      tenants: ['tenant-alpha', 'tenant-beta'],
      stock: { 'var-jersey-001-M': 1, 'var-jersey-001-L': 5 },
    });
    const h = await (await api()).get('/health');
    expect(h.status).toBe(200);
    expect(h.body).toEqual({ status: 'ok' });
  });
});
