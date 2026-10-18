// FR-3.1 social graph: idempotent follow/unfollow per (follower, athlete) with a follower count that cannot drift.
const pool = require('../../src/db/pool');
const { ApiError } = require('../lib/errors');
const { verifyAccessToken } = require('../../src/services/authService');

const ID_RE = /^[A-Za-z0-9._-]{1,128}$/;

function follower(req) {
  const m = /^\s*bearer\s+(\S+)/i.exec(req.headers.authorization || '');
  const principal = m ? verifyAccessToken(m[1]) : null;
  if (!principal) throw new ApiError(401, 'UNAUTHENTICATED', 'Authentication required');
  return principal.user_id;
}

async function athlete(id) {
  if (!ID_RE.test(id) || !(await pool.query('SELECT 1 FROM players WHERE player_id = $1', [id])).rowCount) {
    throw new ApiError(404, 'PLAYER_NOT_FOUND', 'Athlete not found.', { athlete_id: id });
  }
}

const countOf = async (id) => (await pool.query('SELECT count(*)::int AS c FROM day2_follow WHERE athlete_id = $1', [id])).rows[0].c;

module.exports = (router) => {
  router.post('/api/v1/athletes/:id/follow', async (req, res, next) => {
    try {
      const who = follower(req);
      await athlete(req.params.id);
      await pool.query('INSERT INTO day2_follow (follower_id, athlete_id) VALUES ($1, $2) ON CONFLICT DO NOTHING', [who, req.params.id]);
      res.json({ athlete_id: req.params.id, following: true, follower_count: await countOf(req.params.id) });
    } catch (e) { next(e); }
  });

  router.delete('/api/v1/athletes/:id/follow', async (req, res, next) => {
    try {
      const who = follower(req);
      await athlete(req.params.id);
      await pool.query('DELETE FROM day2_follow WHERE follower_id = $1 AND athlete_id = $2', [who, req.params.id]);
      res.json({ athlete_id: req.params.id, following: false, follower_count: await countOf(req.params.id) });
    } catch (e) { next(e); }
  });

  router.get('/api/v1/athletes/:id/followers', async (req, res, next) => {
    try {
      await athlete(req.params.id);
      res.json({ athlete_id: req.params.id, follower_count: await countOf(req.params.id) });
    } catch (e) { next(e); }
  });
};
