const AppError = require('../http/AppError');
const { authorize } = require('../domain/rbac/engine');

const UNAUTH = () => new AppError(401, 'UNAUTHENTICATED', 'Authentication required');
const BEARER = /^bearer +(\S+)$/i;

function principalFromRequest(req) {
  const { verifyAccessToken } = require('../services/authService');
  const header = req.headers.authorization;
  if (typeof header !== 'string') return null;
  const m = BEARER.exec(header);
  if (!m) return null;
  return verifyAccessToken(m[1]);
}

function authenticate(req, res, next) {
  const principal = principalFromRequest(req);
  if (!principal) return next(UNAUTH());
  req.principal = principal;
  return next();
}

// Sets req.principal when a valid token is present, otherwise continues anonymously.
function optionalAuth(req, res, next) {
  req.principal = principalFromRequest(req) || null;
  next();
}

// resolveResource(req) -> resource object (may throw AppError 404, or return a promise).
function requirePermission(action, resolveResource) {
  return async (req, res, next) => {
    try {
      if (!req.principal) throw UNAUTH();
      const { ensurePolicies } = require('../services/rbacService');
      const policies = await ensurePolicies();
      const resource = resolveResource ? await resolveResource(req) : {};
      const decision = authorize(req.principal, action, resource, policies);
      if (!decision.allowed) throw new AppError(403, 'FORBIDDEN', 'You do not have permission to perform this action');
      next();
    } catch (err) {
      next(err);
    }
  };
}

module.exports = { authenticate, optionalAuth, requirePermission, UNAUTH };
