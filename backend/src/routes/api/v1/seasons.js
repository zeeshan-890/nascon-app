const { Router } = require('express');
const { send } = require('../../../http/respond');
const pool = require('../../../db/pool');
const seasons = require('../../../repos/seasons');
const { authenticate } = require('../../../middleware/auth');
const { guard } = require('../../../middleware/guard');
const { openResource, seasonResolver } = require('../../../http/resources');
const { tenantScope } = require('../../../domain/rbac/engine');
const { pageOf100 } = require('../../../services/readModelService');
const competition = require('../../../services/competitionService');
const { wrap, sendList } = require('./util');

const router = Router();

router.get('/seasons', authenticate, guard('season.read', openResource), wrap(async (req, res) => {
  const { items, total } = await seasons.list(pool, tenantScope(req.principal), pageOf100(req.query));
  sendList(req, res, items, total);
}));

router.post('/seasons', authenticate, guard('season.write', openResource), wrap(async (req, res) => {
  const out = await competition.createSeason(req.principal, req.body);
  send(res, out.status, out.body);
}));

router.get('/seasons/:id', authenticate, guard('season.read', seasonResolver()), wrap(async (req, res) => {
  send(res, 200, await seasons.get(pool, req.params.id));
}));

router.patch('/seasons/:id', authenticate, guard('season.write', seasonResolver()), wrap(async (req, res) => {
  send(res, 200, await competition.patchSeason(req.params.id, req.body));
}));

module.exports = router;
