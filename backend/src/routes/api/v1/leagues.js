const { Router } = require('express');
const { send } = require('../../../http/respond');
const { authenticate } = require('../../../middleware/auth');
const { guard } = require('../../../middleware/guard');
const { openResource, leagueResolver } = require('../../../http/resources');
const { tenantScope } = require('../../../domain/rbac/engine');
const read = require('../../../services/readModelService');
const { wrap, sendList, slicePage } = require('./util');

const router = Router();

router.get('/leagues', authenticate, guard('league.read', openResource), wrap(async (req, res) => {
  const { items, total } = await read.listLeagues(tenantScope(req.principal), req.query);
  sendList(req, res, items, total);
}));

router.get('/leagues/:id/fixtures', authenticate, guard('league.read', leagueResolver()), wrap(async (req, res) => {
  const all = await read.fixtures(req.params.id);
  sendList(req, res, slicePage(req, all), all.length);
}));

router.get('/leagues/:id/standings', authenticate, guard('standings.read', leagueResolver()), wrap(async (req, res) => {
  send(res, 200, await read.standings(req.params.id));
}));

module.exports = router;
