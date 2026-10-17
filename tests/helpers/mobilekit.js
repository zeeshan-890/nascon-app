const request = require('../../backend/node_modules/supertest');
const config = require('../../backend/src/config');
const { migrate, waitForDb } = require('../../backend/src/db/migrate');
const createApp = require('../../backend/src/app');
const pool = require('../../backend/src/db/pool');

const PASSWORD = 'Nascon@26';
const USERS = {
  admin: 'admin@nascon.test',
  organizer: 'organizer@nascon.test',
  manager: 'manager@nascon.test',
  player: 'player@nascon.test',
  viewer: 'viewer@nascon.test',
};

let app;
let tokens;

async function boot() {
  if (!app) {
    await waitForDb(5);
    await migrate();
    config.state.ready = true;
    // The rate limiter has its own tests; here it would only slow the suites down.
    app = createApp({ limiter: (req, res, next) => next() });
  }
  return app;
}

const http = async () => request(await boot());

async function resetAll(body) {
  const r = await (await http()).post('/audit/v1/reset').send(body || {});
  if (r.status !== 200) throw new Error(`reset failed ${r.status} ${JSON.stringify(r.body)}`);
  tokens = null;
  return r;
}

async function login(role) {
  const r = await (await http()).post('/api/v1/auth/login').send({ email: USERS[role], password: PASSWORD });
  if (r.status !== 200) throw new Error(`login ${role} failed ${r.status} ${JSON.stringify(r.body)}`);
  return r.body.access_token;
}

async function token(role) {
  if (!tokens) tokens = {};
  if (!tokens[role]) tokens[role] = await login(role);
  return tokens[role];
}

// Authenticated request builder: as('organizer').get('/api/v1/leagues')
function as(role) {
  const make = (method) => async (path, body) => {
    let req = (await http())[method](path).set('Authorization', `Bearer ${await token(role)}`);
    if (body !== undefined) req = req.send(body);
    return req;
  };
  return { get: make('get'), post: make('post'), patch: make('patch'), delete: make('delete') };
}

module.exports = { boot, http, resetAll, as, token, pool, USERS, PASSWORD };
