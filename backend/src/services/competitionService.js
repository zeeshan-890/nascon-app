const crypto = require('crypto');
const pool = require('../db/pool');
const { withTransaction } = require('../db/tx');
const cache = require('../db/cache');
const AppError = require('../http/AppError');
const { isPlainObject } = require('../http/validators');
const seasons = require('../repos/seasons');
const leagues = require('../repos/leagues');
const matches = require('../repos/matches');
const sports = require('../repos/sports');
const settings = require('../repos/settings');
const outbox = require('../repos/outbox');
const standingsService = require('./standingsService');
const { generateFixtures } = require('./fixturesService');
const { FORMATS } = require('../domain/fixtures/validate');
const { validateSeasonCreate, validateSeasonPatch } = require('../domain/competition/seasons');
const { validateLeagueCreate, validateLeaguePatch } = require('../domain/competition/leagueConfig');
const { fixtureRows, renameBracket } = require('../domain/competition/fixtureRows');
const { splitGroups, seedFromGroups } = require('../domain/competition/groups');
const { promotionRelegation } = require('../domain/competition/promotion');

const MAX_ID = 64;
const bad = (fields) => new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields });
const forbidden = () => new AppError(403, 'FORBIDDEN', 'You do not have permission to perform this action');
const isSuper = (principal) => principal.role === 'super_admin';

// Stable stringify (sorted keys) so a jsonb round trip compares equal.
const canon = (v) => {
  if (Array.isArray(v)) return `[${v.map(canon).join(',')}]`;
  if (v && typeof v === 'object') return `{${Object.keys(v).sort().map((k) => `${JSON.stringify(k)}:${canon(v[k])}`).join(',')}}`;
  return JSON.stringify(v === undefined ? null : v);
};

// The tenant a new season/league belongs to: the caller's own, or (super admin only) the one named in the body.
async function resolveTenant(principal, requested) {
  if (!isSuper(principal)) {
    if (requested && requested !== principal.tenant_id) throw forbidden();
    return principal.tenant_id;
  }
  if (!requested) return null;
  const { rows } = await pool.query('SELECT 1 FROM tenants WHERE tenant_id = $1', [requested]);
  if (!rows[0]) throw new AppError(404, 'TENANT_NOT_FOUND', 'Tenant not found');
  return requested;
}

// A foreign-tenant resource is 403 for tenant users; for a super admin it is a conflicting request.
function sameTenantOr(principal, a, b) {
  if ((a || null) === (b || null)) return;
  if (!isSuper(principal)) throw forbidden();
  throw new AppError(409, 'TENANT_MISMATCH', 'The referenced resource belongs to a different tenant');
}

// ---------- seasons ----------
async function createSeason(principal, body) {
  const { fields, value } = validateSeasonCreate(body);
  if (fields.length) throw bad(fields);
  const tenantId = await resolveTenant(principal, value.tenant_id);
  const record = {
    season_id: value.season_id || `season-${crypto.randomUUID()}`,
    tenant_id: tenantId,
    name: value.name,
    start_date: value.start_date,
    end_date: value.end_date,
  };
  const replay = async () => {
    const found = await seasons.get(pool, record.season_id);
    if (found && canon(found) === canon(record)) return { status: 200, body: found };
    throw new AppError(409, 'SEASON_EXISTS', 'A season with this season_id already exists');
  };
  if (value.season_id && await seasons.get(pool, record.season_id)) return replay();
  try {
    await seasons.insert(pool, record);
  } catch (err) {
    if (err.code === '23505') return replay();
    throw err;
  }
  return { status: 201, body: await seasons.get(pool, record.season_id) };
}

async function patchSeason(seasonId, body) {
  return withTransaction(async (client) => {
    const current = await seasons.get(client, seasonId, { forUpdate: true });
    if (!current) throw new AppError(404, 'SEASON_NOT_FOUND', 'Season not found');
    const { fields, value } = validateSeasonPatch(body, current);
    if (fields.length) throw bad(fields);
    const next = {
      name: value.name !== undefined ? value.name : current.name,
      start_date: value.start_date !== undefined ? value.start_date : current.start_date,
      end_date: value.end_date !== undefined ? value.end_date : current.end_date,
    };
    await seasons.update(client, seasonId, next);
    return { ...current, ...next };
  });
}

