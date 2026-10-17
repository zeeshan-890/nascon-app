const http = require('http');
const fs = require('fs');
const path = require('path');
const createApp = require('../../backend/src/app');
const { createHttpServer } = require('../../backend/src/httpServer');
const { describeDb } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');

// Real HTTP client: resolves with what the client actually receives (or rejects on reset).
function rawPost(port, urlPath, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request(
      { host: '127.0.0.1', port, path: urlPath, method: 'POST', headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body), ...headers } },
      (res) => {
        let data = '';
        res.on('data', (c) => { data += c; });
        res.on('end', () => resolve({ status: res.statusCode, body: data, headers: res.headers }));
      }
    );
    req.on('error', reject);
    req.end(body);
  });
}

const nest = (n) => `${'['.repeat(n)}${']'.repeat(n)}`;

describeDb('hardening', () => {
  let server;
  let port;
  beforeAll(async () => {
    await resetDb();
    server = createHttpServer(createApp());
    await new Promise((r) => server.listen(0, '127.0.0.1', r));
    port = server.address().port;
  });
  afterAll(async () => {
    await new Promise((r) => server.close(r));
    await pool.end();
  });

  test('server timeouts configured', () => {
    expect(server.keepAliveTimeout).toBe(65000);
    expect(server.headersTimeout).toBe(66000);
    expect(server.requestTimeout).toBe(30000);
  });

  describe('413 is actually delivered to a real client', () => {
    const big = JSON.stringify({ pad: 'x'.repeat(1.5 * 1024 * 1024) });
    const routes = ['/audit/v1/sports', '/audit/v1/leagues', '/audit/v1/events', '/audit/v1/reset', '/audit/v1/rbac-matrix',
      '/audit/v1/rbac/tenants', '/audit/v1/rbac/leagues/league-alpha-1/fixtures', '/audit/v1/rbac/clubs/club-alpha-1/roster',
      '/audit/v1/rbac/clubs/club-alpha-1/products', '/audit/v1/checkout'];
    test.each(routes)('POST %s', async (p) => {
      const r = await rawPost(port, p, big);
      expect(r.status).toBe(413);
      expect(JSON.parse(r.body)).toEqual({ error: { code: 'PAYLOAD_TOO_LARGE', message: expect.any(String), details: {} } });
    });
    test('chunked upload without Content-Length', async () => {
      const r = await new Promise((resolve, reject) => {
        const req = http.request({ host: '127.0.0.1', port, path: '/audit/v1/events', method: 'POST', headers: { 'Content-Type': 'application/json' } }, (res) => {
          let d = '';
          res.on('data', (c) => { d += c; });
          res.on('end', () => resolve({ status: res.statusCode, body: d }));
        });
        req.on('error', reject);
        req.write(big.slice(0, 700000));
        req.end(big.slice(700000));
      });
      expect(r.status).toBe(413);
    });
    test('server keeps working afterwards', async () => {
      const r = await rawPost(port, '/audit/v1/rbac-matrix', '{}');
      expect(r.status).toBe(200);
    });
  });

  describe('request body hardening', () => {
    const post = async (p, raw) => (await api()).post(p).set('Content-Type', 'application/json').send(raw);
    const expect400 = (r) => {
      expect(r.status).toBe(400);
      expect(r.body).toEqual({ error: { code: 'VALIDATION_ERROR', message: expect.any(String), details: { fields: ['body'] } } });
    };

    test.each([
      ['/audit/v1/events', '{"event_id":"e\\u0000x"}'],
      ['/audit/v1/events', '{"a\\u0000b":1}'],
      ['/audit/v1/sports', '{"sport_id":"x","name":"n\\u0000"}'],
      ['/audit/v1/leagues', '{"payload":["ok","\\u0000"]}'],
      ['/audit/v1/reset', '{"seed":1,"x":{"y":[{"z":"\\u0000"}]}}'],
      ['/audit/v1/events', '"\\u0000"'],
    ])('NUL byte %s %s -> 400 [body]', async (p, raw) => {
      expect400(await post(p, raw));
    });

    test('40-level nesting -> 400, 32-level passes the guard', async () => {
      expect400(await post('/audit/v1/events', nest(40)));
      expect400(await post('/audit/v1/events', `{"a":${nest(40)}}`));
      const ok = await post('/audit/v1/events', nest(32));
      expect(ok.status).toBe(400); // array body: still a validation error, but from the validator
      expect(ok.body.error.details.fields).toEqual(['body']);
    });

    test('very deep body (hundreds of thousands of levels) -> 400, never 5xx', async () => {
      const r = await post('/audit/v1/events', nest(300000));
      expect(r.status).toBe(400);
    });

    test('more than 10000 nodes -> 400', async () => {
      expect400(await post('/audit/v1/events', JSON.stringify({ items: new Array(10000).fill(1) })));
    });

    test('control characters and quotes are data, not errors', async () => {
      const r = await post('/audit/v1/sports', JSON.stringify({ sport_id: 'bad\u0001\u001f', name: '<script>alert("x")</script>' }));
      expect(r.status).toBe(400);
      expect(r.body.error.code).toBe('VALIDATION_ERROR');
    });
  });

  describe('injection', () => {
    test('SQL injection in sport_id -> 400 and table still exists', async () => {
      const r = await (await api()).post('/audit/v1/sports').send({ sport_id: "x'; DROP TABLE sports;--", name: 'n' });
      expect(r.status).toBe(400);
      const { rows } = await pool.query('SELECT count(*)::int AS n FROM sports');
      expect(rows[0].n).toBeGreaterThan(0);
    });

    test('injection in ids on other routes -> 400, never 5xx', async () => {
      const evil = "m1'; DROP TABLE matches;--";
      const r1 = await (await api()).get(`/audit/v1/score/${encodeURIComponent(evil)}`);
      expect(r1.status).toBeLessThan(500);
      const r2 = await (await api()).post('/audit/v1/events').send({ event_id: evil, match_id: evil, type: 'match_init', payload: {} });
      expect(r2.status).toBe(400);
      const { rows } = await pool.query("SELECT to_regclass('public.matches') AS t");
      expect(rows[0].t).toBe('matches');
    });

    test('customer-style quotes and script strings stay plain data', async () => {
      const evil = `O'Brien "Q" <script>alert(1)</script> \\ ; --`;
      const r = await (await api()).post('/audit/v1/reset').send({});
      expect(r.status).toBe(200);
      const s = await (await api()).post('/audit/v1/leagues').send({ league_id: 'lg-inj', name: evil });
      expect(s.status).toBeLessThan(500);
    });

    test('prototype pollution keys are inert', async () => {
      const raw = '{"__proto__":{"polluted":true},"constructor":{"prototype":{"polluted":true}},"prototype":1,"seed":1}';
      const r = await (await api()).post('/audit/v1/reset').set('Content-Type', 'application/json').send(raw);
      expect(r.status).toBeLessThan(500);
      expect({}.polluted).toBeUndefined();
      expect(Object.prototype.polluted).toBeUndefined();
    });
  });

  describe('headers and logging', () => {
    test('no CORS headers, helmet on, x-powered-by off', async () => {
      const r = await (await api()).get('/health').set('Origin', 'https://evil.example');
      expect(r.headers['access-control-allow-origin']).toBeUndefined();
      expect(r.headers['x-powered-by']).toBeUndefined();
      expect(r.headers['x-content-type-options']).toBe('nosniff');
    });
    test('request logger does not reference bodies, tokens or the salt', () => {
      const src = fs.readFileSync(path.join(__dirname, '../../backend/src/app.js'), 'utf8');
      const line = src.split('\n').find((l) => l.includes('logger.info'));
      expect(line).toMatch(/req\.method.*req\.path.*statusCode/);
      expect(line).not.toMatch(/body|authorization|salt|headers/i);
    });
  });

  describe('error mapping', () => {
    const { errorHandler } = require('../../backend/src/middleware/errorHandler');
    const run = (err) => new Promise((resolve) => {
      const res = { headersSent: false, status(s) { this.s = s; return this; }, type() { return this; }, json(b) { resolve({ status: this.s, body: b }); } };
      errorHandler(err, {}, res, () => {});
    });
    test.each(['22021', '22P05', '54000'])('SQLSTATE %s -> 400', async (code) => {
      expect((await run(Object.assign(new Error('x'), { code }))).status).toBe(400);
    });
    test('pool timeout -> 503 SERVICE_UNAVAILABLE', async () => {
      const r = await run(new Error('timeout exceeded when trying to connect'));
      expect(r.status).toBe(503);
      expect(r.body.error.code).toBe('SERVICE_UNAVAILABLE');
    });
    test('pool is configured with a 4 s connection timeout', () => {
      expect(pool.options.connectionTimeoutMillis).toBe(4000);
    });
  });
});
