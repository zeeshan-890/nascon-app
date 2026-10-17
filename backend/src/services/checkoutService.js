const pool = require('../db/pool');
const logger = require('../logger');
const AppError = require('../http/AppError');
const { Id, nowIso, isPlainObject } = require('../http/validators');
const { canonical, sign } = require('../domain/crypto');
const { effectivePrice, computeTotals } = require('../domain/store/pricing');
const { buildReceiptPayload } = require('../domain/store/receipt');
const { generateReceiptPdf } = require('../domain/store/receiptPdf');
const store = require('../repos/store');
const outbox = require('../repos/outbox');
const gateway = require('./paymentGateway');
const { releaseOrder, enqueueStatus } = require('./reservationService');

const HOLD_SECONDS = 60;
let failureHook = null;

// Test hook: called right after Tx1 commits. Never set in production code.
function __setFailureHook(fn) {
  failureHook = fn || null;
}

function fail400(fields) {
  return new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields });
}

function findNul(value, path, out, depth = 0) {
  if (depth > 8) return;
  if (typeof value === 'string') {
    if (value.includes('\u0000')) out.push(path);
  } else if (Array.isArray(value)) {
    value.forEach((v, i) => findNul(v, path ? `${path}.${i}` : String(i), out, depth + 1));
  } else if (isPlainObject(value)) {
    for (const [k, v] of Object.entries(value)) findNul(v, path ? `${path}.${k}` : k, out, depth + 1);
  }
}

function parseRequest(body) {
  if (!isPlainObject(body)) throw fail400(['body']);
  const fields = [];
  const add = (f) => { if (!fields.includes(f)) fields.push(f); };

  if (!Id.safeParse(body.order_id).success) add('order_id');
  if (typeof body.customer_id !== 'string' || body.customer_id.length < 1 || body.customer_id.length > 200) add('customer_id');

  const items = body.items;
  if (!Array.isArray(items) || items.length < 1 || items.length > 10) {
    add('items');
  } else {
    const seen = new Set();
    let dup = false;
    items.forEach((it, i) => {
      if (!isPlainObject(it)) { add(`items.${i}`); return; }
      if (!Id.safeParse(it.variant_id).success) add(`items.${i}.variant_id`);
      else if (seen.has(it.variant_id)) dup = true;
      else seen.add(it.variant_id);
      if (!Number.isInteger(it.quantity) || it.quantity < 1 || it.quantity > 10) add(`items.${i}.quantity`);
    });
    if (dup) add('items');
  }

  const dc = body.discount_code;
  if (dc !== undefined && dc !== null && typeof dc !== 'string') add('discount_code');

  if (!isPlainObject(body.payment)) add('payment');
  else if (typeof body.payment.token !== 'string' || !gateway.TOKENS.includes(body.payment.token)) add('payment.token');

  const nul = [];
  findNul(body, '', nul);
  nul.forEach(add);

  if (fields.length) throw fail400(fields);
  return {
    order_id: body.order_id,
    customer_id: body.customer_id,
    items: items.map((i) => ({ variant_id: i.variant_id, quantity: i.quantity })),
    discount_code: dc === undefined ? null : dc,
    payment: { token: body.payment.token },
  };
}

const errorBody = (code, message, details) => ({ error: { code, message, details } });

function replayOf(order, requestCanonical) {
  if (order.request_canonical !== requestCanonical) {
    return new AppError(409, 'DUPLICATE_ORDER_ID', 'order_id already used with a different request');
  }
  return { status: order.response_status === 201 ? 200 : order.response_status, body: order.response };
}

async function rollbackQuietly(client) {
  try { await client.query('ROLLBACK'); } catch (e) { /* connection may be gone */ }
}

