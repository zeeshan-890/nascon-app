// QR metadata, exports and short-link resolution (FR-3.3). Short URLs never change; routes follow current state.
const crypto = require('crypto');
const QRCode = require('qrcode');
const pool = require('../../src/db/pool');
const { ApiError } = require('../lib/errors');
const { signQr } = require('../lib/sign');
const metrics = require('../lib/metrics');

const ORIGIN = 'http://localhost:8080';
const ID_RE = /^[A-Za-z0-9._-]{1,128}$/;
const ADMIN_STATUSES = ['postponed', 'abandoned', 'cancelled'];
const notFound = () => new ApiError(404, 'QR_NOT_FOUND', 'No QR code for this entity.');

async function matchStatus(id) {
  const { rows } = await pool.query(
    `SELECT m.admin_status,
            EXISTS (SELECT 1 FROM match_events e WHERE e.match_id = m.match_id AND e.type = 'match_end') AS ended,
            EXISTS (SELECT 1 FROM match_events e WHERE e.match_id = m.match_id AND e.type = 'match_start') AS started
     FROM matches m WHERE m.match_id = $1`, [id],
  );
  if (!rows.length) return null;
  const r = rows[0];
  if (ADMIN_STATUSES.includes(r.admin_status)) return r.admin_status;
  if (r.ended) return 'completed';
  return r.started ? 'in_progress' : 'upcoming';
}

// -> { type, status, route } or null when the entity does not exist.
async function resolve(id) {
  if (!ID_RE.test(id)) return null;
  if (id.startsWith('match-')) {
    const status = await matchStatus(id);
    if (!status) return null;
    const base = `/web/matches/${id}`;
    const route = { upcoming: `${base}/preview`, in_progress: `${base}/live`, completed: `${base}/vod` }[status]
      || `${base}/notice?state=${status}`;
    return { type: 'match', status, route };
  }
  if (id.startsWith('player-')) {
    const r = await pool.query('SELECT 1 FROM players WHERE player_id = $1', [id]);
    return r.rowCount ? { type: 'player', status: 'active', route: `/web/players/${id}` } : null;
  }
  if (id.startsWith('club-')) {
    const r = await pool.query('SELECT 1 FROM clubs WHERE club_id = $1', [id]);
    return r.rowCount ? { type: 'club', status: 'active', route: `/web/clubs/${id}` } : null;
  }
  if (id.startsWith('cs-session-')) {
    const r = await pool.query('SELECT response->>\'status\' AS status FROM day2_costream_state WHERE session_id = $1', [id]);
    return r.rowCount ? { type: 'costream', status: r.rows[0].status, route: `/web/costream/${id}` } : null;
  }
  return null;
}

async function codeFor(id) {
  const existing = await pool.query('SELECT code, scan_count FROM day2_qr_code WHERE entity_id = $1', [id]);
  if (existing.rows[0]) return existing.rows[0];
  const code = crypto.createHash('sha256').update(id).digest('hex').slice(0, 10);
  await pool.query('INSERT INTO day2_qr_code (entity_id, code) VALUES ($1, $2) ON CONFLICT DO NOTHING', [id, code]);
  return (await pool.query('SELECT code, scan_count FROM day2_qr_code WHERE entity_id = $1', [id])).rows[0];
}

const deviceOf = (ua) => (/mobi|android|iphone|ipad|ipod/i.test(ua || '') ? 'mobile' : 'desktop');

// Counts one scan atomically and records an aggregate (entity, UTC hour, device family) bucket; nothing personal.
async function countScan(id, ua) {
  const { rows } = await pool.query('UPDATE day2_qr_code SET scan_count = scan_count + 1 WHERE entity_id = $1 RETURNING scan_count', [id]);
  await pool.query(
    `INSERT INTO day2_qr_bucket (entity_id, hour_utc, device, count) VALUES ($1, date_trunc('hour', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC', $2, 1)
     ON CONFLICT (entity_id, hour_utc, device) DO UPDATE SET count = day2_qr_bucket.count + 1`, [id, deviceOf(ua)],
  );
  metrics.inc('nascon_qr_scans_total');
  return Number(rows[0].scan_count);
}

