const express = require('express');
const request = require('supertest');
const AppError = require('../../backend/src/http/AppError');
const { jsonBody } = require('../../backend/src/middleware/bodyParser');
const errorHandler = require('../../backend/src/middleware/errorHandler');
const notFound = require('../../backend/src/middleware/notFound');
const { send, errorBody } = require('../../backend/src/http/respond');
const logger = require('../../backend/src/logger');

function makeApp() {
  const app = express();
  app.use(jsonBody);
  app.post('/echo', (req, res) => send(res, 200, { body: req.body }));
  app.get('/app-error', () => { throw new AppError(422, 'BAD_THING', 'nope', { a: 1 }); });
  app.get('/pg-invalid', () => { const e = new Error('invalid input syntax'); e.code = '22P02'; throw e; });
  app.get('/pg-dup', () => { const e = new Error('duplicate key'); e.code = '23505'; throw e; });
  app.get('/boom', () => { throw new Error('secret internals'); });
  app.use(notFound);
  app.use(errorHandler);
  return app;
}

const expectShape = (res) => {
  expect(res.headers['content-type']).toMatch(/application\/json/);
  expect(Object.keys(res.body)).toEqual(['error']);
  expect(typeof res.body.error.details).toBe('object');
  expect(JSON.stringify(res.body)).not.toMatch(/stack|at .*\.js/);
};

describe('respond', () => {
  test('errorBody defaults', () => {
    expect(errorBody('X', 'm')).toEqual({ error: { code: 'X', message: 'm', details: {} } });
  });
});

describe('error pipeline', () => {
  const app = makeApp();
  beforeAll(() => { jest.spyOn(logger, 'error').mockImplementation(() => {}); });
  afterAll(() => jest.restoreAllMocks());

  test('malformed JSON -> 400 fields body', async () => {
    const res = await request(app).post('/echo').set('Content-Type', 'application/json').send('{"a":');
    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('VALIDATION_ERROR');
    expect(res.body.error.details).toEqual({ fields: ['body'] });
    expectShape(res);
  });
  test('1.1MB body -> 413', async () => {
    const res = await request(app).post('/echo').set('Content-Type', 'application/json')
      .send(JSON.stringify({ s: 'a'.repeat(1.1 * 1024 * 1024) }));
    expect(res.status).toBe(413);
    expect(res.body.error.code).toBe('PAYLOAD_TOO_LARGE');
    expectShape(res);
  });
  test('just under 1MB passes', async () => {
    const res = await request(app).post('/echo').set('Content-Type', 'application/json')
      .send(JSON.stringify({ s: 'a'.repeat(1024 * 1024 - 100) }));
    expect(res.status).toBe(200);
  });
  test('unknown route -> 404', async () => {
    const res = await request(app).get('/nope');
    expect(res.status).toBe(404);
    expect(res.body).toEqual({ error: { code: 'NOT_FOUND', message: 'Route not found', details: {} } });
    expect(res.headers['content-type']).toMatch(/json/);
  });
  test('wrong content-type still parsed', async () => {
    const res = await request(app).post('/echo').set('Content-Type', 'text/plain').send('{"a":1}');
    expect(res.status).toBe(200);
    expect(res.body.body).toEqual({ a: 1 });
  });
  test('missing content-type still parsed', async () => {
    const res = await request(app).post('/echo').set('Content-Type', '').send('{"a":2}');
    expect(res.body.body).toEqual({ a: 2 });
  });
  test('empty body -> {}', async () => {
    const res = await request(app).post('/echo');
    expect(res.status).toBe(200);
    expect(res.body.body).toEqual({});
  });
  test('unsupported charset -> 400', async () => {
    const res = await request(app).post('/echo').set('Content-Type', 'application/json; charset=x-bogus').send('{"a":1}');
    expect(res.status).toBe(400);
    expect(res.body.error.details).toEqual({ fields: ['body'] });
  });
  test('AppError mapped', async () => {
    const res = await request(app).get('/app-error');
    expect(res.status).toBe(422);
    expect(res.body).toEqual({ error: { code: 'BAD_THING', message: 'nope', details: { a: 1 } } });
  });
  test('pg 22P02 -> 400', async () => {
    const res = await request(app).get('/pg-invalid');
    expect(res.status).toBe(400);
    expect(res.body.error).toEqual({ code: 'VALIDATION_ERROR', message: 'Request validation failed', details: { fields: [] } });
  });
  test('pg 23505 -> 409', async () => {
    const res = await request(app).get('/pg-dup');
    expect(res.status).toBe(409);
    expect(res.body.error.code).toBe('CONFLICT');
    expectShape(res);
  });
  test('generic -> 500 without leaking', async () => {
    const res = await request(app).get('/boom');
    expect(res.status).toBe(500);
    expect(res.body.error).toEqual({ code: 'INTERNAL_ERROR', message: 'Internal server error', details: {} });
    expect(JSON.stringify(res.body)).not.toMatch(/secret/);
    expect(logger.error).toHaveBeenCalled();
  });
  test('headersSent delegates to next', () => {
    const next = jest.fn();
    const err = new Error('x');
    errorHandler(err, {}, { headersSent: true }, next);
    expect(next).toHaveBeenCalledWith(err);
  });
});
