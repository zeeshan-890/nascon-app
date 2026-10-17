'use strict';

const { validateAppend } = require('../../../backend/src/domain/scoring/eventRules');

const match = { match_id: 'm1', sport_id: 'kabaddi', league_id: null, home_team_id: 'H', away_team_id: 'A' };

const eventCfg = (extra = {}) => ({
  sport_id: 'kabaddi', name: 'K', scoring_model: 'event',
  periods: { count: 2, duration_minutes: 20 },
  scoring_events: { raid_point: { points: 1 } },
  draw_allowed: true, overtime: { policy: 'none' }, ...extra,
});
const inningsCfg = (over = 20) => ({
  sport_id: 'slog', name: 'S', scoring_model: 'innings',
  periods: { innings_per_team: 1, overs_per_innings: over, balls_per_over: 6, wickets_per_innings: 10 },
  scoring_events: {
    hit: { runs: 4, legal_ball: true, wicket: false },
    wide: { runs: 1, legal_ball: false, wicket: false },
    out: { runs: 0, legal_ball: true, wicket: true },
  },
  draw_allowed: true, overtime: { policy: 'none' },
});
const roundCfg = (best_of) => ({
  sport_id: 'duel', name: 'D', scoring_model: 'round', periods: { best_of },
  scoring_events: { win: { rounds: 1 } }, draw_allowed: false, overtime: { policy: 'none' },
});

let n = 0;
const ev = (type, payload = {}, extra = {}) => {
  n += 1;
  return { sequence: n, event_id: `e${n}`, type, payload, signature: 's', ...extra };
};
const score = (team, event_type) => ev('score', { team_id: team, event_type });
const comp = (target) => ev('compensation', { compensates: target });
const req = (type, payload) => ({ type, payload });
const scoreReq = (team, event_type) => req('score', { team_id: team, event_type });

beforeEach(() => { n = 0; });

describe('payload validation (400)', () => {
  test.each(['score', 'discipline', 'tiebreak_winner'])('%s with foreign team', (type) => {
    const r = validateAppend(eventCfg(), match, [ev('match_start')], req(type, { team_id: 'Z', event_type: 'raid_point' }));
    expect(r).toMatchObject({ ok: false, status: 400, code: 'VALIDATION_ERROR', details: { fields: ['payload.team_id'] } });
    expect(typeof r.message).toBe('string');
  });

  test('missing payload', () => {
    expect(validateAppend(eventCfg(), match, [ev('match_start')], { type: 'discipline' }).code).toBe('VALIDATION_ERROR');
  });

  test('400 checks precede state checks', () => {
    const r = validateAppend(eventCfg(), match, [], scoreReq('Z', 'raid_point'));
    expect(r.status).toBe(400);
  });

  test('unknown event type', () => {
    const r = validateAppend(eventCfg(), match, [ev('match_start')], scoreReq('H', 'nope'));
    expect(r).toMatchObject({ ok: false, status: 400, code: 'UNKNOWN_EVENT_TYPE' });
    expect(validateAppend(eventCfg(), match, [ev('match_start')], req('score', { team_id: 'H' })).code).toBe('UNKNOWN_EVENT_TYPE');
    expect(validateAppend(eventCfg(), match, [ev('match_start')], scoreReq('H', 'toString')).code).toBe('UNKNOWN_EVENT_TYPE');
  });
});

