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
].map(([role, action, scope]) => ({ role, action, scope }));

module.exports = { ROLES, ACTIONS, DEFAULT_POLICIES };