// ---------- leagues ----------
const leagueDto = (row, teams) => ({
  league_id: row.league_id,
  tenant_id: row.tenant_id || null,
  name: row.name || row.league_id,
  kind: row.kind,
  sport_id: row.sport_id,
  season_id: row.season_id || null,
  parent_league_id: row.parent_league_id || null,
  teams,
  ...row.config,
  config: row.config,
});

async function getLeague(leagueId) {
  const row = await leagues.get(pool, leagueId);
  if (!row) throw new AppError(404, 'LEAGUE_NOT_FOUND', 'League not found');
  return leagueDto(row, await leagues.teamIds(pool, leagueId));
}

async function createLeague(principal, body) {
  const sportDefaults = async (sportId) => {
    const sport = await sports.get(pool, sportId);
    const lastSeed = await settings.get(pool, 'last_reset_seed');
    return { sport, defaults: { tieBreak: sport && sport.tie_break_order, lotsSeed: Number.isInteger(lastSeed) ? lastSeed : 42 } };
  };
  const sportId = isPlainObject(body) && typeof body.sport_id === 'string' ? body.sport_id : null;
  const { sport, defaults } = await sportDefaults(sportId);
  const { fields, value } = validateLeagueCreate(body, defaults);
  if (fields.length) throw bad(fields);

  if (!sport) throw new AppError(404, 'SPORT_NOT_FOUND', 'Sport not found');
  const season = value.season_id ? await seasons.get(pool, value.season_id) : null;
  if (value.season_id && !season) throw new AppError(404, 'SEASON_NOT_FOUND', 'Season not found');
  const parent = value.parent_league_id ? await leagues.get(pool, value.parent_league_id) : null;
  if (value.parent_league_id && !parent) throw new AppError(404, 'LEAGUE_NOT_FOUND', 'Parent league not found');

  const tenantId = await resolveTenant(principal, value.tenant_id);
  // A super admin who names no tenant inherits the one of the season (or parent league).
  const leagueTenant = isSuper(principal) && !tenantId && (season || parent) ? (season || parent).tenant_id : tenantId;
  if (season) sameTenantOr(principal, season.tenant_id, leagueTenant);
  if (parent) sameTenantOr(principal, parent.tenant_id, leagueTenant);

  const record = {
    league_id: value.league_id,
    tenant_id: leagueTenant || null,
    sport_id: value.sport_id,
    kind: value.kind,
    name: value.name,
    season_id: value.season_id,
    parent_league_id: value.parent_league_id,
    config: value.config,
  };
  const replay = async () => {
    const found = await leagues.get(pool, record.league_id);
    const same = found && canon({
      league_id: found.league_id, tenant_id: found.tenant_id, sport_id: found.sport_id, kind: found.kind, name: found.name,
      season_id: found.season_id, parent_league_id: found.parent_league_id, config: found.config,
      teams: await leagues.teamIds(pool, record.league_id),
    }) === canon({ ...record, teams: value.teams });
    if (same) return { status: 200, body: await getLeague(record.league_id) };
    throw new AppError(409, 'LEAGUE_EXISTS', 'A league with this league_id already exists');
  };
  if (await leagues.get(pool, record.league_id)) return replay();

  try {
    await withTransaction(async (client) => {
      await leagues.insert(client, record);
      await leagues.addTeams(client, record.league_id, value.teams);
      // The creating organizer owns the new league from their next token on.
      if (principal.role === 'league_organizer') {
        await client.query(
          'UPDATE users SET league_ids = array_append(league_ids, $2) WHERE user_id = $1 AND NOT ($2 = ANY(league_ids))',
          [principal.user_id, record.league_id],
        );
      }
      await outbox.enqueue(client, {
        aggregate_type: 'league', aggregate_id: record.league_id, event_type: 'league.created',
        payload: { league_id: record.league_id, tenant_id: record.tenant_id, kind: record.kind },
      });
    });
  } catch (err) {
    if (err.code === '23505') return replay();
    throw err;
  }
  return { status: 201, body: await getLeague(record.league_id) };
}

