const { computeStandings, qualificationStatus, oversFaced, diffOf, round3, withLots } = require('../../../backend/src/domain/standings');
const { orderTeams } = require('../../../backend/src/domain/standings/ordering');
const { lotsKey } = require('../../../backend/src/domain/crypto');

const side = (team_id, score, extra = {}) => ({ team_id, score, wickets: null, balls: null, fair_play: 0, ...extra });

// Completed match; outcome derived from the scores unless given.
function m(home, away, hs, as, o = {}) {
  const outcome = o.outcome || (hs > as ? 'home_win' : as > hs ? 'away_win' : 'draw');
  return {
    status: o.status || 'completed',
    outcome,
    home: side(home, hs, o.h),
    away: side(away, as, o.a),
  };
}
const pending = (home, away, status = 'upcoming') => m(home, away, 0, 0, { status, outcome: 'pending' });

const ORDER = ['h2h_points', 'h2h_diff', 'overall_diff', 'scored', 'fair_play', 'draw_lots'];
function run(teams, matches, over = {}) {
  return computeStandings({
    league_id: 'lg-1', sport_id: 'sp', teams, scoring_model: 'event', periods: {}, points: undefined,
    tie_break_order: ORDER, qualification_spots: 1, lots_seed: 42, matches, ...over,
  });
}
const ids = (r) => r.table.map((t) => t.team_id);
const by = (r) => Object.fromEntries(r.table.map((t) => [t.team_id, t.resolved_by]));
const row = (r, id) => r.table.find((t) => t.team_id === id);

describe('points and played/remaining', () => {
  test('custom points 2/1/0 and 3/1/0', () => {
    const matches = [m('A', 'B', 2, 0), m('B', 'C', 1, 1), m('C', 'A', 0, 3)];
    const two = run(['A', 'B', 'C'], matches, { points: { win: 2, draw: 1, loss: 0 } });
    expect(two.table.map((t) => [t.team_id, t.points])).toEqual([['A', 4], ['B', 1], ['C', 1]]);
    const three = run(['A', 'B', 'C'], matches, { points: { win: 3, draw: 1, loss: 0 } });
    expect(three.table.map((t) => t.points)).toEqual([6, 1, 1]);
    const odd = run(['A', 'B'], [m('A', 'B', 0, 1)], { points: { win: 5, draw: 2, loss: -1 } });
    expect(odd.table.map((t) => [t.team_id, t.points])).toEqual([['B', 5], ['A', -1]]);
  });

  test('stat lines', () => {
    const r = run(['A', 'B'], [m('A', 'B', 3, 1, { h: { fair_play: -1 }, a: { fair_play: -2 } }), m('B', 'A', 2, 2)]);
    expect(row(r, 'A')).toMatchObject({ played: 2, won: 1, drawn: 1, lost: 0, scored: 5, conceded: 3, diff: 2, points: 4, fair_play: -1 });
    expect(row(r, 'B')).toMatchObject({ played: 2, won: 0, drawn: 1, lost: 1, scored: 3, conceded: 5, diff: -2, points: 1, fair_play: -2 });
  });

  test('in-progress, upcoming and undecided matches are remaining, not played', () => {
    const r = run(['A', 'B'], [
      pending('A', 'B', 'in_progress'), pending('B', 'A'), m('A', 'B', 1, 1, { outcome: 'undecided' }), m('A', 'B', 1, 0),
    ]);
    expect(row(r, 'A')).toMatchObject({ played: 1, points: 3 });
    expect(r.h2h.A.B.played).toBe(1);
  });

  test('a tiebreak winner counts as a win', () => {
    const r = run(['A', 'B'], [m('A', 'B', 1, 1, { outcome: 'away_win' })]);
    expect(row(r, 'B')).toMatchObject({ won: 1, drawn: 0, points: 3, scored: 1, conceded: 1, diff: 0 });
  });

  test('matches with teams outside the league are ignored', () => {
    const r = run(['A', 'B'], [m('A', 'Z', 5, 0), pending('Z', 'B')]);
    expect(row(r, 'A').played).toBe(0);
    expect(r.h2h).toEqual({ A: { B: expect.any(Object) }, B: { A: expect.any(Object) } });
  });
});

