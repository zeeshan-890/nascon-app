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

// Generated fixtures: rows carry the round and optionally a kick-off time and/or a bracket slot (jsonb).
async function insertGenerated(db, { sportId, leagueId, rows }) {
  if (!rows.length) return;
  await db.query(
    `INSERT INTO matches (match_id, sport_id, league_id, home_team_id, away_team_id, origin, version, round, scheduled_at, bracket_slot)
     SELECT f.m, $1, $2, f.h, f.a, 'fixture', 0, f.r, f.s::timestamptz, f.b::jsonb
     FROM unnest($3::text[], $4::text[], $5::text[], $6::int[], $7::text[], $8::text[]) AS f(m, h, a, r, s, b)`,
    [sportId, leagueId, rows.map((x) => x.match_id), rows.map((x) => x.home), rows.map((x) => x.away),
      rows.map((x) => (x.round === undefined ? null : x.round)),
      rows.map((x) => x.scheduled_at || null),
      rows.map((x) => (x.bracket_slot ? JSON.stringify(x.bracket_slot) : null))],
  );
}

async function leagueMatchCount(db, leagueId) {
  const { rows } = await db.query('SELECT count(*)::int AS n FROM matches WHERE league_id = $1', [leagueId]);
  return rows[0].n;
}

async function leagueHasEvents(db, leagueId) {
  const { rows } = await db.query(
    `SELECT EXISTS (SELECT 1 FROM match_events e JOIN matches m ON m.match_id = e.match_id WHERE m.league_id = $1) AS has`,
    [leagueId]
  );
  return rows[0].has;
}

// Only valid while no match of the league has events (events are append-only).
async function deleteLeagueMatches(db, leagueId) {
  await db.query('DELETE FROM match_pins WHERE match_id IN (SELECT match_id FROM matches WHERE league_id = $1)', [leagueId]);
  await db.query('DELETE FROM matches WHERE league_id = $1', [leagueId]);
}

async function hasKnockout(db, leagueId) {
  const { rows } = await db.query(
    `SELECT EXISTS (SELECT 1 FROM matches WHERE league_id = $1 AND bracket_slot->>'stage' = 'knockout') AS has`,
    [leagueId]
  );
  return rows[0].has;
}

module.exports = {
  get, findExistingIds, insertFixtures, insertInit, bumpVersion,
  insertGenerated, leagueMatchCount, leagueHasEvents, deleteLeagueMatches, hasKnockout,
};