async function patchLeague(leagueId, body) {
  const out = await withTransaction(async (client) => {
    const row = await leagues.get(client, leagueId, { forUpdate: true });
    if (!row) throw new AppError(404, 'LEAGUE_NOT_FOUND', 'League not found');
    const teams = await leagues.teamIds(client, leagueId);
    const { fields, value } = validateLeaguePatch(body, {
      league_id: leagueId, kind: row.kind, name: row.name, season_id: row.season_id, config: row.config || {}, teamCount: teams.length,
    });
    if (fields.length) throw bad(fields);
    if (value.seasonChanged && value.season_id) {
      const season = await seasons.get(client, value.season_id);
      if (!season) throw new AppError(404, 'SEASON_NOT_FOUND', 'Season not found');
      if ((season.tenant_id || null) !== (row.tenant_id || null)) throw forbidden();
    }
    if (value.hybridChanged && await matches.leagueMatchCount(client, leagueId) > 0) {
      throw new AppError(409, 'FIXTURES_EXIST', 'The group layout cannot change once fixtures exist');
    }
    await leagues.update(client, leagueId, value);
    await outbox.enqueue(client, {
      aggregate_type: 'league', aggregate_id: leagueId, event_type: 'league.updated',
      payload: { league_id: leagueId },
    });
    return leagueDto({ ...row, name: value.name, season_id: value.season_id, config: value.config }, teams);
  });
  await cache.invalidateLeague(leagueId);
  return out;
}

// ---------- fixture generation ----------
function parseGenerateBody(body) {
  if (!isPlainObject(body)) throw bad(['body']);
  const fields = [];
  if (!FORMATS.includes(body.format)) fields.push('format');
  if (body.params !== undefined && !isPlainObject(body.params)) fields.push('params');
  if (body.replace !== undefined && typeof body.replace !== 'boolean') fields.push('replace');
  if (fields.length) throw bad(fields);
  return { format: body.format, params: body.params || {}, replace: body.replace === true };
}

// Hybrid leagues play their group stage: one round robin per group. Everything else uses the whole league.
function generateRows(row, teams, { format, params }) {
  const groupCount = row.kind === 'hybrid' && row.config.hybrid ? row.config.hybrid.groups : 0;
  if (groupCount && format !== 'round_robin') throw bad(['format']);
  const parts = groupCount
    ? splitGroups(teams, groupCount).map((t, i) => ({ teams: t, prefix: `${row.league_id}:G${i + 1}:`, group: i + 1 }))
    : [{ teams, prefix: `${row.league_id}:`, group: null }];
  const rows = [];
  const skipped = { byes: 0, voided: 0, pending: 0 };
  for (const part of parts) {
    const output = generateFixtures({ format, teams: part.teams, params });
    const r = fixtureRows(format, output, (id) => `${part.prefix}${id}`);
    for (const x of r.rows) {
      if (part.group) x.bracket_slot = { ...x.bracket_slot, group: part.group };
      rows.push(x);
    }
    for (const k of Object.keys(skipped)) skipped[k] += r.skipped[k];
  }
  if (rows.some((r) => r.match_id.length > MAX_ID)) throw bad(['league_id']);
  return { rows, skipped };
}

const matchSummary = (r) => ({
  match_id: r.match_id, home_team_id: r.home, away_team_id: r.away, round: r.round, bracket_slot: r.bracket_slot,
});

async function generateLeagueFixtures(leagueId, body) {
  const req = parseGenerateBody(body);
  const peek = await leagues.get(pool, leagueId);
  if (!peek) throw new AppError(404, 'LEAGUE_NOT_FOUND', 'League not found');
  const teams = await leagues.teamIds(pool, leagueId);
  const generated = generateRows({ ...peek, config: peek.config || {} }, teams, req);

  let replaced = false;
  try {
    await withTransaction(async (client) => {
      const row = await leagues.get(client, leagueId, { forUpdate: true });
      const count = await matches.leagueMatchCount(client, leagueId);
      if (count > 0) {
        if (!req.replace) throw new AppError(409, 'FIXTURES_EXIST', 'The league already has fixtures', { match_count: count });
        if (await matches.leagueHasEvents(client, leagueId)) {
          throw new AppError(409, 'FIXTURES_EXIST', 'The league already has fixtures with recorded events', { has_events: true });
        }
        await matches.deleteLeagueMatches(client, leagueId);
        replaced = true;
      }
      await matches.insertGenerated(client, { sportId: row.sport_id, leagueId, rows: generated.rows });
      await outbox.enqueue(client, {
        aggregate_type: 'league', aggregate_id: leagueId, event_type: 'league.fixtures_generated',
        payload: { league_id: leagueId, format: req.format, match_count: generated.rows.length },
      });
    });
  } catch (err) {
    if (err.code === '23505') throw new AppError(409, 'MATCH_EXISTS', 'A match with this match_id already exists');
    throw err;
  }
  await cache.invalidateLeague(leagueId);
  return {
    league_id: leagueId,
    format: req.format,
    replaced,
    match_count: generated.rows.length,
    skipped: generated.skipped,
    matches: generated.rows.map(matchSummary),
  };
}

