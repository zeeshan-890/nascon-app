const AppError = require('../http/AppError');
const logger = require('../logger');
const { send, errorBody } = require('../http/respond');

const PG_VALIDATION = new Set(['22P02', '22007', '22008', '22003', '22001', '23502']);

// eslint-disable-next-line no-unused-vars
function errorHandler(err, req, res, next) {
  if (res.headersSent) return next(err);

  if (err && err.type === 'entity.too.large') {
    return send(res, 413, errorBody('PAYLOAD_TOO_LARGE', 'Request body too large'));
  }
  if (err && err.type === 'entity.parse.failed') {
    return send(res, 400, errorBody('VALIDATION_ERROR', 'Request validation failed', { fields: ['body'] }));
  }
  if (err && typeof err.type === 'string' && err.status >= 400 && err.status < 500
    && !(err instanceof AppError)) {
    return send(res, 400, errorBody('VALIDATION_ERROR', 'Request validation failed', { fields: ['body'] }));
  }
  if (err instanceof AppError) {
    const details = err.details && typeof err.details === 'object' ? err.details : {};
    return send(res, err.status, errorBody(err.code, err.message, details));
  }
  if (err && PG_VALIDATION.has(err.code)) {
    return send(res, 400, errorBody('VALIDATION_ERROR', 'Request validation failed', { fields: [] }));
  }
  if (err && err.code === '23505') {
    return send(res, 409, errorBody('CONFLICT', 'Resource conflict'));
  }
  logger.error({ err }, 'unhandled error');
  return send(res, 500, errorBody('INTERNAL_ERROR', 'Internal server error'));
}

module.exports = errorHandler;
module.exports.errorHandler = errorHandler;
