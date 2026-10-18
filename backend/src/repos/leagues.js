async function get(db, leagueId, { forUpdate = false } = {}) {
  const { rows } = await db.query(`SELECT * FROM leagues WHERE league_id = $1${forUpdate ? ' FOR UPDATE' : ''}`, [leagueId]);
  return rows[0] || null;
}

async function insert(db, { league_id, tenant_id = null, sport_id, kind = 'league', name = null, season_id = null, parent_league_id = null, config }) {
  await db.query(
    `INSERT INTO leagues (league_id, tenant_id, sport_id, kind, name, season_id, parent_league_id, config)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8::jsonb)`,
    [league_id, tenant_id, sport_id, kind, name, season_id, parent_league_id, JSON.stringify(config)]
  );
}

async function update(db, leagueId, { name, season_id, config }) {
  await db.query(
    'UPDATE leagues SET name = $2, season_id = $3, config = $4::jsonb WHERE league_id = $1',
    [leagueId, name, season_id, JSON.stringify(config)]
  );
}

// Team ids in seed (position) order.
async function teamIds(db, leagueId) {
  const { rows } = await db.query('SELECT team_id FROM league_teams WHERE league_id = $1 ORDER BY position, team_id', [leagueId]);
  return rows.map((r) => r.team_id);
}

async function addTeams(db, leagueId, teams) {
  await db.query(
    `INSERT INTO league_teams (league_id, team_id, position)
     SELECT $1, t.team_id, t.ord - 1 FROM unnest($2::text[]) WITH ORDINALITY AS t(team_id, ord)`,
    [leagueId, teams]
  );
}

module.exports = { get, insert, update, addTeams, teamIds };
