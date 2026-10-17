const { generateFixtures } = require('../../../backend/src/services/fixturesService');

const mk = (n) => Array.from({ length: n }, (_, i) => `t${i + 1}`);
const rr = (n, params) => generateFixtures({ format: 'round_robin', teams: mk(n), params });
const fields = (fn) => {
  try {
    fn();
  } catch (e) {
    expect(e.status).toBe(400);
    expect(e.code).toBe('VALIDATION_ERROR');
    return e.details.fields;
  }
  throw new Error('expected a 400');
};

describe('common validation', () => {
  const ok = { format: 'round_robin', teams: ['a', 'b'] };
  test.each([
    [{ ...ok, format: 'x' }, ['format']],
    [{ ...ok, teams: 'a' }, ['teams']],
    [{ ...ok, teams: ['a', 'a'] }, ['teams']],
    [{ ...ok, teams: ['a', 5] }, ['teams']],
    [{ ...ok, teams: ['a', 'b c'] }, ['teams']],
    [{ ...ok, teams: ['a'] }, ['teams']],
    [{ ...ok, teams: ['a', 'x'.repeat(65)] }, ['teams']],
    [{ ...ok, params: [] }, ['params']],
    [{ ...ok, params: 'x' }, ['params']],
    [{ ...ok, teams: ['a', 'b\u0000'] }, ['teams']],
    [{ ...ok, params: { a: 'x\u0000' } }, ['params']],
    [{ format: 'nope', teams: 'x', params: 1 }, ['format', 'teams', 'params']],
    [[], ['body']],
    [null, ['body']],
  ])('%j', (body, expected) => {
    expect(fields(() => generateFixtures(body)).sort()).toEqual([...expected].sort());
  });

  test('size limits per format', () => {
    const t = (f, n) => () => generateFixtures({ format: f, teams: mk(n) });
    expect(fields(t('round_robin', 41))).toEqual(['teams']);
    expect(fields(t('swiss', 65))).toEqual(['teams']);
    expect(fields(t('single_elim', 129))).toEqual(['teams']);
    expect(fields(t('double_elim', 129))).toEqual(['teams']);
    expect(fields(t('double_elim', 2))).toEqual(['teams']);
    expect(fields(t('single_elim', 1))).toEqual(['teams']);
    expect(() => t('round_robin', 40)()).not.toThrow();
    expect(() => t('swiss', 64)()).not.toThrow();
    expect(() => t('single_elim', 128)()).not.toThrow();
    expect(() => t('double_elim', 3)()).not.toThrow();
  });

  test('unknown params keys are ignored', () => {
    expect(() => generateFixtures({ ...ok, params: { whatever: 1 } })).not.toThrow();
  });

  test('non-fixture errors propagate untouched', () => {
    expect(() => generateFixtures({ format: 'round_robin', teams: ['a', 'b'], get params() { throw new TypeError('boom'); } })).toThrow(TypeError);
  });
});

describe('round robin properties n=2..40', () => {
  for (let n = 2; n <= 40; n += 1) {
    for (const legs of [1, 2]) {
      test(`n=${n} legs=${legs}`, () => {
        const res = rr(n, { legs });
        const k = n % 2 ? n : n - 1;
        expect(res.format).toBe('round_robin');
        expect(res.legs).toBe(legs);
        expect(res.team_count).toBe(n);
        expect(res.round_count).toBe(k * legs);
        expect(res.rounds).toHaveLength(k * legs);
        const total = Math.floor(n / 2) * k * legs;
        expect(res.summary).toEqual({ total, scheduled: total, forfeit: 0, voided: 0 });
        const pair = new Map();
        const ordered = new Map();
        const diff = [new Map(), new Map()];
        const byes = [new Map(), new Map()];
        res.rounds.forEach((rd, idx) => {
          expect(rd.round).toBe(idx + 1);
          const leg = idx < k ? 1 : 2;
          expect(rd.matches).toHaveLength(Math.floor(n / 2));
          expect(rd.byes).toHaveLength(n % 2);
          const playing = new Set();
          rd.matches.forEach((m, mi) => {
            expect(m.match_id).toBe(`RR-L${leg}-R${rd.round}-M${mi + 1}`);
            expect(m.leg).toBe(leg);
            expect(m.status).toBe('scheduled');
            expect(m.awarded_to).toBeNull();
            for (const t of [m.home, m.away]) {
              expect(playing.has(t)).toBe(false);
              playing.add(t);
            }
            const key = [m.home, m.away].sort().join('|');
            pair.set(key, (pair.get(key) || 0) + 1);
            const okey = `${m.home}>${m.away}`;
            ordered.set(okey, (ordered.get(okey) || 0) + 1);
            const d = diff[leg - 1];
            d.set(m.home, (d.get(m.home) || 0) + 1);
            d.set(m.away, (d.get(m.away) || 0) - 1);
          });
          rd.byes.forEach((b) => {
            expect(playing.has(b)).toBe(false);
            byes[leg - 1].set(b, (byes[leg - 1].get(b) || 0) + 1);
          });
          expect(playing.size + rd.byes.length).toBe(n);
        });
        expect(pair.size).toBe((n * (n - 1)) / 2);
        for (const c of pair.values()) expect(c).toBe(legs);
        if (legs === 2) {
          expect(ordered.size).toBe(n * (n - 1));
          for (const c of ordered.values()) expect(c).toBe(1);
        }
        for (const d of diff) for (const v of d.values()) expect(Math.abs(v)).toBeLessThanOrEqual(1);
        if (n % 2) {
          for (const b of byes.slice(0, legs)) {
            expect(b.size).toBe(n);
            for (const c of b.values()) expect(c).toBe(1);
          }
        }
      });
    }
  }

  test('n=2 and n=3 shapes, 40 teams x 2 legs fast', () => {
    expect(rr(2).rounds).toHaveLength(1);
    expect(rr(3).rounds.every((r) => r.byes.length === 1)).toBe(true);
    const t = Date.now();
    rr(40, { legs: 2 });
    expect(Date.now() - t).toBeLessThan(200);
  });
});

