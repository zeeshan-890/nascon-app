const crypto = require('crypto');
const { z } = require('zod');
const pool = require('../db/pool');
const { withTransaction } = require('../db/tx');
const AppError = require('../http/AppError');
const { Id, SportId, Int, IsoDate, parseOr400, requireObjectBody } = require('../http/validators');
const sports = require('../repos/sports');

// League management for the mobile app. Organizer-created leagues start 'pending' and stay hidden from
// every public list until a super admin approves them; super-admin-created leagues are active at once.
const FORMATS = ['round_robin', 'swiss', 'single_elimination', 'double_elimination', 'hybrid'];
const DEFAULT_TENANT = 'tenant-alpha';

const LeagueRequest = z.object({
  name: z.string().trim().min(1).max(60),
  sport_id: SportId,
  format: z.enum(FORMATS),
  transfer_window_opens: IsoDate.nullable().optional(),
  transfer_window_closes: IsoDate.nullable().optional(),
  max_squad_size: Int.refine((n) => n >= 1 && n <= 99).optional(),
  tenant_id: Id.optional(),
});

const forbidden = () => new AppError(403, 'FORBIDDEN', 'You do not have permission to perform this action');
const notFound = () => new AppError(404, 'LEAGUE_NOT_FOUND', 'League not found');

const SELECT = `
  SELECT l.league_id, l.tenant_id, COALESCE(l.name, l.league_id) AS name, l.sport_id, l.status, l.created_by,
         l.config, COALESCE(s.name, l.config->>'season') AS season,
         (SELECT count(*)::int FROM league_teams t WHERE t.league_id = l.league_id) AS team_count,
         (SELECT count(*)::int FROM matches m WHERE m.league_id = l.league_id) AS match_count
  FROM leagues l LEFT JOIN seasons s ON s.season_id = l.season_id`;

function toDto(r) {
  const c = r.config || {};
  return {
    league_id: r.league_id,
    name: r.name,
    sport_id: r.sport_id,
    season: r.season || null,
    format: c.format || 'round_robin',
    status: r.status,
    team_count: r.team_count,
    player_count: 0,
    match_count: r.match_count,
    division_count: 0,
    transfer_window_opens: c.transfer_window_opens || null,
    transfer_window_closes: c.transfer_window_closes || null,
    max_squad_size: c.max_squad_size || 25,
  };
}

// Ownership is read from the database (not the token) so a league created a moment ago already counts.
const owns = (principal, row) => Array.isArray(principal.league_ids) && principal.league_ids.includes(row.league_id)
  || row.created_by === principal.user_id;

function canSee(principal, row) {
  if (principal.role === 'super_admin') return true;
  return principal.role === 'league_organizer' && principal.tenant_id === row.tenant_id && owns(principal, row);
}

function checkWindow(body) {
  const { transfer_window_opens: o, transfer_window_closes: c } = body;
  const fields = [];
  if ((o == null) !== (c == null)) fields.push('transfer_window_opens', 'transfer_window_closes');
  else if (o && c && c <= o) fields.push('transfer_window_closes');
  if (fields.length) throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields });
}

async function fetchRow(id, db = pool) {
  const { rows } = await db.query(`${SELECT} WHERE l.league_id = $1`, [id]);
  if (!rows[0]) throw notFound();
  return rows[0];
}

async function list(principal) {
  if (principal.role !== 'super_admin' && principal.role !== 'league_organizer') throw forbidden();
  const { rows } = await pool.query(`${SELECT} ORDER BY l.created_at DESC, l.league_id`);
  return rows.filter((r) => canSee(principal, r)).map(toDto);
}

async function get(principal, id) {
  const row = await fetchRow(id);
  if (!canSee(principal, row)) throw forbidden();
  return toDto(row);
}

function newId(name) {
  const slug = name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 30) || 'league';
  return `lg-${slug}-${crypto.randomBytes(3).toString('hex')}`;
}