describe('state machine', () => {
  test('match_init always ok', () => {
    expect(validateAppend(eventCfg(), match, [], req('match_init', {}))).toEqual({ ok: true });
  });

  test('match_start', () => {
    expect(validateAppend(eventCfg(), match, [ev('match_init')], req('match_start', {}))).toEqual({ ok: true });
    expect(validateAppend(eventCfg(), match, [ev('match_start')], req('match_start', {}))).toMatchObject({ status: 409, code: 'ILLEGAL_TRANSITION' });
    expect(validateAppend(eventCfg(), match, [ev('match_start'), ev('match_end')], req('match_start', {})).code).toBe('ILLEGAL_TRANSITION');
  });

  test.each(['upcoming', 'completed'])('score/discipline/tiebreak while %s', (st) => {
    const events = st === 'upcoming' ? [ev('match_init')] : [ev('match_start'), ev('match_end')];
    for (const r of [scoreReq('H', 'raid_point'), req('discipline', { team_id: 'H', points: 1 }), req('tiebreak_winner', { team_id: 'H' })]) {
      expect(validateAppend(eventCfg(), match, events, r)).toMatchObject({ ok: false, status: 409, code: 'MATCH_NOT_IN_PROGRESS' });
    }
  });

  test('in-progress accepts score, discipline, tiebreak', () => {
    const events = [ev('match_start')];
    expect(validateAppend(eventCfg(), match, events, scoreReq('H', 'raid_point'))).toEqual({ ok: true });
    expect(validateAppend(eventCfg(), match, events, req('discipline', { team_id: 'A', points: 1 }))).toEqual({ ok: true });
    expect(validateAppend(eventCfg(), match, events, req('tiebreak_winner', { team_id: 'A' }))).toEqual({ ok: true });
  });

  test('match_end requires in_progress', () => {
    expect(validateAppend(eventCfg(), match, [ev('match_init')], req('match_end', {}))).toMatchObject({ status: 409, code: 'ILLEGAL_TRANSITION' });
    expect(validateAppend(eventCfg(), match, [ev('match_start'), ev('match_end')], req('match_end', {})).code).toBe('ILLEGAL_TRANSITION');
    expect(validateAppend(eventCfg(), match, [ev('match_start')], req('match_end', {}))).toEqual({ ok: true });
  });

  test('TIE_NOT_ALLOWED', () => {
    const cfg = eventCfg({ draw_allowed: false });
    const events = [ev('match_start')];
    expect(validateAppend(cfg, match, events, req('match_end', {}))).toMatchObject({ status: 409, code: 'TIE_NOT_ALLOWED' });
    const tb = ev('tiebreak_winner', { team_id: 'H' });
    expect(validateAppend(cfg, match, [...events, tb], req('match_end', {}))).toEqual({ ok: true });
    expect(validateAppend(cfg, match, [...events, tb, comp(tb.event_id)], req('match_end', {})).code).toBe('TIE_NOT_ALLOWED');
    expect(validateAppend(cfg, match, [...events, score('H', 'raid_point')], req('match_end', {}))).toEqual({ ok: true });
    expect(validateAppend(eventCfg(), match, events, req('match_end', {}))).toEqual({ ok: true });
  });
});

describe('compensation', () => {
  test('valid on score, discipline, tiebreak_winner', () => {
    const s = score('H', 'raid_point');
    const d = ev('discipline', { team_id: 'H', points: 1 });
    const t = ev('tiebreak_winner', { team_id: 'H' });
    const events = [ev('match_start'), s, d, t];
    for (const x of [s, d, t]) {
      expect(validateAppend(eventCfg(), match, events, req('compensation', { compensates: x.event_id }))).toEqual({ ok: true });
    }
  });

  test('invalid targets', () => {
    const start = ev('match_start');
    const s = score('H', 'raid_point');
    const c = comp(s.event_id);
    const events = [start, s, c];
    const bad = (id) => validateAppend(eventCfg(), match, events, req('compensation', { compensates: id }));
    expect(bad(start.event_id)).toMatchObject({ ok: false, status: 409, code: 'INVALID_COMPENSATION' });
    expect(bad(c.event_id).code).toBe('INVALID_COMPENSATION');
    expect(bad(s.event_id).code).toBe('INVALID_COMPENSATION'); // already compensated
    expect(bad('missing').code).toBe('INVALID_COMPENSATION');
    expect(validateAppend(eventCfg(), match, events, req('compensation', {})).code).toBe('INVALID_COMPENSATION');
    expect(validateAppend(eventCfg(), match, events, { type: 'compensation' }).code).toBe('INVALID_COMPENSATION');
  });

  test('allowed on completed match', () => {
    const s = score('H', 'raid_point');
    const events = [ev('match_start'), s, ev('match_end')];
    expect(validateAppend(eventCfg(), match, events, req('compensation', { compensates: s.event_id }))).toEqual({ ok: true });
  });

  test('allowed on upcoming match with targetable event', () => {
    const d = ev('discipline', { team_id: 'H', points: 1 });
    expect(validateAppend(eventCfg(), match, [d], req('compensation', { compensates: d.event_id }))).toEqual({ ok: true });
  });
});

