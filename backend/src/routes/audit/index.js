const { Router } = require('express');

const router = Router();
router.use(require('./reset'));
router.use(require('./sports'));
router.use(require('./leagues'));
router.use(require('./events'));
router.use(require('./score'));
router.use(require('./checkout'));
router.use(require('./inventory'));
router.use(require('./rbac'));
router.use(require('./roster'));
router.use(require('./standings'));
router.use(require('./fixtures'));
router.use(require('./offlineSync'));

module.exports = router;
