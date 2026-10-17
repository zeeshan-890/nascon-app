const { Router } = require('express');
const { send } = require('../../http/respond');
const { reset } = require('../../services/resetService');

const router = Router();

router.post('/reset', async (req, res, next) => {
  try {
    send(res, 200, await reset(req.body));
  } catch (err) {
    next(err);
  }
});

module.exports = router;
