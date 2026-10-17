const fs = require('fs');
const path = require('path');
const bcrypt = require('bcryptjs');
const { z } = require('zod');
const pool = require('../db/pool');
const AppError = require('../http/AppError');
const { Int, NonNegInt, issuesToFields, isPlainObject } = require('../http/validators');
const { normalise } = require('./sportService');
const { normaliseTieBreak } = require('../domain/scoring/sportSchema');
const sports = require('../repos/sports');
const settings = require('../repos/settings');
const { DEFAULT_POLICIES } = require('../domain/rbac/defaultPolicies');
const { getRedis } = require('../db/redis');
const { refreshPolicies } = require('./rbacService');

const DEFAULT_SEED = 42;
const DEFAULT_STOCK = { 'var-jersey-001-M': 1, 'var-jersey-001-L': 5 };
const DEMO_PASSWORD = 'Nascon@26';
// Computed once per process; bcrypt at cost 10 is too slow to repeat on every reset.
const DEMO_HASH = bcrypt.hashSync(DEMO_PASSWORD, 10);

const sportsSeed = JSON.parse(fs.readFileSync(path.join(__dirname, '../../seed/sports.json'), 'utf8'));

const ResetBody = z.object({ seed: Int.optional(), stock: z.custom(isPlainObject, 'must be an object').optional() });

function parseBody(body) {
  if (body === undefined || body === null) body = {};
  if (!isPlainObject(body)) {
    throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields: ['body'] });
  }
  const fields = [];
  const parsed = ResetBody.safeParse(body);
  if (!parsed.success) fields.push(...issuesToFields(parsed.error));
  if (isPlainObject(body.stock)) {
    for (const [key, value] of Object.entries(body.stock)) {
      if (!NonNegInt.safeParse(value).success) fields.push(`stock.${key}`);
    }
  }
  if (fields.length) throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields });
  const stock = body.stock || {};
  for (const key of Object.keys(stock)) {
    if (!(key in DEFAULT_STOCK)) {
      throw new AppError(404, 'VARIANT_NOT_FOUND', 'Variant not found', { variant_id: key });
    }
  }
  return { seed: body.seed === undefined ? DEFAULT_SEED : body.seed, stock: { ...DEFAULT_STOCK, ...stock } };
}

async function seedBase(client, { seed, stock }) {
  await client.query(
    `INSERT INTO tenants (tenant_id, name, logo_url, primary_color, secondary_color) VALUES
     ('tenant-alpha', 'Alpha Sports', '/static/tenant-alpha.png', '#0B5FFF', '#FFB800'),
     ('tenant-beta', 'Beta Athletics', NULL, '#0F9D58', '#202124')`
  );

  const sportIds = [];
  for (const raw of sportsSeed) {
    const config = normalise(raw);
    await sports.insert(client, config);
    sportIds.push(config.sport_id);
  }

  await client.query(
    `INSERT INTO products (product_id, tenant_id, club_id, category, name)
     VALUES ('prod-jersey-001', 'tenant-alpha', 'club-alpha-1', 'apparel', 'Club Jersey')`
  );
  await client.query(
    `INSERT INTO variants (variant_id, product_id, size, price_minor, currency, stock, reserved) VALUES
     ('var-jersey-001-M', 'prod-jersey-001', 'M', 450000, 'PKR', $1, 0),
     ('var-jersey-001-L', 'prod-jersey-001', 'L', 450000, 'PKR', $2, 0)`,
    [stock['var-jersey-001-M'], stock['var-jersey-001-L']]
  );
  await client.query(`INSERT INTO discount_codes (code, percent) VALUES ('WELCOME10', 10)`);

  const leagueConfig = JSON.stringify({
    points: { win: 3, draw: 1, loss: 0 },
    tie_break_order: normaliseTieBreak(sportsSeed[0].tie_break_order),
    qualification_spots: 1,
    lots_seed: seed,
  });
  await client.query(
    `INSERT INTO leagues (league_id, tenant_id, sport_id, kind, name, config) VALUES
     ('league-alpha-1', 'tenant-alpha', $1, 'league', 'Alpha League 1', $2::jsonb),
     ('league-alpha-2', 'tenant-alpha', $1, 'league', 'Alpha League 2', $2::jsonb),
     ('league-beta-1', 'tenant-beta', $1, 'league', 'Beta League 1', $2::jsonb)`,
    [sportsSeed[0].sport_id, leagueConfig]
  );
  await client.query(
    `INSERT INTO clubs (club_id, tenant_id, name) VALUES
     ('club-alpha-1', 'tenant-alpha', 'Alpha Club 1'),
     ('club-alpha-2', 'tenant-alpha', 'Alpha Club 2'),
     ('club-beta-1', 'tenant-beta', 'Beta Club 1')`
  );
  await client.query(
    `INSERT INTO players (player_id, club_id, tenant_id, name) VALUES
     ('player-alpha-1', 'club-alpha-1', 'tenant-alpha', 'Alpha Player 1'),
     ('player-alpha-2', 'club-alpha-2', 'tenant-alpha', 'Alpha Player 2'),
     ('player-beta-1', 'club-beta-1', 'tenant-beta', 'Beta Player 1')`
  );
  await client.query(
    `INSERT INTO users (user_id, email, password_hash, role, tenant_id, club_id, player_id, league_ids) VALUES
     ('user-admin', 'admin@nascon.test', $1, 'super_admin', NULL, NULL, NULL, '{}'),
     ('user-organizer', 'organizer@nascon.test', $1, 'league_organizer', 'tenant-alpha', NULL, NULL, '{league-alpha-1}'),
     ('user-manager', 'manager@nascon.test', $1, 'club_manager', 'tenant-alpha', 'club-alpha-1', NULL, '{}'),
     ('user-player', 'player@nascon.test', $1, 'player', 'tenant-alpha', 'club-alpha-1', 'player-alpha-1', '{}'),
     ('user-viewer', 'viewer@nascon.test', $1, 'viewer', 'tenant-alpha', NULL, NULL, '{}')`,
    [DEMO_HASH]
  );
  for (const p of DEFAULT_POLICIES) {
    await client.query('INSERT INTO role_policies (role, action, scope) VALUES ($1, $2, $3)', [p.role, p.action, p.scope]);
  }
  await settings.set(client, 'last_reset_seed', seed);
  return sportIds;
}

