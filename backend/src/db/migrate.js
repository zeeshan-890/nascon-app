const fs = require('fs');
const path = require('path');
const pool = require('./pool');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function waitForDb(attempts = 60) {
  for (let i = 0; i < attempts; i++) {
    try {
      await pool.query('SELECT 1');
      return;
    } catch (e) {
      await sleep(1000);
    }
  }
  throw new Error('database not reachable');
}

// Guarded by an advisory lock so api and worker can boot simultaneously.
async function migrate() {
  const sql = fs.readFileSync(path.join(__dirname, 'schema.sql'), 'utf8');
  const client = await pool.connect();
  try {
    await client.query('SELECT pg_advisory_lock(1)');
    try {
      await client.query('SET statement_timeout = 0');
      await client.query(sql);
    } finally {
      await client.query('SELECT pg_advisory_unlock(1)');
    }
  } finally {
    client.release();
  }
}

module.exports = { migrate, waitForDb };
