const AppError = require('../http/AppError');
const { FixtureError, validateRequest } = require('../domain/fixtures/validate');
const { generateRoundRobin } = require('../domain/fixtures/roundRobin');
const { generateSwiss } = require('../domain/fixtures/swiss');
const { generateSingleElim } = require('../domain/fixtures/singleElim');
const { generateDoubleElim } = require('../domain/fixtures/doubleElim');

const GENERATORS = {
  round_robin: generateRoundRobin,
  swiss: generateSwiss,
  single_elim: generateSingleElim,
  double_elim: generateDoubleElim,
};

const STATUS = { VALIDATION_ERROR: 400, NO_VALID_PAIRING: 409 };

function generateFixtures(body) {
  try {
    const { format, teams, params } = validateRequest(body);
    return GENERATORS[format](teams, params);
  } catch (err) {
    if (err instanceof FixtureError) {
      throw new AppError(STATUS[err.code] || 400, err.code, err.message, err.details);
    }
    throw err;
  }
}

module.exports = { generateFixtures };
