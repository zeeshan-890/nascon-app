const pool = require('../db/pool');
const AppError = require('./AppError');
const { Id } = require('./validators');

function idParam(req, name, field = name) {
  const v = req.params[name];
  if (!Id.safeParse(v).success) {
    throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields: [field] });
  }
  return v;
}

const notFound = (code, message) => new AppError(404, code, message);

async function one(sql, params, code, message) {
  const { rows } = await pool.query(sql, params);
  if (!rows[0]) throw notFound(code, message);
  return rows[0];
}

const leagueResolver = (param = 'id') => async (req) => one(
  'SELECT league_id, tenant_id, sport_id FROM leagues WHERE league_id = $1', [idParam(req, param, 'league_id')],
  'LEAGUE_NOT_FOUND', 'League not found',
);

// A stand-alone match (no league) is open for reads and has no owner, so only a super admin may write.
const matchResolver = (param = 'id', { read = false } = {}) => async (req) => {
  const row = await one(
    `SELECT m.match_id, m.league_id, m.sport_id, m.admin_status, l.tenant_id
     FROM matches m LEFT JOIN leagues l ON l.league_id = m.league_id WHERE m.match_id = $1`,
    [idParam(req, param, 'match_id')], 'MATCH_NOT_FOUND', 'Match not found',
  );
  req.match = row;
  return { ...row, open: read && !row.league_id };
};

const clubResolver = (param = 'id') => async (req) => one(
  'SELECT club_id, tenant_id FROM clubs WHERE club_id = $1', [idParam(req, param, 'club_id')],
  'NOT_FOUND', 'Club not found',
);

const playerResolver = (param = 'id') => async (req) => one(
  `SELECT p.player_id, p.club_id, p.tenant_id FROM players p WHERE p.player_id = $1
   UNION ALL
   SELECT r.player_id, s.club_id, s.tenant_id FROM registrations r JOIN squads s ON s.squad_id = r.squad_id
   WHERE r.player_id = $1 LIMIT 1`,
  [idParam(req, param, 'player_id')], 'PLAYER_NOT_FOUND', 'Player not found',
);

// Owner is the customer; the club is the one that sells the first item (used by order.manage).
const orderResolver = (param = 'id') => async (req) => {
  const row = await one(
    `SELECT o.order_id, o.tenant_id, o.customer_id AS user_id,
            (SELECT p.club_id FROM order_items i JOIN variants v ON v.variant_id = i.variant_id
             JOIN products p ON p.product_id = v.product_id WHERE i.order_id = o.order_id
             ORDER BY i.variant_id LIMIT 1) AS club_id
     FROM orders o WHERE o.order_id = $1`,
    [idParam(req, param, 'order_id')], 'NOT_FOUND', 'Order not found',
  );
  req.order = row;
  return row;
};

// Collection endpoints are scoped by the query itself (tenant filter), so the guard only checks the role.
const openResource = () => ({ open: true });

module.exports = { openResource, idParam, leagueResolver, matchResolver, clubResolver, playerResolver, orderResolver };
