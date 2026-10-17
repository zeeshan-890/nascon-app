const pool = require('./pool');

const RETRYABLE = new Set(['40001', '40P01']);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Runs fn(client) in a transaction; retries serialization failures and deadlocks.
async function withTransaction(fn, { retries = 3 } = {}) {
  for (let attempt = 0; ; attempt++) {
    const client = await pool.connect();
    try {
      await client.query('BEGIN');
      const result = await fn(client);
      await client.query('COMMIT');
      return result;
    } catch (err) {
      try {
        await client.query('ROLLBACK');
      } catch (e) {
        /* connection already broken */
      }
      if (RETRYABLE.has(err.code) && attempt < retries) {
        await sleep(5 + Math.random() * 25);
        continue;
      }
      throw err;
    } finally {
      client.release();
    }
  }
}

module.exports = { withTransaction };
