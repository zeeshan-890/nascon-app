'use strict';

const {
  validateSportConfig, normaliseTieBreak, TIE_BREAK_TOKENS, POINT_FIELD,
} = require('../../../backend/src/domain/scoring/sportSchema');

const roster = () => ({ min_squad_size: 5, max_squad_size: 10, required_positions: { raider: 2 } });

const eventBody = () => ({
  sport_id: 'kabaddi',
  name: 'Kabaddi',
  scoring_model: 'event',
  periods: { count: 2, duration_minutes: 20, extra: 1 },
  scoring_events: { raid_point: { points: 1, junk: 1 }, super_raid: { points: 3 } },
  draw_allowed: true,
  overtime: { policy: 'none' },
  roster: roster(),
  unknown_top: true,
});

const inningsBody = () => ({
  sport_id: 'slogball',
  name: 'Slogball',
  scoring_model: 'innings',
  periods: { innings_per_team: 1, overs_per_innings: 2, balls_per_over: 6, wickets_per_innings: 10 },
  scoring_events: {
    hit: { runs: 4, legal_ball: true, wicket: false },
    wide: { runs: 1, legal_ball: false, wicket: false },
    out: { runs: 0, legal_ball: true, wicket: true },
  },
  draw_allowed: true,
  overtime: { policy: 'super_over', duration_minutes: 0 },
  tie_break_order: ['scored', 'fair_play'],
  roster: roster(),
});

const roundBody = () => ({
  sport_id: 'duelgame',
  name: 'Duel',
  scoring_model: 'round',
  periods: { best_of: 3 },
  scoring_events: { round_taken: { rounds: 1 } },
  draw_allowed: false,
  overtime: { policy: 'golden_score', duration_minutes: 5 },
  roster: { min_squad_size: 1, max_squad_size: 5, required_positions: {} },
});

describe('constants', () => {
  test('exports', () => {
    expect(TIE_BREAK_TOKENS).toEqual(['h2h_points', 'h2h_diff', 'overall_diff', 'scored', 'fair_play', 'draw_lots']);
    expect(POINT_FIELD).toEqual({ event: 'points', innings: 'runs', round: 'rounds' });
  });
});

describe('normaliseTieBreak', () => {
  test('undefined -> full list (copy)', () => {
    const r = normaliseTieBreak(undefined);
    expect(r).toEqual(TIE_BREAK_TOKENS);
    expect(r).not.toBe(TIE_BREAK_TOKENS);
  });
  test('appends draw_lots when missing', () => {
    expect(normaliseTieBreak(['scored'])).toEqual(['scored', 'draw_lots']);
  });
  test('keeps draw_lots position when present', () => {
    expect(normaliseTieBreak(['draw_lots', 'scored'])).toEqual(['draw_lots', 'scored']);
  });
});

describe('valid configs', () => {
  test('event model normalised, unknown keys stripped, key order fixed', () => {
    const { fields, config } = validateSportConfig(eventBody());
    expect(fields).toEqual([]);
    expect(Object.keys(config)).toEqual([
      'sport_id', 'name', 'scoring_model', 'periods', 'scoring_events', 'draw_allowed', 'overtime', 'tie_break_order', 'roster',
    ]);
    expect(config.periods).toEqual({ count: 2, duration_minutes: 20 });
    expect(config.scoring_events).toEqual({ raid_point: { points: 1 }, super_raid: { points: 3 } });
    expect(config.overtime).toEqual({ policy: 'none' });
    expect(config.tie_break_order).toEqual(TIE_BREAK_TOKENS);
    expect(config.roster).toEqual({ min_squad_size: 5, max_squad_size: 10, required_positions: { raider: 2 } });
    expect(config.unknown_top).toBeUndefined();
  });

  test('innings model', () => {
    const { fields, config } = validateSportConfig(inningsBody());
    expect(fields).toEqual([]);
    expect(config.periods.balls_per_over).toBe(6);
    expect(config.scoring_events.wide).toEqual({ runs: 1, legal_ball: false, wicket: false });
    expect(config.overtime).toEqual({ policy: 'super_over', duration_minutes: 0 });
    expect(config.tie_break_order).toEqual(['scored', 'fair_play', 'draw_lots']);
  });

  test('round model with best_of 3 and 5', () => {
    expect(validateSportConfig(roundBody()).fields).toEqual([]);
    const b = roundBody();
    b.periods.best_of = 5;
    expect(validateSportConfig(b).config.periods).toEqual({ best_of: 5 });
  });

  test('empty required_positions allowed', () => {
    expect(validateSportConfig(roundBody()).config.roster.required_positions).toEqual({});
  });
});

