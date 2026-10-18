// DELETE /audit/v1/privacy/user/:user_id (NFR-8): replace personal identifiers in one transaction.
const crypto = require('crypto');
const { withTransaction } = require('../../src/db/tx');
const { ApiError } = require('../lib/errors');
const media = require('../lib/media');

const ID_RE = /^[A-Za-z0-9._-]{1,128}$/;

async function erase(id) {
  const stable = crypto.createHash('sha256').update(`erase:${id}`).digest('hex').slice(0, 12);
  const revoked = [];
  await withTransaction(async (client) => {
    const player = await client.query('SELECT player_id FROM players WHERE player_id = $1', [id]);
    const users = await client.query('SELECT user_id FROM users WHERE user_id = $1 OR player_id = $1', [id]);
    if (!player.rowCount && !users.rowCount) throw new ApiError(404, 'PLAYER_NOT_FOUND', 'User not found.', { user_id: id });
    const userIds = users.rows.map((r) => r.user_id);
    await client.query('UPDATE players SET name = $2 WHERE player_id = $1', [id, `Anonymized ${stable}`]);
    for (const uid of userIds) {
      const h = crypto.createHash('sha256').update(`erase:${uid}`).digest('hex').slice(0, 12);
      await client.query(`UPDATE users SET email = $2, password_hash = '!erased' WHERE user_id = $1`, [uid, `anon-${h}@anonymized.invalid`]);
    }
    if (userIds.length) await client.query('DELETE FROM refresh_tokens WHERE user_id = ANY($1::text[])', [userIds]);
    await client.query(`UPDATE day2_guest_token SET erased = true, cart = '[]'::jsonb WHERE merged_user_id = ANY($1::text[])`, [[id, ...userIds]]);
    const keys = await client.query(
      `UPDATE day2_stream_key SET status = 'revoked', updated_at = now() WHERE player_id = $1 AND status = 'active' RETURNING stream_key`, [id],
    );
    revoked.push(...keys.rows.map((r) => r.stream_key));
  });
  await Promise.all(revoked.map((k) => media.kickKey(k)));
}

module.exports = (router) => {
  router.delete('/audit/v1/privacy/user/:user_id', async (req, res, next) => {
    try {
      const id = req.params.user_id;
      if (!ID_RE.test(id)) throw new ApiError(404, 'PLAYER_NOT_FOUND', 'User not found.', { user_id: id });
      await erase(id);
      res.json({ user_id: id, status: 'anonymized', pii_retained: false, tokens_wiped: true });
    } catch (e) { next(e); }
  });
};
