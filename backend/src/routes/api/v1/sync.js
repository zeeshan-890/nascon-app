const { Router } = require('express');
const { send } = require('../../../http/respond');
const { authenticate } = require('../../../middleware/auth');
const { authorize } = require('../../../domain/rbac/engine');
const { ensurePolicies } = require('../../../services/rbacService');
const { sync } = require('../../../services/offlineSyncService');
const { wrap } = require('./util');

const router = Router();

// Per-action authorization: a denied action is rejected FORBIDDEN, the rest still run.
router.post('/sync', authenticate, wrap(async (req, res) => {
  const policies = await ensurePolicies();
  const allow = async (ctx) => {
    if (ctx.type === 'score_event') {
      return authorize(req.principal, 'match.score.write', { tenant_id: ctx.tenant_id, league_id: ctx.league_id }, policies).allowed;
    }
    return authorize(req.principal, 'club.roster.write', { tenant_id: ctx.tenant_id, club_id: ctx.club_id }, policies).allowed;
  };
  send(res, 200, await sync(req.body, { authorize: allow }));
}));

module.exports = router;