describe('invalid configs', () => {
  test.each([null, undefined, 'x', 5, [], [1]])('body %p -> body', (b) => {
    expect(validateSportConfig(b)).toEqual({ fields: ['body'], config: null });
  });

  test.each([
    ['Football'], ['x'], ['x'.repeat(41)], ["x'; DROP TABLE sports;--"], [5], [undefined],
  ])('bad sport_id %p', (id) => {
    const b = eventBody();
    b.sport_id = id;
    const r = validateSportConfig(b);
    expect(r.fields).toEqual(['sport_id']);
    expect(r.config).toBeNull();
  });

  test('40-char sport_id ok, 2-char ok', () => {
    const b = eventBody();
    b.sport_id = 'a'.repeat(40);
    expect(validateSportConfig(b).fields).toEqual([]);
    b.sport_id = 'a-';
    expect(validateSportConfig(b).fields).toEqual([]);
  });

  test('name bounds', () => {
    const b = eventBody();
    b.name = '';
    expect(validateSportConfig(b).fields).toEqual(['name']);
    b.name = 'n'.repeat(81);
    expect(validateSportConfig(b).fields).toEqual(['name']);
    b.name = 'n'.repeat(80);
    expect(validateSportConfig(b).fields).toEqual([]);
    b.name = 7;
    expect(validateSportConfig(b).fields).toEqual(['name']);
  });

  test('bad scoring_model skips shape checks', () => {
    const b = eventBody();
    b.scoring_model = 'tennis';
    b.periods = { count: 'x' };
    b.scoring_events = { raid_point: { points: 0 } };
    expect(validateSportConfig(b).fields).toEqual(['scoring_model']);
  });

  test('periods not object', () => {
    const b = eventBody();
    b.periods = [];
    expect(validateSportConfig(b).fields).toEqual(['periods']);
  });

  test('event periods bad values', () => {
    const b = eventBody();
    b.periods = { count: '2', duration_minutes: 1.5 };
    expect(validateSportConfig(b).fields).toEqual(['periods.count', 'periods.duration_minutes']);
    b.periods = { count: 0, duration_minutes: true };
    expect(validateSportConfig(b).fields).toEqual(['periods.count', 'periods.duration_minutes']);
    b.periods = { count: NaN, duration_minutes: 1 };
    expect(validateSportConfig(b).fields).toEqual(['periods.count']);
  });

  test('innings periods', () => {
    const b = inningsBody();
    b.periods = { innings_per_team: 0, overs_per_innings: '2', balls_per_over: 6.5 };
    expect(validateSportConfig(b).fields).toEqual([
      'periods.innings_per_team', 'periods.overs_per_innings', 'periods.balls_per_over', 'periods.wickets_per_innings',
    ]);
  });

  test.each([2, 0, 4, -1, 3.5, '3'])('round best_of %p rejected', (n) => {
    const b = roundBody();
    b.periods.best_of = n;
    expect(validateSportConfig(b).fields).toEqual(['periods.best_of']);
  });

  test('empty / non-object scoring_events', () => {
    const b = eventBody();
    b.scoring_events = {};
    expect(validateSportConfig(b).fields).toEqual(['scoring_events']);
    b.scoring_events = [];
    expect(validateSportConfig(b).fields).toEqual(['scoring_events']);
    delete b.scoring_events;
    expect(validateSportConfig(b).fields).toEqual(['scoring_events']);
  });

  test('bad event key reported once', () => {
    const b = eventBody();
    b.scoring_events = { 'Raid Point': { points: 1 }, 'Other-Key': { points: 1 }, ok_key: { points: 1 } };
    expect(validateSportConfig(b).fields).toEqual(['scoring_events']);
  });

  test('event key too long', () => {
    const b = eventBody();
    b.scoring_events = { ['k'.repeat(41)]: { points: 1 } };
    expect(validateSportConfig(b).fields).toEqual(['scoring_events']);
  });

  test('scoring event value not an object', () => {
    const b = eventBody();
    b.scoring_events = { raid_point: 3, other: null, arr: [] };
    expect(validateSportConfig(b).fields).toEqual([
      'scoring_events.raid_point', 'scoring_events.other', 'scoring_events.arr',
    ]);
  });

  test('points 0 rejected for event model', () => {
    const b = eventBody();
    b.scoring_events = { raid_point: { points: 0 } };
    expect(validateSportConfig(b).fields).toEqual(['scoring_events.raid_point.points']);
  });

  test('innings value missing legal_ball', () => {
    const b = inningsBody();
    b.scoring_events = { hit: { runs: 4, wicket: false } };
    expect(validateSportConfig(b).fields).toEqual(['scoring_events.hit.legal_ball']);
  });

  test('innings bad runs / wicket', () => {
    const b = inningsBody();
    b.scoring_events = { hit: { runs: -1, legal_ball: true, wicket: 'no' } };
    expect(validateSportConfig(b).fields).toEqual(['scoring_events.hit.runs', 'scoring_events.hit.wicket']);
  });

  test('innings runs 0 ok', () => {
    const b = inningsBody();
    b.scoring_events = { dot: { runs: 0, legal_ball: true, wicket: false } };
    expect(validateSportConfig(b).fields).toEqual([]);
  });

  test('round rounds < 1', () => {
    const b = roundBody();
    b.scoring_events = { r: { rounds: 0 } };
    expect(validateSportConfig(b).fields).toEqual(['scoring_events.r.rounds']);
  });

  test('draw_allowed strict boolean', () => {
    const b = eventBody();
    b.draw_allowed = 'true';
    expect(validateSportConfig(b).fields).toEqual(['draw_allowed']);
    b.draw_allowed = 1;
    expect(validateSportConfig(b).fields).toEqual(['draw_allowed']);
  });

  test('overtime', () => {
    const b = eventBody();
    b.overtime = 'none';
    expect(validateSportConfig(b).fields).toEqual(['overtime']);
    b.overtime = { policy: 'penalties' };
    expect(validateSportConfig(b).fields).toEqual(['overtime.policy']);
    b.overtime = { policy: 'extra_time', duration_minutes: -1 };
    expect(validateSportConfig(b).fields).toEqual(['overtime.duration_minutes']);
    b.overtime = { policy: 'extra_time', duration_minutes: '5' };
    expect(validateSportConfig(b).fields).toEqual(['overtime.duration_minutes']);
    b.overtime = { policy: 'extra_time', duration_minutes: undefined };
    const ok = validateSportConfig(b);
    expect(ok.fields).toEqual([]);
    expect(ok.config.overtime).toEqual({ policy: 'extra_time' });
  });

  test('tie_break_order duplicate / unknown / not array', () => {
    const b = eventBody();
    b.tie_break_order = ['scored', 'scored'];
    expect(validateSportConfig(b).fields).toEqual(['tie_break_order']);
    b.tie_break_order = ['coin_flip'];
    expect(validateSportConfig(b).fields).toEqual(['tie_break_order']);
    b.tie_break_order = 'scored';
    expect(validateSportConfig(b).fields).toEqual(['tie_break_order']);
  });

  test('draw_lots appended through validation', () => {
    const b = eventBody();
    b.tie_break_order = ['h2h_points'];
    expect(validateSportConfig(b).config.tie_break_order).toEqual(['h2h_points', 'draw_lots']);
  });

  test('roster min > max', () => {
    const b = eventBody();
    b.roster = { min_squad_size: 10, max_squad_size: 5, required_positions: {} };
    expect(validateSportConfig(b).fields).toEqual(['roster.min_squad_size']);
  });

  test('roster individual fields', () => {
    const b = eventBody();
    b.roster = { min_squad_size: 0, max_squad_size: '5' };
    expect(validateSportConfig(b).fields).toEqual([
      'roster.min_squad_size', 'roster.max_squad_size', 'roster.required_positions',
    ]);
  });

  test('roster not object', () => {
    const b = eventBody();
    b.roster = null;
    expect(validateSportConfig(b).fields).toEqual(['roster']);
  });

  test('required_positions entries', () => {
    const b = eventBody();
    b.roster.required_positions = { raider: -1, '': 1, def: 1.5, ok: 0 };
    expect(validateSportConfig(b).fields).toEqual([
      'roster.required_positions.raider', 'roster.required_positions', 'roster.required_positions.def',
    ]);
    b.roster.required_positions = [];
    expect(validateSportConfig(b).fields).toEqual(['roster.required_positions']);
  });

  test('multiple bad fields listed in discovery order, deduplicated', () => {
    const b = eventBody();
    b.sport_id = 'BAD';
    b.name = '';
    b.periods = { count: 0, duration_minutes: 10 };
    b.scoring_events = { 'Bad Key': { points: 0 }, good: { points: 0 } };
    b.draw_allowed = null;
    b.overtime = { policy: 'x' };
    b.tie_break_order = ['nope'];
    b.roster = { min_squad_size: 9, max_squad_size: 2, required_positions: { a: -1 } };
    const r = validateSportConfig(b);
    expect(r.config).toBeNull();
    expect(r.fields).toEqual([
      'sport_id', 'name', 'periods.count', 'scoring_events',
      'scoring_events.Bad Key.points', 'scoring_events.good.points',
      'draw_allowed', 'overtime.policy', 'tie_break_order',
      'roster.min_squad_size', 'roster.required_positions.a',
    ]);
    expect(new Set(r.fields).size).toBe(r.fields.length);
  });
});
