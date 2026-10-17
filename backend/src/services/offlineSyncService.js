const pool = require('../db/pool');
const AppError = require('../http/AppError');
const { canonical } = require('../domain/crypto');
const roster = require('../domain/roster');
const classify = require('../domain/offline/classify');
const eventService = require('./eventService');
const rosterService = require('./rosterService');
const sports = require('../repos/sports');
const leagues = require('../repos/leagues');
const matches = require('../repos/matches');
const events = require('../repos/events');
const rosterRepo = require('../repos/roster');

// Raised for every per-action rejection; carries the error code reported to the device.
class Rejection extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}

const isoDate = /^\d{4}-\d{2}-\d{2}$/;

// Current version of the entity an action targets; 0 when it does not exist or cannot be resolved.
async function readVersion(client, action) {
  const p = action.payload;
  try {
    if (action.type === 'score_event') {
      const m = typeof p.match_id === 'string' ? await matches.get(client, p.match_id) : null;
      return m ? m.version : 0;
    }
    const usable = typeof p.league_id === 'string' && typeof p.player_id === 'string'
      && typeof p.effective_date === 'string' && isoDate.test(p.effective_date);
    if (!usable) return 0;
    if (!(await rosterRepo.getLeague(client, p.league_id))) return 0;
    if (!(await rosterRepo.hasPlayer(client, p.league_id, p.player_id))) return 0;
    return await rosterService.playerVersion(client, p.league_id, p.player_id, p.effective_date);
  } catch (err) {
    return 0;
  }
}

async function record(client, deviceId, action, result) {
  await client.query(
    `INSERT INTO offline_actions (device_id, action_id, type, payload, result)
     VALUES ($1, $2, $3, $4::jsonb, $5::jsonb) ON CONFLICT (device_id, action_id) DO NOTHING`,
    [deviceId, action.action_id, action.type, JSON.stringify(action.payload), JSON.stringify(result)]
  );
}

const applied = (resolution, serverVersion, signature = null) => ({
  status: 'applied', resolution, error_code: null, server_version: serverVersion, signature,
});
const duplicate = (serverVersion) => ({
  status: 'duplicate', resolution: 'skipped_duplicate', error_code: null, server_version: serverVersion, signature: null,
});

function checkBase(action, current) {
  const resolution = classify.resolutionFor(action.base_version, current);
  if (!resolution) throw new Rejection('INVALID_BASE_VERSION');
  return resolution;
}

async function authorizeOrReject(authorize, ctx) {
  if (authorize && !(await authorize(ctx))) throw new Rejection('FORBIDDEN');
}

async function applyScoreEvent(client, action, authorize) {
  const req = eventService.parseEventRequest(action.payload);
  const existing = await events.findById(client, req.event_id);
  if (existing && existing.request_canonical === eventService.canonicalOf(req)) {
    const known = await matches.get(client, req.match_id);
    return { dup: true, version: known ? known.version : 0 };
  }
  const match = await matches.get(client, req.match_id);
  let leagueId = match ? match.league_id : null;
  if (req.type === 'match_init') {
    if (!(await sports.get(client, req.payload.sport_id))) throw new Rejection('SPORT_NOT_FOUND');
    if (req.payload.league_id && !(await leagues.get(client, req.payload.league_id))) throw new Rejection('LEAGUE_NOT_FOUND');
    leagueId = req.payload.league_id || null;
  } else if (!match) {
    throw new Rejection('MATCH_NOT_FOUND');
  }
  const league = leagueId ? await leagues.get(client, leagueId) : null;
  await authorizeOrReject(authorize, { type: 'score_event', league_id: leagueId, tenant_id: league ? league.tenant_id : null, match_id: req.match_id });
  const resolution = checkBase(action, match ? match.version : 0);
  const out = await eventService.appendEventIn(client, req);
  if (out.status === 200) return { dup: true, version: out.body.match_version };
  return { result: applied(resolution, out.body.match_version, out.body.signature) };
}

