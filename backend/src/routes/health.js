const express = require('express');
const pool = require('../db/pool');
const config = require('../config');
const { send, errorBody } = require('../http/respond');

const router = express.Router();

router.get('/health', async (req, res) => {
  try {
    if (config.state.ready) {
      const { rowCount } = await pool.query('SELECT 1 FROM tenants LIMIT 1');
      if (rowCount > 0) return send(res, 200, { status: 'ok' });
    }
  } catch (e) {
    // fall through to 503
  }
  return send(res, 503, errorBody('NOT_READY', 'Service starting'));
});

module.exports = router;
