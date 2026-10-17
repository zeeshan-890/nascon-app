async function get(db, leagueId) {
  const { rows } = await db.query('SELECT * FROM leagues WHERE league_id = $1', [leagueId]);
  return rows[0] || null;
}

async function insert(db, { league_id, tenant_id = null, sport_id, kind = 'league', name = null, config }) {
  await db.query(
    'INSERT INTO leagues (league_id, tenant_id, sport_id, kind, name, config) VALUES ($1, $2, $3, $4, $5, $6::jsonb)',
    [league_id, tenant_id, sport_id, kind, name, JSON.stringify(config)]
  );
}

async function addTeams(db, leagueId, teams) {
  await db.query(
    `INSERT INTO league_teams (league_id, team_id, position)
     SELECT $1, t.team_id, t.ord - 1 FROM unnest($2::text[]) WITH ORDINALITY AS t(team_id, ord)`,
    [leagueId, teams]
  );
}

module.exports = { get, insert, addTeams };