describe('ordering', () => {
  test('2-way tie decided by h2h_points', () => {
    const r = run(['A', 'B', 'C', 'D'], [
      m('A', 'B', 1, 0), m('C', 'A', 1, 0), m('A', 'D', 1, 0), m('B', 'C', 1, 0), m('B', 'D', 5, 0),
    ]);
    // A: 6, B: 6, C: 3, D: 0
    expect(ids(r)).toEqual(['A', 'B', 'C', 'D']);
    expect(by(r)).toEqual({ A: 'h2h_points', B: null, C: null, D: null });
    expect(r.table.map((t) => t.rank)).toEqual([1, 2, 3, 4]);
  });

  test('3-way tie: mini-league splits 1 + 2, the pair restarts from the first criterion', () => {
    const matches = [
      m('A', 'B', 1, 0), m('B', 'A', 0, 1), m('A', 'C', 1, 0), m('C', 'A', 0, 1), // A beats B and C twice
      m('B', 'C', 2, 0), m('C', 'B', 1, 0), // B and C: 3 points each, B better difference
      m('B', 'D', 1, 0), m('B', 'D', 1, 0), m('B', 'D', 1, 0),
      m('C', 'D', 1, 0), m('C', 'D', 1, 0), m('C', 'D', 1, 0),
      m('D', 'A', 1, 0), m('D', 'A', 1, 0), m('D', 'A', 1, 0),
    ];
    const r = run(['A', 'B', 'C', 'D'], matches);
    expect(r.table.map((t) => t.points)).toEqual([12, 12, 12, 9]);
    expect(ids(r)).toEqual(['A', 'B', 'C', 'D']);
    expect(by(r)).toEqual({ A: 'h2h_points', B: 'h2h_diff', C: null, D: null });
  });

  test('3-way tie fully separated by the mini-league', () => {
    const matches = [
      m('A', 'B', 1, 0), m('A', 'C', 1, 0), m('B', 'C', 1, 0),
      m('D', 'A', 1, 0), m('B', 'D', 1, 0), m('C', 'D', 1, 0), m('C', 'D', 1, 0),
    ];
    const r = run(['A', 'B', 'C', 'D'], matches);
    expect(r.table.map((t) => t.points)).toEqual([6, 6, 6, 3]);
    expect(ids(r)).toEqual(['A', 'B', 'C', 'D']);
    expect(by(r)).toEqual({ A: 'h2h_points', B: 'h2h_points', C: null, D: null });
  });

  test('mini-league all equal falls through to overall_diff', () => {
    const matches = [
      m('A', 'B', 0, 0), m('B', 'C', 0, 0), m('C', 'A', 0, 0),
      m('A', 'D', 5, 0), m('B', 'D', 2, 0), m('C', 'D', 1, 0),
    ];
    const r = run(['A', 'B', 'C', 'D'], matches);
    expect(ids(r)).toEqual(['A', 'B', 'C', 'D']);
    expect(by(r)).toEqual({ A: 'overall_diff', B: 'overall_diff', C: null, D: null });
  });

  test('then scored', () => {
    const matches = [
      m('A', 'B', 0, 0), m('B', 'C', 0, 0), m('C', 'A', 0, 0),
      m('A', 'D', 3, 1), m('B', 'D', 2, 0), m('C', 'D', 4, 2),
    ];
    const r = run(['A', 'B', 'C', 'D'], matches);
    expect(ids(r)).toEqual(['C', 'A', 'B', 'D']);
    expect(by(r)).toEqual({ C: 'scored', A: 'scored', B: null, D: null });
  });

  test('then fair_play (higher is better)', () => {
    const matches = [
      m('A', 'B', 0, 0), m('B', 'C', 0, 0), m('C', 'A', 0, 0),
      m('A', 'D', 2, 0, { h: { fair_play: -1 } }), m('B', 'D', 2, 0), m('C', 'D', 2, 0, { h: { fair_play: -3 } }),
    ];
    const r = run(['A', 'B', 'C', 'D'], matches);
    expect(ids(r)).toEqual(['B', 'A', 'C', 'D']);
    expect(by(r)).toEqual({ B: 'fair_play', A: 'fair_play', C: null, D: null });
  });

  test('custom tie_break_order, draw_lots appended when missing', () => {
    const matches = [m('A', 'B', 1, 0), m('B', 'C', 4, 0), m('C', 'A', 1, 0)];
    // A 3, B 3, C 3; scored: A 1, B 4, C 1
    const byScored = run(['A', 'B', 'C'], matches, { tie_break_order: ['scored'] });
    expect(ids(byScored)[0]).toBe('B');
    expect(by(byScored).B).toBe('scored');
    // A and C then separated by drawing of lots
    const [, second, third] = ids(byScored);
    expect(by(byScored)[second]).toBe('draw_lots');
    expect(by(byScored)[third]).toBeNull();
    expect(withLots(['scored'])).toEqual(['scored', 'draw_lots']);
    expect(withLots(['scored', 'draw_lots'])).toEqual(['scored', 'draw_lots']);
    expect(withLots(undefined)).toEqual(['draw_lots']);
  });

  test('all criteria equal: drawing of lots (Appendix A vector 2)', () => {
    expect(lotsKey(42, 'lg-1', 't1').startsWith('0613')).toBe(true);
    expect(lotsKey(42, 'lg-1', 't3').startsWith('c8f2')).toBe(true);
    expect(lotsKey(42, 'lg-1', 't2').startsWith('e3e3')).toBe(true);
    const r = run(['t1', 't2', 't3'], []);
    expect(ids(r)).toEqual(['t1', 't3', 't2']);
    expect(by(r)).toEqual({ t1: 'draw_lots', t3: 'draw_lots', t2: null });
  });

  test('4- and 5-team ties are ordered by ascending lots key', () => {
    for (const n of [4, 5]) {
      const teams = Array.from({ length: n }, (_, i) => `tm${i}`);
      const r = run(teams, [], { lots_seed: 7 });
      const expected = teams.slice().sort((a, b) => (lotsKey(7, 'lg-1', a) < lotsKey(7, 'lg-1', b) ? -1 : 1));
      expect(ids(r)).toEqual(expected);
      expect(r.table.slice(0, -1).every((t) => t.resolved_by === 'draw_lots')).toBe(true);
      expect(r.table[n - 1].resolved_by).toBeNull();
    }
  });

  test('tied groups in the middle and at several points levels at once', () => {
    const matches = [
      m('W', 'A', 1, 0), m('W', 'B', 1, 0), m('W', 'C', 1, 0), m('W', 'D', 1, 0), // W: 12
      m('A', 'B', 1, 0), m('C', 'D', 1, 0), m('D', 'C', 1, 0), m('B', 'A', 1, 0),
      m('A', 'X', 1, 0), m('B', 'X', 1, 0),
    ];
    const r = run(['W', 'A', 'B', 'C', 'D', 'X'], matches);
    // A: 6, B: 6 (tied; h2h equal, then diff etc.), C: 3, D: 3
    expect(r.table.map((t) => t.points)).toEqual([12, 6, 6, 3, 3, 0]);
    expect(r.table[0].resolved_by).toBeNull();
    expect(by(r)[ids(r)[1]]).not.toBeNull();
    expect(by(r)[ids(r)[2]]).toBeNull();
    expect(by(r)[ids(r)[3]]).not.toBeNull();
    expect(by(r)[ids(r)[4]]).toBeNull();
  });

  test('a league with no played matches: zeros, order by lots, nobody eliminated', () => {
    const r = run(['A', 'B', 'C', 'D'], [pending('A', 'B'), pending('C', 'D'), pending('A', 'C'), pending('B', 'D')], { qualification_spots: 2 });
    for (const t of r.table) {
      expect(t).toMatchObject({ played: 0, won: 0, drawn: 0, lost: 0, scored: 0, conceded: 0, diff: 0, points: 0, fair_play: 0, status: 'still_possible' });
    }
    expect(ids(r)).toEqual(['A', 'B', 'C', 'D'].sort((a, b) => (lotsKey(42, 'lg-1', a) < lotsKey(42, 'lg-1', b) ? -1 : 1)));
  });

  test('orderTeams falls back to a stable order when nothing separates (defensive)', () => {
    const stats = new Map([['b', { points: 0, diff: 0, scored: 0, fair_play: 0 }], ['a', { points: 0, diff: 0, scored: 0, fair_play: 0 }]]);
    const ctx = { stats, records: [], model: 'event', periods: {}, points: { win: 3, draw: 1, loss: 0 }, tieBreak: [], leagueId: 'L', lotsSeed: 1 };
    expect(orderTeams(['b', 'a'], ctx).order).toHaveLength(2);
  });
});

