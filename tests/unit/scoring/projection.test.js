'use strict';

const { teamTotals, project } = require('../../../backend/src/domain/scoring/projection');

const match = { match_id: 'm1', sport_id: 'kabaddi', league_id: null, home_team_id: 'H', away_team_id: 'A' };

const eventCfg = (extra = {}) => ({
  sport_id: 'kabaddi', name: 'K', scoring_model: 'event',
  periods: { count: 2, duration_minutes: 20 },
  scoring_events: { raid_point: { points: 1 }, super_raid: { points: 3 } },
  draw_allowed: true, overtime: { policy: 'none' }, ...extra,
});
const inningsCfg = () => ({
  sport_id: 'slog', name: 'S', scoring_model: 'innings',
  periods: { innings_per_team: 1, overs_per_innings: 20, balls_per_over: 6, wickets_per_innings: 10 },
  scoring_events: {
    hit: { runs: 4, legal_ball: true, wicket: false },
    wide: { runs: 1, legal_ball: false, wicket: false },
    out: { runs: 0, legal_ball: true, wicket: true },
  },
  draw_allowed: true, overtime: { policy: 'none' },
});
const roundCfg = () => ({
  sport_id: 'duel', name: 'D', scoring_model: 'round', periods: { best_of: 3 },
  scoring_events: { win: { rounds: 1 } }, draw_allowed: false, overtime: { policy: 'none' },
});

let n = 0;
const ev = (type, payload = {}, extra = {}) => {
  n += 1;
  return { sequence: n, event_id: `e${n}`, type, payload, signature: `sig${n}`, ...extra };
};
const score = (team, event_type) => ev('score', { team_id: team, event_type });
const comp = (target) => ev('compensation', { compensates: target });

beforeEach(() => { n = 0; });

describe('status', () => {
  test('upcoming / in_progress / completed', () => {
    expect(project(eventCfg(), match, [ev('match_init')]).status).toBe('upcoming');
    expect(project(eventCfg(), match, [ev('match_init'), ev('match_start')]).status).toBe('in_progress');
    expect(project(eventCfg(), match, [ev('match_start'), ev('match_end')]).status).toBe('completed');
  });
});

describe('teamTotals', () => {
  test('shape and sums', () => {
    const events = [ev('match_start'), score('H', 'raid_point'), score('H', 'super_raid'), score('A', 'raid_point'),
      ev('discipline', { team_id: 'A', points: 2 })];
    const t = teamTotals(eventCfg(), match, events);
    expect(Object.keys(t).sort()).toEqual(['away', 'compensatedBy', 'home', 'status', 'tiebreakWinnerTeamId']);
    expect(t.home).toEqual({ team_id: 'H', score: 4, wickets: 0, balls: 0, fair_play: 0 });
    expect(t.away).toEqual({ team_id: 'A', score: 1, wickets: 0, balls: 0, fair_play: 2 });
    expect(t.tiebreakWinnerTeamId).toBeNull();
    expect(t.compensatedBy).toBeInstanceOf(Map);
  });

  test('unknown event_type and unknown team skipped', () => {
    const events = [ev('match_start'), score('H', 'mystery'), score('Z', 'raid_point'),
      ev('discipline', { team_id: 'Z', points: 1 }), ev('discipline', { team_id: 'H', points: 'x' }),
      score('H', 'toString')];
    const t = teamTotals(eventCfg(), match, events);
    expect(t.home.score).toBe(0);
    expect(t.away.score).toBe(0);
    expect(t.home.fair_play).toBe(0);
  });

  test('payload_canonical overrides payload', () => {
    const events = [ev('match_start'), ev('score', { team_id: 'A', event_type: 'raid_point' },
      { payload_canonical: JSON.stringify({ team_id: 'H', event_type: 'super_raid' }) })];
    const t = teamTotals(eventCfg(), match, events);
    expect(t.home.score).toBe(3);
    expect(t.away.score).toBe(0);
  });

  test('bad payload_canonical falls back to payload; missing payload -> {}', () => {
    const e1 = ev('score', { team_id: 'H', event_type: 'raid_point' }, { payload_canonical: '{nope' });
    const e2 = { sequence: 9, event_id: 'x', type: 'score', signature: 's' };
    const p = project(eventCfg(), match, [ev('match_start'), e1, e2]);
    expect(p.home.score).toBe(1);
    expect(p.events[1].payload).toEqual({ team_id: 'H', event_type: 'raid_point' });
    expect(p.events[2].payload).toEqual({});
  });

  test('compensates via field or payload fallback', () => {
    const s1 = score('H', 'raid_point');
    const s2 = score('H', 'raid_point');
    const c1 = ev('compensation', {}, { compensates: s1.event_id });
    const c2 = comp(s2.event_id);
    const t = teamTotals(eventCfg(), match, [ev('match_start'), s1, s2, c1, c2]);
    expect(t.home.score).toBe(0);
    expect(t.compensatedBy.get(s1.event_id)).toBe(c1.event_id);
    expect(t.compensatedBy.get(s2.event_id)).toBe(c2.event_id);
  });

  test('compensation without target is ignored', () => {
    const t = teamTotals(eventCfg(), match, [ev('match_start'), score('H', 'raid_point'), ev('compensation', {})]);
    expect(t.home.score).toBe(1);
    expect(t.compensatedBy.size).toBe(0);
  });

  test('tiebreak winner is latest active', () => {
    const t1 = ev('tiebreak_winner', { team_id: 'H' });
    const t2 = ev('tiebreak_winner', { team_id: 'A' });
    const base = [ev('match_start'), t1, t2];
    expect(teamTotals(eventCfg(), match, base).tiebreakWinnerTeamId).toBe('A');
    expect(teamTotals(eventCfg(), match, [...base, comp(t2.event_id)]).tiebreakWinnerTeamId).toBe('H');
    expect(teamTotals(eventCfg(), match, [...base, comp(t2.event_id), comp(t1.event_id)]).tiebreakWinnerTeamId).toBeNull();
  });
  test('tiebreak winner without team_id yields null', () => {
    expect(teamTotals(eventCfg(), match, [ev('tiebreak_winner', {})]).tiebreakWinnerTeamId).toBeNull();
  });
});

