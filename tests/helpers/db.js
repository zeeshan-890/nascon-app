const dbUp = process.env.DB_AVAILABLE === '1';
const redisUp = process.env.REDIS_AVAILABLE === '1';
const describeDb = dbUp ? describe : describe.skip;
const describeRedis = redisUp ? describe : describe.skip;
const describeDbRedis = dbUp && redisUp ? describe : describe.skip;
module.exports = { describeDb, describeRedis, describeDbRedis };