describe('differentials', () => {
  const periods = { innings_per_team: 1, overs_per_innings: 20, balls_per_over: 6, wickets_per_innings: 10 };
  const inn = (home, away, hRuns, hBalls, hWk, aRuns, aBalls, aWk, o = {}) =>
    m(home, away, hRuns, aRuns, { ...o, h: { balls: hBalls, wickets: hWk }, a: { balls: aBalls, wickets: aWk } });
  const runInn = (teams, matches, over = {}) => run(teams, matches, { scoring_model: 'innings', periods, ...over });

  test('event and round: integer difference', () => {
    expect(row(run(['A', 'B'], [m('A', 'B', 3, 1)]), 'A').diff).toBe(2);
    const round = run(['A', 'B'], [m('A', 'B', 2, 1), m('B', 'A', 2, 0)], { scoring_model: 'round' });
    expect(row(round, 'A').diff).toBe(-1);
    expect(row(round, 'B').diff).toBe(1);
  });

  test('net run rate from decimal overs', () => {
    const r = runInn(['A', 'B'], [inn('A', 'B', 120, 120, 5, 100, 60, 2)]);
    expect(row(r, 'A').diff).toBe(-4); // 120/20 - 100/10
    expect(row(r, 'B').diff).toBe(4);
  });

  test('an all-out innings counts as the full overs', () => {
    const r = runInn(['A', 'B'], [inn('A', 'B', 90, 54, 10, 91, 60, 3)]);
    // A: 90/20 - 91/10 = 4.5 - 9.1
    expect(row(r, 'A').diff).toBe(-4.6);
    expect(oversFaced(54, 10, periods)).toBe(20);
    expect(oversFaced(54, 9, periods)).toBe(9);
  });

  test('rounding to 3 decimals and never -0', () => {
    const r = runInn(['A', 'B'], [inn('A', 'B', 100, 43, 3, 99, 41, 3)]);
    const expected = Number((100 / (43 / 6) - 99 / (41 / 6)).toFixed(3));
    expect(row(r, 'A').diff).toBe(expected);
    expect(String(row(r, 'A').diff).split('.')[1].length).toBeLessThanOrEqual(3);
    expect(Object.is(round3(-0.0001), -0)).toBe(false);
    const even = runInn(['A', 'B'], [inn('A', 'B', 100, 60, 3, 100, 60, 3)]);
    expect(Object.is(row(even, 'A').diff, 0)).toBe(true);
  });

  test('zero overs never produce NaN or Infinity', () => {
    const r = runInn(['A', 'B'], [inn('A', 'B', 0, 0, 0, 5, 12, 0)]);
    expect(row(r, 'A').diff).toBe(-2.5);
    expect(row(r, 'B').diff).toBe(2.5);
    expect(diffOf('innings', null)).toBe(0);
    expect(diffOf('innings', { scored: 4, conceded: 4, oversFaced: 0, oversBowled: 0 })).toBe(0);
  });

  test('totals are summed before dividing', () => {
    const r = runInn(['A', 'B', 'C'], [inn('A', 'B', 60, 60, 2, 50, 60, 2), inn('A', 'C', 100, 120, 2, 50, 120, 2)]);
    // A: runs 160 / 30 overs - 100 / 30 overs
    expect(row(r, 'A').diff).toBe(Number((160 / 30 - 100 / 30).toFixed(3)));
  });

  test('h2h net run rate decides a tie', () => {
    // Drawn on runs, but B used half the overs: B's net run rate is better.
    const r = runInn(['A', 'B'], [inn('A', 'B', 100, 120, 3, 100, 60, 3, { outcome: 'draw' })]);
    expect(r.table.map((t) => t.points)).toEqual([1, 1]);
    expect(ids(r)).toEqual(['B', 'A']);
    expect(by(r)).toEqual({ B: 'h2h_diff', A: null });
    expect(row(r, 'B').diff).toBe(5);
    expect(row(r, 'A').diff).toBe(-5);
  });

  test('h2h_diff uses only matches among the group', () => {
    const teams = ['A', 'B', 'C'];
    const matches = [
      inn('A', 'B', 120, 120, 2, 60, 120, 2, { outcome: 'draw' }), // draw in points, A far better NRR vs B
      inn('B', 'C', 100, 120, 2, 100, 120, 2, { outcome: 'draw' }),
      inn('C', 'A', 100, 120, 2, 100, 120, 2, { outcome: 'draw' }),
    ];
    const r = runInn(teams, matches);
    expect(r.table.map((t) => t.points)).toEqual([2, 2, 2]);
    expect(ids(r)[0]).toBe('A');
    expect(by(r).A).toBe('h2h_diff');
  });
});

