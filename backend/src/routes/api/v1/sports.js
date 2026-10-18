const { Router } = require('express');
const { send } = require('../../../http/respond');
const { authenticate } = require('../../../middleware/auth');
const { guard } = require('../../../middleware/guard');
const { openResource, idParam } = require('../../../http/resources');
const read = require('../../../services/readModelService');
const sportService = require('../../../services/sportService');
const { wrap, sendList } = require('./util');

const router = Router();

router.get('/sports', authenticate, guard('sport.read', openResource), wrap(async (req, res) => {
  const { items, total } = await read.listSports(req.query);
  sendList(req, res, items, total);
}));

router.post('/sports', authenticate, guard('sport.write', openResource), wrap(async (req, res) => {
  const out = await sportService.createSportIdempotent(req.body);
  send(res, out.status, out.body);
}));

router.get('/sports/:id', authenticate, guard('sport.read', openResource), wrap(async (req, res) => {
  send(res, 200, await read.getSport(idParam(req, 'id', 'sport_id')));
}));

module.exports = router;
