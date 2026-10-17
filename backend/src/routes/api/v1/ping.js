const { Router } = require('express');
const { send } = require('../../../http/respond');

const router = Router();
router.get('/ping', (req, res) => send(res, 200, { pong: true }));

module.exports = router;