describe('head to head matrix', () => {
  test('every ordered pair, zeros when never met, meetings accumulate', () => {
    const r = run(['A', 'B', 'C'], [m('A', 'B', 2, 1), m('B', 'A', 0, 0), m('A', 'B', 1, 3)]);
    expect(r.h2h.A.B).toEqual({ played: 3, points: 4, scored: 3, conceded: 4 });
    expect(r.h2h.B.A).toEqual({ played: 3, points: 4, scored: 4, conceded: 3 });
    expect(r.h2h.A.C).toEqual({ played: 0, points: 0, scored: 0, conceded: 0 });
    expect(r.h2h.C.B).toEqual({ played: 0, points: 0, scored: 0, conceded: 0 });
    expect(Object.keys(r.h2h.A).sort()).toEqual(['B', 'C']);
    expect(r.h2h.A.A).toBeUndefined();
  });
});

describe('qualification', () => {
  const rows = (...pts) => pts.map((p, i) => ({ team_id: 'ABCD'[i], points: p }));
  const rem = (...n) => new Map(n.map((x, i) => ['ABCD'[i], x]));
  const st = (r, rm, k = 2) => Object.fromEntries(qualificationStatus(r, rm, 3, k));

  test('clinched, eliminated at the end of the season', () => {
    expect(st(rows(9, 6, 3, 0), rem(0, 0, 0, 0))).toEqual({ A: 'clinched', B: 'clinched', C: 'eliminated', D: 'eliminated' });
  });
  test('still possible mid-season', () => {
    expect(st(rows(6, 6, 3, 3), rem(1, 1, 1, 1))).toEqual({ A: 'still_possible', B: 'still_possible', C: 'still_possible', D: 'still_possible' });
  });
  test('clinched early, eliminated early', () => {
    const s = st(rows(12, 12, 0, 0), rem(1, 1, 0, 0));
    expect(s).toEqual({ A: 'clinched', B: 'clinched', C: 'eliminated', D: 'eliminated' });
    const mid = st(rows(9, 3, 3, 0), rem(1, 1, 1, 1), 1);
    expect(mid.A).toBe('clinched');
    expect(mid.D).toBe('eliminated'); // A's 9 points exceed D's best possible 3
  });
  test('everyone with the same remaining fixtures', () => {
    expect(st(rows(0, 0, 0, 0), rem(3, 3, 3, 3))).toEqual({ A: 'still_possible', B: 'still_possible', C: 'still_possible', D: 'still_possible' });
  });
  test('via computeStandings with remaining fixtures counted', () => {
    const r = run(['A', 'B', 'C', 'D'], [m('A', 'B', 1, 0), m('C', 'D', 1, 0), pending('A', 'C'), pending('B', 'D', 'in_progress')], { qualification_spots: 2 });
    expect(r.table.every((t) => t.status === 'still_possible')).toBe(true);
    expect(r.qualification_spots).toBe(2);
    expect(r.league_id).toBe('lg-1');
    expect(r.sport_id).toBe('sp');
  });
});
