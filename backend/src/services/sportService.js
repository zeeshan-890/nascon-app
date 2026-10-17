const pool = require('../db/pool');
const AppError = require('../http/AppError');
const sports = require('../repos/sports');
const { validateSportConfig } = require('../domain/scoring/sportSchema');

const exists = () => new AppError(409, 'SPORT_EXISTS', 'A sport with this sport_id already exists');

// Validates and normalises a sport definition; throws 400 listing every bad field.
function normalise(body) {
  const { fields, config } = validateSportConfig(body);
  if (fields.length) {
    throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields });
  }
  return config;
}

async function createSport(body, db = pool) {
  const config = normalise(body);
  if (await sports.get(db, config.sport_id)) throw exists();
  try {
    await sports.insert(db, config);
  } catch (err) {
    if (err.code === '23505') throw exists();
    throw err;
  }
  return { sport_id: config.sport_id, status: 'created', config };
}

module.exports = { createSport, normalise };
