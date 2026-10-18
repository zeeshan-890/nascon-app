const config = require('./config');
const logger = require('./logger');

if (!config.hasSalt()) {
  logger.fatal('DAY1_SALT is missing or empty');
  process.exit(1);
}

const pool = require('./db/pool');
const { migrate, waitForDb } = require('./db/migrate');
const { getRedis } = require('./db/redis');

const { sweepOnce } = require('./services/sweeperService');
const { uploadWithRetry } = require('./services/receiptUploader');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const { relayOnce } = require('./services/outboxRelayService');

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

const STREAM = 'domain-events';
const GROUP = 'workers';

// Consumer group reader; Redis being down only delays receipt uploads.
async function consumeLoop(isRunning) {
  const consumer = 'w-' + process.pid;
  // BLOCK 2000 would stall every other command on a shared connection (the outbox relay's publishes,
  // i.e. realtime pushes, were delayed by up to 2 s), so the blocking reader gets a connection of its own.
  let redis = null;
  while (isRunning()) {
    try {
      if (!redis) {
        redis = getRedis().duplicate();
        redis.on('error', () => {});
      }
      if (redis.status === 'wait') await redis.connect();
      try {
        await redis.xgroup('CREATE', STREAM, GROUP, '0', 'MKSTREAM');
      } catch (e) {
        if (!/BUSYGROUP/.test(e.message)) throw e;
      }
      const res = await redis.xreadgroup('GROUP', GROUP, consumer, 'COUNT', 10, 'BLOCK', 2000, 'STREAMS', STREAM, '>');
      for (const [, entries] of res || []) {
        for (const [id, fields] of entries) {
          const map = {};
          for (let i = 0; i < fields.length; i += 2) map[fields[i]] = fields[i + 1];
          if (map.type === 'receipt.generated') {
            await uploadWithRetry(map.aggregate_id);
          }
          await redis.xack(STREAM, GROUP, id);
        }
      }
    } catch (err) {
      logger.warn({ err: err.message }, 'event consumer unavailable; retrying');
      if (redis) { redis.disconnect(); redis = null; }
      await sleep(3000);
    }
  }
}

async function sweepLoop(isRunning) {
  while (isRunning()) {
    try {
      await sweepOnce();
    } catch (err) {
      logger.warn({ err: err.message }, 'reservation sweep failed');
    }
    await sleep(5000);
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
  consumeLoop(() => running);
  sweepLoop(() => running);
  while (running) {
    try {
      const n = await relayOnce();
      await sleep(n === 100 ? 0 : 250);
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
