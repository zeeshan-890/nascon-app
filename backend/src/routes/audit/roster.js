const { Router } = require('express');
const { send } = require('../../http/respond');
const { requireObjectBody } = require('../../http/validators');
const rosterService = require('../../services/rosterService');

const router = Router();

router.post('/roster/setup', async (req, res, next) => {
  try {
    requireObjectBody(req.body);
    send(res, 201, await rosterService.setup(req.body));
  } catch (err) {
    next(err);
  }
});

router.post('/roster/transfer', async (req, res, next) => {
  try {
    requireObjectBody(req.body);
    const out = await rosterService.transfer(req.body);
    send(res, out.status, out.body);
  } catch (err) {
    next(err);
  }
});

router.get('/roster/history/:player_id', async (req, res, next) => {
  try {
    const asOf = req.query.as_of;
    send(res, 200, await rosterService.history(req.params.player_id, asOf));
  } catch (err) {
    next(err);
  }
});

module.exports = router;
