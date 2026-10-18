const { Router } = require('express');
const { send } = require('../../../http/respond');
const { authenticate } = require('../../../middleware/auth');
const { guard } = require('../../../middleware/guard');
const { idParam } = require('../../../http/resources');
const svc = require('../../../services/leagueAdminService');
const setup = require('../../../services/leagueSetupService');
const { wrap, sendList } = require('./util');

const router = Router();
const idOf = (req) => idParam(req, 'id', 'league_id');

router.get('/admin/leagues', authenticate, wrap(async (req, res) => {
  const items = await svc.list(req.principal);
  sendList(req, res, items);
}));

router.get('/admin/leagues/:id', authenticate, wrap(async (req, res) => {
  send(res, 200, await svc.get(req.principal, idOf(req)));
}));

// Super admin creates an active league; an organizer's league waits for approval (status 'pending').
router.post('/admin/leagues', authenticate,
  guard('league.create', async (req) => ({ tenant_id: req.principal.tenant_id })),
  wrap(async (req, res) => {
    send(res, 201, await svc.create(req.principal, req.body));
  }));

router.patch('/admin/leagues/:id', authenticate, wrap(async (req, res) => {
  send(res, 200, await svc.update(req.principal, idOf(req), req.body));
}));

router.post('/admin/leagues/:id/approve', authenticate, guard('league.approve'), wrap(async (req, res) => {
  send(res, 200, await svc.review(req.principal, idOf(req), 'approve'));
}));

router.post('/admin/leagues/:id/reject', authenticate, guard('league.approve'), wrap(async (req, res) => {
  send(res, 200, await svc.review(req.principal, idOf(req), 'reject'));
}));

// Teams and generated fixtures of an approved league (super admin, or the organizer who owns it).
router.get('/leagues/:id/teams', authenticate, wrap(async (req, res) => {
  sendList(req, res, await setup.listTeams(req.principal, idOf(req)));
}));

router.post('/leagues/:id/teams', authenticate, wrap(async (req, res) => {
  send(res, 201, await setup.addTeam(req.principal, idOf(req), req.body));
}));

router.delete('/leagues/:id/teams/:teamId', authenticate, wrap(async (req, res) => {
  send(res, 200, await setup.removeTeam(req.principal, idOf(req), idParam(req, 'teamId', 'team_id')));
}));

router.post('/leagues/:id/fixtures/generate', authenticate, wrap(async (req, res) => {
  send(res, 201, await setup.generate(req.principal, idOf(req), req.body));
}));

module.exports = router;
