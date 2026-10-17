const { generateFixtures } = require('../../../backend/src/services/fixturesService');

const mk = (n) => Array.from({ length: n }, (_, i) => `t${i + 1}`);
const sw = (n, params) => generateFixtures({ format: 'swiss', teams: mk(n), params });
const err = (fn) => {
  try {
    fn();
  } catch (e) {
    return e;
  }
  throw new Error('expected an error');
};
const badFields = (fn) => {
  const e = err(fn);
  expect(e.status).toBe(400);
  return e.details.fields;
};

function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

describe('swiss round 1 (exact)', () => {
  test.each([2, 3, 4, 5, 8, 9, 64])('n=%i', (n) => {
    const res = sw(n);
    const m = Math.floor(n / 2);
    expect(res.format).toBe('swiss');
    expect(res.round).toBe(1);
    expect(res.total_rounds).toBe(Math.ceil(Math.log2(n)));
    expect(res.pairings).toHaveLength(m);
    res.pairings.forEach((p, idx) => {
      expect(p).toEqual({ match_id: `SW-R1-M${idx + 1}`, home: `t${idx + 1}`, away: `t${idx + 1 + m}` });
    });
    expect(res.bye).toBe(n % 2 ? `t${n}` : null);
    expect(res.standings.map((s) => s.team_id)).toEqual(mk(n));
    expect(res.standings.every((s, i) => s.rank === i + 1 && s.score === 0 && s.buchholz === 0)).toBe(true);
  });
});

describe('swiss params and history validation', () => {
  test('params', () => {
    expect(badFields(() => sw(4, { total_rounds: 0 }))).toEqual(['params.total_rounds']);
    expect(badFields(() => sw(4, { total_rounds: 4 }))).toEqual(['params.total_rounds']);
    expect(badFields(() => sw(4, { total_rounds: 1.5 }))).toEqual(['params.total_rounds']);
    expect(badFields(() => sw(4, { total_rounds: 2, round: 3 }))).toEqual(['params.round']);
    expect(badFields(() => sw(4, { round: 0 }))).toEqual(['params.round']);
    expect(badFields(() => sw(4, { history: 'x' }))).toEqual(['params.history']);
    expect(() => sw(2, { total_rounds: 1 })).not.toThrow();
  });

  const r1 = { round: 1, bye: null, matches: [{ home: 't1', away: 't3', result: 'home' }, { home: 't2', away: 't4', result: 'draw' }] };
  test.each([
    ['missing round', { round: 3, total_rounds: 3, history: [r1] }],
    ['extra round', { round: 1, history: [r1] }],
    ['out of order', { round: 3, total_rounds: 3, history: [{ ...r1, round: 2 }, { ...r1, round: 1 }] }],
    ['unknown team', { round: 2, history: [{ ...r1, matches: [{ home: 't1', away: 'zz', result: 'home' }] }] }],
    ['unknown bye', { round: 2, history: [{ ...r1, bye: 'zz' }] }],
    ['team twice', { round: 2, history: [{ ...r1, matches: [{ home: 't1', away: 't2', result: 'home' }, { home: 't1', away: 't3', result: 'home' }] }] }],
    ['bye also plays', { round: 2, history: [{ ...r1, bye: 't1' }] }],
    ['home equals away', { round: 2, history: [{ ...r1, matches: [{ home: 't1', away: 't1', result: 'home' }] }] }],
    ['bad result', { round: 2, history: [{ ...r1, matches: [{ home: 't1', away: 't2', result: 'win' }] }] }],
    ['matches not an array', { round: 2, history: [{ round: 1, matches: 5 }] }],
    ['history entry not object', { round: 2, history: [5] }],
  ])('%s', (_, params) => {
    expect(badFields(() => sw(4, params))).toEqual(['params.history']);
  });

  test('absent bye is treated as null', () => {
    const { bye, ...rest } = r1;
    expect(bye).toBeNull();
    expect(() => sw(4, { round: 2, history: [rest] })).not.toThrow();
  });
});

