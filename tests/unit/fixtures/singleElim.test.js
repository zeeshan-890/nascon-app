const { generateFixtures } = require('../../../backend/src/services/fixturesService');
const { seedOrder, nextPow2 } = require('../../../backend/src/domain/fixtures/bracketCommon');

const mk = (n) => Array.from({ length: n }, (_, i) => `t${i + 1}`);
const se = (n, params) => generateFixtures({ format: 'single_elim', teams: mk(n), params });
const flat = (res) => res.rounds.flatMap((r) => r.matches);
const byId = (res) => Object.fromEntries(flat(res).map((m) => [m.match_id, m]));
const failFields = (fn) => {
  try {
    fn();
  } catch (e) {
    expect(e.status).toBe(400);
    return e.details.fields;
  }
  throw new Error('expected 400');
};

const MATCH_KEYS = ['match_id', 'bracket', 'round', 'index', 'home', 'away', 'home_seed', 'away_seed', 'bye', 'winner', 'loser', 'winner_to', 'loser_to'];

test('seed order', () => {
  expect(seedOrder(2)).toEqual([1, 2]);
  expect(seedOrder(4)).toEqual([1, 4, 2, 3]);
  expect(seedOrder(8)).toEqual([1, 8, 4, 5, 2, 7, 3, 6]);
  expect(seedOrder(16)).toEqual([1, 16, 8, 9, 4, 13, 5, 12, 2, 15, 7, 10, 3, 14, 6, 11]);
  expect(nextPow2(1)).toBe(1);
  expect(nextPow2(33)).toBe(64);
});

test('spec example n=6', () => {
  const res = se(6);
  expect(res.bracket_size).toBe(8);
  const m = byId(res);
  expect(m.R1M1).toMatchObject({ home: 't1', away: null, home_seed: 1, away_seed: null, bye: true, winner: 't1', loser: null });
  expect(m.R1M2).toMatchObject({ home: 't4', away: 't5', home_seed: 4, away_seed: 5, bye: false, winner: null });
  expect(m.R1M3).toMatchObject({ home: 't2', away: null, bye: true, winner: 't2' });
  expect(m.R1M4).toMatchObject({ home: 't3', away: 't6', home_seed: 3, away_seed: 6, bye: false });
  expect(m.R2M1).toMatchObject({ home: 't1', home_seed: 1, away: null });
  expect(m.R2M2).toMatchObject({ home: 't2', home_seed: 2, away: null });
  expect(m.R1M1.winner_to).toEqual({ match_id: 'R2M1', slot: 'home' });
  expect(m.R1M2.winner_to).toEqual({ match_id: 'R2M1', slot: 'away' });
  expect(m.R1M3.winner_to).toEqual({ match_id: 'R2M2', slot: 'home' });
  expect(m.R1M4.winner_to).toEqual({ match_id: 'R2M2', slot: 'away' });
  expect(m.R3M1.winner_to).toBeNull();
});

describe('structure for all n', () => {
  const sizes = [...Array.from({ length: 32 }, (_, i) => i + 2), 64, 65, 100, 128];
  test.each(sizes)('n=%i', (n) => {
    const res = se(n);
    const B = nextPow2(n);
    expect(res.format).toBe('single_elim');
    expect(res.bracket_size).toBe(B);
    expect(res.rounds).toHaveLength(Math.log2(B));
    const all = flat(res);
    expect(all).toHaveLength(B - 1);
    const ids = byId(res);
    for (const m of all) {
      expect(Object.keys(m)).toEqual(MATCH_KEYS);
      expect(m.bracket).toBe('main');
      expect(m.match_id).toBe(`R${m.round}M${m.index}`);
      expect(m.loser_to).toBeNull();
      if (m.round === res.rounds.length) {
        expect(m.winner_to).toBeNull();
      } else {
        expect(m.winner_to).toEqual({ match_id: `R${m.round + 1}M${Math.ceil(m.index / 2)}`, slot: m.index % 2 ? 'home' : 'away' });
        expect(ids[m.winner_to.match_id]).toBeDefined();
      }
    }
    res.rounds.forEach((r, i) => expect(r.matches).toHaveLength(B / 2 ** (i + 1)));
    const byes = res.rounds[0].matches.filter((m) => m.bye);
    expect(byes).toHaveLength(B - n);
    for (const m of byes) {
      expect(m.away).toBeNull();
      expect(m.winner).toBe(m.home);
      expect(m.loser).toBeNull();
    }
    const seeded = res.rounds[0].matches.flatMap((m) => [m.home_seed, m.away_seed]).filter(Boolean).sort((a, b) => a - b);
    expect(seeded).toEqual(Array.from({ length: n }, (_, i) => i + 1));
    for (const m of res.rounds[0].matches) {
      if (m.away_seed) expect(m.home_seed).toBeLessThan(m.away_seed);
    }
  });

  test('n=2 single match', () => {
    const res = se(2);
    expect(flat(res)).toHaveLength(1);
    expect(flat(res)[0]).toMatchObject({ match_id: 'R1M1', home: 't1', away: 't2', winner_to: null });
  });
});

