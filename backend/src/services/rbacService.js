const pool = require('../db/pool');
const AppError = require('../http/AppError');
const { Id } = require('../http/validators');
const { authorize, findPolicy } = require('../domain/rbac/engine');
const { DEFAULT_POLICIES } = require('../domain/rbac/defaultPolicies');

let cache = null;
let loading = null;

async function loadPolicies() {
  const { rows } = await pool.query('SELECT role, action, scope FROM role_policies');
  return rows;
}

// Reload from the DB (startup and after every reset).
async function refreshPolicies() {
  const p = loadPolicies();
  loading = p;
  try {
    cache = await p;
    return cache;
  } finally {
    if (loading === p) loading = null;
  }
}

// Boot top-up: a database seeded by an older build gains the rows added since (existing rows are never touched).
async function topUpDefaults() {
  await pool.query(
    `INSERT INTO role_policies (role, action, scope)
     SELECT * FROM unnest($1::text[], $2::text[], $3::text[]) ON CONFLICT (role, action) DO NOTHING`,
    [DEFAULT_POLICIES.map((p) => p.role), DEFAULT_POLICIES.map((p) => p.action), DEFAULT_POLICIES.map((p) => p.scope)],
  );
}

async function ensurePolicies() {
  if (cache) return cache;
  return loading || refreshPolicies();
}

const KINDS = {
  league: {
    sql: 'SELECT league_id, tenant_id FROM leagues WHERE league_id = $1',
    code: 'LEAGUE_NOT_FOUND', message: 'League not found',
  },
  club: {
    sql: 'SELECT club_id, tenant_id FROM clubs WHERE club_id = $1',
    code: 'NOT_FOUND', message: 'Club not found',
  },
  player: {
    sql: 'SELECT player_id, club_id, tenant_id FROM players WHERE player_id = $1',
    code: 'PLAYER_NOT_FOUND', message: 'Player not found',
  },
};

async function loadResource(kind, id) {
  const k = KINDS[kind];
  // Every stored id matches the Id pattern, so anything else cannot exist (and never reaches SQL).
  if (Id.safeParse(id).success) {
    const { rows } = await pool.query(k.sql, [id]);
    if (rows[0]) return rows[0];
  }
  throw new AppError(404, k.code, k.message);
}

const forbidden = () => new AppError(403, 'FORBIDDEN', 'You do not have permission to perform this action');

// Probe evaluation order (DESIGN.md 12): role policy -> any -> id format -> resource -> tenant/scope.
async function probe(principal, action, kind, id) {
  const policies = await ensurePolicies();
  const policy = findPolicy(principal.role, action, policies);
  if (!policy) throw forbidden();
  if (policy.scope === 'any') {
    if (kind) await loadResource(kind, id);
    return { authorized: true };
  }
  if (kind && !Id.safeParse(id).success) {
    throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields: [`${kind}_id`] });
  }
  const resource = kind ? await loadResource(kind, id) : {};
  if (!authorize(principal, action, resource, policies).allowed) throw forbidden();
  return { authorized: true };
}

module.exports = { ensurePolicies, refreshPolicies, topUpDefaults, probe, loadResource };
