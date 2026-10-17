const { z } = require('zod');
const pool = require('../db/pool');
const { withTransaction } = require('../db/tx');
const AppError = require('../http/AppError');
const { Id, Int, parseOr400, issuesToFields, isPlainObject } = require('../http/validators');
const { TIE_BREAK_TOKENS, normaliseTieBreak } = require('../domain/scoring/sportSchema');
const sports = require('../repos/sports');
const leagues = require('../repos/leagues');
const matches = require('../repos/matches');
const settings = require('../repos/settings');

const Fixture = z.object({ match_id: Id, home: Id, away: Id });
const LeagueBody = z.object({
  league_id: Id,
  sport_id: z.string(),
  teams: z.array(Id).min(2).max(40),
  fixtures: z.array(Fixture),
  points: z.object({ win: Int.optional(), draw: Int.optional(), loss: Int.optional() }).optional(),
  tie_break_order: z.array(z.enum(TIE_BREAK_TOKENS)).optional(),
  qualification: z.object({ spots: Int }),
  lots_seed: Int.optional(),
});

// Cross-field rules, evaluated on the raw body so they are reported alongside type errors.
function crossFieldErrors(body) {
  const fields = [];
  const teams = Array.isArray(body.teams) ? body.teams.filter((t) => typeof t === 'string') : [];
  const teamSet = new Set(teams);
  if (teamSet.size !== teams.length) fields.push('teams');
  if (Array.isArray(body.fixtures)) {
    const seen = new Set();
    body.fixtures.forEach((f, i) => {
      if (!isPlainObject(f)) return;
      if (typeof f.home === 'string' && typeof f.away === 'string' && f.home === f.away) fields.push(`fixtures.${i}.away`);
      if (typeof f.home === 'string' && teams.length && !teamSet.has(f.home)) fields.push(`fixtures.${i}.home`);
      if (typeof f.away === 'string' && teams.length && !teamSet.has(f.away)) fields.push(`fixtures.${i}.away`);
      if (typeof f.match_id === 'string') {
        if (seen.has(f.match_id)) fields.push('fixtures');
        seen.add(f.match_id);
      }
    });
  }
  if (isPlainObject(body.qualification) && Number.isInteger(body.qualification.spots)) {
    const max = teams.length || 2;
    if (body.qualification.spots < 1 || body.qualification.spots > max) fields.push('qualification.spots');
  }
  if (Array.isArray(body.tie_break_order) && new Set(body.tie_break_order).size !== body.tie_break_order.length) {
    fields.push('tie_break_order');
  }
  return fields;
}

function parseLeague(body) {
  if (!isPlainObject(body)) {
    throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields: ['body'] });
  }
  const parsed = LeagueBody.safeParse(body);
  const fields = parsed.success ? [] : issuesToFields(parsed.error);
  for (const f of crossFieldErrors(body)) if (!fields.includes(f)) fields.push(f);
  if (fields.length) throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields });
  return parsed.data;
}

const leagueExists = () => new AppError(409, 'LEAGUE_EXISTS', 'A league with this league_id already exists');

async function createLeague(rawBody) {
  const body = parseLeague(rawBody);
  const sport = await sports.get(pool, body.sport_id);
  if (!sport) throw new AppError(404, 'SPORT_NOT_FOUND', 'Sport not found');
  if (await leagues.get(pool, body.league_id)) throw leagueExists();
  const taken = await matches.findExistingIds(pool, body.fixtures.map((f) => f.match_id));
  if (taken.length) {
    throw new AppError(409, 'MATCH_EXISTS', 'A match with this match_id already exists', { match_id: taken[0] });
  }

  const lastSeed = await settings.get(pool, 'last_reset_seed');
  const config = {
    points: { win: 3, draw: 1, loss: 0, ...(body.points || {}) },
    tie_break_order: normaliseTieBreak(body.tie_break_order || sport.tie_break_order),
    qualification_spots: body.qualification.spots,
    lots_seed: body.lots_seed !== undefined ? body.lots_seed : Number.isInteger(lastSeed) ? lastSeed : 42,
  };

  try {
    await withTransaction(async (client) => {
      await leagues.insert(client, { league_id: body.league_id, sport_id: body.sport_id, config });
      await leagues.addTeams(client, body.league_id, body.teams);
      await matches.insertFixtures(client, { sportId: body.sport_id, leagueId: body.league_id, fixtures: body.fixtures });
    });
  } catch (err) {
    if (err.code === '23505') {
      if (/leagues_pkey/.test(err.constraint || '')) throw leagueExists();
      throw new AppError(409, 'MATCH_EXISTS', 'A match with this match_id already exists');
    }
    throw err;
  }
  return { league_id: body.league_id, status: 'created', team_count: body.teams.length, fixture_count: body.fixtures.length };
}

module.exports = { createLeague };
