const { Router } = require('express');
const { send } = require('../../../http/respond');
const AppError = require('../../../http/AppError');
const { isPlainObject } = require('../../../http/validators');
const { authenticate } = require('../../../middleware/auth');
const { guard } = require('../../../middleware/guard');
const { matchResolver } = require('../../../http/resources');
const read = require('../../../services/readModelService');
const eventService = require('../../../services/eventService');
const { wrap } = require('./util');

const router = Router();
const BLOCKED = new Set(['postponed', 'abandoned', 'cancelled']);

router.get('/matches/:id', authenticate, guard('match.read', matchResolver('id', { read: true })), wrap(async (req, res) => {
  send(res, 200, await read.matchDetail(req.params.id));
}));

router.post('/matches/:id/events', authenticate, guard('match.score.write', matchResolver('id')), wrap(async (req, res) => {
  const body = req.body;
  if (!isPlainObject(body)) throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields: ['body'] });
  const parsed = eventService.parseEventRequest(body);
  if (parsed.match_id !== req.params.id) {
    throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields: ['match_id'] });
  }
  if (BLOCKED.has(req.match.admin_status)) {
    throw new AppError(409, 'MATCH_NOT_IN_PROGRESS', 'Match is not in progress', { status: req.match.admin_status });
  }
  const { status, body: out } = await eventService.appendEvent(body);
  send(res, status, out);
}));

module.exports = router;
