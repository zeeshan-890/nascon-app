// Probes the test database once; integration tests skip themselves when it is unreachable.
const { Client } = require('../backend/node_modules/pg');

module.exports = async () => {
  const url = process.env.DATABASE_URL || 'postgres://nascon:nascon@localhost:5433/nascon';
  process.env.DATABASE_URL = url;
  const client = new Client({ connectionString: url, connectionTimeoutMillis: 1500 });
  try {
    await client.connect();
    await client.end();
    process.env.DB_AVAILABLE = '1';
  } catch (e) {
    process.env.DB_AVAILABLE = '0';
  }
};
