// Single definition of the RBAC matrix (DESIGN.md §12). Used by the reset seed and by tests.
// scope: any | own_league | own_club | own_club_player | self | same_tenant

const ROLES = ['super_admin', 'league_organizer', 'club_manager', 'player', 'viewer'];

const ACTIONS = {
  P1: 'tenant.create',
  P2: 'league.fixtures.write',
  P3: 'club.roster.write',
  P4: 'club.products.write',
  P5: 'player.update',
  P6: 'club.read',
  P7: 'platform.config.read',
};

// Actions added for the mobile API (M4). The probe rows above never change.
const MOBILE_ACTIONS = {
  LEAGUE_READ: 'league.read',
  MATCH_READ: 'match.read',
  STANDINGS_READ: 'standings.read',
  SPORT_READ: 'sport.read',
  PRODUCT_READ: 'product.read',
  MATCH_SCORE_WRITE: 'match.score.write',
  MATCH_MANAGE: 'match.manage',
  SEASON_WRITE: 'season.write',
  LEAGUE_CREATE: 'league.create',
  LEAGUE_APPROVE: 'league.approve',
  CLUB_WRITE: 'club.write',
  ORDER_CREATE: 'order.create',
  ORDER_READ: 'order.read',
  ORDER_MANAGE: 'order.manage',
  PLAYER_HISTORY_READ: 'player.history.read',
};

const READ_ACTIONS = ['league.read', 'match.read', 'standings.read', 'sport.read', 'product.read'];
const ALL_ROLES_SAME_TENANT = ['league_organizer', 'club_manager', 'player', 'viewer'];

const MOBILE_POLICIES = [
  ...ROLES.flatMap((role) => READ_ACTIONS.map((a) => [role, a, role === 'super_admin' ? 'any' : 'same_tenant'])),
  ['super_admin', 'match.score.write', 'any'],
  ['league_organizer', 'match.score.write', 'own_league'],
  ['super_admin', 'match.manage', 'any'],
  ['league_organizer', 'match.manage', 'own_league'],
  ['super_admin', 'season.write', 'any'],
  ['super_admin', 'league.create', 'any'],
  ['league_organizer', 'league.create', 'same_tenant'],
  ['super_admin', 'league.approve', 'any'],
  ['super_admin', 'club.write', 'any'],
  ['club_manager', 'club.write', 'own_club'],
  ...ROLES.map((role) => [role, 'order.create', 'any']),
  ['super_admin', 'order.read', 'any'],
  ...ALL_ROLES_SAME_TENANT.map((role) => [role, 'order.read', 'self']),
  ['super_admin', 'order.manage', 'any'],
  ['club_manager', 'order.manage', 'own_club'],
  ['super_admin', 'player.history.read', 'any'],
  ['club_manager', 'player.history.read', 'own_club_player'],
  ['player', 'player.history.read', 'self'],
];

const DEFAULT_POLICIES = [
  ['super_admin', ACTIONS.P1, 'any'],
  ['super_admin', ACTIONS.P2, 'any'],
  ['super_admin', ACTIONS.P3, 'any'],
  ['super_admin', ACTIONS.P4, 'any'],
  ['super_admin', ACTIONS.P5, 'any'],
  ['super_admin', ACTIONS.P6, 'any'],
  ['super_admin', ACTIONS.P7, 'any'],
  ['league_organizer', ACTIONS.P2, 'own_league'],
  ['league_organizer', ACTIONS.P6, 'same_tenant'],
  ['club_manager', ACTIONS.P3, 'own_club'],
  ['club_manager', ACTIONS.P4, 'own_club'],
  ['club_manager', ACTIONS.P5, 'own_club_player'],
  ['club_manager', ACTIONS.P6, 'same_tenant'],
  ['player', ACTIONS.P5, 'self'],
  ['player', ACTIONS.P6, 'same_tenant'],
  ['viewer', ACTIONS.P6, 'same_tenant'],
].concat(MOBILE_POLICIES).map(([role, action, scope]) => ({ role, action, scope }));

module.exports = { ROLES, ACTIONS, MOBILE_ACTIONS, DEFAULT_POLICIES };
