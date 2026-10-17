const Redis = require('ioredis');
const config = require('../config');

let client;

// Lazily created, never throws; callers treat Redis as best-effort.
function getRedis() {
  if (!client) {
    client = new Redis(config.redisUrl, {
      lazyConnect: true,
      maxRetriesPerRequest: 1,
      enableOfflineQueue: false,
      retryStrategy: (n) => (process.env.NODE_ENV === 'test' ? null : Math.min(n * 200, 2000)),
    });
    client.on('error', () => {});
  }
  return client;
}

module.exports = { getRedis };
