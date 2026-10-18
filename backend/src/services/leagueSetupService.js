const crypto = require('crypto');
const { z } = require('zod');
const pool = require('../db/pool');
const { withTransaction } = require('../db/tx');
const cache = require('../db/cache');
const AppError = require('../http/AppError');
const { IsoDate, parseOr400, requireObjectBody } = require('../http/validators');
const matches = require('../repos/matches');
const { manageable } = require('./leagueAdminService');
const { generateFixtures } = require('./fixturesService');

// Teams and generated fixtures for an approved league. Both are for the super admin or the league's organizer.
const MAX_TEAMS = 40;
const KICKOFF = 'T15:00:00Z';

const TeamRequest = z.object({ name: z.string().trim().min(1).max(40) });
const GenerateRequest = z.object({
  format: z.string(),
  teams: z.array(z.string()),
  params: z.record(z.string(), z.any()).optional(),
  start_date: IsoDate.optional(),
  match_days: z.array(z.number().int().min(1).max(7)).min(1).max(7).optional(),
});

const slug = (s, max) => s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, max) || 'team';
const dto = (r) => ({ team_id: r.team_id, name: r.name || r.team_id });

async function listTeams(principal, leagueId) {
  await manageable(principal, leagueId);
  const { rows } = await pool.query(
    'SELECT team_id, name FROM league_teams WHERE league_id = $1 ORDER BY position, team_id', [leagueId],
  );
  return rows.map(dto);
}

async function addTeam(principal, leagueId, rawBody) {
  await manageable(principal, leagueId, { active: true });
  const { name } = parseOr400(TeamRequest, requireObjectBody(rawBody));
  const { rows } = await pool.query('SELECT team_id, name FROM league_teams WHERE league_id = $1', [leagueId]);
  if (rows.length >= MAX_TEAMS) throw new AppError(409, 'TOO_MANY_TEAMS', `A league holds at most ${MAX_TEAMS} teams`);
  if (rows.some((r) => (r.name || '').toLowerCase() === name.toLowerCase())) {
    throw new AppError(409, 'DUPLICATE_TEAM', 'A team with this name is already in the league', { name });
  }
  const teamId = `tm-${slug(name, 24)}-${crypto.randomBytes(2).toString('hex')}`;
  await pool.query(
    'INSERT INTO league_teams (league_id, team_id, position, name) VALUES ($1, $2, $3, $4)',
    [leagueId, teamId, rows.length, name],
  );
  await cache.invalidateLeague(leagueId);
  return { team_id: teamId, name };
}

async function removeTeam(principal, leagueId, teamId) {
  await manageable(principal, leagueId, { active: true });
  const { rows: team } = await pool.query('SELECT 1 FROM league_teams WHERE league_id = $1 AND team_id = $2', [leagueId, teamId]);
  if (!team.length) throw new AppError(404, 'NOT_FOUND', 'Team not found');
  const { rows: used } = await pool.query(
    'SELECT 1 FROM matches WHERE league_id = $1 AND (home_team_id = $2 OR away_team_id = $2) LIMIT 1', [leagueId, teamId],
  );
  if (used.length) throw new AppError(409, 'TEAM_HAS_FIXTURES', 'This team already has fixtures and cannot be removed');
  await pool.query('DELETE FROM league_teams WHERE league_id = $1 AND team_id = $2', [leagueId, teamId]);
  await cache.invalidateLeague(leagueId);
  return { removed: true, team_id: teamId };
}

// Playable matches of a generator result: byes, voided or forfeited games, finished games and slots whose
// teams are not known yet are skipped. `round` orders them into match days.
function playable(format, out) {
  const keep = (m) => m.home && m.away && !m.bye && !m.winner && !m.conditional;
  const flat = (rounds, bracket) => (rounds || []).flatMap((r) => (r.matches || []).filter(keep)
    .map((m) => ({ match_id: m.match_id, home: m.home, away: m.away, round: r.round, bracket })));
  switch (format) {
    case 'round_robin':
      return out.rounds.flatMap((r) => r.matches.filter((m) => m.status === 'scheduled')
        .map((m) => ({ match_id: m.match_id, home: m.home, away: m.away, round: r.round })));
    case 'swiss':
      return out.pairings.map((p) => ({ match_id: p.match_id, home: p.home, away: p.away, round: out.round }));
    case 'single_elim':
      return flat(out.rounds);
    default: {
      const winners = flat(out.winners);
      const losers = flat(out.losers);
      const last = Math.max(0, ...winners.map((m) => m.round), ...losers.map((m) => m.round));
      const finals = (out.grand_final || []).filter(keep).map((m, i) => ({
        match_id: m.match_id, home: m.home, away: m.away, round: last + 1 + i,
      }));
      return [...winners, ...losers, ...finals];
    }
  }
}

// The n-th (0-based) date on or after start whose ISO weekday (1 = Monday ... 7 = Sunday) is in days.
function nthMatchDay(start, days, n) {
  const d = new Date(`${start}T00:00:00Z`);
  let seen = -1;
  for (let guard = 0; guard < 7 * (n + 2); guard += 1) {
    const weekday = d.getUTCDay() === 0 ? 7 : d.getUTCDay();
    if (days.includes(weekday)) seen += 1;
    if (seen === n) return d.toISOString().slice(0, 10);
    d.setUTCDate(d.getUTCDate() + 1);
  }
  return start;
}

async function generate(principal, leagueId, rawBody) {
  const league = await manageable(principal, leagueId, { active: true });
  const body = parseOr400(GenerateRequest, requireObjectBody(rawBody));
  const { rows } = await pool.query('SELECT team_id FROM league_teams WHERE league_id = $1', [leagueId]);
  const known = new Set(rows.map((r) => r.team_id));
  if (body.teams.some((t) => !known.has(t))) {
    throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields: ['teams'] });
  }

  const out = generateFixtures({ format: body.format, teams: body.teams, params: body.params });
  const found = playable(body.format, out);
  const start = body.start_date || new Date().toISOString().slice(0, 10);
  const days = [...new Set(body.match_days || [1, 2, 3, 4, 5, 6, 7])].sort((a, b) => a - b);
  const roundOrder = [...new Set(found.map((m) => m.round))].sort((a, b) => a - b);
  // A short batch tag keeps ids unique when a schedule is generated more than once for the same league.
  const batch = crypto.randomBytes(2).toString('hex');
  const toSave = found.map((m) => ({
    match_id: `${leagueId}-${batch}-${m.match_id}`,
    home: m.home,
    away: m.away,
    round: m.round,
    scheduled_at: `${nthMatchDay(start, days, roundOrder.indexOf(m.round))}${KICKOFF}`,
  }));

  await withTransaction((client) => matches.insertGenerated(client, { sportId: league.sport_id, leagueId, rows: toSave }));
  await cache.invalidateLeague(leagueId);
  return { created: toSave.length, league_id: leagueId };
}

module.exports = { listTeams, addTeam, removeTeam, generate, playable, nthMatchDay };
