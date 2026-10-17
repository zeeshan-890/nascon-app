const { Router } = require('express');
const { z } = require('zod');
const { send } = require('../../../http/respond');
const { parseOr400, requireObjectBody } = require('../../../http/validators');
const { authenticate } = require('../../../middleware/auth');
const { guard } = require('../../../middleware/guard');
const { orderResolver } = require('../../../http/resources');
const { TRANSITIONS } = require('../../../domain/store/orderStateMachine');
const orderService = require('../../../services/orderService');
const { wrap } = require('./util');

const router = Router();
const statuses = [...new Set([...Object.keys(TRANSITIONS), ...Object.values(TRANSITIONS).flat()])];
const StatusBody = z.object({ status: z.enum(statuses) });

router.patch('/orders/:id/status', authenticate, guard('order.manage', orderResolver()), wrap(async (req, res) => {
  const { status } = parseOr400(StatusBody, requireObjectBody(req.body));
  send(res, 200, await orderService.transition(req.params.id, status, req.principal.user_id));
}));

router.post('/orders/:id/refund', authenticate, guard('order.manage', orderResolver()), wrap(async (req, res) => {
  send(res, 200, await orderService.transition(req.params.id, 'refunded', req.principal.user_id));
}));

module.exports = router;
