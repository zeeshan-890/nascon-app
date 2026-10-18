const express = require('express');
const request = require('supertest');
const { ApiError, sendError, markDay2, errorMiddleware } = require('../../../backend/day2/lib/errors');
const guard = require('../../../backend/day2/lib/guestGuard');

const { getGuestToken, guestWriteGuard } = guard;

function appWith(setup) {
  const app = express();
  app.use(guestWriteGuard);
  app.use(markDay2); // marked before parsing so body-parser errors are in scope for the Part II handler
  app.use(express.json({ limit: '1mb', type: () => true, strict: false }));
  setup(app);
  app.use(errorMiddleware);
  return app;
}

describe('errors', () => {
  const app = appWith((a) => {
    a.get('/api-error', (req, res, next) => next(new ApiError(409, 'SOME_CODE', 'nope', { x: 1 })));
    a.get('/boom', () => { throw new Error('secret internals /etc/passwd'); });
    a.post('/echo', (req, res) => res.json(req.body));
    a.get('/send', (req, res) => sendError(res, 404, 'NOT_THERE', 'gone'));
  });

  test('ApiError becomes { error: { code, message, details } }', async () => {
    const r = await request(app).get('/api-error');
    expect(r.status).toBe(409);
    expect(r.body).toEqual({ error: { code: 'SOME_CODE', message: 'nope', details: { x: 1 } } });
  });
  test('sendError defaults details to {}', async () => {
    const r = await request(app).get('/send');
    expect(r.body).toEqual({ error: { code: 'NOT_THERE', message: 'gone', details: {} } });
  });
  test('malformed JSON is a 400 VALIDATION_ERROR on fields ["body"]', async () => {
    const r = await request(app).post('/echo').set('Content-Type', 'application/json').send('{"a":');
    expect(r.status).toBe(400);
    expect(r.body.error.code).toBe('VALIDATION_ERROR');
    expect(r.body.error.details.fields).toEqual(['body']);
  });
  test('a body over 1 MB is a 413 PAYLOAD_TOO_LARGE', async () => {
    const r = await request(app).post('/echo').set('Content-Type', 'application/json').send(JSON.stringify({ a: 'x'.repeat(1.2e6) }));
    expect(r.status).toBe(413);
    expect(r.body.error.code).toBe('PAYLOAD_TOO_LARGE');
  });
  test('an unexpected error is a generic 500 with no internals', async () => {
    const r = await request(app).get('/boom');
    expect(r.status).toBe(500);
    expect(r.body.error.code).toBe('INTERNAL_ERROR');
    expect(JSON.stringify(r.body)).not.toMatch(/passwd|internals|at /);
  });
  test('errors from unmarked (Phase I) routes pass through untouched', () => {
    const next = jest.fn();
    const err = new Error('phase one');
    errorMiddleware(err, { day2: false }, {}, next);
    expect(next).toHaveBeenCalledWith(err);
  });
});

describe('guest guard', () => {
  const app = appWith((a) => {
    a.all('/thing', (req, res) => res.json({ ok: true }));
  });

  test('getGuestToken: bearer (any case, extra spaces), X-Guest-Token, or null', () => {
    expect(getGuestToken({ headers: { authorization: 'Bearer guest_anon_tok_1' } })).toBe('guest_anon_tok_1');
    expect(getGuestToken({ headers: { authorization: 'bEaReR    guest_x' } })).toBe('guest_x');
    expect(getGuestToken({ headers: { 'x-guest-token': 'guest_y' } })).toBe('guest_y');
    expect(getGuestToken({ headers: { authorization: 'Bearer eyJhbGciOi.real.jwt' } })).toBeNull();
    expect(getGuestToken({ headers: { authorization: 'Basic guest_z' } })).toBeNull();
    expect(getGuestToken({ headers: {} })).toBeNull();
  });
  test('a guest write is 403 GUEST_WRITE_FORBIDDEN with the token in details', async () => {
    for (const method of ['post', 'put', 'patch', 'delete']) {
      const r = await request(app)[method]('/thing').set('Authorization', 'bearer   guest_anon_tok_7721');
      expect(r.status).toBe(403);
      expect(r.body).toEqual({ error: {
        code: 'GUEST_WRITE_FORBIDDEN',
        message: 'Anonymous guest tokens cannot perform write mutations.',
        details: { guest_token: 'guest_anon_tok_7721' },
      } });
    }
  });
  test('X-Guest-Token is also a guest', async () => {
    const r = await request(app).post('/thing').set('X-Guest-Token', 'guest_abc');
    expect(r.status).toBe(403);
  });
  test('guest reads are allowed', async () => {
    const r = await request(app).get('/thing').set('Authorization', 'Bearer guest_anon_tok_7721');
    expect(r.status).toBe(200);
  });
  test('requests without a token and with a real token are untouched', async () => {
    expect((await request(app).post('/thing').send({})).status).toBe(200);
    expect((await request(app).post('/thing').set('Authorization', 'Bearer a.b.c').send({})).status).toBe(200);
  });
  test('a guest write with a malformed body is still 403 (the guard runs before body parsing)', async () => {
    const r = await request(app).post('/thing').set('Authorization', 'Bearer guest_q').set('Content-Type', 'application/json').send('{"broken":');
    expect(r.status).toBe(403);
    expect(r.body.error.code).toBe('GUEST_WRITE_FORBIDDEN');
  });
  test('the allow-list lets a listed path through', async () => {
    guard.guestWriteAllowList.push('/thing', /^\/regex-open/);
    try {
      expect((await request(app).post('/thing').set('Authorization', 'Bearer guest_q')).status).toBe(200);
    } finally {
      guard.guestWriteAllowList.length = 0;
    }
    expect((await request(app).post('/thing').set('Authorization', 'Bearer guest_q')).status).toBe(403);
  });
});
