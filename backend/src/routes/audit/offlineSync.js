const { Router } = require('express');
const { send } = require('../../http/respond');
const { sync } = require('../../services/offlineSyncService');

const router = Router();

router.post('/offline-sync', async (req, res, next) => {
  try {
    send(res, 200, await sync(req.body));
  } catch (err) {
    next(err);
  }
});

module.exports = router;
