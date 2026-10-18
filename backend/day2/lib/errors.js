// Part II error type, response helper and error middleware. Body shape: { error: { code, message, details } }.
const logger = require('../../src/logger');

class ApiError extends Error {
  constructor(status, code, message, details = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

function sendError(res, status, code, message, details = {}) {
  return res.status(status).type('application/json').json({ error: { code, message, details } });
}

// Marks a request as served by a Part II route so the middleware below leaves Phase I errors untouched.
function markDay2(req, res, next) {
  req.day2 = true;
  next();
}

// eslint-disable-next-line no-unused-vars
function errorMiddleware(err, req, res, next) {
  if (!req.day2 && !(err instanceof ApiError)) return next(err);
  if (res.headersSent) return next(err);
  if (err instanceof ApiError) return sendError(res, err.status, err.code, err.message, err.details || {});
  if (err && err.type === 'entity.too.large') {
    return sendError(res, 413, 'PAYLOAD_TOO_LARGE', 'Request body is larger than 1 MB.');
  }
  if (err && (err.type === 'entity.parse.failed' || (err instanceof SyntaxError && err.status === 400))) {
    return sendError(res, 400, 'VALIDATION_ERROR', 'Malformed JSON body.', { fields: ['body'] });
  }
  logger.error({ err: err && err.message }, 'unexpected error in a Part II route');
  return sendError(res, 500, 'INTERNAL_ERROR', 'Internal server error.');
}

module.exports = { ApiError, sendError, markDay2, errorMiddleware };
