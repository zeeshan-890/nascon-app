const { splitGroups, seedFromGroups } = require('../../../backend/src/domain/competition/groups');
const { promotionRelegation } = require('../../../backend/src/domain/competition/promotion');
const { fixtureRows, renameBracket, collectMatches } = require('../../../backend/src/domain/competition/fixtureRows');
const { generateFixtures } = require('../../../backend/src/services/fixturesService');

describe('groups', () => {
  test('teams are dealt round-robin by position', () => {
    expect(splitGroups(['a', 'b', 'c', 'd', 'e'], 2)).toEqual([['a', 'c', 'e'], ['b', 'd']]);
    expect(splitGroups(['a', 'b'], 1)).toEqual([['a', 'b']]);
  });

  const row = (team_id, rank, points, diff = 0, scored = 0) => ({ team_id, rank, points, diff, scored });

  test('seeds: winners first, then runners-up, ordered by points, diff, scored, group', () => {
    const tables = [
      [row('a1', 1, 9, 5, 7), row('a2', 2, 4), row('a3', 3, 0)],
      [row('b1', 1, 9, 6, 7), row('b2', 2, 4), row('b3', 3, 0)],
      [row('c1', 1, 9, 5, 8), row('c2', 2, 6), row('c3', 3, 0)],
      [row('d1', 1, 9, 5, 7), row('d2', 2, 4), row('d3', 3, 0)],
    ];
    const seeds = seedFromGroups(tables, 2);
    expect(seeds.map((s) => s.team_id)).toEqual(['b1', 'c1', 'a1', 'd1', 'c2', 'a2', 'b2', 'd2']);
    expect(seeds[0]).toEqual({ seed: 1, team_id: 'b1', group: 2, rank: 1 });
    expect(seeds.map((s) => s.seed)).toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
  });

  test('a group without that rank is skipped', () => {
    const seeds = seedFromGroups([[row('a', 1, 3)], []], 1);
    expect(seeds).toEqual([{ seed: 1, team_id: 'a', group: 1, rank: 1 }]);
  });
});

describe('promotionRelegation', () => {
  const table = ['a', 'b', 'c', 'd', 'e'].map((team_id, i) => ({ team_id, rank: i + 1 }));
  test('top and bottom places', () => {
    const r = promotionRelegation(table, { promotion: { count: 2, to: 'up' }, relegation: { count: 1, to: 'down' } }, true);
    expect(r.final).toBe(true);
    expect(r.promotion).toEqual({ count: 2, to: 'up', teams: [{ team_id: 'a', rank: 1 }, { team_id: 'b', rank: 2 }] });
    expect(r.relegation).toEqual({ count: 1, to: 'down', teams: [{ team_id: 'e', rank: 5 }] });
  });
  test('not configured, short tables and bad input', () => {
    expect(promotionRelegation(table, {}, false)).toEqual({ final: false, promotion: null, relegation: null });
    expect(promotionRelegation(table.slice(0, 1), { promotion: { count: 3, to: 'u' } }).promotion.teams).toHaveLength(1);
    expect(promotionRelegation(undefined, { relegation: { count: 2, to: 'd' } }).relegation.teams).toEqual([]);
  });
});

describe('fixtureRows', () => {
  const id = (g) => `L:${g}`;
  const teams = ['a', 'b', 'c', 'd', 'e'];

  test('round robin skips byes and voided matches, flags forfeits', () => {
    const plain = fixtureRows('round_robin', generateFixtures({ format: 'round_robin', teams, params: {} }), id);
    expect(plain.rows).toHaveLength(10);
    expect(plain.skipped).toEqual({ byes: 5, voided: 0, pending: 0 });
    expect(plain.rows[0]).toMatchObject({ match_id: 'L:RR-L1-R1-M1', round: 1, bracket_slot: { stage: 'round_robin', leg: 1 } });
    const voided = fixtureRows('round_robin', generateFixtures({
      format: 'round_robin', teams: teams.slice(0, 4), params: { withdrawals: [{ team_id: 'd', after_round: 0 }], withdrawal_policy: 'void_all' },
    }), id);
    expect(voided.skipped.voided).toBe(3);
    expect(voided.rows).toHaveLength(3);
    const forfeit = fixtureRows('round_robin', generateFixtures({
      format: 'round_robin', teams: teams.slice(0, 4), params: { withdrawals: [{ team_id: 'd', after_round: 1 }] },
    }), id);
    expect(forfeit.rows.filter((r) => r.bracket_slot.status === 'forfeit').length).toBeGreaterThan(0);
  });

  test('swiss stores the pairings of the round', () => {
    const r = fixtureRows('swiss', generateFixtures({ format: 'swiss', teams, params: {} }), id);
    expect(r.rows).toHaveLength(2);
    expect(r.skipped.byes).toBe(1);
    expect(fixtureRows('swiss', generateFixtures({ format: 'swiss', teams: teams.slice(0, 4), params: {} }), id).skipped.byes).toBe(0);
  });

  test('brackets store playable matches only; the stage can be overridden', () => {
    const single = generateFixtures({ format: 'single_elim', teams, params: {} });
    const r = fixtureRows('single_elim', single, id, 'knockout');
    expect(r.rows.map((x) => x.match_id)).toEqual(['L:R1M2', 'L:R2M2']); // 4v5, and the two seeds that had byes
    expect(r.rows[0].bracket_slot).toMatchObject({ stage: 'knockout', winner_to: { match_id: 'L:R2M1', slot: 'away' }, loser_to: null });
    expect(r.skipped.byes).toBe(3);
    expect(r.skipped.pending).toBeGreaterThan(0);
    const dbl = fixtureRows('double_elim', generateFixtures({ format: 'double_elim', teams: teams.slice(0, 4), params: {} }), id);
    expect(dbl.rows).toHaveLength(2);
    expect(dbl.rows[0].bracket_slot.stage).toBe('double_elim');
    expect(dbl.rows[0].bracket_slot.loser_to).toEqual({ match_id: 'L:LR1M1', slot: 'home' });
    // a decided match (results supplied) is not stored again
    const decided = generateFixtures({ format: 'single_elim', teams: teams.slice(0, 4), params: { results: [{ match_id: 'R1M1', winner: 'a' }] } });
    expect(fixtureRows('single_elim', decided, id).rows.map((x) => x.match_id)).toEqual(['L:R1M2']);
  });

  test('renameBracket maps ids and routing, and collectMatches finds nested matches', () => {
    const bracket = generateFixtures({ format: 'single_elim', teams: teams.slice(0, 4), params: { third_place_match: true } });
    const renamed = renameBracket(bracket, (x) => `Z-${x}`);
    expect(renamed.bracket_size).toBe(4);
    expect(renamed.rounds[0].matches[0]).toMatchObject({ match_id: 'Z-R1M1', winner_to: { match_id: 'Z-R2M1', slot: 'home' } });
    expect(renamed.rounds[0].matches[0].loser_to).toEqual({ match_id: 'Z-TP', slot: 'home' });
    expect(renamed.rounds[1].matches.find((m) => m.match_id === 'Z-R2M1').winner_to).toBeNull();
    expect(collectMatches(bracket)).toHaveLength(4);
    expect(bracket.rounds[0].matches[0].match_id).toBe('R1M1'); // the input is not modified
  });
});
