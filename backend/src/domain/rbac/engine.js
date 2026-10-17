// Pure policy engine (DESIGN.md 12). No I/O: policies are passed in by the caller.
const { ROLES } = require('./defaultPolicies');

const isStr = (v) => typeof v === 'string' && v.length > 0;
const deny = (reason) => ({ allowed: false, reason });

function findPolicy(role, action, policies) {
  if (!ROLES.includes(role) || !Array.isArray(policies)) return null;
  for (const p of policies) {
    if (p && p.role === role && p.action === action) return p;
  }
  return null;
}

// resource: { tenant_id, league_id?, club_id?, player_id? }
function authorize(principal, action, resource, policies) {
  if (!principal || typeof principal !== 'object') return deny('no_principal');
  const policy = findPolicy(principal.role, action, policies);
  if (!policy) return deny('no_policy');
  if (policy.scope === 'any') return { allowed: true };

  const res = resource && typeof resource === 'object' ? resource : {};
  if (!isStr(principal.tenant_id) || res.tenant_id !== principal.tenant_id) return deny('tenant_mismatch');

  switch (policy.scope) {
    case 'same_tenant':
      return { allowed: true };
    case 'own_league':
      return Array.isArray(principal.league_ids) && isStr(res.league_id) && principal.league_ids.includes(res.league_id)
        ? { allowed: true } : deny('scope_mismatch');
    case 'own_club':
    case 'own_club_player':
      return isStr(principal.club_id) && res.club_id === principal.club_id
        ? { allowed: true } : deny('scope_mismatch');
    case 'self':
      return (isStr(principal.player_id) && res.player_id === principal.player_id)
        || (isStr(res.user_id) && res.user_id === principal.user_id)
        ? { allowed: true } : deny('scope_mismatch');
    default:
      return deny('unknown_scope');
  }
}

// null = unrestricted (super admin); otherwise the tenant every query must be filtered by.
function tenantScope(principal) {
  if (principal && principal.role === 'super_admin') return null;
  return principal && isStr(principal.tenant_id) ? principal.tenant_id : '';
}

module.exports = { authorize, findPolicy, tenantScope };
