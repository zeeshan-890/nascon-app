// In-process fan-out state for the Part II realtime channels (match streams and co-stream rooms).
// One Stream per match / room. All work for a stream runs on its own promise chain, so delivery order is
// the order of ingestion and two simultaneous events keep a total order. Both delivery paths (direct publish
// after commit and the worker's Redis relay) feed the same ingest functions and are de-duplicated here.
const pool = require('../../src/db/pool');
const logger = require('../../src/logger');
const scoreService = require('../../src/services/scoreService');
const matches = require('../../src/repos/matches');

const BUFFER_MAX = 200; // replay buffer per stream (SSE Last-Event-ID)
const SEEN_MAX = 1000; // remembered event ids per stream (dedupe)
const IDLE_MS = 10 * 60 * 1000; // a stream with no subscribers is dropped after this
const FRESH_MS = 5000; // a cached match state older than this is re-read when a client joins
const ADMIN_STATUSES = new Set(['postponed', 'abandoned', 'cancelled']);

const streams = new Map(); // "match:<id>" | "room:<id>" -> Stream

const nowIso = () => new Date().toISOString();

async function loadMatch(id) {
  const m = await matches.get(pool, id);
  if (!m) return null;
  const score = await scoreService.getScore(id);
  return {
    status: ADMIN_STATUSES.has(m.admin_status) ? m.admin_status : score.status,
    home: { team_id: score.home.team_id, score: score.home.score },
    away: { team_id: score.away.team_id, score: score.away.score },
    version: Number(m.version) || score.version || 0,
    league_id: m.league_id || null,
  };
}

// Tables of the co-stream and chat modules may not exist yet (they create them lazily): treat as "not found".
const missingTable = (err) => err && err.code === '42P01';

async function loadRoom(id) {
  let session;
  try {
    const { rows } = await pool.query('SELECT * FROM day2_costream_session WHERE session_id = $1', [id]);
    [session] = rows;
  } catch (err) {
    if (!missingTable(err)) throw err;
  }
  if (!session) {
    // Sessions created through POST /audit/v1/costream/session live in day2_costream_state.
    const { rows } = await pool.query('SELECT response FROM day2_costream_state WHERE session_id = $1', [id]).catch((err) => {
      if (missingTable(err)) return { rows: [] };
      throw err;
    });
    if (!rows.length) return null;
    const r = rows[0].response;
    session = {
      status: r.status, active_host: r.active_host, host_player_id: r.active_host, layout: r.layout,
      participants: r.active_participants, active_participants: r.active_participants, handoff_occurred: r.handoff_occurred,
    };
  }
  let recent = [];
  try {
    const { rows } = await pool.query(
      `SELECT room_id, seq, message_id, sender, text, flagged, created_at FROM day2_chat_message
       WHERE room_id = $1 ORDER BY seq DESC LIMIT 50`, [id]);
    recent = rows.reverse().map((r) => ({
      seq: Number(r.seq), message_id: r.message_id, sender: r.sender, text: r.text, flagged: !!r.flagged,
      created_at: r.created_at instanceof Date ? r.created_at.toISOString() : r.created_at,
    }));
  } catch (err) {
    if (!missingTable(err)) throw err;
  }
  return {
    status: session.status,
    active_host: session.active_host || null,
    host_player_id: session.host_player_id || null,
    layout: session.layout || null,
    participants: session.participants || [],
    active_participants: session.active_participants || [],
    handoff_occurred: !!session.handoff_occurred,
    last_chat_seq: recent.length ? recent[recent.length - 1].seq : 0,
    recent_messages: recent,
  };
}

class Stream {
  constructor(kind, id) {
    this.kind = kind;
    this.id = id;
    this.key = `${kind}:${id}`;
    this.seq = 0; // monotonically increasing stream sequence (top-level "sequence" of every frame)
    this.dbSeq = 0; // last match event sequence delivered (match streams)
    this.state = null;
    this.exists = null;
    this.loadedAt = 0;
    this.subs = new Set();
    this.buffer = [];
    this.seen = new Set();
    this.seenOrder = [];
    this.touched = Date.now();
    this.chain = Promise.resolve();
    this.ready = this.run(() => this.reload(true));
  }