async function reserve(client, req, discount) {
  const sorted = [...req.items].sort((a, b) => (a.variant_id < b.variant_id ? -1 : 1));
  await client.query('BEGIN');
  const rows = new Map();
  let failed = null;
  for (const it of sorted) {
    const row = await store.reserveVariant(client, it.variant_id, it.quantity);
    if (!row) { failed = it; break; }
    rows.set(it.variant_id, row);
  }
  if (failed) {
    await client.query('ROLLBACK');
    const stocks = await store.readStocks(client, req.items.map((i) => i.variant_id));
    const short = req.items.find((i) => (stocks.get(i.variant_id) ?? 0) < i.quantity) || failed;
    throw new AppError(409, 'OUT_OF_STOCK', 'Insufficient stock', { variant_id: short.variant_id });
  }
  const ids = req.items.map((i) => i.variant_id);
  const schedules = await store.getSchedules(client, ids);
  const now = new Date();
  const priced = req.items.map((it) => {
    const v = rows.get(it.variant_id);
    const own = schedules.filter((s) => s.variant_id === it.variant_id);
    return { variant_id: it.variant_id, quantity: it.quantity, unit_price_minor: effectivePrice(v, own, now) };
  });
  const totals = computeTotals(priced, discount ? discount.percent : 0);
  const tenantId = await store.getProductTenant(client, rows.get(sorted[0].variant_id).product_id);
  await store.insertOrder(client, {
    order_id: req.order_id, customer_id: req.customer_id, tenant_id: tenantId,
    request_canonical: req.requestCanonical, ...totals,
  });
  const pricedSorted = [...priced].sort((a, b) => (a.variant_id < b.variant_id ? -1 : 1));
  await store.insertItems(client, req.order_id, pricedSorted);
  await store.insertReservations(client, req.order_id, pricedSorted, HOLD_SECONDS);
  await store.addHistory(client, req.order_id, null, 'pending', 'checkout');
  await client.query('COMMIT');
  return { priced, totals, sorted: pricedSorted };
}

// Failure path (Tx2-fail): restore held stock, cancel, record the failed payment, store the 402.
async function failOrder(client, req, payment, code, message) {
  const body = errorBody(code, message, { order_id: req.order_id });
  await client.query('BEGIN');
  await releaseOrder(client, req.order_id, { actor: 'checkout' });
  await store.insertPayment(client, {
    order_id: req.order_id, authorization_id: payment.authorization_id || null,
    state: payment.state, token: req.payment.token,
  });
  await store.storeResponse(client, req.order_id, 402, body);
  await client.query('COMMIT');
  return { status: 402, body };
}

async function checkoutLocked(client, req) {
  // Idempotency
  let existing = await store.getOrder(client, req.order_id);
  if (existing) {
    if (existing.response_status != null && existing.response) {
      const r = replayOf(existing, req.requestCanonical);
      if (r instanceof AppError) throw r;
      return r;
    }
    if ((await store.heldReservationCount(client, req.order_id)) > 0) {
      throw new AppError(409, 'DUPLICATE_ORDER_ID', 'order_id is still being processed');
    }
    await client.query('BEGIN');
    await store.deleteOrderRows(client, req.order_id);
    await client.query('COMMIT');
    existing = null;
  }

  // 404 then discount 400
  const found = await store.existingVariantIds(client, req.items.map((i) => i.variant_id));
  const missing = req.items.find((i) => !found.has(i.variant_id));
  if (missing) throw new AppError(404, 'VARIANT_NOT_FOUND', 'Variant not found', { variant_id: missing.variant_id });
  let discount = null;
  if (req.discount_code !== null) {
    discount = await store.getActiveDiscount(client, req.discount_code);
    if (!discount) throw new AppError(400, 'INVALID_DISCOUNT_CODE', 'Discount code is not valid');
  }

  const { priced, totals } = await reserve(client, req, discount);

  const money = { auth: null, capture: null };
  try {
    if (failureHook) await failureHook();

    const auth = await gateway.authorize(req.payment.token, client);
    if (!auth.ok) {
      return await failOrder(client, req, { state: 'declined' }, 'PAYMENT_DECLINED', 'Payment was declined');
    }
    money.auth = auth.authorization_id;
    const cap = await gateway.capture(auth.authorization_id, req.payment.token);
    if (!cap.ok) {
      await gateway.voidAuthorization(auth.authorization_id);
      money.auth = null;
      return await failOrder(
        client, req, { state: 'capture_failed', authorization_id: auth.authorization_id },
        'PAYMENT_CAPTURE_FAILED', 'Payment capture failed'
      );
    }
    money.capture = cap.capture_id;

    const paidAt = nowIso();
    const payload = buildReceiptPayload(
      { order_id: req.order_id, customer_id: req.customer_id, ...totals }, priced, paidAt
    );
    const signature = sign(req.order_id, payload);
    const pdf = await generateReceiptPdf(payload);
    const body = {
      order_id: req.order_id,
      status: 'paid',
      currency: 'PKR',
      items: payload.items,
      subtotal_minor: totals.subtotal_minor,
      discount_minor: totals.discount_minor,
      total_minor: totals.total_minor,
      payment: { authorization_id: auth.authorization_id, capture_id: cap.capture_id, state: 'captured' },
      receipt: { payload, signature, pdf_base64: pdf.toString('base64') },
    };

    await client.query('BEGIN');
    const held = await store.lockReservations(client, req.order_id);
    if (held.some((r) => r.status !== 'held')) {
      // The sweeper got there first: unwind without double-restoring, refund, report out of stock.
      await releaseOrder(client, req.order_id, { actor: 'checkout' });
      await store.insertPayment(client, {
        order_id: req.order_id, authorization_id: auth.authorization_id, capture_id: cap.capture_id,
        state: 'refunded', token: req.payment.token,
      });
      await client.query('COMMIT');
      await gateway.refund(cap.capture_id);
      money.capture = null;
      throw new AppError(409, 'OUT_OF_STOCK', 'Reservation expired before payment completed', {
        variant_id: held.find((r) => r.status !== 'held').variant_id,
      });
    }
    for (const r of held) await store.commitReserved(client, r.variant_id, r.quantity);
    await store.setReservationStatus(client, held.map((r) => r.reservation_id), 'committed');
    await store.markPaid(client, req.order_id, paidAt, canonical(payload));
    await store.insertPayment(client, {
      order_id: req.order_id, authorization_id: auth.authorization_id, capture_id: cap.capture_id,
      state: 'captured', token: req.payment.token,
    });
    await store.addHistory(client, req.order_id, 'pending', 'paid', 'checkout');
    await store.storeResponse(client, req.order_id, 201, body);
    await enqueueStatus(client, req.order_id, 'pending', 'paid');
    await outbox.enqueue(client, {
      aggregate_type: 'order', aggregate_id: req.order_id, event_type: 'receipt.generated',
      payload: { order_id: req.order_id },
    });
    await client.query('COMMIT');
    return { status: 201, body };
  } catch (err) {
    if (err instanceof AppError && err.status === 409) throw err;
    await rollbackQuietly(client);
    logger.error({ err: err.message, order_id: req.order_id }, 'checkout failed after reservation; compensating');
    await compensate(client, req, money);
    throw new AppError(500, 'INTERNAL_ERROR', 'Internal server error');
  }
}

