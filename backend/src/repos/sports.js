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

module.exports = { get, insert };
