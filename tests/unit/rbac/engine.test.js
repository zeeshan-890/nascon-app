const { authorize, tenantScope, findPolicy, DEFAULT_POLICIES, ACTIONS } = require('../../../backend/src/domain/rbac');

const ROLE_ORDER = ['super_admin', 'league_organizer', 'club_manager', 'player', 'viewer'];

const principals = {
  super_admin: { user_id: 'u1', role: 'super_admin', tenant_id: null, league_ids: [], club_id: null, player_id: null },
  league_organizer: { user_id: 'u2', role: 'league_organizer', tenant_id: 'tenant-alpha', league_ids: ['league-alpha-1'], club_id: null, player_id: null },
  club_manager: { user_id: 'u3', role: 'club_manager', tenant_id: 'tenant-alpha', league_ids: [], club_id: 'club-alpha-1', player_id: null },
  player: { user_id: 'u4', role: 'player', tenant_id: 'tenant-alpha', league_ids: [], club_id: 'club-alpha-1', player_id: 'player-alpha-1' },
  viewer: { user_id: 'u5', role: 'viewer', tenant_id: 'tenant-alpha', league_ids: [], club_id: null, player_id: null },
};

const league = (id, tenant) => ({ league_id: id, tenant_id: tenant });
const club = (id, tenant) => ({ club_id: id, tenant_id: tenant });
const player = (id, c, tenant) => ({ player_id: id, club_id: c, tenant_id: tenant });

const T = true;
const F = false;
// [label, action, resource, expected allowed per role in ROLE_ORDER]
const MATRIX = [
  ['P1', ACTIONS.P1, {}, [T, F, F, F, F]],
  ['P2 own', ACTIONS.P2, league('league-alpha-1', 'tenant-alpha'), [T, T, F, F, F]],
  ['P2 other', ACTIONS.P2, league('league-alpha-2', 'tenant-alpha'), [T, F, F, F, F]],
  ['P2 foreign', ACTIONS.P2, league('league-beta-1', 'tenant-beta'), [T, F, F, F, F]],
  ['P3 own', ACTIONS.P3, club('club-alpha-1', 'tenant-alpha'), [T, F, T, F, F]],
  ['P3 other', ACTIONS.P3, club('club-alpha-2', 'tenant-alpha'), [T, F, F, F, F]],
  ['P3 foreign', ACTIONS.P3, club('club-beta-1', 'tenant-beta'), [T, F, F, F, F]],
  ['P4 own', ACTIONS.P4, club('club-alpha-1', 'tenant-alpha'), [T, F, T, F, F]],
  ['P4 other', ACTIONS.P4, club('club-alpha-2', 'tenant-alpha'), [T, F, F, F, F]],
  ['P4 foreign', ACTIONS.P4, club('club-beta-1', 'tenant-beta'), [T, F, F, F, F]],
  ['P5 own', ACTIONS.P5, player('player-alpha-1', 'club-alpha-1', 'tenant-alpha'), [T, F, T, T, F]],
  ['P5 other', ACTIONS.P5, player('player-alpha-2', 'club-alpha-2', 'tenant-alpha'), [T, F, F, F, F]],
  ['P5 foreign', ACTIONS.P5, player('player-beta-1', 'club-beta-1', 'tenant-beta'), [T, F, F, F, F]],
  ['P6 own', ACTIONS.P6, club('club-alpha-1', 'tenant-alpha'), [T, T, T, T, T]],
  ['P6 other', ACTIONS.P6, club('club-alpha-2', 'tenant-alpha'), [T, T, T, T, T]],
  ['P6 foreign', ACTIONS.P6, club('club-beta-1', 'tenant-beta'), [T, F, F, F, F]],
  ['P7', ACTIONS.P7, {}, [T, F, F, F, F]],
];

describe('policy matrix (every cell)', () => {
  const cells = [];
  for (const [label, action, resource, expected] of MATRIX) {
    ROLE_ORDER.forEach((role, i) => cells.push([label, role, action, resource, expected[i]]));
  }
  test.each(cells)('%s as %s', (label, role, action, resource, expected) => {
    const r = authorize(principals[role], action, resource, DEFAULT_POLICIES);
    expect(r.allowed).toBe(expected);
    if (!expected) expect(typeof r.reason).toBe('string');
  });
  test('table covers 17 targets x 5 roles', () => expect(cells).toHaveLength(85));
});

