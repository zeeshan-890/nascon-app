const { Router } = require('express');
const { send } = require('../../../http/respond');
const { authenticate } = require('../../../middleware/auth');
const { guard } = require('../../../middleware/guard');
const { openResource, idParam } = require('../../../http/resources');
const read = require('../../../services/readModelService');
const pool = require('../../../db/pool');
const { wrap } = require('./util');

const router = Router();

// Bare list for pickers (league form): sport_id and display name.
router.get('/sports', authenticate, guard('sport.read', openResource), wrap(async (req, res) => {
  const { rows } = await pool.query('SELECT sport_id, name FROM sports ORDER BY sport_id');
  res.set('X-Total-Count', String(rows.length));
  send(res, 200, rows);
}));

router.get('/sports/:id',authenticate, guard('sport.read', openResource), wrap(async (req, res) => {
  send(res, 200, await read.getSport(idParam(req, 'id', 'sport_id')));
}));

module.exports = router;
