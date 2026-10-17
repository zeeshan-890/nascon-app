const AppError = require('../http/AppError');
const { inspectBody } = require('../domain/rbac/bodyWalk');

// Mounted right after the JSON parser: rejects NUL characters, excessive nesting and
// oversized node counts before any validator, canonical() or SQL sees the body.
function hardening(req, res, next) {
  if (req.bodyIgnored) return next();
  const r = inspectBody(req.body);
  if (!r.ok) return next(new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields: ['body'] }));
  return next();
}

module.exports = hardening;
module.exports.hardening = hardening;
