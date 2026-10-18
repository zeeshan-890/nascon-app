// Steps 5 and 6: rate-limiter scope, body limits on Part II paths, byte-identical Phase I errors and the guest guard
// against the REAL app. No database is needed: every request here is answered before any query runs.
const fs = require('fs');
const os = require('os');
const path = require('path');
const express = require('express');
const request = require('supertest');
const createApp = require('../../../backend/src/app');
const config = require('../../../backend/src/config');
const jwt = require('jsonwebtoken');
const { mount } = require('../../../backend/day2/routes');
const { jsonBody } = require('../../../backend/src/middleware/bodyParser');
const hardening = require('../../../backend/src/middleware/hardening');
const notFound = require('../../../backend/src/middleware/notFound');
const errorHandler = require('../../../backend/src/middleware/errorHandler');
const { guestWriteGuard } = require('../../../backend/day2/lib/guestGuard');
const { createLimiter } = require('../../../backend/src/middleware/rateLimit');

// Each app gets its own in-memory limiter (redis: null), so tests never share counters through a real Redis.
const makeApp = () => createApp({ limiter: createLimiter({ redis: null }) });

const GUEST_MESSAGE = 'Anonymous guest tokens cannot perform write mutations.';
const member = () => jwt.sign(
  { sub: 'user-manager', role: 'club_manager', tenant_id: 'tenant-alpha', league_ids: [], club_id: 'club-alpha-1', player_id: null },
  config.jwtSecret, { algorithm: 'HS256', expiresIn: '1h' },
);

