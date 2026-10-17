const { Router } = require('express');
const pool = require('../../db/pool');
const AppError = require('../../http/AppError');
const { send } = require('../../http/respond');
const { authenticate } = require('../../middleware/auth');
const { ROLES, ACTIONS } = require('../../domain/rbac/defaultPolicies');
const { signAccessToken } = require('../../services/authService');
const { probe } = require('../../services/rbacService');

const router = Router();

const DEMO_EMAILS = {
  super_admin: 'admin@nascon.test',
  league_organizer: 'organizer@nascon.test',
  club_manager: 'manager@nascon.test',
  player: 'player@nascon.test',
  viewer: 'viewer@nascon.test',
};

const CONTEXT = {
  tenant_id: 'tenant-alpha',
  foreign_tenant_id: 'tenant-beta',
  own_league_id: 'league-alpha-1',
  other_league_id: 'league-alpha-2',
  foreign_league_id: 'league-beta-1',
  own_club_id: 'club-alpha-1',
  other_club_id: 'club-alpha-2',
  foreign_club_id: 'club-beta-1',
  own_player_id: 'player-alpha-1',
  other_player_id: 'player-alpha-2',
  foreign_player_id: 'player-beta-1',
};

// The body is never read here (the tolerant parser already discarded it).
router.post('/rbac-matrix', async (req, res, next) => {
  try {
    const { rows } = await pool.query(
      `SELECT user_id, email, role, tenant_id, club_id, player_id, league_ids
         FROM users WHERE email = ANY($1::text[])`,
      [Object.values(DEMO_EMAILS)]
    );
    const tokens = {};
    for (const role of ROLES) {
      const user = rows.find((u) => u.email === DEMO_EMAILS[role]);
      if (!user) throw new AppError(503, 'SERVICE_UNAVAILABLE', 'Demo users are not seeded yet');
      tokens[role] = signAccessToken(user);
    }
    send(res, 200, { tokens, context: { ...CONTEXT } });
  } catch (err) {
    next(err);
  }
});

function probeRoute(method, path, action, kind, param) {
  router[method](path, authenticate, async (req, res, next) => {
    try {
      send(res, 200, await probe(req.principal, action, kind, param ? req.params[param] : undefined));
    } catch (err) {
      next(err);
    }
  });
}

probeRoute('post', '/rbac/tenants', ACTIONS.P1);
probeRoute('post', '/rbac/leagues/:league_id/fixtures', ACTIONS.P2, 'league', 'league_id');
probeRoute('post', '/rbac/clubs/:club_id/roster', ACTIONS.P3, 'club', 'club_id');
probeRoute('post', '/rbac/clubs/:club_id/products', ACTIONS.P4, 'club', 'club_id');
probeRoute('patch', '/rbac/players/:player_id', ACTIONS.P5, 'player', 'player_id');
probeRoute('get', '/rbac/clubs/:club_id', ACTIONS.P6, 'club', 'club_id');
probeRoute('get', '/rbac/platform/config', ACTIONS.P7);

module.exports = router;