describe('project (innings)', () => {
  test('overs 1.1 after 7 legal balls; wide adds run not ball', () => {
    const events = [ev('match_start')];
    for (let i = 0; i < 7; i++) events.push(score('H', 'hit'));
    events.push(score('H', 'wide'));
    const p = project(inningsCfg(), match, events);
    expect(p.home.balls).toBe(7);
    expect(p.home.overs).toBe('1.1');
    expect(p.home.score).toBe(29);
    expect(p.home.wickets).toBe(0);
    expect(p.away.overs).toBe('0.0');
  });

  test('wickets counted', () => {
    const p = project(inningsCfg(), match, [ev('match_start'), score('A', 'out'), score('A', 'out')]);
    expect(p.away.wickets).toBe(2);
    expect(p.away.balls).toBe(2);
  });
});

describe('project (other models give nulls)', () => {
  test.each([['event', eventCfg(), 'raid_point'], ['round', roundCfg(), 'win']])('%s', (_m, cfg, et) => {
    const p = project(cfg, match, [ev('match_start'), score('H', et)]);
    expect(p.home).toEqual({ team_id: 'H', score: 1, wickets: null, balls: null, overs: null, fair_play: 0 });
    expect(p.away.wickets).toBeNull();
    expect(p.away.balls).toBeNull();
    expect(p.away.overs).toBeNull();
  });
});