async function applyRosterTransfer(client, action, authorize) {
  const body = action.payload;
  const { fields } = roster.validateTransfer(body);
  if (fields.length) throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields });
  const canon = canonical(roster.transferFingerprint(body));
  await rosterRepo.lockLeague(client, body.league_id);

  const version = () => rosterService.playerVersion(client, body.league_id, body.player_id, body.effective_date);
  const stored = await rosterRepo.getRequest(client, body.request_id);
  if (stored && stored.request_canonical === canon) return { dup: true, version: await readVersion(client, action) };

  if (!(await rosterRepo.getLeague(client, body.league_id))) throw new Rejection('LEAGUE_NOT_FOUND');
  if (!(await rosterRepo.hasPlayer(client, body.league_id, body.player_id))) throw new Rejection('PLAYER_NOT_FOUND');
  const squads = new Map((await rosterRepo.listSquads(client, body.league_id)).map((s) => [s.squad_id, s]));
  const from = squads.get(body.from_squad_id);
  if (!from || (body.type !== 'release' && !squads.get(body.to_squad_id))) throw new Rejection('SQUAD_NOT_FOUND');
  await authorizeOrReject(authorize, { type: 'roster_transfer', league_id: body.league_id, club_id: from.club_id, tenant_id: from.tenant_id });

  const resolution = checkBase(action, await version());
  const out = await rosterService.transferIn(client, body, canon);
  if (out.status === 200) return { dup: true, version: await version() };
  return { result: applied(resolution, await version()) };
}

// Fixtures/standings and club rosters cached for the mobile API must reflect an applied action at once.
async function invalidateAfter(action) {
  try {
    if (action.type === 'score_event') await eventService.invalidateForMatch(action.payload.match_id);
    else await require('../db/cache').invalidate('clubs-roster');
  } catch (e) { /* cache is best-effort */ }
}

// Runs one action in its own transaction; the outcome record commits together with the effect.
async function processOne(client, deviceId, action, authorize) {
  await client.query('BEGIN');
  try {
    const prior = await client.query('SELECT 1 FROM offline_actions WHERE device_id = $1 AND action_id = $2', [deviceId, action.action_id]);
    if (prior.rowCount) {
      const result = duplicate(await readVersion(client, action));
      await client.query('COMMIT');
      return result;
    }
    const run = action.type === 'score_event' ? applyScoreEvent : applyRosterTransfer;
    const outcome = await run(client, action, authorize);
    const result = outcome.dup ? duplicate(outcome.version) : outcome.result;
    await record(client, deviceId, action, result);
    await client.query('COMMIT');
    if (result.status === 'applied') await invalidateAfter(action);
    return result;
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    const code = err instanceof Rejection || err instanceof AppError ? err.code : 'INTERNAL_ERROR';
    const rejected = (version) => ({
      status: 'rejected', resolution: 'server_wins', error_code: code, server_version: version, signature: null,
    });
    try {
      await client.query('BEGIN');
      const result = rejected(await readVersion(client, action));
      await record(client, deviceId, action, result);
      await client.query('COMMIT');
      return result;
    } catch (inner) {
      await client.query('ROLLBACK').catch(() => {});
      return rejected(0);
    }
  }
}

async function sync(body, { authorize } = {}) {
  const fields = classify.envelopeFields(body);
  if (fields.length) throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields });

  const ordered = classify.sortActions(body.actions);
  const lockKey = `dev:${body.device_id}`;
  const client = await pool.connect();
  let broken = false;
  try {
    // Session-level lock: each action commits on its own, so a transaction-scoped lock would not span them.
    await client.query('SELECT pg_advisory_lock(hashtextextended($1, 0))', [lockKey]);
    const results = new Array(body.actions.length);
    try {
      for (const { action, index, order } of ordered) {
        const r = await processOne(client, body.device_id, action, authorize);
        results[index] = {
          action_id: action.action_id,
          status: r.status,
          resolution: r.resolution,
          error_code: r.error_code,
          processed_order: order,
          server_version: r.server_version,
          signature: r.signature,
        };
      }
    } finally {
      try {
        await client.query('SELECT pg_advisory_unlock(hashtextextended($1, 0))', [lockKey]);
      } catch (e) {
        broken = true;
      }
    }
    return { device_id: body.device_id, results, summary: classify.summarize(results) };
  } finally {
    client.release(broken || undefined);
  }
}

module.exports = { sync };
