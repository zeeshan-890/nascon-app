// Dead-letter queue for failed notifications. Retries are bounded and recorded, never slept.
const pool = require('../../src/db/pool');
const metrics = require('./metrics');

const RETRY_DELAYS_MS = [100, 200, 400]; // exponential, 3 attempts, then dead-lettered
const MAX_LISTED = 100;

async function depth() {
  const { rows } = await pool.query('SELECT count(*)::int AS d FROM day2_dlq');
  metrics.set('nascon_dlq_depth', rows[0].d);
  return rows[0].d;
}

async function enqueueFailed(n) {
  if (n > 0) {
    await pool.query(
      `INSERT INTO day2_dlq (event_id, event_type, attempts, status)
       SELECT 'evt-' || gen_random_uuid()::text, 'notification.delivery_failed', $1, 'dead_lettered' FROM generate_series(1, $2)`,
      [RETRY_DELAYS_MS.length, n],
    );
  }
  return depth();
}

async function undelivered() {
  const { rows } = await pool.query(
    'SELECT event_id, event_type, attempts, status FROM day2_dlq ORDER BY id LIMIT $1', [MAX_LISTED],
  );
  return rows;
}

module.exports = { enqueueFailed, depth, undelivered, RETRY_DELAYS_MS };
