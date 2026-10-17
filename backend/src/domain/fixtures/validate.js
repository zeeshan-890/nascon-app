const ID_RE = /^[A-Za-z0-9_.:-]{1,64}$/;
const FORMATS = ['round_robin', 'swiss', 'single_elim', 'double_elim'];
const TEAM_LIMITS = {
  round_robin: [2, 40],
  swiss: [2, 64],
  single_elim: [2, 128],
  double_elim: [3, 128],
};

class FixtureError extends Error {
  constructor(code, message, details = {}) {
    super(message);
    this.name = 'FixtureError';
    this.code = code;
    this.details = details;
  }
}

const isObj = (v) => typeof v === 'object' && v !== null && !Array.isArray(v);
const isInt = (v) => typeof v === 'number' && Number.isSafeInteger(v);
const hasNul = (v) => typeof v === 'string' && v.includes('\u0000');
const isId = (v) => typeof v === 'string' && ID_RE.test(v);

function invalid(fields) {
  return new FixtureError('VALIDATION_ERROR', 'Request validation failed', { fields: [...new Set(fields)] });
}

function noNul(value, depth = 0) {
  if (hasNul(value)) return false;
  if (depth > 6) return true;
  if (Array.isArray(value)) return value.every((x) => noNul(x, depth + 1));
  if (isObj(value)) return Object.entries(value).every(([k, x]) => !hasNul(k) && noNul(x, depth + 1));
  return true;
}

/** Validates {format, teams, params}; throws FixtureError listing every bad top-level field. */
function validateRequest(body) {
  if (!isObj(body)) throw invalid(['body']);
  const fields = [];
  const { format, teams, params } = body;
  if (!FORMATS.includes(format)) fields.push('format');
  if (!Array.isArray(teams) || !teams.every(isId) || new Set(teams).size !== teams.length) {
    fields.push('teams');
  } else if (FORMATS.includes(format)) {
    const [lo, hi] = TEAM_LIMITS[format];
    if (teams.length < lo || teams.length > hi) fields.push('teams');
  }
  if (params !== undefined && !isObj(params)) fields.push('params');
  if (!noNul(body)) {
    for (const key of ['format', 'teams', 'params']) {
      if (body[key] !== undefined && !noNul(body[key])) fields.push(key);
    }
  }
  if (fields.length) throw invalid(fields);
  return { format, teams, params: params || {} };
}

module.exports = { FORMATS, TEAM_LIMITS, ID_RE, FixtureError, invalid, isObj, isInt, isId, validateRequest };
