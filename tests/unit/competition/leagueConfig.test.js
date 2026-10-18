const { validateLeagueCreate, validateLeaguePatch, configErrors, KINDS } = require('../../../backend/src/domain/competition/leagueConfig');

const defaults = { tieBreak: ['h2h_points', 'overall_diff'], lotsSeed: 42 };
const base = { league_id: 'L1', sport_id: 'football', name: 'League', kind: 'league', teams: ['a', 'b', 'c', 'd'] };
const create = (extra = {}) => validateLeagueCreate({ ...base, ...extra }, defaults);

describe('validateLeagueCreate', () => {
  test('defaults: points, tie-break (draw_lots appended), one qualification spot, seed from settings', () => {
    const { fields, value } = create();
    expect(fields).toEqual([]);
    expect(value.config).toEqual({
      points: { win: 3, draw: 1, loss: 0 },
      tie_break_order: ['h2h_points', 'overall_diff', 'draw_lots'],
      qualification_spots: 1,
      lots_seed: 42,
    });
    expect(value).toMatchObject({ season_id: null, parent_league_id: null, tenant_id: null, kind: 'league' });
  });

  test('every optional key is carried into the config', () => {
    const full = {
      points: { win: 2 }, tie_break_order: ['scored'], qualification_spots: 2, lots_seed: 5,
      transfer_window: { open: '2026-01-01', close: '2026-02-01' }, max_squad_size: 20, required_positions: { gk: 1 },
      promotion: { count: 1, to: 'up' }, relegation: { count: 1, to: 'down' }, season_id: 's1', parent_league_id: 'P', tenant_id: 't',
    };
    const { fields, value } = create(full);
    expect(fields).toEqual([]);
    expect(value.config).toEqual({
      points: { win: 2, draw: 1, loss: 0 }, tie_break_order: ['scored', 'draw_lots'], qualification_spots: 2, lots_seed: 5,
      transfer_window: full.transfer_window, max_squad_size: 20, required_positions: { gk: 1 },
      promotion: full.promotion, relegation: full.relegation,
    });
    expect(value).toMatchObject({ season_id: 's1', parent_league_id: 'P', tenant_id: 't' });
  });

  test('missing sport tie-break falls back to the full token list', () => {
    const { value } = validateLeagueCreate(base, { tieBreak: undefined, lotsSeed: 1 });
    expect(value.config.tie_break_order).toHaveLength(6);
  });

  test.each(KINDS)('kind %s is accepted (hybrid needs its config)', (kind) => {
    const extra = kind === 'hybrid' ? { kind, hybrid: { groups: 2, advance_per_group: 1 } } : { kind };
    expect(create(extra).fields).toEqual([]);
  });

  test('type errors list every field', () => {
    const { fields, value } = validateLeagueCreate({ league_id: 'x y', teams: 'a', kind: 'cup', points: { win: 'a' } }, defaults);
    expect(value).toBeNull();
    expect(fields).toEqual(expect.arrayContaining(['league_id', 'sport_id', 'name', 'kind', 'teams', 'points.win']));
    expect(validateLeagueCreate(null, defaults).fields).toEqual(['body']);
    expect(validateLeagueCreate([], defaults).fields).toEqual(['body']);
  });

  test('duplicate teams and tie-break entries, self parent', () => {
    expect(create({ teams: ['a', 'a', 'b'] }).fields).toContain('teams');
    expect(create({ tie_break_order: ['scored', 'scored'] }).fields).toContain('tie_break_order');
    expect(create({ parent_league_id: 'L1' }).fields).toContain('parent_league_id');
  });

  test('cross-field rules', () => {
    expect(create({ qualification_spots: 5 }).fields).toEqual(['qualification_spots']);
    expect(create({ transfer_window: { open: '2026-02-01', close: '2026-01-01' } }).fields).toEqual(['transfer_window.close']);
    expect(create({ promotion: { count: 1, to: 'L1' } }).fields).toEqual(['promotion.to']);
    expect(create({ relegation: { count: 1, to: 'L1' } }).fields).toEqual(['relegation.to']);
    expect(create({ promotion: { count: 5, to: 'u' } }).fields).toEqual(['promotion.count']);
    expect(create({ relegation: { count: 5, to: 'd' } }).fields).toEqual(['relegation.count']);
    expect(create({ promotion: { count: 3, to: 'u' }, relegation: { count: 3, to: 'd' } }).fields).toEqual(['relegation.count']);
    expect(create({ hybrid: { groups: 2, advance_per_group: 1 } }).fields).toEqual(['hybrid']);
    expect(create({ kind: 'hybrid' }).fields).toEqual(['hybrid']);
    expect(create({ kind: 'hybrid', hybrid: { groups: 3, advance_per_group: 1 } }).fields).toEqual(['hybrid.groups']);
    expect(create({ kind: 'hybrid', hybrid: { groups: 2, advance_per_group: 3 } }).fields).toEqual(['hybrid.advance_per_group']);
    expect(create({ kind: 'hybrid', teams: ['a', 'b'], hybrid: { groups: 1, advance_per_group: 1 } }).fields).toEqual(['hybrid.advance_per_group']);
  });
});

