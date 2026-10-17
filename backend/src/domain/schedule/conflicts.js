// Pure schedule conflict detection (DESIGN.md 8 / spec FR-1.3). Times are epoch milliseconds.
const MIN = 60 * 1000;
const DEFAULT_DURATION_MIN = 120;
const DEFAULT_MIN_REST_MIN = 1440;

const overlaps = (aStart, aEnd, bStart, bEnd) => aStart < bEnd && bStart < aEnd;

// Duration in minutes: the match's own value, else the event-model sport's periods, else 120.
function durationMinutes(match, sportConfig) {
  if (Number.isInteger(match.duration_minutes) && match.duration_minutes > 0) return match.duration_minutes;
  const p = sportConfig && sportConfig.periods;
  if (sportConfig && sportConfig.scoring_model === 'event' && p && Number.isInteger(p.count) && Number.isInteger(p.duration_minutes)) {
    return p.count * p.duration_minutes;
  }
  return DEFAULT_DURATION_MIN;
}

const toMinutes = (hhmm) => {
  const [h, m] = String(hhmm).split(':').map(Number);
  return h * 60 + m;
};

// hours: [{weekday 0-6 (Sunday = 0), opens 'HH:MM', closes 'HH:MM'}]; empty = always available.
function withinOpeningHours(startMs, endMs, hours) {
  if (!hours || hours.length === 0) return true;
  const start = new Date(startMs);
  const end = new Date(endMs);
  const sameDay = start.getUTCFullYear() === end.getUTCFullYear() && start.getUTCMonth() === end.getUTCMonth() && start.getUTCDate() === end.getUTCDate();
  if (!sameDay) return false;
  const from = start.getUTCHours() * 60 + start.getUTCMinutes();
  const to = end.getUTCHours() * 60 + end.getUTCMinutes();
  return hours.some((h) => h.weekday === start.getUTCDay() && toMinutes(h.opens) <= from && to <= toMinutes(h.closes));
}

const unique = (list) => [...new Set(list)];

/**
 * subject: {match_id, home_team_id, away_team_id, start, duration, venue_id}
 * others:  [{match_id, home_team_id, away_team_id, start, duration, venue_id}] (no cancelled ones)
 * playersByTeam: Map(team_id -> Set(player_id)) from squads whose id equals the team id
 * hours: opening hours of the subject's venue; minRest in minutes
 */
function detectConflicts({ subject, others, playersByTeam = new Map(), hours = [], minRest = DEFAULT_MIN_REST_MIN }) {
  const end = subject.start + subject.duration * MIN;
  const teams = [subject.home_team_id, subject.away_team_id];
  const venue = [];
  const team = [];
  const rest = [];
  const player = [];

  for (const o of others) {
    if (o.match_id === subject.match_id) continue;
    const oEnd = o.start + o.duration * MIN;
    const overlap = overlaps(subject.start, end, o.start, oEnd);
    const shared = teams.filter((t) => t === o.home_team_id || t === o.away_team_id);

    if (overlap && subject.venue_id && o.venue_id === subject.venue_id) venue.push(o);
    if (overlap && shared.length) team.push({ o, shared });
    if (!overlap && shared.length) {
      const gap = o.start >= end ? (o.start - end) / MIN : (subject.start - oEnd) / MIN;
      if (gap < minRest) rest.push({ o, shared });
    }
    if (overlap) {
      const mine = new Set(teams.flatMap((t) => [...(playersByTeam.get(t) || [])]));
      const theirs = [o.home_team_id, o.away_team_id].flatMap((t) => [...(playersByTeam.get(t) || [])]);
      const common = unique(theirs.filter((p) => mine.has(p)));
      if (common.length) player.push({ o, common });
    }
  }

  const conflicts = [];
  if (venue.length) {
    conflicts.push({ type: 'venue_double_booking', entity_ids: [subject.venue_id], match_ids: venue.map((o) => o.match_id).sort() });
  }
  if (team.length) {
    conflicts.push({
      type: 'team_overlap',
      entity_ids: unique(team.flatMap((x) => x.shared)).sort(),
      match_ids: unique(team.map((x) => x.o.match_id)).sort(),
    });
  }
  if (player.length) {
    conflicts.push({
      type: 'player_overlap',
      entity_ids: unique(player.flatMap((x) => x.common)).sort(),
      match_ids: unique(player.map((x) => x.o.match_id)).sort(),
    });
  }
  if (rest.length) {
    conflicts.push({
      type: 'min_rest',
      entity_ids: unique(rest.flatMap((x) => x.shared)).sort(),
      match_ids: unique(rest.map((x) => x.o.match_id)).sort(),
    });
  }
  if (subject.venue_id && !withinOpeningHours(subject.start, end, hours)) {
    conflicts.push({ type: 'venue_unavailable', entity_ids: [subject.venue_id], match_ids: [subject.match_id] });
  }
  return conflicts;
}

module.exports = { detectConflicts, durationMinutes, withinOpeningHours, overlaps, DEFAULT_DURATION_MIN, DEFAULT_MIN_REST_MIN };
