const { DEFAULT_POLICIES, ROLES } = require('../../../backend/src/domain/rbac/defaultPolicies');
const { authorize } = require('../../../backend/src/domain/rbac/engine');

const principals = {
  super_admin: { user_id: 'u0', role: 'super_admin', tenant_id: null, league_ids: [], club_id: null, player_id: null },
  league_organizer: { user_id: 'u1', role: 'league_organizer', tenant_id: 't1', league_ids: ['L1'], club_id: null, player_id: null },
  club_manager: { user_id: 'u2', role: 'club_manager', tenant_id: 't1', league_ids: [], club_id: 'C1', player_id: null },
  player: { user_id: 'u3', role: 'player', tenant_id: 't1', league_ids: [], club_id: 'C1', player_id: 'P1' },
  viewer: { user_id: 'u4', role: 'viewer', tenant_id: 't1', league_ids: [], club_id: null, player_id: null },
};

// scope per role for every new action (null = no policy at all)
const MATRIX = {
  'league.read': { super_admin: 'any', league_organizer: 'same_tenant', club_manager: 'same_tenant', player: 'same_tenant', viewer: 'same_tenant' },
  'match.read': { super_admin: 'any', league_organizer: 'same_tenant', club_manager: 'same_tenant', player: 'same_tenant', viewer: 'same_tenant' },
  'standings.read': { super_admin: 'any', league_organizer: 'same_tenant', club_manager: 'same_tenant', player: 'same_tenant', viewer: 'same_tenant' },
  'sport.read': { super_admin: 'any', league_organizer: 'same_tenant', club_manager: 'same_tenant', player: 'same_tenant', viewer: 'same_tenant' },
  'product.read': { super_admin: 'any', league_organizer: 'same_tenant', club_manager: 'same_tenant', player: 'same_tenant', viewer: 'same_tenant' },
  'match.score.write': { super_admin: 'any', league_organizer: 'own_league' },
  'match.manage': { super_admin: 'any', league_organizer: 'own_league' },
  'season.read': { super_admin: 'any', league_organizer: 'same_tenant', club_manager: 'same_tenant', player: 'same_tenant', viewer: 'same_tenant' },
  'season.write': { super_admin: 'any', league_organizer: 'same_tenant' },
  'league.create': { super_admin: 'any', league_organizer: 'same_tenant' },
  'league.write': { super_admin: 'any', league_organizer: 'own_league' },
  'league.approve': { super_admin: 'any' },
  'sport.write': { super_admin: 'any' },
  'club.write': { super_admin: 'any', club_manager: 'own_club' },
  'order.create': { super_admin: 'any', league_organizer: 'any', club_manager: 'any', player: 'any', viewer: 'any' },
  'order.read': { super_admin: 'any', league_organizer: 'self', club_manager: 'self', player: 'self', viewer: 'self' },
  'order.manage': { super_admin: 'any', club_manager: 'own_club' },
  'player.history.read': { super_admin: 'any', club_manager: 'own_club_player', player: 'self' },
  'club.roster.write': { super_admin: 'any', club_manager: 'own_club' },
  'club.read': { super_admin: 'any', league_organizer: 'same_tenant', club_manager: 'same_tenant', player: 'same_tenant', viewer: 'same_tenant' },
  'tenant.create': { super_admin: 'any' },
  'league.fixtures.write': { super_admin: 'any', league_organizer: 'own_league' },
};

const own = { tenant_id: 't1', league_id: 'L1', club_id: 'C1', player_id: 'P1', user_id: 'u3' };
const foreignTenant = { tenant_id: 't2', league_id: 'L1', club_id: 'C1', player_id: 'P1', user_id: 'u3' };
const otherScope = { tenant_id: 't1', league_id: 'L2', club_id: 'C2', player_id: 'P2', user_id: 'other' };

describe('every cell of the mobile RBAC matrix', () => {
  for (const [action, perRole] of Object.entries(MATRIX)) {
    for (const role of ROLES) {
      const scope = perRole[role] || null;
      test(`${role} / ${action} -> ${scope || 'denied'}`, () => {
        const row = DEFAULT_POLICIES.find((p) => p.role === role && p.action === action);
        expect(row ? row.scope : null).toBe(scope);
        const p = principals[role];
        const decide = (res) => authorize(p, action, res, DEFAULT_POLICIES).allowed;
        if (!scope) {
          expect(decide(own)).toBe(false);
          return;
        }
        if (scope === 'any') {
          expect(decide(foreignTenant)).toBe(true);
          return;
        }
        expect(decide(foreignTenant)).toBe(false);
        if (scope === 'same_tenant') {
          expect(decide(own)).toBe(true);
          expect(decide(otherScope)).toBe(true);
        } else {
          const ownResource = { ...own, user_id: p.user_id };
          expect(decide(ownResource)).toBe(true);
          expect(decide(otherScope)).toBe(false);
        }
      });
    }
  }

  test('there are no policies beyond the documented ones', () => {
    const documented = new Set(Object.entries(MATRIX).flatMap(([a, per]) => Object.keys(per).map((r) => `${r}|${a}`)));
    const extras = DEFAULT_POLICIES.filter((p) => !documented.has(`${p.role}|${p.action}`)).map((p) => `${p.role}|${p.action}`);
    // the P4/P5/P7 probe rows predate the mobile matrix
    expect(extras.sort()).toEqual([
      'club_manager|club.products.write', 'club_manager|player.update', 'player|player.update',
      'super_admin|club.products.write', 'super_admin|platform.config.read', 'super_admin|player.update',
    ].sort());
  });

  test('order.read self works through the owning user id', () => {
    const viewer = principals.viewer;
    expect(authorize(viewer, 'order.read', { tenant_id: 't1', user_id: 'u4' }, DEFAULT_POLICIES).allowed).toBe(true);
    expect(authorize(viewer, 'order.read', { tenant_id: 't1', user_id: 'u9' }, DEFAULT_POLICIES).allowed).toBe(false);
  });
});
