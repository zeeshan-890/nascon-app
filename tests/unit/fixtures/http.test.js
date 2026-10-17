const request = require('../../../backend/node_modules/supertest');
const createApp = require('../../../backend/src/app');

const app = createApp();
const mk = (n) => Array.from({ length: n }, (_, i) => `t${i + 1}`);
const post = (body) => request(app).post('/audit/v1/fixtures').send(body);
const keys = (o) => Object.keys(o).sort();

test('round robin response shape', async () => {
  const r = await post({ format: 'round_robin', teams: mk(5), params: { legs: 1, withdrawals: [{ team_id: 't3', after_round: 2 }] } });
  expect(r.status).toBe(200);
  expect(keys(r.body)).toEqual(['format', 'legs', 'round_count', 'rounds', 'summary', 'team_count']);
  expect(keys(r.body.summary)).toEqual(['forfeit', 'scheduled', 'total', 'voided']);
  expect(keys(r.body.rounds[0])).toEqual(['byes', 'matches', 'round']);
  expect(keys(r.body.rounds[0].matches[0])).toEqual(['awarded_to', 'away', 'home', 'leg', 'match_id', 'status']);
  expect(r.body.round_count).toBe(5);
  expect(r.body.summary.forfeit).toBeGreaterThan(0);
});

test('swiss response shape (round 2)', async () => {
  const history = [{ round: 1, bye: null, matches: [{ home: 't1', away: 't3', result: 'home' }, { home: 't2', away: 't4', result: 'draw' }] }];
  const r = await post({ format: 'swiss', teams: mk(4), params: { round: 2, total_rounds: 2, history } });
  expect(r.status).toBe(200);
  expect(keys(r.body)).toEqual(['bye', 'format', 'pairings', 'round', 'standings', 'total_rounds']);
  expect(keys(r.body.pairings[0])).toEqual(['away', 'home', 'match_id']);
  expect(keys(r.body.standings[0])).toEqual(['buchholz', 'rank', 'score', 'team_id']);
  expect(r.body.bye).toBeNull();
  expect(r.body.standings[0]).toEqual({ rank: 1, team_id: 't1', score: 1, buchholz: 0 });
});

test('swiss 409 error body', async () => {
  const side = (round, pairs) => ({ round, bye: null, matches: pairs.map(([home, away]) => ({ home, away, result: 'draw' })) });
  const history = [
    side(1, [['t1', 't4'], ['t2', 't5'], ['t3', 't6']]),
    side(2, [['t1', 't5'], ['t2', 't6'], ['t3', 't4']]),
    side(3, [['t1', 't6'], ['t2', 't4'], ['t3', 't5']]),
  ];
  const r = await post({ format: 'swiss', teams: mk(6), params: { round: 4, total_rounds: 5, history } });
  expect(r.status).toBe(409);
  expect(r.body).toEqual({ error: { code: 'NO_VALID_PAIRING', message: expect.any(String), details: { round: 4 } } });
});

test('single elimination response shape', async () => {
  const r = await post({ format: 'single_elim', teams: mk(6), params: { third_place_match: true } });
  expect(r.status).toBe(200);
  expect(keys(r.body)).toEqual(['bracket_size', 'format', 'rounds']);
  expect(keys(r.body.rounds[0])).toEqual(['matches', 'round']);
  expect(keys(r.body.rounds[0].matches[0])).toEqual(
    ['away', 'away_seed', 'bracket', 'bye', 'home', 'home_seed', 'index', 'loser', 'loser_to', 'match_id', 'round', 'winner', 'winner_to'],
  );
});

test('double elimination response shape', async () => {
  const r = await post({ format: 'double_elim', teams: mk(8), params: { bracket_reset: true } });
  expect(r.status).toBe(200);
  expect(keys(r.body)).toEqual(['bracket_reset', 'bracket_size', 'format', 'grand_final', 'losers', 'winners']);
  expect(r.body.grand_final.map((m) => m.match_id)).toEqual(['GF1', 'GF2']);
  expect(keys(r.body.winners[0].matches[0])).toContain('conditional');
  expect(keys(r.body.winners[0].matches[0])).toContain('required');
  expect(r.body.grand_final[1].required).toBeNull();
});

test('validation error body lists all fields; non-object body is a 400', async () => {
  const r = await post({ format: 'x', teams: 'y', params: 3 });
  expect(r.status).toBe(400);
  expect(r.body.error.code).toBe('VALIDATION_ERROR');
  expect(r.body.error.details.fields.sort()).toEqual(['format', 'params', 'teams']);
  expect((await request(app).post('/audit/v1/fixtures').send('[]').set('Content-Type', 'application/json')).status).toBe(400);
});
