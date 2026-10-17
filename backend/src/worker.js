const config = require('./config');
const logger = require('./logger');

if (!config.hasSalt()) {
  logger.fatal('DAY1_SALT is missing or empty');
  process.exit(1);
}

const pool = require('./db/pool');
const { migrate, waitForDb } = require('./db/migrate');
const { getRedis } = require('./db/redis');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Publishes committed outbox rows to the Redis stream and pub/sub channel.
async function relayOnce() {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const { rows } = await client.query(
      `SELECT id, aggregate_type, aggregate_id, event_type, payload FROM outbox
       WHERE published_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED`
    );
    if (!rows.length) {
      await client.query('COMMIT');
      return 0;
    }
    const redis = getRedis();
    if (redis.status === 'wait') await redis.connect();
    for (const row of rows) {
      const json = JSON.stringify(row.payload);
      await redis.xadd('domain-events', '*', 'type', row.event_type, 'aggregate_id', row.aggregate_id, 'json', json);
      const topic = `${row.aggregate_type}:${row.aggregate_id}`;
      await redis.publish(`rt:${topic}`, JSON.stringify({ type: row.event_type, match_id: row.aggregate_id, data: row.payload }));
    }
    await client.query('UPDATE outbox SET published_at = now() WHERE id = ANY($1::bigint[])', [rows.map((r) => r.id)]);
    await client.query('COMMIT');
    return rows.length;
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    throw err;
  } finally {
    client.release();
  }
}

// Receipt storage is optional: failures here must never stop the relay.
async function ensureBucket() {
  try {
    const Minio = require('minio');
    const client = new Minio.Client({
      endPoint: config.minio.endpoint,
      port: config.minio.port,
      useSSL: false,
      accessKey: config.minio.accessKey,
      secretKey: config.minio.secretKey,
    });
    for (let i = 0; i < 30; i++) {
      try {
        if (!(await client.bucketExists(config.minio.bucket))) await client.makeBucket(config.minio.bucket);
        logger.info('minio bucket ready');
        return;
      } catch (e) {
        await sleep(5000);
      }
    }
    logger.warn('minio unavailable; continuing without it');
  } catch (e) {
    logger.warn({ err: e.message }, 'minio setup skipped');
  }
}

async function main() {
  await waitForDb();
  await migrate();
  ensureBucket();
  let running = true;
  const stop = () => {
    running = false;
    setTimeout(() => process.exit(0), 3000).unref();
  };
  process.on('SIGTERM', stop);
  process.on('SIGINT', stop);
  while (running) {
    try {
      const n = await relayOnce();
      await sleep(n === 100 ? 0 : 500);
    } catch (err) {
      logger.warn({ err: err.message }, 'outbox relay failed; retrying');
      await sleep(2000);
    }
  }
  await pool.end();
}

main().catch((err) => {
  logger.fatal({ err: err.message }, 'worker failed');
  process.exit(1);
});
