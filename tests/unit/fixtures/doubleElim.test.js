const { generateFixtures } = require('../../../backend/src/services/fixturesService');
const { nextPow2 } = require('../../../backend/src/domain/fixtures/bracketCommon');

const mk = (n) => Array.from({ length: n }, (_, i) => `t${i + 1}`);
const de = (n, params) => generateFixtures({ format: 'double_elim', teams: mk(n), params });
const all = (res) => [
  ...res.winners.flatMap((r) => r.matches),
  ...res.losers.flatMap((r) => r.matches),
  ...res.grand_final,
];
const byId = (res) => Object.fromEntries(all(res).map((m) => [m.match_id, m]));
const failFields = (fn) => {
  try {
    fn();
  } catch (e) {
    expect(e.status).toBe(400);
    return e.details.fields;
  }
  throw new Error('expected 400');
};

/** Reusable structural checker for a double-elimination response. */
function checkGraph(res) {
  const matches = all(res);
  const ids = new Set(matches.map((m) => m.match_id));
  const feeders = new Map();
  const feed = (target, source) => {
    const key = `${target.match_id}:${target.slot}`;
    feeders.set(key, [...(feeders.get(key) || []), source]);
  };
  const k = Math.log2(res.bracket_size);
  for (const m of matches) {
    expect(m.conditional).toBe(m.match_id === 'GF2');
    if (m.winner_to) {
      expect(ids.has(m.winner_to.match_id)).toBe(true);
      feed(m.winner_to, `${m.match_id}:W`);
    }
    if (m.loser_to) {
      expect(ids.has(m.loser_to.match_id)).toBe(true);
      feed(m.loser_to, `${m.match_id}:L`);
    }
    if (m.bracket === 'winners') expect(m.loser_to).not.toBeNull();
    if (m.bracket === 'losers') {
      expect(m.loser_to).toBeNull();
      expect(m.winner_to).not.toBeNull();
    }
    if (m.bracket === 'winners') expect(m.winner_to).not.toBeNull();
  }
  const wf = res.winners[k - 1].matches[0];
  const lf = res.losers[2 * (k - 1) - 1].matches[0];
  expect(wf.winner_to).toEqual({ match_id: 'GF1', slot: 'home' });
  expect(wf.loser_to).toEqual({ match_id: lf.match_id, slot: 'away' });
  expect(lf.winner_to).toEqual({ match_id: 'GF1', slot: 'away' });
  const gf1 = res.grand_final[0];
  if (res.bracket_reset) {
    expect(res.grand_final).toHaveLength(2);
    expect(gf1.winner_to).toEqual({ match_id: 'GF2', slot: 'home' });
    expect(gf1.loser_to).toEqual({ match_id: 'GF2', slot: 'away' });
    expect(res.grand_final[1]).toMatchObject({ conditional: true, winner_to: null, loser_to: null });
  } else {
    expect(res.grand_final).toHaveLength(1);
    expect(gf1.winner_to).toBeNull();
  }
  // every slot except WR1 is fed by exactly one source; WR1 slots are fed by none
  for (const m of matches) {
    for (const slot of ['home', 'away']) {
      const f = feeders.get(`${m.match_id}:${slot}`) || [];
      if (m.match_id.startsWith('WR1M')) expect(f).toHaveLength(0);
      else expect(f).toHaveLength(1);
    }
  }
}