describe('guest guard on the real app', () => {
  const app = makeApp();
  const WRITE_ROUTES = ['/api/v1/chat', '/audit/v1/checkout', '/audit/v1/events'];

  const guestHeaders = [
    ['Bearer guest_anon_tok_7721', 'guest_anon_tok_7721'],
    ['bearer guest_anon_tok_7721', 'guest_anon_tok_7721'],
    ['BEARER guest_anon_tok_7721', 'guest_anon_tok_7721'],
    ['BeArEr guest_anon_tok_7721', 'guest_anon_tok_7721'],
    ['Bearer    guest_anon_tok_7721', 'guest_anon_tok_7721'],
    ['  Bearer guest_anon_tok_7721', 'guest_anon_tok_7721'],
    ['Bearer guest_anon_tok_7721 ', 'guest_anon_tok_7721'], // trailing space on the token
  ];

  for (const route of WRITE_ROUTES) {
    test.each(guestHeaders)(`POST ${route} with "%s" -> 403 GUEST_WRITE_FORBIDDEN`, async (header, token) => {
      const r = await request(app).post(route).set('Authorization', header).send({});
      expect(r.status).toBe(403);
      expect(r.body).toEqual({ error: { code: 'GUEST_WRITE_FORBIDDEN', message: GUEST_MESSAGE, details: { guest_token: token } } });
    });

    test(`POST ${route} with a malformed or empty body is still 403 for a guest`, async () => {
      const malformed = await request(app).post(route).set('Authorization', 'Bearer guest_anon_tok_7721')
        .set('Content-Type', 'application/json').send('{"broken":');
      expect(malformed.status).toBe(403);
      expect(malformed.body.error.code).toBe('GUEST_WRITE_FORBIDDEN');
      const empty = await request(app).post(route).set('Authorization', 'Bearer guest_anon_tok_7721');
      expect(empty.status).toBe(403);
      const huge = await request(app).post(route).set('Authorization', 'Bearer guest_anon_tok_7721')
        .set('Content-Type', 'application/json').send(JSON.stringify({ a: 'x'.repeat(1.2e6) }));
      expect(huge.status).toBe(403); // the guard answers before the body is even read
    });

    test(`POST ${route} with X-Guest-Token is blocked too`, async () => {
      const r = await request(app).post(route).set('X-Guest-Token', 'guest_anon_tok_7721').send({});
      expect(r.status).toBe(403);
      expect(r.body.error.details).toEqual({ guest_token: 'guest_anon_tok_7721' });
    });
  }

  test('PUT, PATCH and DELETE are blocked as well', async () => {
    for (const method of ['put', 'patch', 'delete']) {
      const r = await request(app)[method]('/api/v1/anything').set('Authorization', 'Bearer guest_anon_tok_7721');
      expect(r.status).toBe(403);
    }
  });

  test('a guest-prefixed token never reaches JWT verification (no 401 on writes)', async () => {
    const r = await request(app).post('/api/v1/auth/logout').set('Authorization', 'Bearer guest_anon_tok_7721').send({});
    expect(r.status).toBe(403);
    expect(r.body.error.code).not.toBe('UNAUTHENTICATED');
  });

  test('GET, HEAD and OPTIONS are never blocked', async () => {
    const get = await request(app).get('/api/v1/ping').set('Authorization', 'Bearer guest_anon_tok_7721');
    expect(get.status).toBe(200);
    expect(get.body).toEqual({ pong: true });
    expect((await request(app).head('/api/v1/ping').set('Authorization', 'Bearer guest_anon_tok_7721')).status).toBe(200);
    const options = await request(app).options('/api/v1/ping').set('Authorization', 'Bearer guest_anon_tok_7721');
    expect(options.status).not.toBe(403);
  });

  test('a guest GET on an unknown /api/v1 route gives the normal 404', async () => {
    const r = await request(app).get('/api/v1/does-not-exist').set('Authorization', 'Bearer guest_anon_tok_7721');
    expect(r.status).toBe(404);
    expect(r.body.error.code).toBe('NOT_FOUND');
  });

  test('a real member JWT behaves as in Phase I (the guard ignores it)', async () => {
    const chat = await request(app).post('/api/v1/chat').set('Authorization', `Bearer ${member()}`).send({});
    expect(chat.status).toBe(404); // route does not exist yet -> the normal 404, not 403
    expect(chat.body.error.code).toBe('NOT_FOUND');
    const checkout = await request(app).post('/audit/v1/checkout').set('Authorization', `Bearer ${member()}`).send({});
    expect(checkout.status).toBe(400); // Phase I schema validation
    expect(checkout.body.error.code).toBe('VALIDATION_ERROR');
  });

  test('no token behaves as in Phase I', async () => {
    const checkout = await request(app).post('/audit/v1/checkout').send({});
    expect(checkout.status).toBe(400);
    const events = await request(app).post('/audit/v1/events').send({});
    expect(events.status).toBe(400);
    expect((await request(app).post('/api/v1/chat').send({})).status).toBe(404);
  });

  test('tokens that are not guests are not blocked: "guest" without the underscore and a Basic scheme', async () => {
    for (const header of ['Bearer guestanon', 'Basic guest_anon_tok_7721', 'Bearer a.b.c']) {
      const r = await request(app).post('/audit/v1/checkout').set('Authorization', header).send({});
      expect(r.status).not.toBe(403);
    }
  });
});

describe('rate limiter scope', () => {
  test('300 rapid requests to /audit routes and /health never produce 429', async () => {
    const app = makeApp();
    const results = await Promise.all(Array.from({ length: 300 }, (_, i) => (i % 2
      ? request(app).post('/audit/v1/events').send({})
      : request(app).get('/health'))));
    expect(results.some((r) => r.status === 429)).toBe(false);
  }, 60000);

  test('/web, /q and /metrics style paths are not limited either', async () => {
    const app = makeApp();
    const results = await Promise.all(Array.from({ length: 150 }, (_, i) => request(app).get(['/web/matches/x/live', '/q/m1-live', '/metrics'][i % 3])));
    expect(results.some((r) => r.status === 429)).toBe(false);
  }, 60000);

  test('150 rapid requests to a limited /api/v1 route still hit the limit', async () => {
    const app = makeApp();
    const results = [];
    for (let i = 0; i < 150; i += 1) results.push((await request(app).get('/api/v1/ping')).status);
    expect(results.filter((s) => s === 200)).toHaveLength(100);
    expect(results.filter((s) => s === 429)).toHaveLength(50);
  }, 60000);
});

