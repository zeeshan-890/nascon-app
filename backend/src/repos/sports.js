async function get(db, sportId) {
  const { rows } = await db.query('SELECT config FROM sports WHERE sport_id = $1', [sportId]);
  return rows.length ? rows[0].config : null;
}

async function insert(db, config) {
  await db.query(
    'INSERT INTO sports (sport_id, name, scoring_model, config) VALUES ($1, $2, $3, $4::jsonb)',
    [config.sport_id, config.name, config.scoring_model, JSON.stringify(config)]
  );
}

async function list(db, { limit, offset }) {
  const { rows } = await db.query(
    'SELECT config, count(*) OVER ()::int AS total FROM sports ORDER BY sport_id LIMIT $1 OFFSET $2', [limit, offset]
  );
  return { total: rows.length ? rows[0].total : 0, items: rows.map((r) => r.config) };
}

module.exports = { get, insert, list };