describe('structure', () => {
  const sizes = [...Array.from({ length: 15 }, (_, i) => i + 3), 33, 64, 100, 128];
  test.each(sizes)('n=%i', (n) => {
    for (const bracket_reset of [false, true]) {
      const res = de(n, { bracket_reset });
      const B = nextPow2(n);
      const k = Math.log2(B);
      expect(res).toMatchObject({ format: 'double_elim', bracket_size: B, bracket_reset });
      expect(res.winners).toHaveLength(k);
      res.winners.forEach((r, i) => {
        expect(r.round).toBe(i + 1);
        expect(r.matches).toHaveLength(B / 2 ** (i + 1));
        r.matches.forEach((m, mi) => {
          expect(m.match_id).toBe(`WR${i + 1}M${mi + 1}`);
          expect(m.bracket).toBe('winners');
        });
      });
      expect(res.losers).toHaveLength(2 * (k - 1));
      res.losers.forEach((r, i) => {
        const j = Math.ceil((i + 1) / 2);
        expect(r.round).toBe(i + 1);
        expect(r.matches).toHaveLength(B / 2 ** (j + 1));
        r.matches.forEach((m, mi) => {
          expect(m.match_id).toBe(`LR${i + 1}M${mi + 1}`);
          expect(m.bracket).toBe('losers');
        });
      });
      expect(res.losers.flatMap((r) => r.matches)).toHaveLength(B - 2);
      expect(res.grand_final[0]).toMatchObject({ match_id: 'GF1', bracket: 'grand_final' });
      checkGraph(res);
    }
  });

  test('WR1 byes are identical to single elimination', () => {
    for (const n of [5, 6, 9, 17]) {
      const d = de(n).winners[0].matches;
      const s = generateFixtures({ format: 'single_elim', teams: mk(n) }).rounds[0].matches;
      d.forEach((m, i) => {
        for (const key of ['home', 'away', 'home_seed', 'away_seed', 'bye', 'winner', 'loser']) expect(m[key]).toEqual(s[i][key]);
      });
    }
  });

  test('routing for n=8', () => {
    const m = byId(de(8));
    expect(m.WR1M3.loser_to).toEqual({ match_id: 'LR1M2', slot: 'home' });
    expect(m.WR1M4.loser_to).toEqual({ match_id: 'LR1M2', slot: 'away' });
    expect(m.WR2M2.loser_to).toEqual({ match_id: 'LR2M2', slot: 'away' });
    expect(m.WR3M1.loser_to).toEqual({ match_id: 'LR4M1', slot: 'away' });
    expect(m.LR1M2.winner_to).toEqual({ match_id: 'LR2M2', slot: 'home' });
    expect(m.LR2M1.winner_to).toEqual({ match_id: 'LR3M1', slot: 'home' });
    expect(m.LR2M2.winner_to).toEqual({ match_id: 'LR3M1', slot: 'away' });
    expect(m.LR3M1.winner_to).toEqual({ match_id: 'LR4M1', slot: 'home' });
    expect(m.LR4M1.winner_to).toEqual({ match_id: 'GF1', slot: 'away' });
  });

  test('every match carries conditional and required', () => {
    for (const m of all(de(8, { bracket_reset: true }))) {
      expect(typeof m.conditional).toBe('boolean');
      expect(m).toHaveProperty('required');
    }
    expect(all(de(8)).every((m) => m.required === true && m.conditional === false)).toBe(true);
  });
});

describe('dead slots', () => {
  test('n=5: LR1M2 is dead and LR2M2 advances its arrival without playing', () => {
    const res = de(5);
    const m = byId(res);
    expect(m.LR1M1).toMatchObject({ home: null, away: null, bye: false });
    expect(m.LR1M2).toMatchObject({ home: null, away: null, winner: null });
    // WR1M2 (t4 v t5) is the only real first-round game; give WR2 results so the loser arrives
    const played = de(5, {
      results: [
        { match_id: 'WR1M2', winner: 't4' },
        { match_id: 'WR2M2', winner: 't2' },
      ],
    });
    const p = byId(played);
    expect(p.WR2M2).toMatchObject({ home: 't2', away: 't3' });
    expect(p.LR1M1).toMatchObject({ away: 't5', bye: true, winner: 't5' });
    expect(p.LR2M1).toMatchObject({ home: 't5' });
    expect(p.LR2M2).toMatchObject({ away: 't3', bye: true, winner: 't3', loser: null });
    expect(p.LR3M1).toMatchObject({ away: 't3' });
  });

  test.each([5, 6, 9, 3])('n=%i: nothing deadlocks; all results can be played out', (n) => {
    // play every match (winner = home when known) repeatedly until bracket is complete
    let results = [];
    for (let guard = 0; guard < 200; guard += 1) {
      const res = de(n, { bracket_reset: true, results });
      const playable = all(res).filter((m) => m.winner === null && m.home && m.away && m.required !== false);
      if (!playable.length) {
        const gf = res.grand_final;
        expect(gf[0].winner).not.toBeNull();
        for (const m of all(res)) {
          if (m.match_id === 'GF2' && m.required === false) continue;
          if (m.bye && m.home === null && m.away === null) continue; // dead match
          expect(m.winner).not.toBeNull();
        }
        return;
      }
      results = [...results, { match_id: playable[0].match_id, winner: playable[0].home }];
    }
    throw new Error('did not finish');
  });
});