describe('configErrors', () => {
  test('a valid config has no errors', () => {
    expect(configErrors({ qualification_spots: 2 }, { kind: 'league', teamCount: 4 })).toEqual([]);
  });
  test('works without a league id', () => {
    expect(configErrors({ promotion: { count: 1, to: 'x' } }, { kind: 'league', teamCount: 4 })).toEqual([]);
  });
});

describe('validateLeaguePatch', () => {
  const current = {
    league_id: 'L1', kind: 'league', name: 'Old', season_id: null, teamCount: 6,
    config: { points: { win: 3, draw: 1, loss: 0 }, tie_break_order: ['scored', 'draw_lots'], qualification_spots: 1, lots_seed: 42 },
  };

  test('merges points, normalises the tie-break, sets and clears optional keys', () => {
    const r = validateLeaguePatch({
      name: 'New', points: { draw: 2 }, tie_break_order: ['overall_diff'], qualification_spots: 3,
      promotion: { count: 1, to: 'u' }, transfer_window: { open: '2026-01-01', close: '2026-01-02' },
    }, current);
    expect(r.fields).toEqual([]);
    expect(r.value.name).toBe('New');
    expect(r.value.config.points).toEqual({ win: 3, draw: 2, loss: 0 });
    expect(r.value.config.tie_break_order).toEqual(['overall_diff', 'draw_lots']);
    expect(r.value.config.promotion).toEqual({ count: 1, to: 'u' });
    expect(r.value.seasonChanged).toBe(false);
    expect(r.value.hybridChanged).toBe(false);
    const cleared = validateLeaguePatch({ promotion: null }, { ...current, config: { ...current.config, promotion: { count: 1, to: 'u' } } });
    expect(cleared.value.config.promotion).toBeUndefined();
  });

  test('season changes are flagged, including clearing', () => {
    expect(validateLeaguePatch({ season_id: 's1' }, current).value).toMatchObject({ season_id: 's1', seasonChanged: true });
    expect(validateLeaguePatch({ season_id: null }, { ...current, season_id: 's1' }).value).toMatchObject({ season_id: null, seasonChanged: true });
    expect(validateLeaguePatch({ season_id: 's1' }, { ...current, season_id: 's1' }).value.seasonChanged).toBe(false);
  });

  test('empty, non-object and invalid bodies', () => {
    expect(validateLeaguePatch({}, current).fields).toEqual(['body']);
    expect(validateLeaguePatch('x', current).fields).toEqual(['body']);
    expect(validateLeaguePatch({ name: '' }, current).fields).toEqual(['name']);
    expect(validateLeaguePatch({ tie_break_order: ['scored', 'scored'] }, current).fields).toEqual(['tie_break_order']);
  });

  test('only patched keys are judged against the merged config', () => {
    const odd = { ...current, teamCount: 0 }; // qualification_spots 1 > 0 teams, pre-existing
    expect(validateLeaguePatch({ name: 'x' }, odd).fields).toEqual([]);
    expect(validateLeaguePatch({ qualification_spots: 2 }, odd).fields).toEqual(['qualification_spots']);
    expect(validateLeaguePatch({ promotion: { count: 4, to: 'u' }, relegation: { count: 4, to: 'd' } }, current).fields).toEqual(['relegation.count']);
    expect(validateLeaguePatch({ promotion: { count: 1, to: 'L1' } }, current).fields).toEqual(['promotion.to']);
  });

  test('hybrid rules apply to hybrid leagues only', () => {
    const hy = { ...current, kind: 'hybrid', teamCount: 8, config: { ...current.config, hybrid: { groups: 2, advance_per_group: 2 } } };
    const ok = validateLeaguePatch({ hybrid: { groups: 4, advance_per_group: 1 } }, hy);
    expect(ok.fields).toEqual([]);
    expect(ok.value.hybridChanged).toBe(true);
    expect(validateLeaguePatch({ hybrid: { groups: 8, advance_per_group: 1 } }, hy).fields).toEqual(['hybrid.groups']);
    expect(validateLeaguePatch({ hybrid: { groups: 2, advance_per_group: 1 } }, current).fields).toEqual(['hybrid']);
  });
});