describe('project shape and outcomes', () => {
  test('exact key order', () => {
    const p = project(eventCfg(), { ...match, league_id: 'L1' }, [ev('match_init')]);
    expect(Object.keys(p)).toEqual([
      'match_id', 'sport_id', 'league_id', 'status', 'outcome', 'winner_team_id', 'tiebreak_winner_team_id',
      'home', 'away', 'version', 'events',
    ]);
    expect(Object.keys(p.home)).toEqual(['team_id', 'score', 'wickets', 'balls', 'overs', 'fair_play']);
    expect(p.league_id).toBe('L1');
    expect(p.version).toBe(1);
    expect(Object.keys(p.events[0])).toEqual([
      'sequence', 'event_id', 'type', 'payload', 'signature', 'compensated', 'compensated_by',
    ]);
  });

  test('league_id null when missing', () => {
    const { league_id, ...m } = match;
    expect(project(eventCfg(), m, []).league_id).toBeNull();
  });

  test('pending', () => {
    const p = project(eventCfg(), match, [ev('match_start'), score('H', 'raid_point')]);
    expect(p.outcome).toBe('pending');
    expect(p.winner_team_id).toBe('H');
  });

  test('home_win, away_win', () => {
    const h = project(eventCfg(), match, [ev('match_start'), score('H', 'raid_point'), ev('match_end')]);
    expect([h.outcome, h.winner_team_id]).toEqual(['home_win', 'H']);
    const a = project(eventCfg(), match, [ev('match_start'), score('A', 'raid_point'), ev('match_end')]);
    expect([a.outcome, a.winner_team_id]).toEqual(['away_win', 'A']);
  });

  test('draw when allowed, undecided when not', () => {
    const evs = [ev('match_start'), score('H', 'raid_point'), score('A', 'raid_point'), ev('match_end')];
    const d = project(eventCfg(), match, evs);
    expect([d.outcome, d.winner_team_id]).toEqual(['draw', null]);
    const u = project(eventCfg({ draw_allowed: false }), match, evs);
    expect(u.outcome).toBe('undecided');
  });

  test('tiebreak decides level game', () => {
    const evs = [ev('match_start'), score('H', 'raid_point'), score('A', 'raid_point'),
      ev('tiebreak_winner', { team_id: 'H' }), ev('tiebreak_winner', { team_id: 'A' }), ev('match_end')];
    const p = project(eventCfg({ draw_allowed: false }), match, evs);
    expect(p.outcome).toBe('away_win');
    expect(p.tiebreak_winner_team_id).toBe('A');
  });

  test('tiebreak ignored if scores differ', () => {
    const evs = [ev('match_start'), score('H', 'raid_point'), ev('tiebreak_winner', { team_id: 'A' }), ev('match_end')];
    const p = project(eventCfg(), match, evs);
    expect(p.winner_team_id).toBe('H');
    expect(p.tiebreak_winner_team_id).toBe('A');
  });

  test('undecided when tiebreak compensated after match_end', () => {
    const tb = ev('tiebreak_winner', { team_id: 'H' });
    const evs = [ev('match_start'), tb, ev('match_end'), comp(tb.event_id)];
    const p = project(eventCfg({ draw_allowed: false }), match, evs);
    expect(p.outcome).toBe('undecided');
    expect(p.tiebreak_winner_team_id).toBeNull();
  });

  test('compensated flags and fair_play restore', () => {
    const s = score('H', 'raid_point');
    const d = ev('discipline', { team_id: 'H', points: 3 });
    const c = comp(d.event_id);
    const p = project(eventCfg(), match, [ev('match_start'), s, d, c]);
    expect(p.home.fair_play).toBe(0);
    const byId = Object.fromEntries(p.events.map((e) => [e.event_id, e]));
    expect(byId[d.event_id]).toMatchObject({ compensated: true, compensated_by: c.event_id });
    expect(byId[s.event_id]).toMatchObject({ compensated: false, compensated_by: null });
    expect(byId[c.event_id]).toMatchObject({ compensated: false, compensated_by: null });
    const p2 = project(eventCfg(), match, [ev('match_start'), d]);
    expect(p2.home.fair_play).toBe(3);
  });

  test('events preserved in order with parsed payload and signature', () => {
    const evs = [ev('match_init', { a: 1 }, { payload_canonical: '{"a":2}' }), ev('match_start')];
    const p = project(eventCfg(), match, evs);
    expect(p.events.map((e) => e.sequence)).toEqual([1, 2]);
    expect(p.events[0]).toMatchObject({ event_id: 'e1', type: 'match_init', payload: { a: 2 }, signature: 'sig1' });
    expect(p.version).toBe(2);
  });
});

describe('performance', () => {
  test('10,000 events under 50 ms', () => {
    const evs = [ev('match_start')];
    for (let i = 0; i < 9998; i++) evs.push(score(i % 2 ? 'H' : 'A', 'raid_point'));
    evs.push(ev('match_end'));
    project(eventCfg(), match, evs); // warm up
    const t0 = process.hrtime.bigint();
    const p = project(eventCfg(), match, evs);
    const ms = Number(process.hrtime.bigint() - t0) / 1e6;
    expect(p.version).toBe(10000);
    expect(ms).toBeLessThan(50);
  });
});
