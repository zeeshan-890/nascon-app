const { Router } = require('express');
const { send } = require('../../http/respond');
const { requireObjectBody } = require('../../http/validators');
const { createLeague } = require('../../services/leagueService');

const router = Router();

router.post('/leagues', async (req, res, next) => {
  try {
    requireObjectBody(req.body);
    send(res, 201, await createLeague(req.body));
  } catch (err) {
    next(err);
  }
});

module.exports = router;