// ---------- knockout seeding ----------
const FINISHED = ['cancelled', 'abandoned'];
const unresolved = (details) => new AppError(409, 'STANDINGS_UNRESOLVED', 'The group standings are not final yet', details);

async function seedKnockout(leagueId, body) {
  if (body !== undefined && !isPlainObject(body)) throw bad(['body']);
  const input = body || {};
  if (input.third_place_match !== undefined && typeof input.third_place_match !== 'boolean') throw bad(['third_place_match']);

  const out = await withTransaction(async (client) => {
    const row = await leagues.get(client, leagueId, { forUpdate: true });
    if (!row) throw new AppError(404, 'LEAGUE_NOT_FOUND', 'League not found');
    if (!['league', 'hybrid'].includes(row.kind)) {
      throw new AppError(409, 'KNOCKOUT_UNSUPPORTED', 'Only league and hybrid competitions seed a knockout bracket', { kind: row.kind });
    }
    if (await matches.hasKnockout(client, leagueId)) throw new AppError(409, 'KNOCKOUT_EXISTS', 'The knockout bracket is already seeded');

    const state = await standingsService.loadLeagueState(leagueId);
    const cfg = row.config || {};
    const { groups, advance_per_group: advance } = cfg.hybrid || { groups: 1, advance_per_group: cfg.qualification_spots || 1 };
    if (!state.projected.length) throw unresolved({ reason: 'no_group_matches' });
    const pending = state.projected.filter((m) => !FINISHED.includes(m.admin_status) && (m.status !== 'completed' || m.outcome === 'undecided'));
    if (pending.length) throw unresolved({ reason: 'matches_pending', pending: pending.length });
    const groupTeams = splitGroups(state.teams, groups);
    if (groupTeams.some((t) => t.length < advance)) throw unresolved({ reason: 'group_too_small' });
    const tables = groupTeams.map((t) => standingsService.tableOf(state, t, { qualification_spots: advance }).table);
    const seeds = seedFromGroups(tables, advance);
    if (seeds.length < 2) throw unresolved({ reason: 'too_few_qualifiers' });

    const bracket = generateFixtures({
      format: 'single_elim',
      teams: seeds.map((s) => s.team_id),
      params: input.third_place_match === true ? { third_place_match: true } : {},
    });
    const id = (gen) => `${leagueId}:KO-${gen}`;
    const { rows } = fixtureRows('single_elim', bracket, id, 'knockout');
    if (rows.some((r) => r.match_id.length > MAX_ID)) throw bad(['league_id']);
    await matches.insertGenerated(client, { sportId: row.sport_id, leagueId, rows });
    await outbox.enqueue(client, {
      aggregate_type: 'league', aggregate_id: leagueId, event_type: 'league.knockout_seeded',
      payload: { league_id: leagueId, match_count: rows.length },
    });
    return {
      league_id: leagueId,
      bracket_size: bracket.bracket_size,
      seeds,
      rounds: renameBracket(bracket, id).rounds,
      matches: rows.map(matchSummary),
    };
  });
  await cache.invalidateLeague(leagueId);
  return out;
}

// ---------- promotion / relegation ----------
async function getPromotionRelegation(leagueId) {
  const state = await standingsService.loadLeagueState(leagueId);
  const { table } = standingsService.tableOf(state);
  const final = state.projected.length > 0
    && state.projected.every((m) => FINISHED.includes(m.admin_status) || (m.status === 'completed' && m.outcome !== 'undecided'));
  return { league_id: leagueId, ...promotionRelegation(table, state.config, final) };
}

module.exports = {
  createSeason, patchSeason, getLeague, createLeague, patchLeague, generateLeagueFixtures, seedKnockout, getPromotionRelegation,
};
