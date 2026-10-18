// Guest sessions, server-side guest cart staging (FR-4.3) and the cart-merge probe (POST /audit/v1/guest/cart-merge).
const crypto = require('crypto');
const pool = require('../../src/db/pool');
const { withTransaction } = require('../../src/db/tx');
const { ApiError } = require('../lib/errors');
const { guestWriteGuard, guestWriteAllowList, getGuestToken } = require('../lib/guestGuard');
const { signCartMerge } = require('../lib/sign');
const { invalid, nonEmptyString, bodyObject, isObject } = require('../lib/validate');
const decisions = require('../decisions');

guestWriteAllowList.push('/api/v1/guest/session', '/api/v1/guest/cart', '/audit/v1/guest/cart-merge');

const TTL_HOURS = 2;
const MAX_LINES = 10;
const CURRENCY = 'PKR';

const lock = (client, key) => client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [key]);
const isLine = (l) => isObject(l) && nonEmptyString(l.variant_id) && Number.isInteger(l.quantity) && l.quantity >= 1;

function parseMerge(req) {
  const b = bodyObject(req);
  const fields = [];
  if (!nonEmptyString(b.guest_token) || !b.guest_token.startsWith('guest_')) fields.push('guest_token');
  if (!nonEmptyString(b.registered_user_id)) fields.push('registered_user_id');
  if (!Array.isArray(b.guest_cart) || b.guest_cart.length < 1 || b.guest_cart.length > MAX_LINES) fields.push('guest_cart');
  else b.guest_cart.forEach((l, i) => { if (!isLine(l)) fields.push(`guest_cart[${i}]`); });
  if (b.test_write_block !== undefined && typeof b.test_write_block !== 'boolean') fields.push('test_write_block');
  if (fields.length) throw invalid(fields);
  const sums = new Map();
  for (const l of b.guest_cart) sums.set(l.variant_id, (sums.get(l.variant_id) || 0) + l.quantity);
  return { token: b.guest_token, user: b.registered_user_id, sums, block: b.test_write_block !== false };
}

// Runs the real guest write-guard decision for a guest mutation and reports the status it would answer.
function guardStatus(token) {
  let status = 200;
  const res = { status(c) { status = c; return this; }, type() { return this; }, json() { return this; } };
  guestWriteGuard({ method: 'POST', path: '/api/v1/chat', headers: { authorization: `Bearer ${token}` } }, res, () => {});
  return status;
}

async function merge(p) {
  const fingerprint = crypto.createHash('sha256')
    .update(JSON.stringify([p.user, [...p.sums.entries()].sort(([a], [b]) => (a < b ? -1 : 1))])).digest('hex');
  return withTransaction(async (client) => {
    await lock(client, `guest-token:${p.token}`);
    await client.query(
      `INSERT INTO day2_guest_token (token, expires_at) VALUES ($1, now() + interval '${TTL_HOURS} hours') ON CONFLICT DO NOTHING`, [p.token],
    );
    const row = (await client.query('SELECT merged, merge_fingerprint, merge_response FROM day2_guest_token WHERE token = $1', [p.token])).rows[0];
    if (row.merged) {
      if (row.merge_fingerprint === fingerprint) return JSON.parse(row.merge_response);
      throw new ApiError(409, 'GUEST_TOKEN_ALREADY_MERGED', 'This guest token has already been merged.', { guest_token: p.token });
    }
    const user = await client.query('SELECT 1 FROM players WHERE player_id = $1', [p.user]);
    if (!user.rowCount) throw new ApiError(404, 'PLAYER_NOT_FOUND', 'Registered user not found.', { registered_user_id: p.user });
    const ids = [...p.sums.keys()];
    const variants = (await client.query(
      'SELECT variant_id, price_minor, stock, reserved FROM variants WHERE variant_id = ANY($1::text[])', [ids],
    )).rows;
    const byId = new Map(variants.map((v) => [v.variant_id, v]));
    for (const id of ids) if (!byId.has(id)) throw new ApiError(404, 'VARIANT_NOT_FOUND', 'Variant not found.', { variant_id: id });

    await lock(client, `cart-user:${p.user}`);
    const have = new Map((await client.query('SELECT variant_id, quantity FROM day2_cart WHERE user_id = $1', [p.user])).rows
      .map((r) => [r.variant_id, r.quantity]));
    for (const [id, qty] of p.sums) {
      const v = byId.get(id);
      if ((have.get(id) || 0) + qty > v.stock - (decisions.CART_MERGE_RESERVES_STOCK ? v.reserved : 0)) {
        throw new ApiError(409, 'OUT_OF_STOCK', 'Not enough stock for this variant.', { variant_id: id });
      }
    }
    const verified = p.block ? guardStatus(p.token) === 403 : false;
    for (const [id, qty] of p.sums) {
      await client.query(
        `INSERT INTO day2_cart (user_id, variant_id, quantity) VALUES ($1, $2, $3)
         ON CONFLICT (user_id, variant_id) DO UPDATE SET quantity = day2_cart.quantity + EXCLUDED.quantity`, [p.user, id, qty],
      );
    }
    const cart = (await client.query(
      `SELECT c.variant_id, c.quantity, v.price_minor FROM day2_cart c JOIN variants v USING (variant_id)
       WHERE c.user_id = $1 ORDER BY c.variant_id`, [p.user],
    )).rows;
    const totalItems = cart.reduce((s, r) => s + r.quantity, 0);
    const code = verified ? 403 : decisions.WRITE_REJECTION_CODE_WHEN_NO_BLOCK_TEST;
    const body = {
      guest_token: p.token,
      write_block_verified: verified,
      write_rejection_code: code,
      cart_merged: true,
      active_cart: {
        user_id: p.user,
        currency: CURRENCY,
        total_items: totalItems,
        subtotal_minor: cart.reduce((s, r) => s + r.quantity * r.price_minor, 0),
        items: cart.map((r) => ({ variant_id: r.variant_id, quantity: r.quantity, unit_price_minor: r.price_minor })),
      },
    };
    body.signature = signCartMerge({
      cart_merged: true, guest_token: p.token, registered_user_id: p.user, total_items: totalItems, write_rejection_code: code,
    });
    await client.query(
      'UPDATE day2_guest_token SET merged = true, merged_user_id = $2, merge_fingerprint = $3, merge_response = $4 WHERE token = $1',
      [p.token, p.user, fingerprint, JSON.stringify(body)],
    );
    return body;
  });
}

