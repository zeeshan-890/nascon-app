const { Router } = require('express');
const { send } = require('../../../http/respond');
const { authenticate } = require('../../../middleware/auth');
const { guard } = require('../../../middleware/guard');
const { openResource, leagueResolver } = require('../../../http/resources');
const { tenantScope } = require('../../../domain/rbac/engine');
const read = require('../../../services/readModelService');
const competition = require('../../../services/competitionService');
const { wrap, sendList, slicePage } = require('./util');

const router = Router();

// A body that is not a JSON object is reported by the service; league creation also works for tenant organizers.
router.post('/leagues', authenticate, guard('league.create', openResource), wrap(async (req, res) => {
  const out = await competition.createLeague(req.principal, req.body);
  send(res, out.status, out.body);
}));

router.get('/leagues/:id', authenticate, guard('league.read', leagueResolver()), wrap(async (req, res) => {
  send(res, 200, await competition.getLeague(req.params.id));
}));

router.patch('/leagues/:id', authenticate, guard('league.write', leagueResolver()), wrap(async (req, res) => {
  send(res, 200, await competition.patchLeague(req.params.id, req.body));
}));

router.post('/leagues/:id/fixtures/build', authenticate, guard('league.fixtures.write', leagueResolver()), wrap(async (req, res) => {
  send(res, 201, await competition.generateLeagueFixtures(req.params.id, req.body));
}));

router.post('/leagues/:id/knockout', authenticate, guard('league.fixtures.write', leagueResolver()), wrap(async (req, res) => {
  send(res, 201, await competition.seedKnockout(req.params.id, req.body));
}));

router.get('/leagues/:id/promotion-relegation', authenticate, guard('standings.read', leagueResolver()), wrap(async (req, res) => {
  send(res, 200, await competition.getPromotionRelegation(req.params.id));
}));

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
