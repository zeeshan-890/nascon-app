const describeDb = process.env.DB_AVAILABLE === '1' ? describe : describe.skip;
module.exports = { describeDb };