describe('innings limits', () => {
  test('10 wickets -> INNINGS_COMPLETE; compensating one re-allows', () => {
    const events = [ev('match_start')];
    const outs = [];
    for (let i = 0; i < 10; i++) { const o = score('H', 'out'); outs.push(o); events.push(o); }
    const r = validateAppend(inningsCfg(), match, events, scoreReq('H', 'hit'));
    expect(r).toMatchObject({ ok: false, status: 409, code: 'INNINGS_COMPLETE' });
    expect(validateAppend(inningsCfg(), match, events, scoreReq('A', 'hit'))).toEqual({ ok: true });
    events.push(comp(outs[0].event_id));
    expect(validateAppend(inningsCfg(), match, events, scoreReq('H', 'hit'))).toEqual({ ok: true });
  });

  test('2 overs x 6 balls: 13th legal ball blocked; wides do not count', () => {
    const cfg = inningsCfg(2);
    const events = [ev('match_start')];
    for (let i = 0; i < 11; i++) events.push(score('H', 'hit'));
    events.push(score('H', 'wide'));
    expect(validateAppend(cfg, match, events, scoreReq('H', 'hit'))).toEqual({ ok: true });
    events.push(score('H', 'hit'));
    expect(validateAppend(cfg, match, events, scoreReq('H', 'hit')).code).toBe('INNINGS_COMPLETE');
    expect(validateAppend(cfg, match, events, scoreReq('H', 'wide')).code).toBe('INNINGS_COMPLETE');
  });
});

describe('round limits', () => {
  test('best_of 3 decided after 2', () => {
    const events = [ev('match_start'), score('H', 'win')];
    expect(validateAppend(roundCfg(3), match, events, scoreReq('A', 'win'))).toEqual({ ok: true });
    events.push(score('H', 'win'));
    expect(validateAppend(roundCfg(3), match, events, scoreReq('A', 'win'))).toMatchObject({ status: 409, code: 'SERIES_ALREADY_DECIDED' });
    expect(validateAppend(roundCfg(3), match, events, scoreReq('H', 'win')).code).toBe('SERIES_ALREADY_DECIDED');
  });

  test('best_of 5 needs 3 (either team)', () => {
    const events = [ev('match_start'), score('A', 'win'), score('A', 'win')];
    expect(validateAppend(roundCfg(5), match, events, scoreReq('H', 'win'))).toEqual({ ok: true });
    events.push(score('A', 'win'));
    expect(validateAppend(roundCfg(5), match, events, scoreReq('H', 'win')).code).toBe('SERIES_ALREADY_DECIDED');
  });

  test('compensating a round re-opens the series', () => {
    const w1 = score('H', 'win');
    const w2 = score('H', 'win');
    const events = [ev('match_start'), w1, w2, comp(w2.event_id)];
    expect(validateAppend(roundCfg(3), match, events, scoreReq('A', 'win'))).toEqual({ ok: true });
  });
});

describe('misc', () => {
  test('details default to empty object', () => {
    const r = validateAppend(eventCfg(), match, [], scoreReq('H', 'raid_point'));
    expect(r.ok).toBe(false);
    expect(r.details).toEqual({ status: 'upcoming' });
    const r2 = validateAppend(eventCfg({ draw_allowed: false }), match, [ev('match_start')], req('match_end', {}));
    expect(r2.details).toEqual({});
  });

  test('unknown request type with no rules is ok', () => {
    expect(validateAppend(eventCfg(), match, [], { type: 'whatever' })).toEqual({ ok: true });
    expect(validateAppend(eventCfg(), match, [], undefined)).toEqual({ ok: true });
  });

  test('event-model score has no limits', () => {
    const events = [ev('match_start')];
    for (let i = 0; i < 200; i++) events.push(score('H', 'raid_point'));
    expect(validateAppend(eventCfg(), match, events, scoreReq('H', 'raid_point'))).toEqual({ ok: true });
  });
});