describe('body limits and malformed bodies on Part II paths', () => {
  let dir;
  let app;
  beforeAll(() => {
    dir = fs.mkdtempSync(path.join(os.tmpdir(), 'day2-body-'));
    fs.writeFileSync(path.join(dir, 'echo.js'), `
      module.exports = (router) => {
        router.post('/audit/v1/day2-echo', (req, res) => {
          const b = req.body;
          if (b === null || typeof b !== 'object' || Array.isArray(b) || Object.keys(b).length === 0) {
            return res.status(400).json({ error: { code: 'VALIDATION_ERROR', message: 'Body must be a non-empty JSON object.', details: { fields: ['body'] } } });
          }
          return res.status(201).json({ ok: true });
        });
      };`);
    app = express();
    app.use(guestWriteGuard);
    app.use(jsonBody);
    app.use(hardening);
    mount(app, dir);
    app.use(notFound);
    app.use(errorHandler);
  });
  afterAll(() => { fs.rmSync(dir, { recursive: true, force: true }); });

  const envelope = (b) => expect(Object.keys(b.error).sort()).toEqual(['code', 'details', 'message']);

  test('a body over 1 MB -> 413 PAYLOAD_TOO_LARGE in the standard envelope', async () => {
    const r = await request(app).post('/audit/v1/day2-echo').set('Content-Type', 'application/json').send(JSON.stringify({ a: 'x'.repeat(1.2e6) }));
    expect(r.status).toBe(413);
    expect(r.body.error.code).toBe('PAYLOAD_TOO_LARGE');
    envelope(r.body);
    expect(typeof r.body.error.details).toBe('object');
  });

  test('malformed JSON -> 400 VALIDATION_ERROR with details.fields ["body"]', async () => {
    const r = await request(app).post('/audit/v1/day2-echo').set('Content-Type', 'application/json').send('{"a":');
    expect(r.status).toBe(400);
    expect(r.body.error.code).toBe('VALIDATION_ERROR');
    expect(r.body.error.details.fields).toEqual(['body']);
    envelope(r.body);
  });

  test('empty body, non-object JSON and a wrong content type -> 400 VALIDATION_ERROR', async () => {
    const empty = await request(app).post('/audit/v1/day2-echo');
    const arr = await request(app).post('/audit/v1/day2-echo').set('Content-Type', 'application/json').send('[1,2]');
    const str = await request(app).post('/audit/v1/day2-echo').set('Content-Type', 'application/json').send('"text"');
    const num = await request(app).post('/audit/v1/day2-echo').set('Content-Type', 'application/json').send('5');
    const plain = await request(app).post('/audit/v1/day2-echo').set('Content-Type', 'text/plain').send('not json at all');
    for (const r of [empty, arr, str, num, plain]) {
      expect(r.status).toBe(400);
      expect(r.body.error.code).toBe('VALIDATION_ERROR');
      expect(r.body.error.details.fields).toEqual(['body']);
      envelope(r.body);
    }
  });

  test('a valid JSON object sent with a wrong content type is still parsed (Phase I rule)', async () => {
    const r = await request(app).post('/audit/v1/day2-echo').set('Content-Type', 'text/plain').send('{"a":1}');
    expect(r.status).toBe(201);
  });

  test('NUL bytes and over-deep nesting are 400s, never 5xx', async () => {
    const nul = await request(app).post('/audit/v1/day2-echo').set('Content-Type', 'application/json').send('{"a":"x\\u0000y"}');
    expect(nul.status).toBe(400);
    const deep = await request(app).post('/audit/v1/day2-echo').set('Content-Type', 'application/json').send(`${'{"a":'.repeat(40)}1${'}'.repeat(40)}`);
    expect(deep.status).toBe(400);
  });
});

describe('Phase I error responses are byte-identical to the baseline fixtures', () => {
  const FIXTURES = require('../../fixtures/phase1_errors.json');
  const app = makeApp();
  test.each(Object.keys(FIXTURES))('%s', async (name) => {
    const f = FIXTURES[name];
    let r = request(app)[f.method.toLowerCase()](f.path);
    if (f.contentType) r = r.set('Content-Type', f.contentType);
    r = await (f.rawBody === undefined ? r : r.send(f.rawBody));
    expect(r.status).toBe(f.status);
    expect(r.text).toBe(f.text);
  });
});
