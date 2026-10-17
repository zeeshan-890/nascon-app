const { Router } = require('express');
const { send } = require('../../http/respond');
const { checkout } = require('../../services/checkoutService');

const router = Router();

router.post('/checkout', async (req, res, next) => {
  try {
    const r = await checkout(req.body);
    send(res, r.status, r.body);
  } catch (err) {
    next(err);
  }
});

module.exports = router;
