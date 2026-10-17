const { Router } = require('express');
const { send } = require('../../http/respond');
const { getStandings } = require('../../services/standingsService');

const router = Router();

router.get('/standings/:league_id', async (req, res, next) => {
  try {
    send(res, 200, await getStandings(req.params.league_id));
  } catch (err) {
    next(err);
  }
});

module.exports = router;
