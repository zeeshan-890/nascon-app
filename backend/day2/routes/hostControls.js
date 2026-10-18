// Basic host moderation for a co-stream (FR-3.3): signed invite token, mute, spotlight, kick. Only the active host may use them.
const pool = require('../../src/db/pool');
const { withTransaction } = require('../../src/db/tx');
const { ApiError } = require('../lib/errors');
const { sign, signCostream } = require('../lib/sign');
const { verifyAccessToken } = require('../../src/services/authService');
const { invalid, nonEmptyString, bodyObject } = require('../lib/validate');

function principalOf(req) {
  const m = /^\s*bearer\s+(\S+)/i.exec(req.headers.authorization || '');
  const p = m ? verifyAccessToken(m[1]) : null;
  if (!p) throw new ApiError(401, 'UNAUTHENTICATED', 'Authentication required');
  return p;
}

// Runs fn(session, client) as the active host, with the session row locked.
async function asHost(req, fn) {
  const p = principalOf(req);
  return withTransaction(async (client) => {
    const id = req.params.id;
    await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [`costream:${id}`]);
    const row = (await client.query('SELECT response FROM day2_costream_state WHERE session_id = $1', [id])).rows[0];
    if (!row) throw new ApiError(404, 'STREAM_NOT_FOUND', 'Co-stream session not found.', { session_id: id });
    if (!p.player_id || p.player_id !== row.response.active_host) throw new ApiError(403, 'FORBIDDEN', 'Only the active host can do this.');
    return fn(row.response, client);
  });
}

const flag = (sessionId, kind, playerId, on) => pool.query(
  `INSERT INTO day2_costream_ctrl (session_id, kind, player_id) VALUES ($1, $2, $3) ON CONFLICT DO NOTHING`, [sessionId, kind, playerId],
).then(() => (on ? null : pool.query('DELETE FROM day2_costream_ctrl WHERE session_id = $1 AND kind = $2 AND player_id = $3', [sessionId, kind, playerId])));

module.exports = (router) => {
  router.post('/api/v1/costream/:id/invite', async (req, res, next) => {
    try {
      const b = bodyObject(req);
      if (!nonEmptyString(b.player_id)) throw invalid(['player_id']);
      await asHost(req, async () => null);
      const token = `invite_${b.player_id}_${sign(req.params.id, { player_id: b.player_id, session_id: req.params.id })}`;
      res.status(201).json({ session_id: req.params.id, player_id: b.player_id, invite_token: token });
    } catch (e) { next(e); }
  });

  for (const action of ['mute', 'unmute', 'spotlight']) {
    router.post(`/api/v1/costream/:id/${action}`, async (req, res, next) => {
      try {
        const b = bodyObject(req);
        if (!nonEmptyString(b.player_id)) throw invalid(['player_id']);
        const session = await asHost(req, async (s, client) => {
          if (!s.active_participants.includes(b.player_id)) throw new ApiError(404, 'PLAYER_NOT_FOUND', 'Participant not in session.', { player_id: b.player_id });
          if (action === 'spotlight') {
            await client.query(`DELETE FROM day2_costream_ctrl WHERE session_id = $1 AND kind = 'spotlight'`, [req.params.id]);
            await client.query(`INSERT INTO day2_costream_ctrl (session_id, kind, player_id) VALUES ($1, 'spotlight', $2)`, [req.params.id, b.player_id]);
          } else if (action === 'mute') {
            await client.query(`INSERT INTO day2_costream_ctrl (session_id, kind, player_id) VALUES ($1, 'mute', $2) ON CONFLICT DO NOTHING`, [req.params.id, b.player_id]);
          } else {
            await client.query(`DELETE FROM day2_costream_ctrl WHERE session_id = $1 AND kind = 'mute' AND player_id = $2`, [req.params.id, b.player_id]);
          }
          return s;
        });
        require('../realtime').publishRoom(req.params.id, `host.${action}`, { event_id: `${action}-${Date.now()}`, player_id: b.player_id });
        res.json({ session_id: session.session_id, action, player_id: b.player_id });
      } catch (e) { next(e); }
    });
  }

  router.post('/api/v1/costream/:id/kick', async (req, res, next) => {
    try {
      const b = bodyObject(req);
      if (!nonEmptyString(b.player_id)) throw invalid(['player_id']);
      const next_ = await asHost(req, async (s, client) => {
        if (b.player_id === s.active_host) throw invalid(['player_id']);
        if (!s.active_participants.includes(b.player_id)) throw new ApiError(404, 'PLAYER_NOT_FOUND', 'Participant not in session.', { player_id: b.player_id });
        const rest = s.active_participants.filter((x) => x !== b.player_id);
        const body = { ...s, active_participants: rest, participant_count: rest.length };
        body.signature = signCostream({
          active_host: body.active_host, handoff_occurred: body.handoff_occurred, layout: body.layout,
          participant_count: body.participant_count, session_id: body.session_id, status: body.status,
        });
        await client.query('UPDATE day2_costream_state SET response = $2::jsonb, updated_at = now() WHERE session_id = $1', [req.params.id, JSON.stringify(body)]);
        return body;
      });
      require('../realtime').publishRoom(req.params.id, 'participant.kicked', { event_id: `kick-${Date.now()}`, player_id: b.player_id });
      res.json(next_);
    } catch (e) { next(e); }
  });
};

module.exports.flag = flag;
