const {
  snapshot, checkRules, checkCrossTenant, blockingMatch, buildRegistration, validateSetup, validateTransfer,
  transferFingerprint,
} = require('../../../backend/src/domain/roster');

const league = {
  league_id: 'L', tenant_id: 't1', window_open: '2026-01-10', window_close: '2026-02-10',
  max_squad_size: 3, required_positions: { gk: 1 },
};
const setupRows = [
  { player_id: 'gk1', squad_id: 'A', role: 'starter', position: 'gk' },
  { player_id: 'd1', squad_id: 'A', role: 'starter', position: 'df' },
  { player_id: 'gk2', squad_id: 'B', role: 'starter', position: 'gk' },
  { player_id: 'd2', squad_id: 'B', role: 'starter', position: 'df' },
  { player_id: 'd3', squad_id: 'B', role: 'starter', position: 'df' },
];
const req = (o) => ({ type: 'transfer', player_id: 'd1', from_squad_id: 'A', to_squad_id: 'C', effective_date: '2026-01-15', ...o });
const check = (o, entries = []) => {
  const r = req(o);
  return checkRules({ league, req: r, state: snapshot(setupRows, entries, r.effective_date) });
};

describe('checkRules order', () => {
  test('passes', () => expect(check({})).toBeNull());
  test('NOT_IN_SQUAD wins over everything', () => {
    expect(check({ from_squad_id: 'B', effective_date: '2030-01-01' }).code).toBe('NOT_IN_SQUAD');
    expect(check({ player_id: 'ghost' }).code).toBe('NOT_IN_SQUAD');
  });
  test('window then loan then full then min position', () => {
    expect(check({ effective_date: '2026-01-09' }).code).toBe('TRANSFER_WINDOW_CLOSED');
    expect(check({ effective_date: '2026-02-11' }).code).toBe('TRANSFER_WINDOW_CLOSED');
    expect(check({ effective_date: '2026-02-10' })).toBeNull();
    expect(check({ player_id: 'gk1', to_squad_id: 'B' }).code).toBe('SQUAD_FULL');
    expect(check({ player_id: 'gk1', to_squad_id: 'C' }).code).toBe('MIN_POSITION_VIOLATION');
    const loan = { audit_id: 'au-1', action: 'loan', player_id: 'd1', from_squad_id: 'A', to_squad_id: 'B', effective_date: '2026-01-12', loan_end_date: '2026-12-01' };
    expect(check({ from_squad_id: 'B', to_squad_id: 'C', effective_date: '2026-01-14' }, [loan]).code).toBe('PLAYER_ON_LOAN');
    expect(check({ from_squad_id: 'B', to_squad_id: 'C', effective_date: '2026-03-01' }, [loan]).code).toBe('TRANSFER_WINDOW_CLOSED');
  });
  test('release ignores the window and destination, but not minimum positions', () => {
    expect(check({ type: 'release', to_squad_id: null, effective_date: '2031-01-01' })).toBeNull();
    expect(check({ type: 'release', to_squad_id: null, player_id: 'gk1' }).code).toBe('MIN_POSITION_VIOLATION');
  });
  test('positions without a minimum never block', () => {
    expect(check({ player_id: 'd1', type: 'release', to_squad_id: null })).toBeNull();
  });
});

describe('cross tenant', () => {
  const a = { tenant_id: 't1' };
  const x = { tenant_id: 't2' };
  test('checks both squads, release only the from squad', () => {
    expect(checkCrossTenant(league, a, a, 'transfer')).toBeNull();
    expect(checkCrossTenant(league, a, x, 'loan').code).toBe('CROSS_TENANT_TRANSFER');
    expect(checkCrossTenant(league, x, a, 'transfer').code).toBe('CROSS_TENANT_TRANSFER');
    expect(checkCrossTenant(league, a, null, 'release')).toBeNull();
    expect(checkCrossTenant(league, x, null, 'release').code).toBe('CROSS_TENANT_TRANSFER');
  });
});

