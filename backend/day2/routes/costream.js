// Co-stream orchestration with host handoff (FR-3.2).
const crypto = require('crypto');
const { withTransaction } = require('../../src/db/tx');
const pool = require('../../src/db/pool');
const { ApiError } = require('../lib/errors');
const { signCostream } = require('../lib/sign');
const { invalid, nonEmptyString, bodyObject } = require('../lib/validate');
const decisions = require('../decisions');

const LAYOUTS = { solo: 1, grid_2: 2, grid_4: 4, spotlight: 4 };
const MAX_PARTICIPANTS = 4;

function parse(req) {
  const b = bodyObject(req);
  const fields = [];
  if (!nonEmptyString(b.session_id, 128)) fields.push('session_id');
  if (!nonEmptyString(b.host_player_id)) fields.push('host_player_id');
  const parts = b.participants;
  if (!Array.isArray(parts) || parts.length === 0 || !parts.every((p) => nonEmptyString(p)) || new Set(parts).size !== parts.length) {
    fields.push('participants');
  }
  const layout = b.layout === undefined ? 'grid_4' : b.layout;
  if (!Object.prototype.hasOwnProperty.call(LAYOUTS, layout)) fields.push('layout');
  if (b.simulate_host_drop !== undefined && typeof b.simulate_host_drop !== 'boolean') fields.push('simulate_host_drop');
  if (fields.length) throw invalid(fields);
  return { id: b.session_id, host: b.host_player_id, parts, layout, drop: b.simulate_host_drop === true };
}

function build(p) {
  let activeHost = p.host;
  let handoff = false;
  let active = p.parts.slice();
  let status = 'active';
  if (p.drop) {
    active = p.parts.filter((x) => x !== p.host);
    if (active.length) { activeHost = active[0]; handoff = true; } else { activeHost = null; status = 'terminated'; }
  }
  const effective = decisions.COSTREAM_LAYOUT_USES_EFFECTIVE_COUNT ? active.length : p.parts.length;
  if (effective > LAYOUTS[p.layout] && (p.layout === 'solo' || p.layout === 'grid_2')) {
    throw new ApiError(400, 'UNSUPPORTED_LAYOUT', 'Layout cannot show this many participants.', { layout: p.layout, participant_count: effective });
  }
  const body = {
    session_id: p.id,
    status,
    active_host: activeHost,
    handoff_occurred: handoff,
    participant_count: active.length,
    active_participants: active,
    layout: p.layout,
    composite_playback_url: `http://localhost:8888/costream/${p.id}/index.m3u8`,
    chat_room_id: `chat-${p.id}`,
  };
  body.signature = signCostream({
    active_host: body.active_host, handoff_occurred: body.handoff_occurred, layout: body.layout,
    participant_count: body.participant_count, session_id: body.session_id, status: body.status,
  });
  return body;
}

async function create(p) {
  const fingerprint = crypto.createHash('sha256').update(JSON.stringify([p.host, p.parts, p.layout, p.drop])).digest('hex');
  return withTransaction(async (client) => {
    await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [`costream:${p.id}`]);
    const found = await client.query('SELECT id FROM unnest($1::text[]) AS id WHERE id IN (SELECT player_id FROM players)', [p.parts]);
    const known = new Set(found.rows.map((r) => r.id));
    for (const id of p.parts) if (!known.has(id)) throw new ApiError(404, 'PLAYER_NOT_FOUND', 'Player not found.', { player_id: id });
    if (!known.has(p.host)) {
      if (!(await client.query('SELECT 1 FROM players WHERE player_id = $1', [p.host])).rowCount) {
        throw new ApiError(404, 'PLAYER_NOT_FOUND', 'Player not found.', { player_id: p.host });
      }
    }
    if (!p.parts.includes(p.host)) throw invalid(['host_player_id']);
    if (p.parts.length > MAX_PARTICIPANTS) {
      throw new ApiError(409, 'COSTREAM_SESSION_FULL', 'A co-stream session holds at most 4 participants.', { max_participants: MAX_PARTICIPANTS });
    }
    const body = build(p);
    const prev = (await client.query('SELECT fingerprint FROM day2_costream_state WHERE session_id = $1', [p.id])).rows[0];
    await client.query(
      `INSERT INTO day2_costream_state (session_id, fingerprint, response) VALUES ($1, $2, $3::jsonb)
       ON CONFLICT (session_id) DO UPDATE SET fingerprint = EXCLUDED.fingerprint, response = EXCLUDED.response, updated_at = now()`,
      [p.id, fingerprint, JSON.stringify(body)],
    );
    return { body, status: prev ? 200 : 201 };
  });
}

module.exports = (router) => {
  router.post('/audit/v1/costream/session', async (req, res, next) => {
    try {
      const r = await create(parse(req));
      res.status(r.status).json(r.body);
    } catch (e) { next(e); }
  });

  router.get('/api/v1/costream/:session_id', async (req, res, next) => {
    try {
      const { rows } = await pool.query('SELECT response FROM day2_costream_state WHERE session_id = $1', [req.params.session_id]);
      if (!rows.length) throw new ApiError(404, 'STREAM_NOT_FOUND', 'Co-stream session not found.', { session_id: req.params.session_id });
      res.json(rows[0].response);
    } catch (e) { next(e); }
  });
};
