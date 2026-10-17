const request = require('../../backend/node_modules/supertest');
const config = require('../../backend/src/config');
const pool = require('../../backend/src/db/pool');
const { migrate, waitForDb } = require('../../backend/src/db/migrate');
const createApp = require('../../backend/src/app');

let app;

async function bootApp() {
  if (!app) {
    await waitForDb(5);
    await migrate();
    config.state.ready = true;
    app = createApp();
  }
  return app;
}

async function api() {
  return request(await bootApp());
}

async function resetDb(body) {
  const r = await (await api()).post('/audit/v1/reset').send(body || {});
  if (r.status !== 200) throw new Error(`reset failed: ${r.status} ${JSON.stringify(r.body)}`);
  return r;
}

module.exports = { api, resetDb, pool };