async function loadEntity(id) {
  // A code row only exists for entities that resolved before, so reading both at once is safe: the entity must still resolve.
  const [ent, known] = await Promise.all([resolve(id), ID_RE.test(id) ? pool.query('SELECT code, scan_count FROM day2_qr_code WHERE entity_id = $1', [id]) : null]);
  if (!ent) throw notFound();
  const qr = known && known.rows[0] ? known.rows[0] : await codeFor(id);
  return { ent, shortUrl: `${ORIGIN}/q/${qr.code}`, scans: Number(qr.scan_count) };
}

// Minimal hand-written PDF: the id and short URL as plain text, the QR drawn as vector squares (no compression, no images).
function pdfBuffer(id, shortUrl) {
  const esc = (t) => t.replace(/[\\()]/g, (c) => `\\${c}`);
  const qr = QRCode.create(shortUrl, { errorCorrectionLevel: 'M' }).modules;
  const cell = 6;
  const quiet = 4 * cell;
  const origin = 40;
  const side = qr.size * cell + 2 * quiet;
  let draw = `1 g\n${origin} ${origin} ${side} ${side} re f\n0 g\n`;
  for (let r = 0; r < qr.size; r += 1) {
    for (let c = 0; c < qr.size; c += 1) {
      if (qr.get(r, c)) draw += `${origin + quiet + c * cell} ${origin + quiet + (qr.size - 1 - r) * cell} ${cell} ${cell} re f\n`;
    }
  }
  const top = origin + side + 20;
  const content = `${draw}BT /F1 14 Tf 40 ${top + 18} Td (${esc(id)}) Tj ET\nBT /F1 10 Tf 40 ${top} Td (${esc(shortUrl)}) Tj ET\n`;
  const objs = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${side + 80} ${top + 60}] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>`,
    `<< /Length ${Buffer.byteLength(content)} >>\nstream\n${content}endstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
  ];
  let out = '%PDF-1.4\n';
  const offsets = [];
  objs.forEach((o, i) => { offsets.push(Buffer.byteLength(out)); out += `${i + 1} 0 obj\n${o}\nendobj\n`; });
  const xref = Buffer.byteLength(out);
  out += `xref\n0 ${objs.length + 1}\n0000000000 65535 f \n${offsets.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join('')}`;
  out += `trailer\n<< /Size ${objs.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(out, 'latin1');
}

module.exports = (router) => {
  const meta = async (req, res, next) => {
    try {
      const id = req.params.id;
      const { ent, shortUrl, scans } = await loadEntity(id);
      const count = req.query.inspect === 'true' ? scans : await countScan(id, req.get('user-agent'));
      const base = `${ORIGIN}/audit/v1/qr/${id}`;
      res.json({
        entity_id: id,
        entity_type: ent.type,
        entity_status: ent.status,
        short_url: shortUrl,
        target_route: ent.route,
        deep_link_uri: `nascon://entity/${id}`,
        scan_count: count,
        export_formats: { svg: `${base}/export.svg`, png: `${base}/export.png`, pdf: `${base}/export.pdf` },
        signature: signQr({ entity_id: id, short_url: shortUrl, target_route: ent.route }),
      });
    } catch (e) { next(e); }
  };
  router.get('/audit/v1/qr/:id', meta);

  router.get('/audit/v1/qr/:id/export.:ext', async (req, res, next) => {
    try {
      const { ext } = req.params;
      if (!['svg', 'png', 'pdf'].includes(ext)) {
        throw new ApiError(400, 'VALIDATION_ERROR', 'Unsupported export format.', { fields: ['ext'] });
      }
      const { shortUrl } = await loadEntity(req.params.id);
      res.set('Cache-Control', 'no-store');
      if (ext === 'svg') {
        res.type('image/svg+xml').send(await QRCode.toString(shortUrl, { type: 'svg', margin: 2, width: 512 }));
        return;
      }
      if (ext === 'pdf') { res.type('application/pdf').send(pdfBuffer(req.params.id, shortUrl)); return; }
      res.type('image/png').send(await QRCode.toBuffer(shortUrl, { type: 'png', margin: 2, width: 512 }));
    } catch (e) { next(e); }
  });

  router.get('/q/:code', async (req, res, next) => {
    try {
      const { rows } = await pool.query('SELECT entity_id FROM day2_qr_code WHERE code = $1', [req.params.code]);
      if (!rows.length) throw notFound();
      const ent = await resolve(rows[0].entity_id);
      if (!ent) throw notFound();
      await countScan(rows[0].entity_id, req.get('user-agent'));
      res.redirect(302, ent.route);
    } catch (e) { next(e); }
  });
};