describe('grand final reset', () => {
  const play = (n, reset, winnerSide) => {
    // winners bracket always won by the home team; losers bracket always by the home team; GF1 by side
    let results = [];
    for (let guard = 0; guard < 300; guard += 1) {
      const res = de(n, { bracket_reset: reset, results });
      const playable = all(res).filter((m) => m.winner === null && m.home && m.away && m.match_id !== 'GF1' && m.match_id !== 'GF2');
      if (playable.length) {
        results = [...results, { match_id: playable[0].match_id, winner: playable[0].home }];
      } else {
        const gf1 = res.grand_final[0];
        return { res, results, gf1 };
      }
    }
    throw new Error('did not finish');
  };

  test('before GF1 result: required null; no reset: no GF2', () => {
    const { res } = play(8, true);
    expect(res.grand_final[1]).toMatchObject({ conditional: true, required: null });
    expect(res.grand_final[1].home).toBe(res.grand_final[0].home);
    expect(res.grand_final[1].away).toBe(res.grand_final[0].away);
    expect(play(8, false).res.grand_final).toHaveLength(1);
  });

  test('winners champion wins GF1 -> required false', () => {
    const { results, gf1 } = play(8, true);
    const res = de(8, { bracket_reset: true, results: [...results, { match_id: 'GF1', winner: gf1.home }] });
    expect(res.grand_final[1].required).toBe(false);
    expect(failFields(() => de(8, { bracket_reset: true, results: [...results, { match_id: 'GF1', winner: gf1.home }, { match_id: 'GF2', winner: gf1.home }] }))).toEqual(['params.results']);
  });

  test('losers champion wins GF1 -> required true and GF2 playable', () => {
    const { results, gf1 } = play(8, true);
    const withGf1 = [...results, { match_id: 'GF1', winner: gf1.away }];
    const res = de(8, { bracket_reset: true, results: withGf1 });
    expect(res.grand_final[1].required).toBe(true);
    expect(res.grand_final[1]).toMatchObject({ home: gf1.home, away: gf1.away });
    const done = de(8, { bracket_reset: true, results: [...withGf1, { match_id: 'GF2', winner: gf1.home }] });
    expect(done.grand_final[1]).toMatchObject({ winner: gf1.home, loser: gf1.away });
  });

  test('GF2 result before GF1 is a 400; GF1 result alone without reset is terminal', () => {
    const { results, gf1 } = play(8, true);
    expect(failFields(() => de(8, { bracket_reset: true, results: [...results, { match_id: 'GF2', winner: gf1.home }] }))).toEqual(['params.results']);
    const r = play(8, false);
    const fin = de(8, { results: [...r.results, { match_id: 'GF1', winner: r.gf1.away }] });
    expect(fin.grand_final).toHaveLength(1);
    expect(fin.grand_final[0].winner).toBe(r.gf1.away);
  });

  test('GF2 as result when no reset exists is unknown', () => {
    expect(failFields(() => de(8, { results: [{ match_id: 'GF2', winner: 't1' }] }))).toEqual(['params.results']);
  });

  test('results validation and ordering', () => {
    expect(failFields(() => de(8, { bracket_reset: 'yes' }))).toEqual(['params.bracket_reset']);
    expect(failFields(() => de(8, { results: [{ match_id: 'WR1M1', winner: 't9' }] }))).toEqual(['params.results']);
    expect(failFields(() => de(8, { results: [{ match_id: 'LR1M1', winner: 't1' }] }))).toEqual(['params.results']);
    const shuffled = [
      { match_id: 'LR1M1', winner: 't8' },
      { match_id: 'WR1M1', winner: 't1' },
      { match_id: 'WR1M2', winner: 't4' },
    ];
    const res = de(8, { results: shuffled });
    const m = byId(res);
    expect(m.LR1M1).toMatchObject({ home: 't8', away: 't5', winner: 't8' });
    expect(m.LR2M1).toMatchObject({ home: 't8' });
  });

  test('n=128 with reset under 200 ms', () => {
    const t = Date.now();
    de(128, { bracket_reset: true });
    expect(Date.now() - t).toBeLessThan(200);
  });
});
