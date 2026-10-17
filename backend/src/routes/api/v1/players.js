const { Router } = require('express');
const { send } = require('../../../http/respond');
const { authenticate } = require('../../../middleware/auth');
const { guard } = require('../../../middleware/guard');
const { playerResolver } = require('../../../http/resources');
const rosterService = require('../../../services/rosterService');
const { wrap } = require('./util');

const router = Router();

router.get('/players/:id/history', authenticate, guard('player.history.read', playerResolver()), wrap(async (req, res) => {
  send(res, 200, await rosterService.history(req.params.id, req.query.as_of));
}));

module.exports = router;
