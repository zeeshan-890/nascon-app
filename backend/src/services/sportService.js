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

// Production variant: replaying an identical definition answers 200 with the stored config; a different one is 409.
async function createSportIdempotent(body, db = pool) {
  const config = normalise(body);
  const found = await sports.get(db, config.sport_id);
  if (found) {
    if (canon(found) === canon(config)) return { status: 200, body: { sport_id: config.sport_id, status: 'exists', config: found } };
    throw exists();
  }
  return { status: 201, body: await createSport(body, db) };
}

const canon = (v) => {
  if (Array.isArray(v)) return `[${v.map(canon).join(',')}]`;
  if (v && typeof v === 'object') return `{${Object.keys(v).sort().map((k) => `${JSON.stringify(k)}:${canon(v[k])}`).join(',')}}`;
  return JSON.stringify(v === undefined ? null : v);
};

module.exports = { createSport, createSportIdempotent, normalise };
