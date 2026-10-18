// Per-key mutual exclusion: an in-process queue (so only one caller per key waits on the database)
// plus a Postgres advisory lock (so separate API processes also exclude each other).
const pool = require('../../src/db/pool');

const queues = new Map();
const results = new Map();

async function withLock(key, fn) {
  const previous = queues.get(key) || Promise.resolve();
  let open;
  const gate = new Promise((resolve) => { open = resolve; });
  const tail = previous.then(() => gate);
  queues.set(key, tail);
  await previous;
  let client;
  try {
    client = await pool.connect();
    await client.query('SET statement_timeout = 0'); // waiting for the lock must not hit the 4 s pool limit
    const name = `day2:${key}`;
    await client.query('SELECT pg_advisory_lock(hashtextextended($1, 0))', [name]);
    try {
      return await fn();
    } finally {
      await client.query('SELECT pg_advisory_unlock(hashtextextended($1, 0))', [name]).catch(() => {});
    }
  } finally {
    if (client) {
      await client.query('SET statement_timeout = 4000').catch(() => {});
      client.release();
    }
    open();
    if (queues.get(key) === tail) queues.delete(key);
  }
}

// Runs fn at most once for a key; later callers (also concurrent ones) get the same result.
// A failed run is forgotten so the caller can retry. The memo is per process.
function once(key, fn) {
  if (results.has(key)) return results.get(key);
  const promise = withLock(`once:${key}`, fn).catch((err) => {
    results.delete(key);
    throw err;
  });
  results.set(key, promise);
  return promise;
}

module.exports = { withLock, once };