describe('third place', () => {
  test.each([[2, false], [3, true], [4, true], [7, true]])('n=%i', (n, has) => {
    const res = se(n, { third_place_match: true });
    const all = flat(res);
    const tp = all.find((m) => m.match_id === 'TP');
    const last = res.rounds[res.rounds.length - 1].matches;
    if (!has) {
      expect(tp).toBeUndefined();
      return;
    }
    expect(tp).toMatchObject({ bracket: 'third_place', round: res.rounds.length, index: 1, winner_to: null, loser_to: null });
    expect(last[last.length - 1].match_id).toBe('TP');
    expect(last[last.length - 2].match_id).toMatch(/^R\d+M1$/);
    const semis = res.rounds[res.rounds.length - 2].matches;
    expect(semis[0].loser_to).toEqual({ match_id: 'TP', slot: 'home' });
    expect(semis[1].loser_to).toEqual({ match_id: 'TP', slot: 'away' });
  });

  test('flag false or absent adds nothing; non-boolean is a 400', () => {
    expect(flat(se(8, { third_place_match: false })).find((m) => m.match_id === 'TP')).toBeUndefined();
    expect(failFields(() => se(8, { third_place_match: 'yes' }))).toEqual(['params.third_place_match']);
  });

  test('semifinal losers fill TP', () => {
    const res = se(4, { third_place_match: true, results: [{ match_id: 'R1M1', winner: 't1' }, { match_id: 'R1M2', winner: 't3' }] });
    const m = byId(res);
    expect(m.TP).toMatchObject({ home: 't4', away: 't2', home_seed: 4, away_seed: 2 });
    expect(m.R2M1).toMatchObject({ home: 't1', away: 't3' });
  });
});

describe('results', () => {
  test('out of order and multi-round advancement', () => {
    const results = [
      { match_id: 'R3M1', winner: 't1' },
      { match_id: 'R2M1', winner: 't1' },
      { match_id: 'R1M1', winner: 't1' },
      { match_id: 'R1M2', winner: 't4' },
      { match_id: 'R2M2', winner: 't2' },
      { match_id: 'R1M4', winner: 't3' },
    ];
    const res = se(6, { results });
    const m = byId(res);
    expect(m.R1M2).toMatchObject({ winner: 't4', loser: 't5' });
    expect(m.R2M1).toMatchObject({ home: 't1', away: 't4', winner: 't1', loser: 't4' });
    expect(m.R2M2).toMatchObject({ home: 't2', away: 't3', winner: 't2', loser: 't3' });
    expect(m.R3M1).toMatchObject({ home: 't1', away: 't2', home_seed: 1, away_seed: 2, winner: 't1', loser: 't2' });
  });

  test('result on a bye match is valid only for the present team', () => {
    expect(() => se(6, { results: [{ match_id: 'R1M1', winner: 't1' }] })).not.toThrow();
    expect(failFields(() => se(6, { results: [{ match_id: 'R1M1', winner: 't2' }] }))).toEqual(['params.results']);
  });

  test.each([
    ['not an array', 'x'],
    ['item not an object', [5]],
    ['missing winner', [{ match_id: 'R1M1' }]],
    ['unknown match', [{ match_id: 'R9M9', winner: 't1' }]],
    ['winner not a participant', [{ match_id: 'R1M2', winner: 't1' }]],
    ['duplicate', [{ match_id: 'R1M2', winner: 't4' }, { match_id: 'R1M2', winner: 't5' }]],
    ['no participants yet', [{ match_id: 'R3M1', winner: 't1' }]],
    ['one participant only', [{ match_id: 'R2M1', winner: 't1' }]],
    ['not a string winner', [{ match_id: 'R1M2', winner: 4 }]],
  ])('400: %s', (_, results) => {
    expect(failFields(() => se(6, { results }))).toEqual(['params.results']);
  });

  test('empty results are fine', () => {
    expect(flat(se(4, { results: [] }))).toHaveLength(3);
  });

  test('n=128 under 200 ms', () => {
    const t = Date.now();
    se(128, { third_place_match: true });
    expect(Date.now() - t).toBeLessThan(200);
  });
});
