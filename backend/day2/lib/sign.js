// Part II signatures: HMAC-SHA256(key = utf8(DAY2_SALT), msg = entityId + "|" + canonical(payload)), lowercase hex.
const crypto = require('crypto');
const { ApiError } = require('./errors');

const byCodePoint = (a, b) => {
  const x = Array.from(a);
  const y = Array.from(b);
  const n = Math.min(x.length, y.length);
  for (let i = 0; i < n; i += 1) {
    const d = x[i].codePointAt(0) - y[i].codePointAt(0);
    if (d !== 0) return d;
  }
  return x.length - y.length;
};

// Same bytes as Python json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False).
function canonical(value) {
  if (value === undefined) throw new TypeError('canonical: undefined is not allowed');
  if (value === null) return 'null';
  switch (typeof value) {
    case 'boolean':
      return value ? 'true' : 'false';
    case 'string':
      return JSON.stringify(value);
    case 'number':
      if (!Number.isInteger(value)) throw new TypeError('canonical: only integer numbers are allowed');
      return String(value);
    case 'object':
      if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
      return `{${Object.keys(value).sort(byCodePoint).map((k) => `${JSON.stringify(k)}:${canonical(value[k])}`).join(',')}}`;
    default:
      throw new TypeError(`canonical: unsupported type ${typeof value}`);
  }
}

function sign(entityId, payload) {
  const salt = process.env.DAY2_SALT; // read at call time, verbatim
  if (typeof salt !== 'string' || salt.length === 0) {
    throw new ApiError(500, 'INTERNAL_ERROR', 'Signing is not configured on this server.');
  }
  return crypto.createHmac('sha256', Buffer.from(salt, 'utf8'))
    .update(Buffer.from(`${entityId}|${canonical(payload)}`, 'utf8'))
    .digest('hex');
}

const signStreamKey = ({ action, player_id, status, stream_key }) =>
  sign(stream_key, { action, player_id, status, stream_key });

const signCostream = ({ active_host, handoff_occurred, layout, participant_count, session_id, status }) =>
  sign(session_id, { active_host, handoff_occurred, layout, participant_count, session_id, status });

const signQr = ({ entity_id, short_url, target_route }) =>
  sign(entity_id, { entity_id, short_url, target_route });

const signCartMerge = ({ cart_merged, guest_token, registered_user_id, total_items, write_rejection_code }) =>
  sign(guest_token, { cart_merged, guest_token, registered_user_id, total_items, write_rejection_code });

const signModeration = ({ chat_room_id, flagged_count, kicked_users, queue_depth }) =>
  sign(chat_room_id, { chat_room_id, flagged_count, kicked_users, queue_depth });

module.exports = { canonical, sign, signStreamKey, signCostream, signQr, signCartMerge, signModeration };
