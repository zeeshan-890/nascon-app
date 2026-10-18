const COLUMNS = `season_id, tenant_id, name, to_char(start_date, 'YYYY-MM-DD') AS start_date, to_char(end_date, 'YYYY-MM-DD') AS end_date`;

async function get(db, seasonId, { forUpdate = false } = {}) {
  const { rows } = await db.query(`SELECT ${COLUMNS} FROM seasons WHERE season_id = $1${forUpdate ? ' FOR UPDATE' : ''}`, [seasonId]);
  return rows[0] || null;
}

async function list(db, tenantScope, { limit, offset }) {
  const { rows } = await db.query(
    `SELECT ${COLUMNS}, count(*) OVER ()::int AS total FROM seasons
     WHERE ($1::text IS NULL OR tenant_id = $1) ORDER BY start_date DESC NULLS LAST, season_id LIMIT $2 OFFSET $3`,
    [tenantScope, limit, offset]
  );
  return { total: rows.length ? rows[0].total : 0, items: rows.map(({ total, ...r }) => r) };
}

async function insert(db, { season_id, tenant_id, name, start_date, end_date }) {
  await db.query(
    'INSERT INTO seasons (season_id, tenant_id, name, start_date, end_date) VALUES ($1, $2, $3, $4, $5)',
    [season_id, tenant_id, name, start_date, end_date]
  );
}

async function update(db, seasonId, { name, start_date, end_date }) {
  await db.query('UPDATE seasons SET name = $2, start_date = $3, end_date = $4 WHERE season_id = $1', [seasonId, name, start_date, end_date]);
}

module.exports = { get, list, insert, update };
