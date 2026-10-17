const { Router } = require('express');

const router = Router();
router.use(require('./ping'));
router.use(require('./auth'));

module.exports = router;