  run(fn) {
    const p = this.chain.then(fn);
    this.chain = p.catch((err) => logger.warn({ err: err && err.message, stream: this.key }, 'realtime task failed'));
    return p;
  }

  async reload(initial = false) {
    const state = this.kind === 'match' ? await loadMatch(this.id) : await loadRoom(this.id);
    this.exists = !!state;
    this.state = state;
    this.loadedAt = Date.now();
    if (state && initial) {
      this.dbSeq = this.kind === 'match' ? state.version : 0;
      this.seq = this.kind === 'match' ? state.version : state.last_chat_seq;
    }
    return state;
  }

  remember(id) {
    if (id === undefined || id === null) return;
    this.seen.add(id);
    this.seenOrder.push(id);
    if (this.seenOrder.length > SEEN_MAX) this.seen.delete(this.seenOrder.shift());
  }

  idField() {
    return this.kind === 'match' ? { match_id: this.id } : { session_id: this.id };
  }

  snapshotData() {
    const s = this.state;
    if (this.kind === 'match') {
      return {
        match_id: this.id, status: s.status, home: s.home, away: s.away,
        home_score: s.home.score, away_score: s.away.score, version: this.dbSeq, league_id: s.league_id,
      };
    }
    return { session_id: this.id, ...s };
  }

  snapshotEntry() {
    const obj = { type: 'snapshot', ...this.idField(), sequence: this.seq, ts: nowIso(), data: this.snapshotData() };
    return { type: 'snapshot', sequence: this.seq, ws: JSON.stringify(obj), sse: `event: snapshot\ndata: ${JSON.stringify(obj)}\n\n` };
  }

  // Builds, buffers and broadcasts one frame. Must run inside the chain.
  emit(type, data) {
    this.seq += 1;
    const obj = { type, ...this.idField(), sequence: this.seq, ts: nowIso(), data };
    const json = JSON.stringify(obj);
    const entry = { type, sequence: this.seq, ws: json, sse: `id: ${this.seq}\nevent: ${type}\ndata: ${json}\n\n` };
    this.buffer.push(entry);
    if (this.buffer.length > BUFFER_MAX) this.buffer.shift();
    this.touched = Date.now();
    for (const sink of [...this.subs]) {
      if (sink.write(entry) === false) this.subs.delete(sink);
    }
    return entry;
  }

  // Adds a sink and sends the snapshot (then, for SSE reconnects, the missed frames) atomically with respect
  // to publishing, so a client never misses a frame between its snapshot and live delivery.
  join(sink, { afterSequence = null } = {}) {
    return this.run(async () => {
      if (this.kind === 'room' || Date.now() - this.loadedAt > FRESH_MS) await this.reload();
      if (!this.exists) return false;
      this.touched = Date.now();
      sink.write(this.snapshotEntry());
      if (afterSequence !== null && afterSequence < this.seq) {
        for (const entry of this.buffer) if (entry.sequence > afterSequence) sink.write(entry);
      }
      this.subs.add(sink);
      return true;
    });
  }

  leave(sink) {
    this.subs.delete(sink);
    this.touched = Date.now();
  }

  resendSnapshot(sink) {
    return this.run(async () => {
      if (this.kind === 'room') await this.reload();
      if (this.exists) sink.write(this.snapshotEntry());
    });
  }

  // ---- match ingestion -------------------------------------------------------------------------------------
  async fetchMissing(afterSeq, beforeSeq) {
    const { rows } = await pool.query(
      `SELECT event_id, sequence, type FROM match_events WHERE match_id = $1 AND sequence > $2 AND sequence < $3
       ORDER BY sequence`, [this.id, afterSeq, beforeSeq]);
    return rows.map((r) => ({ event_id: r.event_id, sequence: r.sequence, event_type: r.type }));
  }

  emitMatchEvent(info) {
    const data = {
      match_id: this.id,
      event_id: info.event_id,
      sequence: info.sequence,
      version: info.version === undefined ? info.sequence : info.version,
      event_type: info.event_type || info.type,
      status: this.state.status,
      home: this.state.home,
      away: this.state.away,
      home_score: this.state.home.score,
      away_score: this.state.away.score,
    };
    this.remember(info.event_id);
    if (Number.isInteger(info.sequence)) this.dbSeq = Math.max(this.dbSeq, info.sequence);
    this.emit('match.event_appended', data);
  }

