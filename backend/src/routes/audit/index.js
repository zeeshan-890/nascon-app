const { Router } = require('express');

const router = Router();
router.use(require('./reset'));
router.use(require('./sports'));
router.use(require('./leagues'));
router.use(require('./events'));
router.use(require('./score'));

module.exports = router;
