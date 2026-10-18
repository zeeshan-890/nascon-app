// Public surface of the Part II realtime module. Everything here is fire-and-forget and never throws.
const logger = require('../../src/logger');
const streams = require('./streams');
const gauge = require('./gauge');
const { createWsServer } = require('./wsServer');

const swallow = (what) => (err) => logger.warn({ err: err && err.message }, `realtime ${what} failed`);

// A match event was committed (called by the API process right after the commit).
function publishMatchEvent(info) {
  try {
    if (!info || !info.match_id) return;
    streams.getStream('match', info.match_id).ingestEvent(info).catch(swallow('publish'));
  } catch (err) { swallow('publish')(err); }
}

// A match status changed without a new event (postponed, cancelled, abandoned, back from one of those).
function publishMatchStatus(matchId) {
  try {
    if (matchId) streams.getStream('match', matchId).refreshStatus().catch(swallow('status publish'));
  } catch (err) { swallow('status publish')(err); }
}

// Co-stream / chat room frames: type is e.g. chat.message, participant.joined, host.handoff, session.status.
// For chat messages pass data.seq (the room sequence number) and data.message_id.
function publishRoom(sessionId, type, data = {}) {
  try {
    if (sessionId && type) streams.getStream('room', sessionId).ingestRoom(type, data).catch(swallow('room publish'));
  } catch (err) { swallow('room publish')(err); }
}

// Feeds a message that arrived on the Redis channel rt:<topic> (the worker's relay path) into the streams.
function ingestRedis(topic, message) {
  let msg;
  try { msg = JSON.parse(message); } catch (e) { return; }
  if (!msg || typeof msg !== 'object') return;
  const m = /^(match|costream):(.+)$/.exec(topic);
  if (!m) return;
  if (m[1] === 'costream') { publishRoom(m[2], msg.type, msg.data || {}); return; }
  if (msg.type === 'match.event_appended' && msg.data) publishMatchEvent({ ...msg.data, match_id: m[2] });
  else if (msg.type === 'match.status_changed') publishMatchStatus(m[2]);
}

// After POST /audit/v1/reset: every connected client gets a fresh snapshot.
async function onReset() {
  try { await streams.resetAll(); } catch (err) { swallow('reset')(err); }
}

module.exports = {
  createWsServer, publishMatchEvent, publishMatchStatus, publishRoom, ingestRedis, onReset,
  resetGauge: gauge.reset, trackSocket: gauge.track, openStream: streams.open, clearStreams: streams.clearAll,
};
