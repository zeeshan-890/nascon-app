const crypto = require('crypto');

const config = {
  get salt() {
    return process.env.DAY1_SALT;
  },
  get jwtSecret() {
    return crypto.createHmac('sha256', this.salt).update('jwt-signing-key').digest('hex');
  },
  port: Number(process.env.PORT) || 8080,
  databaseUrl: process.env.DATABASE_URL || 'postgres://nascon:nascon@postgres:5432/nascon',
  redisUrl: process.env.REDIS_URL || 'redis://redis:6379',
  minio: {
    endpoint: process.env.MINIO_ENDPOINT || 'minio',
    port: Number(process.env.MINIO_PORT) || 9000,
    accessKey: process.env.MINIO_ACCESS_KEY || 'minioadmin',
    secretKey: process.env.MINIO_SECRET_KEY || 'minioadmin',
    bucket: process.env.MINIO_BUCKET || 'receipts',
  },
  logLevel: process.env.LOG_LEVEL || 'info',
  // Flipped to true by server.js only after migrations and seed have finished.
  state: { ready: false },
  // Returns true when DAY1_SALT is usable; callers log fatal and exit otherwise.
  hasSalt() {
    return typeof process.env.DAY1_SALT === 'string' && process.env.DAY1_SALT.length > 0;
  },
};

module.exports = config;
