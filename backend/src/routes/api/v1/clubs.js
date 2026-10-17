const { Router } = require('express');
const { send } = require('../../../http/respond');
const AppError = require('../../../http/AppError');
const { isPlainObject } = require('../../../http/validators');
const { authenticate } = require('../../../middleware/auth');
const { guard, forbidden } = require('../../../middleware/guard');
const { openResource, clubResolver } = require('../../../http/resources');
const { tenantScope } = require('../../../domain/rbac/engine');
const pool = require('../../../db/pool');
const roster = require('../../../domain/roster');
const read = require('../../../services/readModelService');
const rosterService = require('../../../services/rosterService');
const { wrap, sendList } = require('./util');

const router = Router();

router.get('/clubs', authenticate, guard('club.read', openResource), wrap(async (req, res) => {
  const { items, total } = await read.listClubs(tenantScope(req.principal), req.query);
  sendList(req, res, items, total);
}));

router.get('/clubs/:id', authenticate, guard('club.read', clubResolver()), wrap(async (req, res) => {
  send(res, 200, await read.clubDetail(req.params.id));
}));

// The squad a player leaves must belong to the club of the URL; a missing squad is left to the
// service (404). Validation errors come first.
async function assertOwnSquad(clubId, body, squadKey) {
  if (typeof body.league_id !== 'string' || typeof body[squadKey] !== 'string') return;
  const { rows } = await pool.query(
    'SELECT club_id FROM roster_squads WHERE league_id = $1 AND squad_id = $2', [body.league_id, body[squadKey]],
  );
  if (rows[0] && rows[0].club_id !== clubId) throw forbidden();
}

router.post('/clubs/:id/roster/transfer', authenticate, guard('club.roster.write', clubResolver()), wrap(async (req, res) => {
  if (!isPlainObject(req.body)) throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields: ['body'] });
  const body = { ...req.body, requested_by: req.body.requested_by === undefined ? req.principal.user_id : req.body.requested_by };
  const { fields } = roster.validateTransfer(body);
  if (fields.length) throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields });
  await assertOwnSquad(req.params.id, body, 'from_squad_id');
  const out = await rosterService.transfer(body);
  send(res, out.status, out.body);
}));

// Registers a new player into one of the club's squads (one registration per league season).
router.post('/clubs/:id/roster/add', authenticate, guard('club.roster.write', clubResolver()), wrap(async (req, res) => {
  if (!isPlainObject(req.body)) throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields: ['body'] });
  const body = { ...req.body, requested_by: req.body.requested_by === undefined ? req.principal.user_id : req.body.requested_by };
  const { fields } = roster.validateAdd(body);
  if (fields.length) throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields });
  // 404 for a missing league or squad comes from the service; a squad of another club is 403.
  const found = await pool.query('SELECT club_id FROM roster_squads WHERE league_id = $1 AND squad_id = $2', [body.league_id, body.squad_id]);
  if (found.rows[0] && found.rows[0].club_id !== req.params.id) throw forbidden();
  const out = await rosterService.addPlayer(body);
  send(res, out.status, out.body);
}));

module.exports = router;