async function create(principal, rawBody) {
  const body = parseOr400(LeagueRequest, requireObjectBody(rawBody));
  checkWindow(body);
  const isAdmin = principal.role === 'super_admin';
  const tenantId = isAdmin ? body.tenant_id || DEFAULT_TENANT : principal.tenant_id;
  if (!tenantId) throw forbidden();
  const sport = await sports.get(pool, body.sport_id);
  if (!sport) throw new AppError(404, 'SPORT_NOT_FOUND', 'Sport not found');
  if (isAdmin) {
    const { rows } = await pool.query('SELECT 1 FROM tenants WHERE tenant_id = $1', [tenantId]);
    if (!rows.length) throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields: ['tenant_id'] });
  }

  const id = newId(body.name);
  const config = {
    points: { win: 3, draw: 1, loss: 0 },
    qualification_spots: 1,
    format: body.format,
    transfer_window_opens: body.transfer_window_opens || null,
    transfer_window_closes: body.transfer_window_closes || null,
    max_squad_size: body.max_squad_size || 25,
  };
  const status = isAdmin ? 'active' : 'pending';
  await withTransaction(async (client) => {
    await client.query(
      `INSERT INTO leagues (league_id, tenant_id, sport_id, kind, name, config, status, created_by)
       VALUES ($1, $2, $3, 'league', $4, $5::jsonb, $6, $7)`,
      [id, tenantId, body.sport_id, body.name, JSON.stringify(config), status, principal.user_id],
    );
    // A refreshed token then carries the new league in league_ids.
    if (!isAdmin) {
      await client.query('UPDATE users SET league_ids = array_append(league_ids, $2) WHERE user_id = $1', [principal.user_id, id]);
    }
  });
  return toDto(await fetchRow(id));
}

async function update(principal, id, rawBody) {
  const body = parseOr400(LeagueRequest, requireObjectBody(rawBody));
  checkWindow(body);
  const row = await fetchRow(id);
  if (!canSee(principal, row)) throw forbidden();
  if (!(await sports.get(pool, body.sport_id))) throw new AppError(404, 'SPORT_NOT_FOUND', 'Sport not found');
  const config = {
    ...row.config,
    format: body.format,
    transfer_window_opens: body.transfer_window_opens || null,
    transfer_window_closes: body.transfer_window_closes || null,
    max_squad_size: body.max_squad_size || row.config.max_squad_size || 25,
  };
  await pool.query(
    'UPDATE leagues SET name = $2, sport_id = $3, config = $4::jsonb WHERE league_id = $1',
    [id, body.name, body.sport_id, JSON.stringify(config)],
  );
  return toDto(await fetchRow(id));
}

// pending -> active | rejected. Anything else is an illegal transition.
async function review(principal, id, decision) {
  const status = decision === 'approve' ? 'active' : 'rejected';
  const { rows } = await pool.query(
    `UPDATE leagues SET status = $2, reviewed_by = $3, reviewed_at = now()
     WHERE league_id = $1 AND status = 'pending' RETURNING league_id`,
    [id, status, principal.user_id],
  );
  if (!rows.length) {
    const row = await fetchRow(id);
    throw new AppError(409, 'ILLEGAL_TRANSITION', `A ${row.status} league cannot be ${decision}d`, { status: row.status });
  }
  return toDto(await fetchRow(id));
}

// The league row for someone allowed to manage it (super admin, or the organizer who owns it); 403 for everyone else.
async function manageable(principal, id, { active = false } = {}) {
  if (principal.role !== 'super_admin' && principal.role !== 'league_organizer') throw forbidden();
  const row = await fetchRow(id);
  if (!canSee(principal, row)) throw forbidden();
  if (active && row.status !== 'active') {
    throw new AppError(409, 'LEAGUE_NOT_ACTIVE', 'This league is not approved yet', { status: row.status });
  }
  return row;
}

module.exports = { list, get, create, update, review, manageable };
