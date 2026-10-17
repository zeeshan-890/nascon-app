// SQL only. Every function takes a pool or a transaction client as `db`.

async function getOrder(db, orderId, { forUpdate = false } = {}) {
  const { rows } = await db.query(`SELECT * FROM orders WHERE order_id = $1${forUpdate ? ' FOR UPDATE' : ''}`, [orderId]);
  return rows[0] || null;
}

async function deleteOrderRows(db, orderId) {
  for (const table of ['order_items', 'reservations', 'payments', 'order_status_history']) {
    await db.query(`DELETE FROM ${table} WHERE order_id = $1`, [orderId]);
  }
  await db.query('DELETE FROM orders WHERE order_id = $1', [orderId]);
}

async function heldReservationCount(db, orderId) {
  const { rows } = await db.query(`SELECT count(*)::int AS n FROM reservations WHERE order_id = $1 AND status = 'held'`, [orderId]);
  return rows[0].n;
}

async function existingVariantIds(db, ids) {
  const { rows } = await db.query('SELECT variant_id FROM variants WHERE variant_id = ANY($1::text[])', [ids]);
  return new Set(rows.map((r) => r.variant_id));
}

async function getActiveDiscount(db, code) {
  const { rows } = await db.query(
    `SELECT code, percent FROM discount_codes
     WHERE code = $1 AND (active_from IS NULL OR active_from <= now()) AND (active_to IS NULL OR active_to > now())`,
    [code]
  );
  return rows[0] || null;
}

// Conditional reservation: returns the variant row, or null when stock is insufficient.
async function reserveVariant(db, variantId, quantity) {
  const { rows } = await db.query(
    `UPDATE variants SET stock = stock - $2, reserved = reserved + $2
     WHERE variant_id = $1 AND stock >= $2 RETURNING variant_id, product_id, price_minor`,
    [variantId, quantity]
  );
  return rows[0] || null;
}

async function readStocks(db, ids) {
  const { rows } = await db.query('SELECT variant_id, stock FROM variants WHERE variant_id = ANY($1::text[])', [ids]);
  return new Map(rows.map((r) => [r.variant_id, r.stock]));
}

async function getSchedules(db, ids) {
  const { rows } = await db.query(
    'SELECT variant_id, price_minor, effective_from FROM price_schedules WHERE variant_id = ANY($1::text[])',
    [ids]
  );
  return rows;
}

async function getProductTenant(db, productId) {
  const { rows } = await db.query('SELECT tenant_id FROM products WHERE product_id = $1', [productId]);
  return rows[0] ? rows[0].tenant_id : null;
}

async function insertOrder(db, o) {
  await db.query(
    `INSERT INTO orders (order_id, customer_id, tenant_id, status, request_canonical, subtotal_minor, discount_minor, total_minor)
     VALUES ($1, $2, $3, 'pending', $4, $5, $6, $7)`,
    [o.order_id, o.customer_id, o.tenant_id, o.request_canonical, o.subtotal_minor, o.discount_minor, o.total_minor]
  );
}

async function insertItems(db, orderId, items) {
  for (const it of items) {
    await db.query(
      'INSERT INTO order_items (order_id, variant_id, quantity, unit_price_minor) VALUES ($1, $2, $3, $4)',
      [orderId, it.variant_id, it.quantity, it.unit_price_minor]
    );
  }
}

async function insertReservations(db, orderId, items, ttlSeconds) {
  for (const it of items) {
    await db.query(
      `INSERT INTO reservations (order_id, variant_id, quantity, status, expires_at)
       VALUES ($1, $2, $3, 'held', now() + ($4 || ' seconds')::interval)`,
      [orderId, it.variant_id, it.quantity, String(ttlSeconds)]
    );
  }
}

async function addHistory(db, orderId, from, to, actor) {
  await db.query(
    'INSERT INTO order_status_history (order_id, from_status, to_status, actor) VALUES ($1, $2, $3, $4)',
    [orderId, from, to, actor]
  );
}

async function insertPayment(db, { order_id, authorization_id = null, capture_id = null, state, token }) {
  await db.query(
    'INSERT INTO payments (order_id, authorization_id, capture_id, state, token) VALUES ($1, $2, $3, $4, $5)',
    [order_id, authorization_id, capture_id, state, token]
  );
}

async function setPaymentState(db, orderId, state) {
  await db.query('UPDATE payments SET state = $2 WHERE order_id = $1 AND capture_id IS NOT NULL', [orderId, state]);
}

