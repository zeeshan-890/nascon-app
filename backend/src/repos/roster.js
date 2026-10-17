const AUDIT_COLS = `audit_id, audit_seq, league_id, player_id, action, from_squad_id, to_squad_id,
  effective_date::text AS effective_date, loan_end_date::text AS loan_end_date, loan_audit_id, who, why, at, state, role, position`;

// All roster work for a league is serialised on this transaction-scoped advisory lock.
async function lockLeague(client, leagueId) {
  await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`roster:${leagueId}`]);
}

async function getLeague(db, leagueId) {
  const { rows } = await db.query(
    `SELECT league_id, tenant_id, sport_id, window_open::text AS window_open, window_close::text AS window_close,
            max_squad_size, required_positions
     FROM roster_leagues WHERE league_id = $1`,
    [leagueId]
  );
  return rows[0] || null;
}

const LEAGUE_TABLES = ['roster_audit', 'roster_requests', 'roster_live_matches', 'roster_matches',
  'roster_initial', 'registrations', 'roster_squads', 'roster_leagues'];

async function wipeLeague(client, leagueId) {
  // roster_audit is append-only; this flag lets the trigger allow the per-league wipe of setup.
  await client.query("SELECT set_config('app.roster_reset', 'on', true)");
  for (const table of LEAGUE_TABLES) {
    await client.query(`DELETE FROM ${table} WHERE league_id = $1`, [leagueId]);
  }
}

async function insertLeague(client, l) {
  await client.query(
    `INSERT INTO roster_leagues (league_id, tenant_id, sport_id, window_open, window_close, max_squad_size, required_positions)
     VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb)`,
    [l.league_id, l.tenant_id, l.sport_id, l.window_open, l.window_close, l.max_squad_size, JSON.stringify(l.required_positions)]
  );
}

async function insertSquads(client, leagueId, squads) {
  if (!squads.length) return;
  await client.query(
    `INSERT INTO roster_squads (league_id, squad_id, club_id, tenant_id, name)
     SELECT $1, s, c, t, n FROM unnest($2::text[], $3::text[], $4::text[], $5::text[]) AS x(s, c, t, n)`,
    [leagueId, squads.map((s) => s.squad_id), squads.map((s) => s.club_id), squads.map((s) => s.tenant_id), squads.map((s) => s.name)]
  );
}

async function insertInitial(client, leagueId, players) {
  if (!players.length) return;
  await client.query(
    `INSERT INTO roster_initial (league_id, squad_id, player_id, role, position)
     SELECT $1, s, p, r, pos FROM unnest($2::text[], $3::text[], $4::text[], $5::text[]) AS x(s, p, r, pos)`,
    [leagueId, players.map((p) => p.squad_id), players.map((p) => p.player_id), players.map((p) => p.role), players.map((p) => p.position)]
  );
  await client.query(
    `INSERT INTO players (player_id, name, position)
     SELECT p, n, pos FROM unnest($1::text[], $2::text[], $3::text[]) AS x(p, n, pos)
     ON CONFLICT (player_id) DO UPDATE SET name = EXCLUDED.name, position = EXCLUDED.position`,
    [players.map((p) => p.player_id), players.map((p) => p.name), players.map((p) => p.position)]
  );
}

async function insertMatches(client, leagueId, matches, players) {
  if (!matches.length) return;
  await client.query(
    `INSERT INTO roster_matches (league_id, match_id, home_squad_id, away_squad_id)
     SELECT $1, m, h, a FROM unnest($2::text[], $3::text[], $4::text[]) AS x(m, h, a)`,
    [leagueId, matches.map((m) => m.match_id), matches.map((m) => m.home_squad_id), matches.map((m) => m.away_squad_id)]
  );
  // Players registered for each live match at setup stay eligible for it under their old squad.
  const rows = [];
  for (const m of matches) {
    for (const p of players) {
      if (p.squad_id !== null && (p.squad_id === m.home_squad_id || p.squad_id === m.away_squad_id)) {
        rows.push([m.match_id, p.player_id]);
      }
    }
  }
  if (rows.length) {
    await client.query(
      `INSERT INTO roster_live_matches (league_id, match_id, player_id)
       SELECT $1, m, p FROM unnest($2::text[], $3::text[]) AS x(m, p)`,
      [leagueId, rows.map((r) => r[0]), rows.map((r) => r[1])]
    );
  }
}

async function listSquads(db, leagueId) {
  const { rows } = await db.query(
    'SELECT squad_id, club_id, tenant_id, name FROM roster_squads WHERE league_id = $1 ORDER BY squad_id',
    [leagueId]
  );
  return rows;
}

async function listInitial(db, leagueId) {
  const { rows } = await db.query(
    'SELECT player_id, squad_id, role, position FROM roster_initial WHERE league_id = $1',
    [leagueId]
  );
  return rows;
}

