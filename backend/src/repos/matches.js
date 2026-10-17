async function get(db, matchId, { forUpdate = false } = {}) {
  const { rows } = await db.query(
    `SELECT * FROM matches WHERE match_id = $1${forUpdate ? ' FOR UPDATE' : ''}`,
    [matchId]
  );
  return rows[0] || null;
}

async function findExistingIds(db, matchIds) {
  if (!matchIds.length) return [];
  const { rows } = await db.query('SELECT match_id FROM matches WHERE match_id = ANY($1::text[])', [matchIds]);
  return rows.map((r) => r.match_id);
}

async function insertFixtures(db, { sportId, leagueId, fixtures }) {
  if (!fixtures.length) return;
  await db.query(
    `INSERT INTO matches (match_id, sport_id, league_id, home_team_id, away_team_id, origin, version)
     SELECT m, $1, $2, h, a, 'fixture', 0
     FROM unnest($3::text[], $4::text[], $5::text[]) AS f(m, h, a)`,
    [sportId, leagueId, fixtures.map((f) => f.match_id), fixtures.map((f) => f.home), fixtures.map((f) => f.away)]
  );
}

// Returns the inserted row, or null when the match_id already exists.
async function insertInit(db, { match_id, sport_id, league_id, home_team_id, away_team_id }) {
  const { rows } = await db.query(
    `INSERT INTO matches (match_id, sport_id, league_id, home_team_id, away_team_id, origin, version)
     VALUES ($1, $2, $3, $4, $5, 'init', 0)
     ON CONFLICT (match_id) DO NOTHING RETURNING match_id`,
    [match_id, sport_id, league_id || null, home_team_id, away_team_id]
  );
  return rows[0] || null;
}

async function bumpVersion(db, matchId) {
  await db.query('UPDATE matches SET version = version + 1 WHERE match_id = $1', [matchId]);
}

module.exports = { get, findExistingIds, insertFixtures, insertInit, bumpVersion };
