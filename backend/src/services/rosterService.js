const pool = require('../db/pool');
const { withTransaction } = require('../db/tx');
const AppError = require('../http/AppError');
const { Id, IsoDate } = require('../http/validators');
const { canonical } = require('../domain/crypto');
const roster = require('../domain/roster');
const sports = require('../repos/sports');
const repo = require('../repos/roster');
const outbox = require('../repos/outbox');

const invalid = (fields) => new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields });
const notFound = (code, message) => new AppError(404, code, message);

// Server time at second precision, so the stored value and the returned `when` always match.
const clock = () => new Date(Math.floor(Date.now() / 1000) * 1000);
const iso = (d) => d.toISOString().replace(/\.\d{3}Z$/, 'Z');

function entryView(e) {
  return {
    audit_id: e.audit_id,
    action: e.action,
    player_id: e.player_id,
    league_id: e.league_id,
    from_squad_id: e.from_squad_id,
    to_squad_id: e.to_squad_id,
    effective_date: e.effective_date,
    state: e.state,
    who: e.who,
    when: iso(e.at),
    why: e.why,
  };
}

// Inserts the loan_return entry for every elapsed loan of the player that has none yet.
// Safe to repeat: the partial unique index makes a second insert a no-op.
async function materialiseReturns(client, leagueId, playerId, date, audit) {
  const mine = audit.filter((e) => e.player_id === playerId);
  for (const loan of roster.dueLoanReturns(mine, date)) {
    const inserted = await repo.insertAudit(client, {
      league_id: leagueId,
      player_id: playerId,
      action: 'loan_return',
      from_squad_id: loan.to_squad_id,
      to_squad_id: loan.from_squad_id,
      effective_date: roster.addDays(loan.loan_end_date, 1),
      loan_end_date: null,
      loan_audit_id: loan.audit_id,
      who: 'system',
      why: 'Loan ended',
      at: clock(),
      state: 'active',
    });
    if (inserted) audit.push(inserted);
  }
}

async function setup(body) {
  const { fields } = roster.validateSetup(body);
  if (fields.length) throw invalid(fields);

  const sport = await sports.get(pool, body.league.sport_id);
  if (!sport) throw notFound('SPORT_NOT_FOUND', 'Sport not found');
  const defaults = sport.roster || {};

  const league = {
    league_id: body.league.league_id,
    tenant_id: body.league.tenant_id,
    sport_id: body.league.sport_id,
    window_open: body.league.transfer_window.open,
    window_close: body.league.transfer_window.close,
    max_squad_size: body.league.max_squad_size != null ? body.league.max_squad_size : defaults.max_squad_size,
    required_positions: body.league.required_positions != null ? body.league.required_positions : defaults.required_positions || {},
  };
  const squads = body.squads;
  const players = body.players;
  const matches = body.matches_in_progress || [];

  return withTransaction(async (client) => {
    await repo.lockLeague(client, league.league_id);
    await repo.wipeLeague(client, league.league_id);
    await repo.insertLeague(client, league);
    await repo.insertSquads(client, league.league_id, squads);
    await repo.insertInitial(client, league.league_id, players);
    await repo.insertMatches(client, league.league_id, matches, players);
    const state = roster.snapshot(players, [], roster.todayUtc());
    await repo.writeRegistrations(client, league.league_id, state);
    return {
      league_id: league.league_id,
      status: 'configured',
      squad_counts: roster.squadCounts(state, squads.map((s) => s.squad_id)),
    };
  });
}

function replayOf(stored, canon) {
  if (stored.request_canonical !== canon) {
    throw new AppError(409, 'DUPLICATE_REQUEST_ID', 'request_id was already used with a different body');
  }
  return { status: 200, body: stored.response };
}

