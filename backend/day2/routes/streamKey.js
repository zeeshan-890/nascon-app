// Stream key lifecycle (FR-3.1), activation, and the media server auth hook.
const crypto = require('crypto');
const { withTransaction } = require('../../src/db/tx');
const pool = require('../../src/db/pool');
const { ApiError } = require('../lib/errors');
const { signStreamKey } = require('../lib/sign');
const { invalid, nonEmptyString, bodyObject } = require('../lib/validate');
const media = require('../lib/media');
const metrics = require('../lib/metrics');

const ACTIONS = ['generate', 'rotate', 'revoke'];
const OVERRIDE_RE = /^[A-Za-z0-9_.-]{1,128}$/;

const endpoints = (key) => ({
  ingest_endpoints: { rtmp: `rtmp://localhost:1935/live/${key}`, webrtc: `http://localhost:8889/live/${key}/whip` },
  playback_url: `http://localhost:8888/live/${key}/index.m3u8`,
});

function parse(req) {
  const b = bodyObject(req);
  const fields = [];
  if (!ACTIONS.includes(b.action)) fields.push('action');
  if (!nonEmptyString(b.player_id)) fields.push('player_id');
  if (b.label !== undefined && !nonEmptyString(b.label, 64)) fields.push('label');
  if (b.stream_key_override !== undefined && !(typeof b.stream_key_override === 'string' && OVERRIDE_RE.test(b.stream_key_override))) {
    fields.push('stream_key_override');
  }
  if (fields.length) throw invalid(fields);
  return { action: b.action, player: b.player_id, label: b.label === undefined ? 'default' : b.label, override: b.stream_key_override };
}

async function run(p) {
  const result = await withTransaction(async (client) => {
    await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [`stream:${p.player}`]);
    if (!(await client.query('SELECT 1 FROM players WHERE player_id = $1', [p.player])).rowCount) {
      throw new ApiError(404, 'PLAYER_NOT_FOUND', 'Player not found.', { player_id: p.player });
    }
    const keys = (await client.query('SELECT stream_key, label, status FROM day2_stream_key WHERE player_id = $1 ORDER BY updated_at DESC, stream_key', [p.player])).rows;
    const revoked = [];
    if (p.action === 'revoke') {
      if (!keys.length) throw new ApiError(404, 'STREAM_NOT_FOUND', 'No stream key for this player.', { player_id: p.player });
      for (const k of keys.filter((x) => x.status === 'active')) revoked.push(k.stream_key);
      await client.query(`UPDATE day2_stream_key SET status = 'revoked', updated_at = now() WHERE player_id = $1 AND status = 'active'`, [p.player]);
      const shown = keys.find((k) => k.status === 'active') || keys[0];
      return { key: shown.stream_key, label: shown.label, status: 'revoked', created: false, revoked };
    }
    let key = p.override;
    if (key) {
      const owner = (await client.query('SELECT player_id FROM day2_stream_key WHERE stream_key = $1', [key])).rows[0];
      if (owner && owner.player_id !== p.player) throw invalid(['stream_key_override']);
    } else key = `sk_live_${crypto.randomBytes(8).toString('hex')}`;
    for (const k of keys.filter((x) => x.status === 'active' && x.stream_key !== key)) revoked.push(k.stream_key);
    await client.query(`UPDATE day2_stream_key SET status = 'revoked', updated_at = now() WHERE player_id = $1 AND status = 'active' AND stream_key <> $2`, [p.player, key]);
    await client.query(
      `INSERT INTO day2_stream_key (stream_key, player_id, label, status) VALUES ($1, $2, $3, 'active')
       ON CONFLICT (stream_key) DO UPDATE SET status = 'active', label = EXCLUDED.label, updated_at = now()`, [key, p.player, p.label],
    );
    return { key, label: p.label, status: 'active', created: p.action === 'generate', revoked };
  });
  await Promise.all(result.revoked.map((k) => media.kickKey(k)));
  return result;
}

async function lookup(key) {
  const { rows } = await pool.query('SELECT stream_key, player_id, status FROM day2_stream_key WHERE stream_key = $1', [key]);
  if (!rows.length) throw new ApiError(404, 'STREAM_NOT_FOUND', 'Unknown stream key.');
  if (rows[0].status === 'revoked') throw new ApiError(409, 'KEY_REVOKED', 'This stream key has been revoked.');
  return rows[0];
}

module.exports = (router) => {
  router.post('/audit/v1/stream/key', async (req, res, next) => {
    try {
      const p = parse(req);
      const r = await run(p);
      const active = r.status === 'active';
      res.status(active && r.created ? 201 : 200).json({
        player_id: p.player,
        label: r.label,
        stream_key: r.key,
        status: r.status,
        ingest_endpoints: active ? endpoints(r.key).ingest_endpoints : null,
        playback_url: active ? endpoints(r.key).playback_url : null,
        signature: signStreamKey({ action: p.action, player_id: p.player, status: r.status, stream_key: r.key }),
      });
    } catch (e) { next(e); }
  });

  router.post('/api/v1/streams/activate', async (req, res, next) => {
    try {
      const b = bodyObject(req);
      if (!nonEmptyString(b.stream_key, 256)) throw invalid(['stream_key']);
      const row = await lookup(b.stream_key);
      res.json({ player_id: row.player_id, stream_key: row.stream_key, status: 'active', ...endpoints(row.stream_key) });
    } catch (e) { next(e); }
  });

  // MediaMTX external auth hook: POST {action, path, ...}. 2xx allows, anything else denies.
  const hook = async (req, res, next) => {
    try {
      const b = req.body || {};
      const path = typeof b.path === 'string' ? b.path : '';
      if (b.action === 'publish') {
        const m = /^live\/([A-Za-z0-9_.-]{1,128})$/.exec(path);
        if (!m) throw new ApiError(403, 'FORBIDDEN', 'Publishing is only allowed on live/<stream key>.');
        await lookup(m[1]);
        metrics.inc('nascon_active_streams_total', 0);
      }
      res.status(200).json({});
    } catch (e) { next(e); }
  };
  router.post('/internal/media/auth', hook);
  router.post('/audit/v1/media/auth', hook);
};
