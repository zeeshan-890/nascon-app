const { Router } = require('express');
const { send } = require('../../../http/respond');
const { authenticate } = require('../../../middleware/auth');
const { guard } = require('../../../middleware/guard');
const { openResource, idParam } = require('../../../http/resources');
const read = require('../../../services/readModelService');
const { wrap } = require('./util');

const router = Router();

router.get('/sports/:id', authenticate, guard('sport.read', openResource), wrap(async (req, res) => {
  send(res, 200, await read.getSport(idParam(req, 'id', 'sport_id')));
}));

module.exports = router;
