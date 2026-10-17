const crypto = require('crypto');
const { createSlidingWindow } = require('../domain/rbac/slidingWindow');
const { getRedis } = require('../db/redis');
const { send, errorBody } = require('../http/respond');

const LIMIT = 100;
const WINDOW_MS = 10000;
const REDIS_TIMEOUT_MS = 200;
const REDIS_COOLDOWN_MS = 2000;

// Sliding-window log in one atomic script; Redis TIME is the clock; only allowed hits are recorded.
const LUA = `
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[1])
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
local count = redis.call('ZCARD', KEYS[1])
if count < limit then
  redis.call('ZADD', KEYS[1], now, now .. '-' .. ARGV[3])
  redis.call('PEXPIRE', KEYS[1], window)
  return {1, limit - count - 1}
end
local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
return {0, tonumber(oldest[2]) + window - now}
`;

function withTimeout(promise, ms) {
  let timer;
  const timeout = new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('redis timeout')), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}

function clientIp(req) {
  const addr = (req.socket && req.socket.remoteAddress) || 'unknown';
  return addr.replace(/^::ffff:/, '');
}

function testWindowOverride() {
  if (process.env.NODE_ENV !== 'test') return null;
  const n = Number(process.env.RATE_LIMIT_WINDOW_MS);
  return Number.isFinite(n) && n > 0 ? n : null;
}

// A gate = Redis sliding window with an identical in-memory fallback.
// opts.redis: client getter, or null to force the in-memory path.
function createGate({ prefix, limit, windowMs, now = Date.now, redis = getRedis, sweepMs } = {}) {
  const mem = createSlidingWindow({ limit, windowMs, now });
  let redisDownUntil = 0;
  const timer = setInterval(() => mem.cleanup(), sweepMs || Math.max(1000, Math.min(windowMs, 30000)));
  timer.unref();

  async function viaRedis(key) {
    const client = redis();
    if (client.status === 'wait') await withTimeout(client.connect(), REDIS_TIMEOUT_MS);
    const member = crypto.randomBytes(8).toString('hex');
    const res = await withTimeout(client.eval(LUA, 1, prefix + key, limit, windowMs, member), REDIS_TIMEOUT_MS);
    const [ok, n] = res;
    return Number(ok) === 1
      ? { allowed: true, remaining: Number(n), retryMs: 0 }
      : { allowed: false, remaining: 0, retryMs: Math.max(1, Number(n)) };
  }

  async function hit(key) {
    if (redis && Date.now() >= redisDownUntil) {
      try {
        return await viaRedis(key);
      } catch (e) {
        redisDownUntil = Date.now() + REDIS_COOLDOWN_MS;
      }
    }
    return mem.hit(prefix + key);
  }

  return { hit, memory: mem, limit, windowMs, stop: () => clearInterval(timer) };
}

function createLimiter(opts = {}) {
  const limit = opts.limit || LIMIT;
  const windowMs = opts.windowMs || testWindowOverride() || WINDOW_MS;
  const gate = createGate({ prefix: 'rl:', limit, windowMs, now: opts.now, redis: opts.redis === undefined ? getRedis : opts.redis });

  async function limiter(req, res, next) {
    let r;
    try {
      r = await gate.hit(clientIp(req));
    } catch (e) {
      return next(); // the limiter itself must never break the API
    }
    res.set('X-RateLimit-Limit', String(limit));
    res.set('X-RateLimit-Remaining', String(r.remaining));
    if (r.allowed) return next();
    const seconds = Math.max(1, Math.ceil(r.retryMs / 1000));
    res.set('Retry-After', String(seconds));
    return send(res, 429, errorBody('RATE_LIMITED', 'Too many requests. Try again later.', { retry_after_seconds: seconds }));
  }
  limiter.gate = gate;
  return limiter;
}

const sha = (s) => crypto.createHash('sha256').update(s).digest('hex');

module.exports = { createLimiter, createGate, clientIp, sha, LUA, LIMIT, WINDOW_MS };
