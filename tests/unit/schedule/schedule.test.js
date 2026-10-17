const { STATUSES, TRANSITIONS, canTransition, effectOf } = require('../../../backend/src/domain/schedule/statusMachine');
const { detectConflicts, durationMinutes, withinOpeningHours } = require('../../../backend/src/domain/schedule/conflicts');

describe('status machine: full transition table', () => {
  const legal = new Set([
    'upcoming>in_progress', 'upcoming>postponed', 'upcoming>cancelled',
    'postponed>upcoming', 'postponed>cancelled',
    'in_progress>completed', 'in_progress>abandoned', 'in_progress>postponed',
  ]);
  for (const from of STATUSES) {
    for (const to of STATUSES) {
      test(`${from} -> ${to}`, () => {
        expect(canTransition(from, to)).toBe(legal.has(`${from}>${to}`));
      });
    }
  }

  test('terminal states have no exits; unknown states are never legal', () => {
    for (const t of ['completed', 'abandoned', 'cancelled']) expect(TRANSITIONS[t]).toEqual([]);
    expect(canTransition('nope', 'upcoming')).toBe(false);
  });

  test('effects', () => {
    expect(effectOf('upcoming', 'in_progress')).toEqual({ event: 'match_start', admin: undefined });
    expect(effectOf('in_progress', 'completed')).toEqual({ event: 'match_end', admin: undefined });
    expect(effectOf('upcoming', 'postponed')).toEqual({ event: null, admin: 'postponed' });
    expect(effectOf('in_progress', 'abandoned')).toEqual({ event: null, admin: 'abandoned' });
    expect(effectOf('upcoming', 'cancelled')).toEqual({ event: null, admin: 'cancelled' });
    expect(effectOf('postponed', 'upcoming')).toEqual({ event: null, admin: null });
  });
});

const H = 3600 * 1000;
const T0 = Date.UTC(2026, 9, 20, 12, 0, 0); // Tuesday 12:00 UTC
const m = (id, home, away, startOffsetH, over = {}) => ({
  match_id: id, home_team_id: home, away_team_id: away, start: T0 + startOffsetH * H, duration: 120, venue_id: 'v1', ...over,
});
const subject = m('s', 'a', 'b', 0);
const types = (cs) => cs.map((c) => c.type);

describe('conflict detection', () => {
  test('no conflicts', () => {
    expect(detectConflicts({ subject, others: [m('x', 'c', 'd', 48, { venue_id: 'v2' })] })).toEqual([]);
  });

  test('venue double booking only for overlapping time at the same venue', () => {
    const r = detectConflicts({ subject, others: [m('x', 'c', 'd', 1), m('y', 'e', 'f', 1, { venue_id: 'v2' }), m('z', 'g', 'h', 2)] });
    expect(r).toEqual([{ type: 'venue_double_booking', entity_ids: ['v1'], match_ids: ['x'] }]);
  });

  test('team overlap (home or away team busy)', () => {
    const r = detectConflicts({ subject, others: [m('x', 'c', 'a', 1, { venue_id: 'v2' }), m('y', 'b', 'd', 0, { venue_id: 'v3' })] });
    expect(r).toEqual([{ type: 'team_overlap', entity_ids: ['a', 'b'], match_ids: ['x', 'y'] }]);
  });

  test('min rest between matches of the same team', () => {
    const close = m('x', 'a', 'z', 10, { venue_id: 'v2' }); // starts 8h after the end
    expect(detectConflicts({ subject, others: [close] })).toEqual([{ type: 'min_rest', entity_ids: ['a'], match_ids: ['x'] }]);
    expect(detectConflicts({ subject, others: [close], minRest: 60 })).toEqual([]);
    const before = m('y', 'b', 'z', -20, { venue_id: 'v2' }); // ended 18h earlier
    expect(types(detectConflicts({ subject, others: [before] }))).toEqual(['min_rest']);
    expect(detectConflicts({ subject, others: [m('w', 'a', 'z', 40, { venue_id: 'v2' })] })).toEqual([]);
  });

  test('player overlap: a player registered in squads of two overlapping matches', () => {
    const playersByTeam = new Map([['a', new Set(['p1', 'p2'])], ['b', new Set(['p3'])], ['c', new Set(['p2', 'p9'])], ['d', new Set(['p3'])]]);
    const r = detectConflicts({ subject, others: [m('x', 'c', 'd', 0, { venue_id: 'v2' })], playersByTeam });
    expect(r).toEqual([{ type: 'player_overlap', entity_ids: ['p2', 'p3'], match_ids: ['x'] }]);
    expect(detectConflicts({ subject, others: [m('x', 'c', 'd', 30, { venue_id: 'v2' })], playersByTeam })).toEqual([]);
  });

  test('venue unavailable outside opening hours', () => {
    const hours = [{ weekday: 2, opens: '09:00', closes: '18:00' }];
    expect(detectConflicts({ subject, others: [], hours })).toEqual([]);
    const late = { ...subject, start: T0 + 5 * H }; // 17:00-19:00
    expect(detectConflicts({ subject: late, others: [], hours })).toEqual([{ type: 'venue_unavailable', entity_ids: ['v1'], match_ids: ['s'] }]);
    const wrongDay = { ...subject, start: T0 + 24 * H };
    expect(types(detectConflicts({ subject: wrongDay, others: [], hours }))).toEqual(['venue_unavailable']);
    expect(withinOpeningHours(T0, T0 + H, [])).toBe(true);
    expect(withinOpeningHours(T0 + 11.5 * H, T0 + 13 * H, [{ weekday: 2, opens: '00:00', closes: '23:59' }])).toBe(false);
  });

  test('several conflicts at once, ignoring the subject itself', () => {
    const hours = [{ weekday: 5, opens: '09:00', closes: '18:00' }];
    const others = [m('s', 'a', 'b', 0), m('x', 'a', 'c', 1)];
    expect(types(detectConflicts({ subject, others, hours }))).toEqual(['venue_double_booking', 'team_overlap', 'venue_unavailable']);
  });

  test('no venue: venue checks are skipped', () => {
    const noVenue = { ...subject, venue_id: null };
    expect(detectConflicts({ subject: noVenue, others: [m('x', 'c', 'd', 0, { venue_id: null })], hours: [{ weekday: 1, opens: '01:00', closes: '02:00' }] })).toEqual([]);
  });

  test('durations', () => {
    expect(durationMinutes({ duration_minutes: 30 }, null)).toBe(30);
    expect(durationMinutes({}, { scoring_model: 'event', periods: { count: 2, duration_minutes: 45 } })).toBe(90);
    expect(durationMinutes({}, { scoring_model: 'innings', periods: { count: 2, duration_minutes: 45 } })).toBe(120);
    expect(durationMinutes({}, null)).toBe(120);
    expect(durationMinutes({ duration_minutes: 0 }, null)).toBe(120);
  });
});
