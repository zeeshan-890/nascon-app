const { Router } = require('express');
const { z } = require('zod');
const { send } = require('../../../http/respond');
const AppError = require('../../../http/AppError');
const pool = require('../../../db/pool');
const matchesRepo = require('../../../repos/matches');
const { Id, IsoTimestamp, parseOr400, requireObjectBody } = require('../../../http/validators');
const { authenticate } = require('../../../middleware/auth');
const { guard, forbidden } = require('../../../middleware/guard');
const { matchResolver } = require('../../../http/resources');
const { STATUSES } = require('../../../domain/schedule/statusMachine');
const ops = require('../../../services/matchOpsService');
const { wrap } = require('./util');

const router = Router();
const manage = guard('match.manage', matchResolver('id'));

const StatusBody = z.object({ status: z.enum(STATUSES) });
const ScheduleBody = z.object({ scheduled_at: IsoTimestamp, venue_id: Id.nullable().optional() });
const PinBody = z.object({ product_id: Id });

router.patch('/matches/:id/status', authenticate, manage, wrap(async (req, res) => {
  const { status } = parseOr400(StatusBody, requireObjectBody(req.body));
  send(res, 200, await ops.changeStatus(req.params.id, status, req.principal.user_id));
}));

// Resolves the requested slot: the venue defaults to the current one; a venue of another tenant is 403.
async function slotFrom(req) {
  const body = parseOr400(ScheduleBody, requireObjectBody(req.body));
  const match = await matchesRepo.get(pool, req.params.id);
  let venueId = body.venue_id === undefined ? match.venue_id : body.venue_id;
  if (venueId) {
    const venue = await ops.loadVenue(venueId);
    if (req.principal.role !== 'super_admin' && venue.tenant_id !== req.principal.tenant_id) throw forbidden();
  } else {
    venueId = null;
  }
  return { match, slot: { scheduledAt: body.scheduled_at, venueId } };
}

router.post('/matches/:id/schedule/check', authenticate, manage, wrap(async (req, res) => {
  const { match, slot } = await slotFrom(req);
  const { conflicts } = await ops.checkSchedule(match, slot);
  send(res, 200, { conflicts });
}));

router.patch('/matches/:id/schedule', authenticate, manage, wrap(async (req, res) => {
  const { match, slot } = await slotFrom(req);
  send(res, 200, await ops.reschedule(match, slot));
}));

// The product must exist and belong to the league's tenant.
router.post('/matches/:id/pins', authenticate, manage, wrap(async (req, res) => {
  const { product_id: productId } = parseOr400(PinBody, requireObjectBody(req.body));
  const { rows } = await pool.query('SELECT tenant_id FROM products WHERE product_id = $1', [productId]);
  if (!rows[0]) throw new AppError(404, 'PRODUCT_NOT_FOUND', 'Product not found');
  if (req.match.tenant_id && rows[0].tenant_id !== req.match.tenant_id) throw forbidden();
  send(res, 201, await ops.pin(req.params.id, productId));
}));

router.delete('/matches/:id/pins/:product_id', authenticate, manage, wrap(async (req, res) => {
  const productId = req.params.product_id;
  if (!Id.safeParse(productId).success) {
    throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields: ['product_id'] });
  }
  send(res, 200, await ops.unpin(req.params.id, productId));
}));

module.exports = router;
