// Small request-validation helpers shared by the Part II routes.
const { ApiError } = require('./errors');

const isObject = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const invalid = (fields) => new ApiError(400, 'VALIDATION_ERROR', 'Request validation failed.', { fields });
const nonEmptyString = (v, max = 256) => typeof v === 'string' && v.length > 0 && v.length <= max;

function bodyObject(req) {
  if (!isObject(req.body)) throw invalid(['body']);
  return req.body;
}

module.exports = { isObject, invalid, nonEmptyString, bodyObject };
