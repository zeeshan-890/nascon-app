// POST /audit/v1/moderation/test (FR-4.4): filter probe messages, report kicked/muted senders and the DLQ.
const { guestWriteAllowList } = require('../lib/guestGuard');
const { signModeration } = require('../lib/sign');
const { invalid, nonEmptyString, bodyObject, isObject } = require('../lib/validate');
const { moderate } = require('../lib/moderation');
const dlq = require('../lib/dlq');
const metrics = require('../lib/metrics');

guestWriteAllowList.push('/audit/v1/moderation/test');

function parse(req) {
  const b = bodyObject(req);
  const fields = [];
  if (!nonEmptyString(b.chat_room_id)) fields.push('chat_room_id');
  const msgs = b.incoming_messages;
  if (!Array.isArray(msgs) || msgs.length > 100) fields.push('incoming_messages');
  else {
    msgs.forEach((m, i) => {
      if (!isObject(m)) { fields.push(`incoming_messages[${i}]`); return; }
      if (!nonEmptyString(m.message_id)) fields.push(`incoming_messages[${i}].message_id`);
      if (!nonEmptyString(m.sender)) fields.push(`incoming_messages[${i}].sender`);
      if (typeof m.text !== 'string' || m.text.length > 2000) fields.push(`incoming_messages[${i}].text`);
    });
  }
  if (b.check_dlq !== undefined && typeof b.check_dlq !== 'boolean') fields.push('check_dlq');
  const n = b.simulate_failed_notifications === undefined ? 0 : b.simulate_failed_notifications;
  if (!Number.isInteger(n) || n < 0 || n > 1000) fields.push('simulate_failed_notifications');
  if (fields.length) throw invalid(fields);
  return { room: b.chat_room_id, msgs, check: b.check_dlq === true, n };
}

module.exports = (router) => {
  router.post('/audit/v1/moderation/test', async (req, res, next) => {
    try {
      const { room, msgs, check, n } = parse(req);
      const r = moderate(msgs);
      let queueDepth = metrics.get('nascon_dlq_depth');
      let events = [];
      if (n > 0 || check) queueDepth = await dlq.enqueueFailed(n);
      if (check) events = await dlq.undelivered();
      res.json({
        chat_room_id: room,
        filtered_messages: r.filtered,
        kicked_users: r.kicked,
        muted_users: r.muted,
        dlq_status: { queue_depth: queueDepth, undelivered_events: events },
        signature: signModeration({ chat_room_id: room, flagged_count: r.flagged, kicked_users: r.kicked, queue_depth: queueDepth }),
      });
    } catch (e) { next(e); }
  });
};
