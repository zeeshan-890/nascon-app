const pool = require('../db/pool');
const AppError = require('../http/AppError');
const { Id } = require('../http/validators');
const { project } = require('../domain/scoring/projection');
const { computeStandings } = require('../domain/standings');

// Exactly three queries, then everything is derived in memory. Never cached.
// Knockout-stage matches (bracket_slot.stage = 'knockout') are not part of the table.
async function loadLeagueState(leagueId) {
  if (!Id.safeParse(leagueId).success) {
    throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields: ['league_id'] });
  }

  const { rows: leagueRows } = await pool.query(
    `SELECT l.league_id, l.sport_id, l.config, s.config AS sport_config, t.team_id
     FROM leagues l
     LEFT JOIN sports s ON s.sport_id = l.sport_id
     LEFT JOIN league_teams t ON t.league_id = l.league_id
     WHERE l.league_id = $1 ORDER BY t.position, t.team_id`,
    [leagueId]
  );
  if (!leagueRows.length) throw new AppError(404, 'LEAGUE_NOT_FOUND', 'League not found');
  const league = leagueRows[0];
  const teams = leagueRows.filter((r) => r.team_id !== null).map((r) => r.team_id);

  const { rows: matchRows } = await pool.query(
    `SELECT match_id, sport_id, league_id, home_team_id, away_team_id, admin_status FROM matches
     WHERE league_id = $1 AND COALESCE(bracket_slot->>'stage', '') <> 'knockout' ORDER BY match_id`,
    [leagueId]
  );
  const { rows: eventRows } = await pool.query(
    `SELECT match_id, sequence, event_id, type, payload_canonical, signature, compensates
     FROM match_events WHERE match_id = ANY($1::text[]) ORDER BY match_id, sequence`,
    [matchRows.map((m) => m.match_id)]
  );

  const byMatch = new Map();
  for (const e of eventRows) {
    if (!byMatch.has(e.match_id)) byMatch.set(e.match_id, []);
    byMatch.get(e.match_id).push(e);
  }

  const sport = league.sport_config || { scoring_model: 'event', scoring_events: {}, periods: {}, draw_allowed: true };
  const config = league.config || {};
  const projected = matchRows.map((m) => ({ ...project(sport, m, byMatch.get(m.match_id) || []), admin_status: m.admin_status }));
  return { league, sport, config, teams, projected };
}

function tableOf({ league, sport, config, teams, projected }, subset = teams, overrides = {}) {
  return computeStandings({
    league_id: league.league_id,
    sport_id: league.sport_id,
    teams: subset,
    scoring_model: sport.scoring_model,
    periods: sport.periods || {},
    points: config.points,
    tie_break_order: config.tie_break_order || sport.tie_break_order,
    qualification_spots: overrides.qualification_spots === undefined ? config.qualification_spots : overrides.qualification_spots,
    lots_seed: config.lots_seed,
    matches: projected,
  });
}

async function getStandings(leagueId) {
  return tableOf(await loadLeagueState(leagueId));
}

module.exports = { getStandings, loadLeagueState, tableOf };
