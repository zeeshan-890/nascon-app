const { getRedis } = require('./redis');

const TTL_SECONDS = 30;
const inflight = new Map();

async function redisOr(fn, fallback) {
  try {
    const redis = getRedis();
    if (redis.status === 'wait') await redis.connect();
    return await Promise.race([fn(redis), new Promise((_, rej) => setTimeout(() => rej(new Error('redis timeout')), 300))]);
  } catch (e) {
    return fallback;
  }
}

// Returns the cached JSON for key or computes it once (concurrent misses share one computation).
// `group` names the invalidation set the key belongs to.
async function cached(key, group, compute, ttl = TTL_SECONDS) {
  const hit = await redisOr((r) => r.get(key), null);
  if (hit) {
    try {
      return JSON.parse(hit);
    } catch (e) { /* recompute */ }
  }
  if (inflight.has(key)) return inflight.get(key);
  const p = (async () => {
    const value = await compute();
    await redisOr(async (r) => {
      await r.set(key, JSON.stringify(value), 'EX', ttl);
      await r.sadd(`cache:group:${group}`, key);
      await r.expire(`cache:group:${group}`, ttl * 4);
      return true;
    }, null);
    return value;
  })().finally(() => inflight.delete(key));
  inflight.set(key, p);
  return p;
}

// Deletes every key registered under the group (cheap: no SCAN).
async function invalidate(group) {
  inflight.clear();
  await redisOr(async (r) => {
    const setKey = `cache:group:${group}`;
    const keys = await r.smembers(setKey);
    if (keys.length) await r.del(...keys);
    await r.del(setKey);
    return true;
  }, false);
}

const invalidateLeague = (leagueId) => (leagueId ? invalidate(`league:${leagueId}`) : Promise.resolve());
const invalidateClub = (clubId) => (clubId ? invalidate(`club:${clubId}`) : Promise.resolve());
const invalidateClubs = () => invalidate('clubs');
const invalidateProducts = () => invalidate('products');

module.exports = { cached, invalidate, invalidateLeague, invalidateClub, invalidateClubs, invalidateProducts };
