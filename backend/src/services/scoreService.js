const pool = require('../db/pool');
const AppError = require('../http/AppError');
const { Id } =require('../http/validators');
const { project } = require('../domain/scoring/projection');
const sports = require('../repos/sports');
const matches = require('../repos/matches');
const events = require('../repos/events');

// Scores are always rebuilt from the event log; nothing is cached or counted.
async function getScore(matchId) {
  if (!Id.safeParse(matchId).success) {
    throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields: ['match_id'] });
  }
  const match = await matches.get(pool, matchId);
  if (!match) throw new AppError(404, 'MATCH_NOT_FOUND', 'Match not found');
  const config = await sports.get(pool, match.sport_id);
  const history = await events.listByMatch(pool, matchId);
  return project(config, match, history);
}

module.exports = { getScore };