async function stagedCart(token) {
  const { rows } = await pool.query('SELECT cart, expires_at FROM day2_guest_token WHERE token = $1 AND NOT erased', [token]);
  return rows[0] ? { items: rows[0].cart, expires_at: rows[0].expires_at.toISOString() } : { items: [], expires_at: null };
}

module.exports = (router) => {
  router.post('/api/v1/guest/session', async (req, res, next) => {
    try {
      const token = `guest_anon_tok_${crypto.randomBytes(8).toString('hex')}`;
      const { rows } = await pool.query(
        `INSERT INTO day2_guest_token (token, expires_at) VALUES ($1, now() + interval '${TTL_HOURS} hours') RETURNING expires_at`, [token],
      );
      res.status(201).json({ guest_token: token, role: 'guest', expires_at: rows[0].expires_at.toISOString() });
    } catch (e) { next(e); }
  });

  router.get('/api/v1/guest/cart', async (req, res, next) => {
    try {
      const token = getGuestToken(req);
      if (!token) throw new ApiError(401, 'UNAUTHENTICATED', 'A guest token is required.');
      res.json({ guest_token: token, ...(await stagedCart(token)) });
    } catch (e) { next(e); }
  });

  router.put('/api/v1/guest/cart', async (req, res, next) => {
    try {
      const token = getGuestToken(req);
      if (!token) throw new ApiError(401, 'UNAUTHENTICATED', 'A guest token is required.');
      const b = bodyObject(req);
      if (!Array.isArray(b.items) || b.items.length > MAX_LINES) throw invalid(['items']);
      const bad = [];
      b.items.forEach((l, i) => { if (!isLine(l) || l.quantity > 99) bad.push(`items[${i}]`); });
      if (bad.length) throw invalid(bad);
      const ids = [...new Set(b.items.map((l) => l.variant_id))];
      const found = await pool.query('SELECT variant_id FROM variants WHERE variant_id = ANY($1::text[])', [ids]);
      const known = new Set(found.rows.map((r) => r.variant_id));
      for (const id of ids) if (!known.has(id)) throw new ApiError(404, 'VARIANT_NOT_FOUND', 'Variant not found.', { variant_id: id });
      const items = b.items.map((l) => ({ variant_id: l.variant_id, quantity: l.quantity }));
      const { rows } = await pool.query(
        `INSERT INTO day2_guest_token (token, expires_at, cart) VALUES ($1, now() + interval '${TTL_HOURS} hours', $2::jsonb)
         ON CONFLICT (token) DO UPDATE SET cart = EXCLUDED.cart, erased = false RETURNING expires_at`, [token, JSON.stringify(items)],
      );
      res.json({ guest_token: token, items, expires_at: rows[0].expires_at.toISOString() });
    } catch (e) { next(e); }
  });

  router.post('/audit/v1/guest/cart-merge', async (req, res, next) => {
    try {
      res.json(await merge(parseMerge(req)));
    } catch (e) { next(e); }
  });
};
