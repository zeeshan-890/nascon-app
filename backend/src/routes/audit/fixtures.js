const { Router } = require('express');
const { send } = require('../../http/respond');
const { generateFixtures } = require('../../services/fixturesService');

const router = Router();

router.post('/fixtures', (req, res, next) => {
  try {
    send(res, 200, generateFixtures(req.body));
  } catch (err) {
    next(err);
  }
});

module.exports = router;
