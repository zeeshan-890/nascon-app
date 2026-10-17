const { describeDb } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');

const json = async (path, raw, type = 'application/json') =>
  (await api()).post(path).set('Content-Type', type).send(raw);

describeDb('error handling', () => {
  beforeAll(() => resetDb());
  afterAll(() => pool.end());

  describe('malformed bodies', () => {
    test.each([['{"bad'], ['"not json'], ['{a:1}'], ['[1,']])('raw %s -> 400 [body]', async (raw) => {
      const r = await json('/audit/v1/events', raw);
      expect(r.status).toBe(400);
      expect(r.body).toEqual({ error: { code: 'VALIDATION_ERROR', message: expect.any(String), details: { fields: ['body'] } } });
    });
    test.each(['/audit/v1/sports', '/audit/v1/leagues', '/audit/v1/reset'])('malformed JSON to %s -> 400', async (p) => {
      const r = await json(p, '{"bad');
      expect(r.status).toBe(400);
      expect(r.body.error.details.fields).toEqual(['body']);
    });
  });

  describe('body size', () => {
    test('1.1 MB body -> 413 PAYLOAD_TOO_LARGE', async () => {
      const big = JSON.stringify({ pad: 'x'.repeat(1.1 * 1024 * 1024) });
      const r = await json('/audit/v1/sports', big);
      expect(r.status).toBe(413);
      expect(r.body.error.code).toBe('PAYLOAD_TOO_LARGE');
      expect(r.body.error.details).toEqual({});
    });
    test('900 KB body is parsed (validation error, not 413)', async () => {
      const r = await json('/audit/v1/sports', JSON.stringify({ pad: 'x'.repeat(900 * 1024) }));
      expect(r.status).toBe(400);
      expect(r.body.error.code).toBe('VALIDATION_ERROR');
      expect(r.body.error.details.fields).toContain('sport_id');
    });
  });

  describe('routing', () => {
    test('unknown route -> exact 404 body', async () => {
      const r = await (await api()).get('/nope/nothing');
      expect(r.status).toBe(404);
      expect(r.body).toEqual({ error: { code: 'NOT_FOUND', message: 'Route not found', details: {} } });
    });
    test('unknown POST route -> 404', async () => {
      const r = await (await api()).post('/audit/v1/unknown').send({});
      expect(r.status).toBe(404);
      expect(r.body.error.code).toBe('NOT_FOUND');
    });
  });

  describe('content type', () => {
    test('text/plain with JSON text is still parsed', async () => {
      const r = await json('/audit/v1/reset', '{"seed":"x"}', 'text/plain');
      expect(r.status).toBe(400);
      expect(r.body.error.details.fields).toContain('seed');
    });
    test('text/plain valid reset body honoured', async () => {
      const r = await json('/audit/v1/reset', '{"seed":9}', 'text/plain');
      expect(r.status).toBe(200);
      expect(r.body.seed).toBe(9);
      await resetDb();
    });
    test('no content-type header still parsed', async () => {
      const r = await (await api()).post('/audit/v1/sports').unset('Content-Type').send(Buffer.from('{"sport_id":"X"}'));
      expect(r.status).toBe(400);
      expect(r.body.error.details.fields).toContain('sport_id');
    });
  });

  describe('error response shape', () => {
    test('error responses are application/json without x-powered-by', async () => {
      const responses = [
        await (await api()).get('/nope'),
        await json('/audit/v1/events', '{"bad'),
        await json('/audit/v1/sports', '{}'),
      ];
      for (const r of responses) {
        expect(r.headers['content-type']).toMatch(/^application\/json/);
        expect(r.headers['x-powered-by']).toBeUndefined();
      }
    });
    test('no stack traces in error bodies', async () => {
      const responses = [
        await (await api()).get('/nope'),
        await json('/audit/v1/events', '{"bad'),
        await json('/audit/v1/sports', '[]'),
        await json('/audit/v1/leagues', '{"league_id":1}'),
        await json('/audit/v1/reset', '{"stock":{"zz":1}}'),
        await json('/audit/v1/sports', JSON.stringify({ pad: 'x'.repeat(1.1 * 1024 * 1024) })),
      ];
      for (const r of responses) {
        const text = JSON.stringify(r.body);
        expect(text).not.toMatch(/\n\s+at /);
        expect(text).not.toMatch(/\bat \S+ \(/);
        expect(text).not.toMatch(/node_modules|\.js:\d+/);
        expect(Object.keys(r.body)).toEqual(['error']);
        expect(Object.keys(r.body.error).sort()).toEqual(['code', 'details', 'message']);
      }
    });
    test.each(['/audit/v1/sports', '/audit/v1/leagues', '/audit/v1/events'])('non-object JSON bodies to %s -> 400 [body]', async (p) => {
      for (const raw of ['[1,2]', '"str"', '42', 'null', 'true']) {
        const r = await json(p, raw);
        expect(r.status).toBe(400);
        expect(r.body.error.code).toBe('VALIDATION_ERROR');
        expect(r.body.error.details.fields).toEqual(['body']);
      }
    });
  });
});