describe('swiss scoring and standings', () => {
  test('exact score and buchholz with draws and byes', () => {
    // 5 teams; t5 had the bye (1 pt). t1 beat t3, t2 drew t4.
    const history = [{
      round: 1,
      bye: 't5',
      matches: [{ home: 't1', away: 't3', result: 'home' }, { home: 't2', away: 't4', result: 'draw' }],
    }];
    const res = sw(5, { round: 2, history });
    const by = Object.fromEntries(res.standings.map((s) => [s.team_id, s]));
    expect(by.t1).toMatchObject({ score: 1, buchholz: 0 });
    expect(by.t5).toMatchObject({ score: 1, buchholz: 0 });
    expect(by.t2).toMatchObject({ score: 0.5, buchholz: 0.5 });
    expect(by.t4).toMatchObject({ score: 0.5, buchholz: 0.5 });
    expect(by.t3).toMatchObject({ score: 0, buchholz: 1 });
    // ordering: score desc, buchholz desc, seed asc
    expect(res.standings.map((s) => s.team_id)).toEqual(['t1', 't5', 't2', 't4', 't3']);
    expect(res.standings.map((s) => s.rank)).toEqual([1, 2, 3, 4, 5]);
  });

  test('buchholz uses current scores of opponents (bye contributes 0)', () => {
    const history = [
      { round: 1, bye: null, matches: [{ home: 't1', away: 't2', result: 'home' }, { home: 't3', away: 't4', result: 'away' }] },
      { round: 2, bye: null, matches: [{ home: 't1', away: 't4', result: 'draw' }, { home: 't2', away: 't3', result: 'home' }] },
    ];
    const res = sw(4, { round: 3, total_rounds: 3, history });
    const by = Object.fromEntries(res.standings.map((s) => [s.team_id, s]));
    // scores: t1 1.5, t2 1, t3 0, t4 1.5
    expect(by.t1.score).toBe(1.5);
    expect(by.t1.buchholz).toBe(2.5); // t2 (1) + t4 (1.5)
    expect(by.t4.buchholz).toBe(1.5); // t3 (0) + t1 (1.5)
    expect(by.t2.buchholz).toBe(1.5); // t1 + t3
    expect(by.t3.buchholz).toBe(2.5); // t4 + t2
    expect(res.standings.map((s) => s.team_id)).toEqual(['t1', 't4', 't2', 't3']);
  });
});

describe('swiss later rounds', () => {
  test('bye goes to the lowest scorer without a bye (ties: largest index)', () => {
    const history = [{ round: 1, bye: 't5', matches: [{ home: 't1', away: 't3', result: 'home' }, { home: 't2', away: 't4', result: 'away' }] }];
    const res = sw(5, { round: 2, history });
    // scores: t1 1, t4 1, t5 1, t2 0, t3 0 -> candidates exclude t5; lowest with largest index: t3
    expect(res.bye).toBe('t3');
    const names = res.pairings.flatMap((p) => [p.home, p.away]).sort();
    expect(names).toEqual(['t1', 't2', 't4', 't5']);
    expect(res.pairings.map((p) => p.match_id)).toEqual(['SW-R2-M1', 'SW-R2-M2']);
  });

  test('falls back to the next bye candidate when the first leaves an unpairable rest', () => {
    const history = [
      { round: 1, bye: 't2', matches: [{ home: 't5', away: 't4', result: 'draw' }, { home: 't3', away: 't1', result: 'draw' }] },
      { round: 2, bye: 't5', matches: [{ home: 't1', away: 't2', result: 'draw' }, { home: 't3', away: 't4', result: 'draw' }] },
      { round: 3, bye: 't1', matches: [{ home: 't2', away: 't3', result: 'draw' }, { home: 't5', away: 't4', result: 'draw' }] },
    ];
    // candidates (no bye yet): t3 and t4, equal score -> t4 first, but removing t4 leaves t1,t2,t3,t5 which cannot be paired.
    const res = sw(5, { round: 4, total_rounds: 4, history });
    expect(res.bye).toBe('t3');
    const pairs = res.pairings.map((p) => [p.home, p.away].sort().join('-')).sort();
    expect(pairs).toEqual(['t1-t4', 't2-t5'].sort());
  });

  test('NO_VALID_PAIRING (even field)', () => {
    // sides {t1,t2,t3} and {t4,t5,t6} have met each other in three rounds: only two triangles remain.
    const side = (round, pairs) => ({ round, bye: null, matches: pairs.map(([home, away]) => ({ home, away, result: 'draw' })) });
    const history = [
      side(1, [['t1', 't4'], ['t2', 't5'], ['t3', 't6']]),
      side(2, [['t1', 't5'], ['t2', 't6'], ['t3', 't4']]),
      side(3, [['t1', 't6'], ['t2', 't4'], ['t3', 't5']]),
    ];
    const e = err(() => sw(6, { round: 4, total_rounds: 5, history }));
    expect(e).toMatchObject({ status: 409, code: 'NO_VALID_PAIRING' });
    expect(e.details).toEqual({ round: 4 });
  });

  test('odd field: only teams without a bye are candidates', () => {
    const history = [{ round: 1, bye: 't3', matches: [{ home: 't1', away: 't2', result: 'draw' }] }];
    expect(sw(3, { round: 2, total_rounds: 2, history }).bye).toBe('t2');
  });

  test('round equal to total_rounds still pairs', () => {
    const r = sw(2, { total_rounds: 1 });
    expect(r.round).toBe(r.total_rounds);
  });
});

