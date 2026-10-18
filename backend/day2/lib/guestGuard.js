// Server-side read-only boundary for anonymous guests (FR-4.3).
const { sendError } = require('./errors');

const WRITE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
const BEARER = /^\s*bearer\s+(\S+)/i;

// Routes later modules may open to guests: exact path strings or RegExps tested against req.path.
const guestWriteAllowList = [];

function getGuestToken(req) {
  const auth = req.headers && req.headers.authorization;
  if (typeof auth === 'string') {
    const m = BEARER.exec(auth);
    if (m && m[1].startsWith('guest_')) return m[1];
  }
  const h = req.headers && req.headers['x-guest-token'];
  const value = Array.isArray(h) ? h[0] : h;
  if (typeof value === 'string' && value.trim().startsWith('guest_')) return value.trim();
  return null;
}

const allowed = (req) =>
  guestWriteAllowList.some((entry) => (entry instanceof RegExp ? entry.test(req.path) : entry === req.path));

function guestWriteGuard(req, res, next) {
  if (!WRITE_METHODS.has(req.method)) return next();
  const token = getGuestToken(req);
  if (!token || allowed(req)) return next();
  return sendError(res, 403, 'GUEST_WRITE_FORBIDDEN', 'Anonymous guest tokens cannot perform write mutations.', { guest_token: token });
}

module.exports = { getGuestToken, guestWriteGuard, guestWriteAllowList };
