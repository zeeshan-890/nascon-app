const { Router } = require('express');
const { send } = require('../../http/respond');
const { appendEvent } = require('../../services/eventService');

const router = Router();

router.post('/events', async (req, res, next) => {
  try {
    const { status, body } = await appendEvent(req.body);
    send(res, status, body);
  } catch (err) {
    next(err);
  }
});

module.exports = router;
