const AppError = require('../http/AppError');
const { authorize, findPolicy } = require('../domain/rbac/engine');
const { UNAUTH } = require('./auth');

const forbidden = () => new AppError(403, 'FORBIDDEN', 'You do not have permission to perform this action');

// Order of checks: 401 -> role has the action (403) -> resolver (400/404) -> scope/tenant (403).
// resolve(req) returns the resource used for the scope check; it may mark `open: true` to skip
// the scope check (stand-alone matches are readable by every authenticated user).
function guard(action, resolve) {
  return async (req, res, next) => {
    try {
      if (!req.principal) throw UNAUTH();
      const { ensurePolicies } = require('../services/rbacService');
      const policies = await ensurePolicies();
      if (!findPolicy(req.principal.role, action, policies)) throw forbidden();
      const resource = resolve ? await resolve(req) : {};
      if (!(resource && resource.open) && !authorize(req.principal, action, resource, policies).allowed) throw forbidden();
      req.resource = resource;
      next();
    } catch (err) {
      next(err);
    }
  };
}

module.exports = { guard, forbidden };
