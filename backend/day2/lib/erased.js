// Users erased through the privacy route: their previously issued access tokens are rejected (NFR-8).
const jwt = require('jsonwebtoken');
const { sendError } = require('./errors');

const erased = new Set();
const BEARER = /^\s*bearer\s+(\S+)/i;

const markErased = (ids) => ids.forEach((id) => erased.add(id));
const clear = () => erased.clear();

// Runs before the Phase I auth check; only looks at the (unverified) subject, Phase I still verifies the signature.
function rejectErasedTokens(req, res, next) {
  if (erased.size === 0) return next();
  const m = BEARER.exec(req.headers.authorization || '');
  if (!m || m[1].startsWith('guest_')) return next();
  const claims = jwt.decode(m[1]);
  if (claims && typeof claims.sub === 'string' && erased.has(claims.sub)) {
    return sendError(res, 401, 'UNAUTHENTICATED', 'Authentication required', {});
  }
  return next();
}

module.exports = { markErased, clear, rejectErasedTokens };