// Reference brute force over rematch-free perfect matchings for the given bye choice.
function bruteMin(scores, met, ids) {
  let best = null;
  (function rec(rest, acc) {
    if (!rest.length) {
      if (best === null || acc < best) best = acc;
      return;
    }
    const [a, ...others] = rest;
    others.forEach((b, i) => {
      if (met.has(`${Math.min(a, b)}-${Math.max(a, b)}`)) return;
      rec(others.filter((_, j) => j !== i), acc + Math.abs(scores[a] - scores[b]));
    });
  }(ids, 0));
  return best;
}

describe('swiss random histories', () => {
  test('rematch-free, everybody paired once, minimal difference vs brute force', () => {
    const rand = rng(2024);
    let checked = 0;
    for (let trial = 0; trial < 150; trial += 1) {
      const n = 4 + Math.floor(rand() * 7); // 4..10
      const teams = mk(n);
      const history = [];
      const met = new Set();
      const hadBye = new Set();
      const rounds = 1 + Math.floor(rand() * 3);
      let ok = true;
      for (let r = 1; r <= rounds && ok; r += 1) {
        const req = { format: 'swiss', teams, params: { round: r, total_rounds: Math.min(n - 1, 5), history: history.slice() } };
        let out;
        try {
          out = generateFixtures(req);
        } catch (e) {
          expect(e.code).toBe('NO_VALID_PAIRING');
          ok = false;
          break;
        }
        const seen = new Set();
        for (const p of out.pairings) {
          for (const t of [p.home, p.away]) {
            expect(seen.has(t)).toBe(false);
            seen.add(t);
          }
          const a = teams.indexOf(p.home);
          const b = teams.indexOf(p.away);
          const key = `${Math.min(a, b)}-${Math.max(a, b)}`;
          expect(met.has(key)).toBe(false);
        }
        if (out.bye) {
          expect(seen.has(out.bye)).toBe(false);
          expect(hadBye.has(out.bye)).toBe(false);
          seen.add(out.bye);
        }
        expect(seen.size).toBe(n);
        // optimality against brute force for the bye actually chosen
        const score = teams.map((t) => out.standings.find((s) => s.team_id === t).score);
        const idx = teams.map((_, i) => i).filter((i) => teams[i] !== out.bye);
        const optimum = bruteMin(score, met, idx);
        const got = out.pairings.reduce((s, p) => s + Math.abs(score[teams.indexOf(p.home)] - score[teams.indexOf(p.away)]), 0);
        expect(got).toBe(optimum);
        checked += 1;
        // record the round with random results
        const entry = { round: r, bye: out.bye, matches: [] };
        for (const p of out.pairings) {
          const a = teams.indexOf(p.home);
          const b = teams.indexOf(p.away);
          met.add(`${Math.min(a, b)}-${Math.max(a, b)}`);
          entry.matches.push({ home: p.home, away: p.away, result: ['home', 'away', 'draw'][Math.floor(rand() * 3)] });
        }
        if (out.bye) hadBye.add(out.bye);
        history.push(entry);
      }
    }
    expect(checked).toBeGreaterThan(100);
  });

  test('n=64 plays several rounds quickly', () => {
    const teams = mk(64);
    const rand = rng(7);
    const history = [];
    for (let r = 1; r <= 6; r += 1) {
      const t0 = Date.now();
      const out = generateFixtures({ format: 'swiss', teams, params: { round: r, total_rounds: 6, history: history.slice() } });
      expect(Date.now() - t0).toBeLessThan(200);
      expect(out.pairings).toHaveLength(32);
      history.push({
        round: r,
        bye: out.bye,
        matches: out.pairings.map((p) => ({ home: p.home, away: p.away, result: ['home', 'away', 'draw'][Math.floor(rand() * 3)] })),
      });
    }
  });
});
