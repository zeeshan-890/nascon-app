const { Router } = require('express');
const pool = require('../db/pool');
const { Id } = require('../http/validators');
const { send, errorBody } = require('../http/respond');
const art = require('../domain/art/png');

const router = Router();
const memo = new Map();
const MAX_ENTRIES = 500;

async function tenantColors(id) {
  const { rows } = await pool.query('SELECT primary_color, secondary_color FROM tenants WHERE tenant_id = $1', [id]);
  return rows[0] ? { primary: rows[0].primary_color, secondary: rows[0].secondary_color } : null;
}

function serve(kind, make) {
  router.get(`/static/${kind}/:file`, async (req, res, next) => {
    try {
      const m = /^(.+)\.png$/.exec(req.params.file);
      if (!m || !Id.safeParse(m[1]).success) {
        return send(res, 404, errorBody('NOT_FOUND', 'Not found'));
      }
      const id = m[1];
      const key = `${kind}:${id}`;
      let png = memo.get(key);
      if (!png) {
        png = make(id, kind === 'logo' ? await tenantColors(id) : null);
        if (memo.size >= MAX_ENTRIES) memo.delete(memo.keys().next().value);
        memo.set(key, png);
      }
      return res.status(200).set('Content-Type', 'image/png').set('Cache-Control', 'public, max-age=3600').send(png);
    } catch (err) {
      return next(err);
    }
  });
}

serve('logo', art.logoPng);
serve('product', art.productPng);

// Colours may change after a reset, so the generated images are forgotten then.
router.clearArtCache = () => memo.clear();

module.exports = router;