async function transfer(body) {
  const { fields } = roster.validateTransfer(body);
  if (fields.length) throw invalid(fields);
  const canon = canonical(roster.transferFingerprint(body));

  const early = await repo.getRequest(pool, body.request_id);
  if (early) return replayOf(early, canon);

  const date = body.effective_date;
  return withTransaction(async (client) => {
    await repo.lockLeague(client, body.league_id);
    // A parallel identical request may have finished while this one waited for the lock.
    const stored = await repo.getRequest(client, body.request_id);
    if (stored) return replayOf(stored, canon);

    const league = await repo.getLeague(client, body.league_id);
    if (!league) throw notFound('LEAGUE_NOT_FOUND', 'League not found');
    if (!(await repo.hasPlayer(client, body.league_id, body.player_id))) {
      throw notFound('PLAYER_NOT_FOUND', 'Player not found in this league');
    }
    const squads = new Map((await repo.listSquads(client, body.league_id)).map((s) => [s.squad_id, s]));
    const fromSquad = squads.get(body.from_squad_id);
    if (!fromSquad) throw notFound('SQUAD_NOT_FOUND', 'Squad not found in this league');
    const isRelease = body.type === 'release';
    const toSquad = isRelease ? null : squads.get(body.to_squad_id);
    if (!isRelease && !toSquad) throw notFound('SQUAD_NOT_FOUND', 'Squad not found in this league');

    const crossTenant = roster.checkCrossTenant(league, fromSquad, toSquad, body.type);
    if (crossTenant) throw new AppError(crossTenant.status, crossTenant.code, crossTenant.message);

    const setupRows = await repo.listInitial(client, body.league_id);
    const audit = await repo.listAudit(client, body.league_id);
    await materialiseReturns(client, body.league_id, body.player_id, date, audit);

    const req = { ...body, to_squad_id: isRelease ? null : body.to_squad_id };
    const rule = roster.checkRules({
      league: { ...league, required_positions: league.required_positions || {} },
      req,
      state: roster.snapshot(setupRows, audit, date),
    });
    if (rule) throw new AppError(rule.status, rule.code, rule.message);

    const blocking = roster.blockingMatch(await repo.listLiveMatches(client, body.league_id), body.from_squad_id);
    const entry = await repo.insertAudit(client, {
      league_id: body.league_id,
      player_id: body.player_id,
      action: body.type,
      from_squad_id: body.from_squad_id,
      to_squad_id: req.to_squad_id,
      effective_date: date,
      loan_end_date: body.type === 'loan' ? body.loan_end_date : null,
      who: body.requested_by,
      why: body.reason,
      at: clock(),
      state: blocking ? 'deferred_until_match_end' : 'active',
    });
    audit.push(entry);

    const after = roster.snapshot(setupRows, audit, date).get(body.player_id);
    const response = {
      request_id: body.request_id,
      status: 'accepted',
      state: entry.state,
      blocking_match_id: blocking,
      registration: roster.buildRegistration(req, body.league_id, after),
      audit_entry: entryView(entry),
    };
    await repo.writeRegistrations(client, body.league_id, roster.snapshot(setupRows, audit, roster.todayUtc()));
    await repo.insertRequest(client, {
      request_id: body.request_id, league_id: body.league_id, request_canonical: canon, response, status: 201,
    });
    await outbox.enqueue(client, {
      aggregate_type: 'roster',
      aggregate_id: body.league_id,
      event_type: 'roster.changed',
      payload: { league_id: body.league_id, player_id: body.player_id, audit_id: entry.audit_id, action: body.type },
    });
    return { status: 201, body: response };
  });
}

function parseHistoryParams(playerId, asOf) {
  const bad = [];
  if (!Id.safeParse(playerId).success) bad.push('player_id');
  if (asOf !== undefined && !(typeof asOf === 'string' && IsoDate.safeParse(asOf).success)) bad.push('as_of');
  if (bad.length) throw invalid(bad);
}

async function history(playerId, asOf) {
  parseHistoryParams(playerId, asOf);
  const date = asOf === undefined ? roster.todayUtc() : asOf;
  const leagueId = await repo.latestLeagueOfPlayer(pool, playerId);
  if (!leagueId) throw notFound('PLAYER_NOT_FOUND', 'Player not found');

  return withTransaction(async (client) => {
    await repo.lockLeague(client, leagueId);
    if (!(await repo.hasPlayer(client, leagueId, playerId))) throw notFound('PLAYER_NOT_FOUND', 'Player not found');
    const setupRows = await repo.listInitial(client, leagueId);
    const audit = await repo.listAudit(client, leagueId);
    await materialiseReturns(client, leagueId, playerId, date, audit);

    const current = roster.snapshot(setupRows, audit, date).get(playerId);
    const mine = audit
      .filter((e) => e.player_id === playerId && !(e.action === 'loan_return' && e.effective_date > date))
      .map(entryView);
    return {
      player_id: playerId,
      as_of: date,
      league_id: leagueId,
      current_squad_id: current.squad_id,
      parent_squad_id: current.parent_squad_id,
      history: mine,
    };
  });
}

module.exports = { setup, transfer, history };
