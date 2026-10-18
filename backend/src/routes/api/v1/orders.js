const { Router } = require('express');
const { send } = require('../../../http/respond');
const AppError = require('../../../http/AppError');
const pool = require('../../../db/pool');
const config = require('../../../config');
const { isPlainObject } = require('../../../http/validators');
const { authenticate } = require('../../../middleware/auth');
const { guard, forbidden } = require('../../../middleware/guard');
const { openResource, orderResolver } = require('../../../http/resources');
const checkoutService = require('../../../services/checkoutService');
const store = require('../../../repos/store');
const { wrap } = require('./util');

const router = Router();

// Every variant must exist (404) and belong to a product of the caller's tenant (403).
async function assertVariantsInTenant(principal, items) {
  const ids = items.map((i) => i.variant_id);
  const { rows } = await pool.query(
    `SELECT v.variant_id, p.tenant_id FROM variants v JOIN products p ON p.product_id = v.product_id
     WHERE v.variant_id = ANY($1::text[])`,
    [ids],
  );
  const found = new Map(rows.map((r) => [r.variant_id, r.tenant_id]));
  for (const id of ids) {
    if (!found.has(id)) throw new AppError(404, 'VARIANT_NOT_FOUND', 'Variant not found', { variant_id: id });
  }
  if (principal.role !== 'super_admin') {
    for (const t of found.values()) if (t !== principal.tenant_id) throw forbidden();
  }
}

router.post('/checkout', authenticate, guard('order.create', openResource), wrap(async (req, res) => {
  if (!isPlainObject(req.body)) throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields: ['body'] });
  // The customer is always the authenticated user; a client-supplied customer_id is ignored.
  const body = { ...req.body, customer_id: req.principal.user_id };
  const parsed = checkoutService.parseRequest(body);
  await assertVariantsInTenant(req.principal, parsed.items);
  const out = await checkoutService.checkout(body);
  send(res, out.status, out.body);
}));

const iso = (d) => (d ? new Date(d).toISOString().replace(/\.\d{3}Z$/, 'Z') : null);

router.get('/orders/:id', authenticate, guard('order.read', orderResolver()), wrap(async (req, res) => {
  const { rows } = await pool.query(
    'SELECT order_id, status, subtotal_minor, discount_minor, total_minor, currency, paid_at, created_at FROM orders WHERE order_id = $1',
    [req.params.id],
  );
  const o = rows[0];
  const items = await store.getItems(pool, o.order_id);
  // Display names for the app's order screen; a variant that no longer exists simply has no name.
  const { rows: names } = await pool.query(
    `SELECT v.variant_id, p.name FROM variants v JOIN products p ON p.product_id = v.product_id
     WHERE v.variant_id = ANY($1::text[])`,
    [items.map((i) => i.variant_id)],
  );
  const nameOf = new Map(names.map((n) => [n.variant_id, n.name]));
  const { rows: history } = await pool.query(
    'SELECT from_status, to_status, at FROM order_status_history WHERE order_id = $1 ORDER BY id',
    [o.order_id],
  );
  send(res, 200, {
    order_id: o.order_id,
    status: o.status,
    items: items.map((i) => ({
      variant_id: i.variant_id, name: nameOf.get(i.variant_id) || null, quantity: i.quantity, unit_price_minor: i.unit_price_minor,
    })),
    subtotal_minor: o.subtotal_minor,
    discount_minor: o.discount_minor,
    total_minor: o.total_minor,
    currency: o.currency,
    paid_at: iso(o.paid_at),
    created_at: iso(o.created_at),
    status_history: history.map((h) => ({ from_status: h.from_status || null, to_status: h.to_status, at: iso(h.at) })),
  });
}));

// MinIO first (when the worker already uploaded it), the stored base64 otherwise.
async function receiptBytes(orderId, objectKey) {
  if (objectKey) {
    try {
      const Minio = require('minio');
      const client = new Minio.Client({
        endPoint: config.minio.endpoint, port: config.minio.port, useSSL: false,
        accessKey: config.minio.accessKey, secretKey: config.minio.secretKey,
      });
      const download = (async () => {
        const stream = await client.getObject(config.minio.bucket, objectKey);
        const chunks = [];
        for await (const c of stream) chunks.push(c);
        return Buffer.concat(chunks);
      })();
      return await Promise.race([download, new Promise((_, rej) => setTimeout(() => rej(new Error('minio timeout')), 1500))]);
    } catch (e) { /* fall back to the stored copy */ }
  }
  const b64 = await store.receiptPdfBase64(pool, orderId);
  return b64 ? Buffer.from(b64, 'base64') : null;
}

router.get('/orders/:id/receipt.pdf', authenticate, guard('order.read', orderResolver()), wrap(async (req, res) => {
  const { rows } = await pool.query('SELECT receipt_object_key FROM orders WHERE order_id = $1', [req.params.id]);
  const bytes = await receiptBytes(req.params.id, rows[0].receipt_object_key);
  if (!bytes) throw new AppError(404, 'NOT_FOUND', 'Receipt not available for this order');
  res.status(200)
    .set('Content-Type', 'application/pdf')
    .set('Content-Disposition', `inline; filename="receipt-${req.params.id}.pdf"`)
    .set('Cache-Control', 'private, no-store')
    .send(bytes);
}));

module.exports = router;
