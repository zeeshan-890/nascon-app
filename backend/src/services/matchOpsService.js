const crypto = require('crypto');
const pool = require('../db/pool');
const { withTransaction } = require('../db/tx');
const AppError = require('../http/AppError');
const matches = require('../repos/matches');
const outbox = require('../repos/outbox');
const sports = require('../repos/sports');
const eventService = require('./eventService');
const scoreService = require('./scoreService');
const { effectiveStatus } = require('./readModelService');
const { canTransition, effectOf } = require('../domain/schedule/statusMachine');
const { detectConflicts, durationMinutes, DEFAULT_MIN_REST_MIN } = require('../domain/schedule/conflicts');

const MIN = 60 * 1000;
const INACTIVE = ['postponed', 'abandoned', 'cancelled'];

const toIso = (d) => new Date(d).toISOString().replace(/\.\d{3}Z$/, 'Z');

async function changeStatus(matchId, to, actor) {
  const projected = (await scoreService.getScore(matchId)).status;
  const match = await matches.get(pool, matchId);
  const from = effectiveStatus(match.admin_status, projected);
  if (!canTransition(from, to)) {
    throw new AppError(409, 'ILLEGAL_TRANSITION', `Cannot move match from ${from} to ${to}`, { from, to });
  }
  const effect = effectOf(from, to);
  if (effect.event) {
    // The event service enqueues match.status_changed and invalidates the caches itself.
    await eventService.appendEvent({ event_id: `adm-${crypto.randomUUID()}`, match_id: matchId, type: effect.event, payload: {} });
    return { match_id: matchId, status: to, changed_by: actor };
  }
  const status = await withTransaction(async (client) => {
    const locked = await matches.get(client, matchId, { forUpdate: true });
    const current = effectiveStatus(locked.admin_status, projected);
    if (current !== from) {
      throw new AppError(409, 'ILLEGAL_TRANSITION', `Cannot move match from ${current} to ${to}`, { from: current, to });
    }
    await client.query('UPDATE matches SET admin_status = $2 WHERE match_id = $1', [matchId, effect.admin]);
    const after = effect.admin || projected;
    await outbox.enqueue(client, {
      aggregate_type: 'match',
      aggregate_id: matchId,
      event_type: 'match.status_changed',
      payload: { match_id: matchId, status: after, league_id: match.league_id },
    });
    return after;
  });
  await eventService.invalidateForMatch(matchId);
  return { match_id: matchId, status, changed_by: actor };
}

async function sportCache(ids) {
  const out = new Map();
  for (const id of ids) if (!out.has(id)) out.set(id, await sports.get(pool, id));
  return out;
}

// Returns {subject, conflicts} for moving the match to (scheduledAt, venueId).
async function checkSchedule(match, { scheduledAt, venueId }) {
  const start = new Date(scheduledAt).getTime();
  const { rows: leagueRows } = match.league_id
    ? await pool.query('SELECT config FROM leagues WHERE league_id = $1', [match.league_id])
    : { rows: [] };
  const config = (leagueRows[0] && leagueRows[0].config) || {};
  const minRest = Number.isInteger(config.min_rest_minutes) ? config.min_rest_minutes : DEFAULT_MIN_REST_MIN;

  const sportConfig = await sports.get(pool, match.sport_id);
  const subject = {
    match_id: match.match_id,
    home_team_id: match.home_team_id,
    away_team_id: match.away_team_id,
    start,
    duration: durationMinutes(match, sportConfig),
    venue_id: venueId,
  };
  const reach = (minRest + 24 * 60) * MIN + subject.duration * MIN;
  const { rows: near } = await pool.query(
    `SELECT match_id, sport_id, home_team_id, away_team_id, venue_id, scheduled_at, duration_minutes
     FROM matches WHERE match_id <> $1 AND scheduled_at IS NOT NULL AND scheduled_at BETWEEN $2 AND $3
       AND (admin_status IS NULL OR admin_status <> ALL($4::text[]))`,
    [match.match_id, new Date(start - reach), new Date(start + reach), INACTIVE],
  );
  const sportMap = await sportCache(near.map((n) => n.sport_id));
  const others = near.map((n) => ({
    match_id: n.match_id,
    home_team_id: n.home_team_id,
    away_team_id: n.away_team_id,
    venue_id: n.venue_id,
    start: new Date(n.scheduled_at).getTime(),
    duration: durationMinutes(n, sportMap.get(n.sport_id)),
  }));

  const teamIds = [...new Set([match.home_team_id, match.away_team_id, ...near.flatMap((n) => [n.home_team_id, n.away_team_id])])];
  const { rows: regs } = await pool.query('SELECT squad_id, player_id FROM registrations WHERE squad_id = ANY($1::text[])', [teamIds]);
  const playersByTeam = new Map();
  for (const r of regs) {
    if (!playersByTeam.has(r.squad_id)) playersByTeam.set(r.squad_id, new Set());
    playersByTeam.get(r.squad_id).add(r.player_id);
  }
  const { rows: hours } = venueId
    ? await pool.query(
      `SELECT weekday, to_char(opens, 'HH24:MI') AS opens, to_char(closes, 'HH24:MI') AS closes FROM venue_hours WHERE venue_id = $1`,
      [venueId],
    )
    : { rows: [] };
  return { subject, conflicts: detectConflicts({ subject, others, playersByTeam, hours, minRest }) };
}

async function loadVenue(venueId) {
  const { rows } = await pool.query('SELECT venue_id, tenant_id FROM venues WHERE venue_id = $1', [venueId]);
  if (!rows[0]) throw new AppError(404, 'VENUE_NOT_FOUND', 'Venue not found');
  return rows[0];
}

async function reschedule(match, { scheduledAt, venueId }) {
  const { conflicts } = await checkSchedule(match, { scheduledAt, venueId });
  if (conflicts.length) throw new AppError(409, 'SCHEDULE_CONFLICT', 'The new slot conflicts with other matches', { conflicts });
  await pool.query('UPDATE matches SET scheduled_at = $2, venue_id = $3 WHERE match_id = $1', [match.match_id, scheduledAt, venueId]);
  await eventService.invalidateForMatch(match.match_id);
  return { match_id: match.match_id, scheduled_at: toIso(scheduledAt), venue_id: venueId, conflicts: [] };
}

async function pin(matchId, productId) {
  await withTransaction(async (client) => {
    await client.query('INSERT INTO match_pins (match_id, product_id) VALUES ($1, $2) ON CONFLICT DO NOTHING', [matchId, productId]);
    await outbox.enqueue(client, {
      aggregate_type: 'match', aggregate_id: matchId, event_type: 'product.pinned', payload: { match_id: matchId, product_id: productId },
    });
  });
  await eventService.invalidateForMatch(matchId);
  return { match_id: matchId, product_id: productId, pinned: true };
}

async function unpin(matchId, productId) {
  await pool.query('DELETE FROM match_pins WHERE match_id = $1 AND product_id = $2', [matchId, productId]);
  await eventService.invalidateForMatch(matchId);
  return { match_id: matchId, product_id: productId, pinned: false };
}

module.exports = { changeStatus, checkSchedule, reschedule, loadVenue, pin, unpin };
