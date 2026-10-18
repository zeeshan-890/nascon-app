// Real chat route (FR-3.3 unified chat, FR-5.3 moderation): gapless increasing room sequence, pre-broadcast filtering.
const crypto = require('crypto');
const pool = require('../../src/db/pool');
const { withTransaction } = require('../../src/db/tx');
const { ApiError } = require('../lib/errors');
const { verifyAccessToken } = require('../../src/services/authService');
const { invalid, nonEmptyString, bodyObject } = require('../lib/validate');
const { isFlood, mask } = require('../lib/moderation');

const WINDOW_MS = 2000;
const MAX_IN_WINDOW = 3;
const recent = new Map(); // sender -> timestamps (sliding window)

const rateLimited = (sender) => {
  const now = Date.now();
  const hits = (recent.get(sender) || []).filter((t) => now - t < WINDOW_MS);
  if (hits.length >= MAX_IN_WINDOW) { recent.set(sender, hits); return true; }
  hits.push(now);
  recent.set(sender, hits);
  return false;
};

module.exports = (router) => {
  // History for a room, oldest first. Open to guests (reads are allowed); `after` returns only newer sequence numbers.
  router.get('/api/v1/chat/:room/messages', async (req, res, next) => {
    try {
      const room = String(req.params.room || '');
      if (!/^[A-Za-z0-9_.:-]{1,80}$/.test(room)) throw invalid(['room']);
      const after = req.query.after === undefined ? 0 : Number(req.query.after);
      if (!Number.isInteger(after) || after < 0) throw invalid(['after']);
      const { rows } = await pool.query(
        `SELECT room_id, seq, message_id, sender, text, flagged FROM day2_chat_message
         WHERE room_id = $1 AND seq > $2 ORDER BY seq ASC LIMIT 200`, [room, after],
      );
      return res.status(200).json({
        messages: rows.map((r) => ({
          message_id: r.message_id, room_id: r.room_id, sequence: Number(r.seq), sender: r.sender, text: r.text, flagged: r.flagged,
        })),
      });
    } catch (e) { return next(e); }
  });

  router.post('/api/v1/chat', async (req, res, next) => {
    try {
      const m = /^\s*bearer\s+(\S+)/i.exec(req.headers.authorization || '');
      const principal = m ? verifyAccessToken(m[1]) : null;
      if (!principal) throw new ApiError(401, 'UNAUTHENTICATED', 'Authentication required');
      const b = bodyObject(req);
      const fields = [];
      if (!nonEmptyString(b.chat_room_id)) fields.push('chat_room_id');
      if (typeof b.text !== 'string' || b.text.length === 0 || b.text.length > 2000) fields.push('text');
      if (fields.length) throw invalid(fields);
      const sender = principal.user_id;
      const base = { chat_room_id: b.chat_room_id, sender };
      if (rateLimited(sender)) return res.status(200).json({ ...base, delivered: false, flagged: true, reason: 'rate_limit_exceeded' });
      if (isFlood(b.text)) return res.status(200).json({ ...base, delivered: false, flagged: true, reason: 'flood_character_repeat' });
      const r = mask(b.text);
      const messageId = `msg-${crypto.randomUUID()}`;
      const seq = await withTransaction(async (client) => {
        await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [`chat:${b.chat_room_id}`]);
        const next = (await client.query('SELECT coalesce(max(seq), 0) + 1 AS n FROM day2_chat_message WHERE room_id = $1', [b.chat_room_id])).rows[0].n;
        await client.query(
          'INSERT INTO day2_chat_message (room_id, seq, message_id, sender, text, flagged, created_at) VALUES ($1, $2, $3, $4, $5, $6, now())',
          [b.chat_room_id, next, messageId, sender, r.text, r.hit],
        );
        return Number(next);
      });
      const roomId = b.chat_room_id.replace(/^chat-/, '');
      require('../realtime').publishRoom(roomId, 'chat.message', { seq, message_id: messageId, sender, text: r.text, flagged: r.hit });
      return res.status(201).json({ ...base, delivered: true, seq, message_id: messageId, text: r.text, flagged: r.hit, reason: r.hit ? 'profanity_leetspeak' : null });
    } catch (e) { return next(e); }
  });
};
