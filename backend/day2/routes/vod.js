// Basic VOD index (FR-3.2): the media server reports a finished recording, the athlete's archive lists it.
const pool = require('../../src/db/pool');
const { ApiError } = require('../lib/errors');
const { invalid, nonEmptyString, bodyObject } = require('../lib/validate');

const KEY_RE = /^[A-Za-z0-9_.-]{1,128}$/;

module.exports = (router) => {
  // Called by the media server when a recording finishes (compose network only). Body: { path, recording_path? }.
  router.post('/internal/media/recording', async (req, res, next) => {
    try {
      const b = bodyObject(req);
      const m = /^live\/([A-Za-z0-9_.-]{1,128})$/.exec(typeof b.path === 'string' ? b.path : '');
      if (!m) throw invalid(['path']);
      const key = (await pool.query('SELECT player_id FROM day2_stream_key WHERE stream_key = $1', [m[1]])).rows[0];
      if (!key) throw new ApiError(404, 'STREAM_NOT_FOUND', 'Unknown stream key.');
      const recording = nonEmptyString(b.recording_path, 512) ? b.recording_path : null;
      const { rows } = await pool.query(
        `INSERT INTO day2_vod (player_id, stream_key, recording_path) VALUES ($1, $2, $3) RETURNING vod_id, created_at`, [key.player_id, m[1], recording],
      );
      res.status(201).json({ vod_id: Number(rows[0].vod_id), player_id: key.player_id, stream_key: m[1], created_at: rows[0].created_at.toISOString() });
    } catch (e) { next(e); }
  });

  router.get('/api/v1/athletes/:id/vod', async (req, res, next) => {
    try {
      if (!KEY_RE.test(req.params.id) || !(await pool.query('SELECT 1 FROM players WHERE player_id = $1', [req.params.id])).rowCount) {
        throw new ApiError(404, 'PLAYER_NOT_FOUND', 'Athlete not found.', { athlete_id: req.params.id });
      }
      const { rows } = await pool.query('SELECT vod_id, stream_key, recording_path, created_at FROM day2_vod WHERE player_id = $1 ORDER BY vod_id DESC', [req.params.id]);
      res.json({
        athlete_id: req.params.id,
        items: rows.map((r) => ({ vod_id: Number(r.vod_id), stream_key: r.stream_key, recording_path: r.recording_path, created_at: r.created_at.toISOString() })),
      });
    } catch (e) { next(e); }
  });
};
