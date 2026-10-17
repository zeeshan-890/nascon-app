const { Pool } = require('pg');
const config = require('../config');

const pool = new Pool({
  connectionString: config.databaseUrl,
  max: 30,
  options: '-c statement_timeout=4000 -c idle_in_transaction_session_timeout=5000',
});

pool.on('error', () => {});

module.exports = pool;
