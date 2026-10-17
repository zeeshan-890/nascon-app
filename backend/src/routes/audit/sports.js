const { Router } = require('express');
const { send } = require('../../http/respond');
const { requireObjectBody } = require('../../http/validators');
const { createSport } = require('../../services/sportService');

const router = Router();

router.post('/sports', async (req, res, next) => {
  try {
    requireObjectBody(req.body);
    send(res, 201, await createSport(req.body));
  } catch (err) {
    next(err);
  }
});

module.exports = router;
