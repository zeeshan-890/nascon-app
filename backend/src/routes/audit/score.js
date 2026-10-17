const { Router } = require('express');
const { send } = require('../../http/respond');
const { getScore } = require('../../services/scoreService');

const router = Router();

router.get('/score/:match_id', async (req, res, next) => {
  try {
    send(res, 200, await getScore(req.params.match_id));
  } catch (err) {
    next(err);
  }
});

module.exports = router;