// Safety net: give the stock back, cancel the order, undo any money taken. Never throws.
async function compensate(client, req, money) {
  try {
    if (money.capture) await gateway.refund(money.capture);
    else if (money.auth) await gateway.voidAuthorization(money.auth);
  } catch (e) {
    logger.error({ err: e.message }, 'gateway compensation failed');
  }
  try {
    await client.query('BEGIN');
    await releaseOrder(client, req.order_id, { actor: 'checkout-compensation' });
    if (money.auth || money.capture) {
      await store.insertPayment(client, {
        order_id: req.order_id, authorization_id: money.auth, capture_id: money.capture,
        state: money.capture ? 'refunded' : 'voided', token: req.payment.token,
      });
    }
    await client.query('COMMIT');
  } catch (e) {
    await rollbackQuietly(client);
    logger.error({ err: e.message, order_id: req.order_id }, 'stock compensation failed');
  }
}

async function checkout(body) {
  const parsed = parseRequest(body);
  const req = {
    ...parsed,
    requestCanonical: canonical({
      order_id: parsed.order_id,
      customer_id: parsed.customer_id,
      items: parsed.items,
      discount_code: parsed.discount_code,
      payment: parsed.payment,
    }),
  };

  const client = await pool.connect();
  let broken = false;
  try {
    await client.query('SET statement_timeout = 0');
    await client.query('SELECT pg_advisory_lock(hashtextextended($1, 0))', [req.order_id]);
    await client.query('SET statement_timeout = 4000');
    return await checkoutLocked(client, req);
  } catch (err) {
    await rollbackQuietly(client);
    if (!(err instanceof AppError) && err && err.code) broken = false;
    throw err;
  } finally {
    try {
      await client.query('SELECT pg_advisory_unlock(hashtextextended($1, 0))', [req.order_id]);
      await client.query('SET statement_timeout = 4000');
    } catch (e) {
      broken = true;
    }
    client.release(broken ? true : undefined);
  }
}

module.exports = { checkout, parseRequest, __setFailureHook };