// Hook for M4 (demo fixtures, rosters, extra products). Intentionally empty for now.
async function seedDemoData(client) {} // eslint-disable-line no-unused-vars

async function clearCache() {
  try {
    const redis = getRedis();
    const work = (async () => {
      if (redis.status === 'wait') await redis.connect();
      for (const pattern of ['cache:*', 'lt:*']) {
        let cursor = '0';
        do {
          const [next, keys] = await redis.scan(cursor, 'MATCH', pattern, 'COUNT', 200);
          cursor = next;
          if (keys.length) await redis.del(...keys);
        } while (cursor !== '0');
      }
    })();
    await Promise.race([work, new Promise((r) => setTimeout(r, 1500))]);
  } catch (e) {
    /* Redis is best-effort */
  }
}

async function runReset(parsed) {
  const client = await pool.connect();
  try {
    await client.query('SET statement_timeout = 0');
    await client.query('SELECT pg_advisory_lock(42)');
    try {
      await client.query('BEGIN');
      try {
        const { rows } = await client.query(`SELECT tablename FROM pg_tables WHERE schemaname = 'public'`);
        const tables = rows.map((r) => `"${r.tablename}"`).join(', ');
        await client.query(`TRUNCATE ${tables} RESTART IDENTITY CASCADE`);
        await client.query('ALTER SEQUENCE audit_seq RESTART WITH 1');
        await client.query('ALTER SEQUENCE payment_seq RESTART WITH 1');
        const sportIds = await seedBase(client, parsed);
        await seedDemoData(client);
        await client.query('COMMIT');
        return sportIds;
      } catch (err) {
        await client.query('ROLLBACK');
        throw err;
      }
    } finally {
      await client.query('SELECT pg_advisory_unlock(42)');
    }
  } finally {
    // Reset the session timeout before the connection goes back to the pool.
    await client.query('SET statement_timeout = 4000').catch(() => {});
    client.release();
  }
}

async function reset(body) {
  const parsed = parseBody(body);
  const sportIds = await runReset(parsed);
  await refreshPolicies();
  require('./authService').resetThrottles();
  await clearCache();
  return {
    status: 'reset_complete',
    seed: parsed.seed,
    sports: sportIds,
    tenants: ['tenant-alpha', 'tenant-beta'],
    stock: parsed.stock,
  };
}

module.exports = { reset, seedBase, DEFAULT_SEED };