describe('round robin params and withdrawals', () => {
  test('param validation lists every bad field', () => {
    expect(fields(() => rr(4, { legs: 3 }))).toEqual(['params.legs']);
    expect(fields(() => rr(4, { legs: '1' }))).toEqual(['params.legs']);
    expect(fields(() => rr(4, { legs: 1.5, withdrawal_policy: 'x' })).sort()).toEqual(['params.legs', 'params.withdrawal_policy']);
    expect(fields(() => rr(4, { withdrawals: 'x' }))).toEqual(['params.withdrawals']);
    const w = (x) => () => rr(4, { withdrawals: x });
    expect(fields(w([{ team_id: 'zz', after_round: 1 }]))).toEqual(['params.withdrawals']);
    expect(fields(w([{ team_id: 't1', after_round: -1 }]))).toEqual(['params.withdrawals']);
    expect(fields(w([{ team_id: 't1', after_round: 4 }]))).toEqual(['params.withdrawals']);
    expect(fields(w([{ team_id: 't1', after_round: 1.5 }]))).toEqual(['params.withdrawals']);
    expect(fields(w([{ team_id: 't1', after_round: 1 }, { team_id: 't1', after_round: 2 }]))).toEqual(['params.withdrawals']);
    expect(fields(w([null]))).toEqual(['params.withdrawals']);
    expect(() => w([{ team_id: 't1', after_round: 3 }])()).not.toThrow();
  });

  const all = (res) => res.rounds.flatMap((r) => r.matches.map((m) => ({ ...m, round: r.round })));

  test('after_round 0 forfeits every fixture of the team', () => {
    const res = rr(4, { withdrawals: [{ team_id: 't1', after_round: 0 }] });
    const mine = all(res).filter((m) => m.home === 't1' || m.away === 't1');
    expect(mine).toHaveLength(3);
    for (const m of mine) {
      expect(m.status).toBe('forfeit');
      expect(m.awarded_to).toBe(m.home === 't1' ? m.away : m.home);
    }
    expect(res.summary).toEqual({ total: 6, scheduled: 3, forfeit: 3, voided: 0 });
  });

  test('after_round = round_count has no effect', () => {
    const res = rr(4, { legs: 2, withdrawals: [{ team_id: 't2', after_round: 6 }] });
    expect(res.summary.scheduled).toBe(res.summary.total);
  });

  test('forfeit only after the cut-off round; byes untouched', () => {
    const base = rr(5);
    const res = rr(5, { withdrawals: [{ team_id: 't3', after_round: 2 }] });
    expect(res.rounds.map((r) => r.byes)).toEqual(base.rounds.map((r) => r.byes));
    for (const m of all(res)) {
      const involves = m.home === 't3' || m.away === 't3';
      expect(m.status).toBe(involves && m.round > 2 ? 'forfeit' : 'scheduled');
    }
  });

  test('void_all voids every fixture of withdrawn teams', () => {
    const res = rr(4, { withdrawal_policy: 'void_all', withdrawals: [{ team_id: 't1', after_round: 3 }] });
    for (const m of all(res)) {
      const involves = m.home === 't1' || m.away === 't1';
      expect(m.status).toBe(involves ? 'voided' : 'scheduled');
      expect(m.awarded_to).toBeNull();
    }
    expect(res.summary).toEqual({ total: 6, scheduled: 3, forfeit: 0, voided: 3 });
  });

  test('two withdrawn teams meeting are voided under both policies', () => {
    for (const withdrawal_policy of ['forfeit_remaining', 'void_all']) {
      const res = rr(4, { withdrawal_policy, withdrawals: [{ team_id: 't1', after_round: 0 }, { team_id: 't2', after_round: 0 }] });
      const meet = all(res).find((m) => [m.home, m.away].sort().join() === 't1,t2');
      expect(meet.status).toBe('voided');
      expect(meet.awarded_to).toBeNull();
    }
  });

  test('forfeit with different cut-offs', () => {
    const res = rr(4, { withdrawals: [{ team_id: 't1', after_round: 1 }, { team_id: 't2', after_round: 2 }] });
    const meet = all(res).find((m) => [m.home, m.away].sort().join() === 't1,t2');
    let expected = 'scheduled';
    if (meet.round > 2) expected = 'voided';
    else if (meet.round > 1) expected = 'forfeit';
    expect(meet.status).toBe(expected);
    expect(res.summary.scheduled + res.summary.forfeit + res.summary.voided).toBe(6);
  });
});
