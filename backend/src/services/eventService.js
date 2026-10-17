const { z } = require('zod');
const pool = require('../db/pool');
const { withTransaction } = require('../db/tx');
const AppError = require('../http/AppError');
const { Id, Int, Text, parseOr400, issuesToFields, isPlainObject } = require('../http/validators');
const { canonical, sign } = require('../domain/crypto');
const { validateAppend } = require('../domain/scoring/eventRules');
const sports = require('../repos/sports');
const leagues = require('../repos/leagues');
const matches = require('../repos/matches');
const events = require('../repos/events');
const outbox = require('../repos/outbox');

const TYPES = ['match_init', 'match_start', 'match_end', 'score', 'discipline', 'tiebreak_winner', 'compensation'];
const STATUS_TYPES = new Set(['match_start', 'match_end']);

const Base = z.object({ event_id: Id, match_id: Id, type: z.enum(TYPES), payload: z.custom(isPlainObject, 'must be an object') });
const PayloadSchemas = {
  match_init: z.object({ sport_id: z.string(), home_team_id: Id, away_team_id: Id, league_id: Id.optional() }),
  match_start: z.object({}),
  match_end: z.object({}),
  score: z.object({ team_id: Id, event_type: z.string() }),
  discipline: z.object({ team_id: Id, points: Int }),
  tiebreak_winner: z.object({ team_id: Id }),
  compensation: z.object({ compensates: Id, reason: Text(500) }),
};

function parseRequest(body) {
  if (!isPlainObject(body)) {
    throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields: ['body'] });
  }
  const fields = [];
  const add = (list) => list.forEach((f) => !fields.includes(f) && fields.push(f));
  const base = Base.safeParse(body);
  if (!base.success) add(issuesToFields(base.error));
  if (TYPES.includes(body.type) && isPlainObject(body.payload)) {
    const p = PayloadSchemas[body.type].safeParse(body.payload);
    if (!p.success) add(issuesToFields(p.error, 'payload'));
    else if (body.type === 'match_init' && p.data.home_team_id === p.data.away_team_id) add(['payload.away_team_id']);
  }
  if (fields.length) throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields });
  return { event_id: body.event_id, match_id: body.match_id, type: body.type, payload: body.payload };
}

function replay(existing, requestCanonical) {
  if (existing.request_canonical === requestCanonical) return { status: 200, body: existing.response };
  throw new AppError(409, 'DUPLICATE_EVENT_ID', 'event_id already used with a different request');
}

async function doAppend(client, req, requestCanonical) {
  const { event_id, match_id, type, payload } = req;
  let match;

  if (type === 'match_init') {
    if (!(await sports.get(client, payload.sport_id))) throw new AppError(404, 'SPORT_NOT_FOUND', 'Sport not found');
    if (payload.league_id && !(await leagues.get(client, payload.league_id))) {
      throw new AppError(404, 'LEAGUE_NOT_FOUND', 'League not found');
    }
    const row = await matches.insertInit(client, { match_id, ...payload });
    if (!row) throw new AppError(409, 'MATCH_EXISTS', 'A match with this match_id already exists');
    match = await matches.get(client, match_id, { forUpdate: true });
  } else {
    match = await matches.get(client, match_id, { forUpdate: true });
    if (!match) throw new AppError(404, 'MATCH_NOT_FOUND', 'Match not found');
  }

  // A concurrent identical request may have committed while we waited for the lock.
  const already = await events.findById(client, event_id);
  if (already) return { replay: replay(already, requestCanonical) };

  const config = await sports.get(client, match.sport_id);
  const history = await events.listByMatch(client, match_id);

  if (type === 'compensation') {
    const target = await events.findById(client, payload.compensates);
    if (!target) throw new AppError(404, 'EVENT_NOT_FOUND', 'Event to compensate not found');
    if (target.match_id !== match_id) {
      throw new AppError(409, 'INVALID_COMPENSATION', 'Event belongs to a different match');
    }
  }

  const verdict = validateAppend(config, match, history, { type, payload });
  if (!verdict.ok) throw new AppError(verdict.status, verdict.code, verdict.message, verdict.details || {});

  const sequence = match.version + 1;
  const signature = sign(event_id, payload);
  const response = { event_id, match_id, type, sequence, match_version: sequence, signature };
  await events.insert(client, {
    event_id, match_id, sequence, type, payload,
    payload_canonical: canonical(payload),
    signature,
    request_canonical: requestCanonical,
    compensates: type === 'compensation' ? payload.compensates : null,
    response,
  });
  await matches.bumpVersion(client, match_id);

  const out = { match_id, event_id, type, sequence, match_version: sequence };
  await outbox.enqueue(client, { aggregate_type: 'match', aggregate_id: match_id, event_type: 'match.event_appended', payload: out });
  if (STATUS_TYPES.has(type)) {
    await outbox.enqueue(client, { aggregate_type: 'match', aggregate_id: match_id, event_type: 'match.status_changed', payload: out });
  }
  return { created: response };
}

async function appendEvent(body) {
  const req = parseRequest(body);
  const requestCanonical = canonical({ event_id: req.event_id, match_id: req.match_id, type: req.type, payload: req.payload });

  const existing = await events.findById(pool, req.event_id);
  if (existing) return replay(existing, requestCanonical);

  try {
    const result = await withTransaction((client) => doAppend(client, req, requestCanonical));
    return result.replay || { status: 201, body: result.created };
  } catch (err) {
    // A racing request with the same event_id may have won; resolve idempotently.
    if (err instanceof AppError || err.code === '23505') {
      const raced = await events.findById(pool, req.event_id);
      if (raced) return replay(raced, requestCanonical);
    }
    if (err.code === '23505') {
      if (/compensates/.test(err.constraint || '')) {
        throw new AppError(409, 'INVALID_COMPENSATION', 'Event was already compensated');
      }
      if (/matches_pkey/.test(err.constraint || '')) throw new AppError(409, 'MATCH_EXISTS', 'A match with this match_id already exists');
      throw new AppError(409, 'DUPLICATE_EVENT_ID', 'event_id already used');
    }
    throw err;
  }
}

module.exports = { appendEvent };