async function getCapturedPayment(db, orderId) {
  const { rows } = await db.query(
    `SELECT * FROM payments WHERE order_id = $1 AND state = 'captured' ORDER BY payment_id DESC LIMIT 1`,
    [orderId]
  );
  return rows[0] || null;
}

async function setOrderStatus(db, orderId, status) {
  await db.query('UPDATE orders SET status = $2 WHERE order_id = $1', [orderId, status]);
}

async function storeResponse(db, orderId, status, body) {
  await db.query('UPDATE orders SET response_status = $2, response = $3::json WHERE order_id = $1', [
    orderId, status, JSON.stringify(body),
  ]);
}

async function markPaid(db, orderId, paidAt, receiptCanonical) {
  await db.query(
    `UPDATE orders SET status = 'paid', paid_at = $2::timestamptz, receipt_canonical = $3 WHERE order_id = $1`,
    [orderId, paidAt, receiptCanonical]
  );
}

async function lockReservations(db, orderId, { onlyHeld = false, onlyExpired = false, skipLocked = false } = {}) {
  const conds = ['order_id = $1'];
  if (onlyHeld || onlyExpired) conds.push(`status = 'held'`);
  if (onlyExpired) conds.push('expires_at < now()');
  const { rows } = await db.query(
    `SELECT reservation_id, variant_id, quantity, status FROM reservations WHERE ${conds.join(' AND ')}
     ORDER BY variant_id, reservation_id FOR UPDATE${skipLocked ? ' SKIP LOCKED' : ''}`,
    [orderId]
  );
  return rows;
}

async function setReservationStatus(db, ids, status) {
  if (!ids.length) return;
  await db.query('UPDATE reservations SET status = $2 WHERE reservation_id = ANY($1::bigint[])', [ids, status]);
}

// Held stock goes back to the shelf (reservation released).
async function releaseStock(db, variantId, quantity) {
  await db.query('UPDATE variants SET stock = stock + $2, reserved = reserved - $2 WHERE variant_id = $1', [variantId, quantity]);
}

// Reservation turned into a sale.
async function commitReserved(db, variantId, quantity) {
  await db.query('UPDATE variants SET reserved = reserved - $2 WHERE variant_id = $1', [variantId, quantity]);
}

// Sold stock returned (refund/cancel of a paid order).
async function restock(db, variantId, quantity) {
  await db.query('UPDATE variants SET stock = stock + $2 WHERE variant_id = $1', [variantId, quantity]);
}

async function getItems(db, orderId) {
  const { rows } = await db.query(
    `SELECT oi.variant_id, oi.quantity, oi.unit_price_minor, p.category
     FROM order_items oi
     LEFT JOIN variants v ON v.variant_id = oi.variant_id
     LEFT JOIN products p ON p.product_id = v.product_id
     WHERE oi.order_id = $1 ORDER BY oi.variant_id`,
    [orderId]
  );
  return rows;
}

async function expiredHeldOrderIds(db, limit = 100) {
  const { rows } = await db.query(
    `SELECT DISTINCT order_id FROM reservations WHERE status = 'held' AND expires_at < now() LIMIT $1`,
    [limit]
  );
  return rows.map((r) => r.order_id);
}

async function getInventory(db, variantId) {
  const { rows } = await db.query('SELECT variant_id, stock, reserved FROM variants WHERE variant_id = $1', [variantId]);
  return rows[0] || null;
}

async function receiptPdfBase64(db, orderId) {
  const { rows } = await db.query(`SELECT response #>> '{receipt,pdf_base64}' AS pdf FROM orders WHERE order_id = $1`, [orderId]);
  return rows[0] ? rows[0].pdf : null;
}

async function setReceiptKey(db, orderId, key) {
  await db.query('UPDATE orders SET receipt_object_key = $2 WHERE order_id = $1', [orderId, key]);
}

module.exports = {
  getOrder, deleteOrderRows, heldReservationCount, existingVariantIds, getActiveDiscount, reserveVariant,
  readStocks, getSchedules, getProductTenant, insertOrder, insertItems, insertReservations, addHistory,
  insertPayment, setPaymentState, getCapturedPayment, setOrderStatus, storeResponse, markPaid,
  lockReservations, setReservationStatus, releaseStock, commitReserved, restock, getItems,
  expiredHeldOrderIds, getInventory, receiptPdfBase64, setReceiptKey,
};