describe('authorize edge cases', () => {
  const pol = DEFAULT_POLICIES;
  test('unknown role denied', () => {
    expect(authorize({ role: 'root', tenant_id: 'tenant-alpha' }, ACTIONS.P6, club('c', 'tenant-alpha'), pol)).toEqual({ allowed: false, reason: 'no_policy' });
  });
  test('inherited property names are not roles', () => {
    expect(authorize({ role: '__proto__' }, ACTIONS.P6, {}, pol).allowed).toBe(false);
    expect(authorize({ role: 'constructor' }, ACTIONS.P6, {}, pol).allowed).toBe(false);
  });
  test('unknown action denied', () => {
    expect(authorize(principals.super_admin, 'nope.nope', {}, pol).allowed).toBe(false);
  });
  test('missing / bad principal and policies deny', () => {
    expect(authorize(null, ACTIONS.P6, {}, pol).reason).toBe('no_principal');
    expect(authorize('x', ACTIONS.P6, {}, pol).allowed).toBe(false);
    expect(authorize(principals.viewer, ACTIONS.P6, club('c', 'tenant-alpha'), undefined).allowed).toBe(false);
    expect(authorize(principals.viewer, ACTIONS.P6, club('c', 'tenant-alpha'), []).allowed).toBe(false);
  });
  test('missing league_ids denies own_league', () => {
    const p = { role: 'league_organizer', tenant_id: 'tenant-alpha' };
    expect(authorize(p, ACTIONS.P2, league('league-alpha-1', 'tenant-alpha'), pol).allowed).toBe(false);
    expect(authorize({ ...p, league_ids: 'league-alpha-1' }, ACTIONS.P2, league('league-alpha-1', 'tenant-alpha'), pol).allowed).toBe(false);
  });
  test('null club_id never matches a resource without club', () => {
    const p = { ...principals.club_manager, club_id: null };
    expect(authorize(p, ACTIONS.P3, { tenant_id: 'tenant-alpha', club_id: null }, pol).allowed).toBe(false);
    expect(authorize(p, ACTIONS.P3, { tenant_id: 'tenant-alpha' }, pol).allowed).toBe(false);
    expect(authorize(p, ACTIONS.P5, { tenant_id: 'tenant-alpha', club_id: null }, pol).allowed).toBe(false);
  });
  test('null player_id never matches self', () => {
    const p = { ...principals.player, player_id: null };
    expect(authorize(p, ACTIONS.P5, { tenant_id: 'tenant-alpha', player_id: null }, pol).allowed).toBe(false);
  });
  test('tenant mismatch denies even when club id matches', () => {
    const r = authorize(principals.club_manager, ACTIONS.P3, club('club-alpha-1', 'tenant-beta'), pol);
    expect(r).toEqual({ allowed: false, reason: 'tenant_mismatch' });
  });
  test('principal without tenant or resource without tenant deny', () => {
    expect(authorize({ ...principals.viewer, tenant_id: null }, ACTIONS.P6, { tenant_id: null }, pol).allowed).toBe(false);
    expect(authorize(principals.viewer, ACTIONS.P6, null, pol).allowed).toBe(false);
    expect(authorize(principals.viewer, ACTIONS.P6, { club_id: 'club-alpha-1' }, pol).allowed).toBe(false);
  });
  test('unknown scope in a policy denies', () => {
    const odd = [{ role: 'viewer', action: 'x.y', scope: 'galaxy' }];
    expect(authorize(principals.viewer, 'x.y', { tenant_id: 'tenant-alpha' }, odd)).toEqual({ allowed: false, reason: 'unknown_scope' });
  });
  test('any scope ignores the resource entirely', () => {
    expect(authorize(principals.super_admin, ACTIONS.P2, undefined, pol).allowed).toBe(true);
  });
  test('findPolicy', () => {
    expect(findPolicy('viewer', ACTIONS.P6, pol)).toEqual({ role: 'viewer', action: ACTIONS.P6, scope: 'same_tenant' });
    expect(findPolicy('viewer', ACTIONS.P1, pol)).toBeNull();
    expect(findPolicy('viewer', ACTIONS.P6, null)).toBeNull();
  });
  test('tenantScope', () => {
    expect(tenantScope(principals.super_admin)).toBeNull();
    expect(tenantScope(principals.viewer)).toBe('tenant-alpha');
    expect(tenantScope({ role: 'viewer', tenant_id: null })).toBe('');
    expect(tenantScope(null)).toBe('');
  });
});