  ingestEvent(info) {
    return this.run(async () => {
      if (!this.exists) {
        await this.reload(true);
        if (!this.exists) return;
      }
      if (info.event_id && this.seen.has(info.event_id)) return;
      const s = Number(info.sequence);
      const hasSeq = Number.isInteger(s) && s > 0;
      if (hasSeq && s <= this.dbSeq) { this.remember(info.event_id); return; } // already delivered
      const prevStatus = this.state.status;
      const prevDbSeq = this.dbSeq;
      await this.reload();
      if (!this.exists) return;
      if (hasSeq && s > prevDbSeq + 1) {
        for (const missed of await this.fetchMissing(prevDbSeq, s)) {
          if (!this.seen.has(missed.event_id)) this.emitMatchEvent(missed);
        }
      }
      this.emitMatchEvent({ ...info, sequence: hasSeq ? s : info.sequence });
      if (this.state.status !== prevStatus) this.emit('match.status_changed', { match_id: this.id, status: this.state.status });
    });
  }

  // The authoritative status is always re-read, so a late relay copy can never move a client backwards.
  refreshStatus() {
    return this.run(async () => {
      const prev = this.state && this.state.status;
      if (!(await this.reload())) return;
      if (prev !== undefined && this.state.status !== prev) this.emit('match.status_changed', { match_id: this.id, status: this.state.status });
    });
  }

  // ---- room ingestion --------------------------------------------------------------------------------------
  ingestRoom(type, data) {
    return this.run(async () => {
      if (!this.exists) {
        await this.reload(true);
        if (!this.exists) return;
      }
      const key = data && (data.message_id || data.event_id);
      if (key && this.seen.has(`${type}:${key}`)) return;
      if (key) this.remember(`${type}:${key}`);
      this.emit(type, { session_id: this.id, ...data });
    });
  }

  // After POST /audit/v1/reset: forget history, re-read state, send everybody a fresh snapshot (or close them).
  resetState() {
    return this.run(async () => {
      this.buffer = [];
      this.seen.clear();
      this.seenOrder = [];
      const lastSeq = this.seq;
      await this.reload(true);
      if (!this.exists) {
        for (const sink of [...this.subs]) sink.close(4404, `${this.kind} not found`);
        this.subs.clear();
        return;
      }
      this.seq = Math.max(lastSeq, this.seq) + 1; // the fresh snapshot is newer than anything a client has seen
      const entry = this.snapshotEntry();
      for (const sink of [...this.subs]) if (sink.write(entry) === false) this.subs.delete(sink);
    });
  }
}

function getStream(kind, id, { create = true } = {}) {
  const key = `${kind}:${id}`;
  let s = streams.get(key);
  if (!s && create) {
    s = new Stream(kind, id);
    streams.set(key, s);
  }
  return s;
}

// Resolves to a ready stream, or null when the match / room does not exist (the failed stream is not kept).
async function open(kind, id) {
  if (typeof id !== 'string' || !/^[A-Za-z0-9_.:-]{1,64}$/.test(id)) return null;
  const s = getStream(kind, id);
  try {
    await s.ready;
  } catch (err) {
    streams.delete(s.key);
    throw err;
  }
  if (!s.exists) {
    if (s.subs.size === 0) streams.delete(s.key);
    return null;
  }
  return s;
}

const sweeper = setInterval(() => {
  const now = Date.now();
  for (const [key, s] of streams) if (s.subs.size === 0 && now - s.touched > IDLE_MS) streams.delete(key);
}, 60 * 1000);
sweeper.unref();

async function resetAll() {
  await Promise.all([...streams.values()].map((s) => s.resetState().catch(() => {})));
}

// Server shutdown: end every SSE response and WebSocket still attached, then forget all streams.
function clearAll() {
  for (const s of streams.values()) {
    for (const sink of [...s.subs]) {
      try { sink.close(1001, 'server shutting down'); } catch (e) { /* already closed */ }
    }
    s.subs.clear();
  }
  streams.clear();
}

module.exports = { Stream, getStream, open, resetAll, clearAll, streams, BUFFER_MAX };