describe('blockingMatch and buildRegistration', () => {
  test('lowest match id among live matches of the squad', () => {
    const ms = [
      { match_id: 'm9', home_squad_id: 'A', away_squad_id: 'B' },
      { match_id: 'm10', home_squad_id: 'C', away_squad_id: 'A' },
      { match_id: 'm1', home_squad_id: 'C', away_squad_id: 'D' },
    ];
    expect(blockingMatch(ms, 'A')).toBe('m10');
    expect(blockingMatch(ms, 'Z')).toBeNull();
  });
  test('release has no registration', () => {
    expect(buildRegistration({ type: 'release' }, 'L', {})).toBeNull();
    const s = { squad_id: 'C', parent_squad_id: null, registration_type: 'transfer', start_date: 'd', loan_end_date: null, role: 'substitute' };
    expect(buildRegistration({ type: 'transfer', player_id: 'p' }, 'L', s)).toEqual({ player_id: 'p', league_id: 'L', ...s });
  });
});

describe('validateTransfer', () => {
  const ok = {
    request_id: 'r1', league_id: 'L', player_id: 'p', type: 'transfer', from_squad_id: 'A', to_squad_id: 'B',
    effective_date: '2026-01-15', reason: 'why', requested_by: 'me',
  };
  test('valid bodies', () => {
    expect(validateTransfer(ok).fields).toEqual([]);
    expect(validateTransfer({ ...ok, type: 'release', to_squad_id: '%%' }).fields).toEqual([]);
    expect(validateTransfer({ ...ok, type: 'loan', loan_end_date: '2026-01-16' }).fields).toEqual([]);
  });
  test('invalid bodies', () => {
    expect(validateTransfer([]).fields).toEqual(['body']);
    expect(validateTransfer({ ...ok, to_squad_id: 'A' }).fields).toEqual(['to_squad_id']);
    expect(validateTransfer({ ...ok, type: 'loan' }).fields).toEqual(['loan_end_date']);
    expect(validateTransfer({ ...ok, loan_end_date: 'x' }).fields).toEqual(['loan_end_date']);
    expect(validateTransfer({ ...ok, reason: 'x'.repeat(201) }).fields).toEqual(['reason']);
    expect(validateTransfer({ ...ok, to_squad_id: 5 }).fields).toEqual(['to_squad_id']);
  });
  test('fingerprint uses known fields only, absent values as null', () => {
    expect(transferFingerprint({ ...ok, extra: 1, to_squad_id: undefined })).toEqual({ ...ok, to_squad_id: null, loan_end_date: null });
  });
});

describe('validateSetup', () => {
  const ok = {
    league: { league_id: 'L', tenant_id: 't', sport_id: 'football', transfer_window: { open: '2026-01-01', close: '2026-01-01' } },
    squads: [{ squad_id: 'A', club_id: 'c', tenant_id: 't', name: 'n' }],
    players: [
      { player_id: 'p', name: 'n', position: 'gk', squad_id: 'A', role: 'captain' },
      { player_id: 'q', name: 'n', position: 'gk', squad_id: null, role: 'starter' },
    ],
  };
  test('valid', () => expect(validateSetup(ok).fields).toEqual([]));
  test('invalid shapes', () => {
    expect(validateSetup(null).fields).toEqual(['body']);
    expect(validateSetup({}).fields).toEqual(['league', 'squads', 'players']);
    const bad = validateSetup({
      ...ok,
      league: { ...ok.league, max_squad_size: 1.5, required_positions: { gk: -1 }, transfer_window: 5 },
      squads: [1, { squad_id: 'A' }],
      players: [null, { player_id: 'p' }],
      matches_in_progress: [7, { match_id: 'm', home_squad_id: 'A', away_squad_id: 'A' }, { match_id: 'm', home_squad_id: 'A', away_squad_id: 'Z' }],
    }).fields;
    expect(bad).toEqual(expect.arrayContaining([
      'league.max_squad_size', 'league.required_positions.gk', 'league.transfer_window', 'squads.0', 'squads.1.club_id',
      'players.0', 'players.1.name', 'matches_in_progress.0', 'matches_in_progress.2.match_id', 'matches_in_progress.2.away_squad_id',
    ]));
    expect(validateSetup({ ...ok, league: { ...ok.league, required_positions: [] } }).fields).toEqual(['league.required_positions']);
    expect(validateSetup({ ...ok, matches_in_progress: {} }).fields).toEqual(['matches_in_progress']);
    expect(validateSetup({ ...ok, league: 3 }).fields).toContain('league');
  });
});
