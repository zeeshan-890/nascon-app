// In-process deterministic mock gateway. Ids come from the Postgres sequence payment_seq
// (restarted by /reset) and are consumed only when an authorization is granted.

const TOKENS = ['tok_success', 'tok_declined', 'tok_capture_fail'];

// db: the request's pool client (or the pool). Returns { ok, authorization_id }.
async function authorize(token, db) {
  if (token === 'tok_declined' || !TOKENS.includes(token)) return { ok: false, reason: 'declined' };
  const { rows } = await db.query(`SELECT nextval('payment_seq')::int AS n`);
  return { ok: true, authorization_id: `auth_${rows[0].n}` };
}

async function capture(authorizationId, token) {
  if (token === 'tok_success') return { ok: true, capture_id: `cap_${authorizationId.replace(/^auth_/, '')}` };
  return { ok: false, reason: 'capture_failed' };
}

async function voidAuthorization() {
  return { ok: true };
}

async function refund() {
  return { ok: true };
}

module.exports = { TOKENS, authorize, capture, voidAuthorization, refund };
