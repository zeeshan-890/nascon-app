const { Router } = require('express');

const router = Router();
router.use(require('./ping'));
router.use(require('./auth'));
for (const name of ['leagues', 'matches', 'sports', 'clubs', 'products', 'orders', 'sync', 'matchOps', 'orderAdmin', 'players']) {
  router.use(require(`./${name}`));
}

module.exports = router;
