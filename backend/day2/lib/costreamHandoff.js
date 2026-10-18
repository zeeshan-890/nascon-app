// Real host disconnect: when the active host's room socket closes, the next participant is promoted (FR-3.2).
const { withTransaction } = require('../../src/db/tx');
const { signCostream } = require('./sign');

// Returns the new session body when a handoff happened, otherwise null.
async function promoteHost(sessionId, playerId) {
  const body = await withTransaction(async (client) => {
    await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [`costream:${sessionId}`]);
    const row = (await client.query('SELECT response FROM day2_costream_state WHERE session_id = $1', [sessionId])).rows[0];
    if (!row || row.response.status !== 'active' || row.response.active_host !== playerId) return null;
    const rest = row.response.active_participants.filter((p) => p !== playerId);
    const next = {
      ...row.response,
      active_participants: rest,
      participant_count: rest.length,
      active_host: rest.length ? rest[0] : null,
      handoff_occurred: rest.length > 0,
      status: rest.length ? 'active' : 'terminated',
    };
    next.signature = signCostream({
      active_host: next.active_host, handoff_occurred: next.handoff_occurred, layout: next.layout,
      participant_count: next.participant_count, session_id: next.session_id, status: next.status,
    });
    await client.query('UPDATE day2_costream_state SET response = $2::jsonb, updated_at = now() WHERE session_id = $1', [sessionId, JSON.stringify(next)]);
    return next;
  });
  if (body) {
    try {
      require('../realtime').publishRoom(sessionId, 'host.handoff', {
        event_id: `handoff-${sessionId}-${Date.now()}`, active_host: body.active_host, status: body.status, participant_count: body.participant_count,
      });
    } catch (e) { /* realtime is best effort */ }
  }
  return body;
}

module.exports = { promoteHost };