async function listLiveMatches(db, leagueId) {
  const { rows } = await db.query(
    'SELECT match_id, home_squad_id, away_squad_id FROM roster_matches WHERE league_id = $1',
    [leagueId]
  );
  return rows;
}

async function listAudit(db, leagueId) {
  const { rows } = await db.query(
    `SELECT ${AUDIT_COLS} FROM roster_audit WHERE league_id = $1 ORDER BY audit_seq`,
    [leagueId]
  );
  return rows;
}

// Players added after setup join the initial list without a squad; their `add` entry places them.
async function addInitialPlayer(client, leagueId, p) {
  await client.query(
    `INSERT INTO roster_initial (league_id, squad_id, player_id, role, position) VALUES ($1, NULL, $2, $3, $4)
     ON CONFLICT (league_id, player_id) DO NOTHING`,
    [leagueId, p.player_id, p.role, p.position],
  );
  await client.query(
    `INSERT INTO players (player_id, name, position, club_id, tenant_id) VALUES ($1, $2, $3, $4, $5)
     ON CONFLICT (player_id) DO UPDATE SET name = EXCLUDED.name, position = EXCLUDED.position`,
    [p.player_id, p.name, p.position, p.club_id, p.tenant_id],
  );
}

async function hasPlayer(db, leagueId, playerId) {
  const { rows } = await db.query('SELECT 1 FROM roster_initial WHERE league_id = $1 AND player_id = $2', [leagueId, playerId]);
  return rows.length > 0;
}

// League of the player's most recent setup.
async function latestLeagueOfPlayer(db, playerId) {
  const { rows } = await db.query(
    `SELECT rl.league_id FROM roster_initial ri JOIN roster_leagues rl ON rl.league_id = ri.league_id
     WHERE ri.player_id = $1 ORDER BY rl.setup_no DESC LIMIT 1`,
    [playerId]
  );
  return rows.length ? rows[0].league_id : null;
}

async function nextAuditNumber(client) {
  const { rows } = await client.query("SELECT nextval('audit_seq') AS n");
  return Number(rows[0].n);
}

// Returns the inserted entry (with its audit id) or null when a loan return already exists.
async function insertAudit(client, e) {
  const n = await nextAuditNumber(client);
  const auditId = `au-${String(n).padStart(4, '0')}`;
  const { rowCount } = await client.query(
    `INSERT INTO roster_audit (audit_id, audit_seq, league_id, player_id, action, from_squad_id, to_squad_id,
       effective_date, loan_end_date, loan_audit_id, who, why, at, state, role, position)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16)
     ON CONFLICT DO NOTHING`,
    [auditId, n, e.league_id, e.player_id, e.action, e.from_squad_id, e.to_squad_id, e.effective_date,
      e.loan_end_date || null, e.loan_audit_id || null, e.who, e.why, e.at, e.state, e.role || null, e.position || null]
  );
  return rowCount ? { ...e, audit_id: auditId, audit_seq: n } : null;
}

// Rewrites the registrations mirror from a replayed snapshot (Map player_id -> state).
async function writeRegistrations(client, leagueId, state) {
  await client.query('DELETE FROM registrations WHERE league_id = $1', [leagueId]);
  const list = [...state.values()];
  if (!list.length) return;
  await client.query(
    `INSERT INTO registrations (league_id, player_id, squad_id, parent_squad_id, registration_type, start_date, loan_end_date, role)
     SELECT $1, p, s, ps, rt, sd::date, le::date, r
     FROM unnest($2::text[], $3::text[], $4::text[], $5::text[], $6::text[], $7::text[], $8::text[]) AS x(p, s, ps, rt, sd, le, r)`,
    [leagueId, list.map((s) => s.player_id), list.map((s) => s.squad_id), list.map((s) => s.parent_squad_id),
      list.map((s) => s.registration_type), list.map((s) => s.start_date), list.map((s) => s.loan_end_date), list.map((s) => s.role)]
  );
}

async function getRequest(db, requestId) {
  const { rows } = await db.query('SELECT request_canonical, response, status FROM roster_requests WHERE request_id = $1', [requestId]);
  return rows[0] || null;
}

async function insertRequest(client, { request_id, league_id, request_canonical, response, status }) {
  await client.query(
    'INSERT INTO roster_requests (request_id, league_id, request_canonical, response, status) VALUES ($1, $2, $3, $4::json, $5)',
    [request_id, league_id, request_canonical, JSON.stringify(response), status]
  );
}

module.exports = {
  lockLeague, getLeague, wipeLeague, insertLeague, insertSquads, insertInitial, insertMatches, listSquads,
  listInitial, listLiveMatches, listAudit, addInitialPlayer, hasPlayer, latestLeagueOfPlayer, insertAudit,
  writeRegistrations, getRequest, insertRequest,
};
