const crypto = require('crypto');
const jwt = require('jsonwebtoken');
const bcrypt = require('bcryptjs');
const config = require('../config');
const pool = require('../db/pool');
const AppError = require('../http/AppError');
const { ROLES } = require('../domain/rbac/defaultPolicies');
const { createGate, sha } = require('../middleware/rateLimit');

const ACCESS_TTL_SECONDS = 12 * 3600;
const REFRESH_TTL_DAYS = 7;
const MAX_FAILED = 5;
const MAX_TOKEN_LENGTH = 8192;
// Compared against when the email is unknown so both failures cost the same.
const DUMMY_HASH = bcrypt.hashSync('dummy-password-for-timing', 10);

const loginGate = createGate({ prefix: 'lt:', limit: 10, windowMs: 5 * 60 * 1000 });

// Read per call so tests (and operators) can change it without a restart.
const lockSeconds = () => {
  const n = Number(process.env.LOGIN_LOCK_SECONDS);
  return Number.isFinite(n) && n > 0 ? n : 60;
};

// ---------- access tokens ----------
function signAccessToken(user, secret = config.jwtSecret) {
  return jwt.sign(
    {
      sub: user.user_id,
      role: user.role,
      tenant_id: user.tenant_id || null,
      league_ids: Array.isArray(user.league_ids) ? user.league_ids : [],
      club_id: user.club_id || null,
      player_id: user.player_id || null,
      jti: crypto.randomUUID(),
    },
    secret,
    { algorithm: 'HS256', expiresIn: ACCESS_TTL_SECONDS }
  );
}

// Returns a principal, or null for ANY problem (callers answer one identical 401).
function verifyAccessToken(token, secret = config.jwtSecret) {
  try {
    if (typeof token !== 'string' || token.length === 0 || token.length > MAX_TOKEN_LENGTH) return null;
    if (token.split('.').length !== 3) return null;
    const c = jwt.verify(token, secret, { algorithms: ['HS256'] });
    if (!c || typeof c !== 'object' || typeof c.exp !== 'number') return null;
    if (!ROLES.includes(c.role) || typeof c.sub !== 'string' || !c.sub) return null;
    return {
      user_id: c.sub,
      role: c.role,
      tenant_id: typeof c.tenant_id === 'string' ? c.tenant_id : null,
      league_ids: Array.isArray(c.league_ids) ? c.league_ids.filter((x) => typeof x === 'string') : [],
      club_id: typeof c.club_id === 'string' ? c.club_id : null,
      player_id: typeof c.player_id === 'string' ? c.player_id : null,
    };
  } catch (e) {
    return null;
  }
}

// ---------- DTOs ----------
const userDto = (u) => ({
  user_id: u.user_id,
  email: u.email,
  role: u.role,
  tenant_id: u.tenant_id || null,
  league_ids: u.league_ids || [],
  club_id: u.club_id || null,
  player_id: u.player_id || null,
});

async function tenantDto(tenantId) {
  if (!tenantId) return null;
  const { rows } = await pool.query(
    'SELECT tenant_id, name, logo_url, primary_color, secondary_color FROM tenants WHERE tenant_id = $1',
    [tenantId]
  );
  return rows[0] || null;
}

// ---------- refresh tokens ----------
const hashToken = (t) => crypto.createHash('sha256').update(t).digest('hex');

async function issueRefreshToken(userId) {
  const token = crypto.randomBytes(32).toString('base64url');
  await pool.query(
    'INSERT INTO refresh_tokens (token_hash, user_id, expires_at) VALUES ($1, $2, now() + make_interval(days => $3))',
    [hashToken(token), userId, REFRESH_TTL_DAYS]
  );
  return token;
}

const unauthenticated = (msg) => new AppError(401, 'UNAUTHENTICATED', msg);

function rateLimited(retryMs, message = 'Too many requests. Try again later.') {
  const seconds = Math.max(1, Math.ceil(retryMs / 1000));
  const err = new AppError(429, 'RATE_LIMITED', message, { retry_after_seconds: seconds });
  err.retryAfter = seconds;
  return err;
}

// ---------- login ----------
async function login({ email, password, ip }) {
  const gate = await loginGate.hit(`${ip}:${sha(email.toLowerCase())}`);
  if (!gate.allowed) throw rateLimited(gate.retryMs);

  const { rows } = await pool.query(
    `SELECT user_id, email, password_hash, role, tenant_id, club_id, player_id, league_ids,
            CASE WHEN locked_until > now() THEN CEIL(EXTRACT(EPOCH FROM (locked_until - now())))::int ELSE 0 END AS lock_remaining
       FROM users WHERE lower(email) = lower($1)`,
    [email]
  );
  const user = rows[0];
  if (!user) {
    await bcrypt.compare(password, DUMMY_HASH);
    throw unauthenticated('Email or password is incorrect');
  }
  if (user.lock_remaining > 0) {
    throw rateLimited(user.lock_remaining * 1000, 'Account temporarily locked. Try again later.');
  }

  const ok = await bcrypt.compare(password, user.password_hash);
  if (!ok) {
    // Atomic increment; a counter left over from an expired lock restarts at 1.
    await pool.query(
      `UPDATE users SET
         failed_logins = CASE WHEN locked_until IS NOT NULL AND locked_until <= now() THEN 1 ELSE failed_logins + 1 END,
         locked_until = CASE
           WHEN (CASE WHEN locked_until IS NOT NULL AND locked_until <= now() THEN 1 ELSE failed_logins + 1 END) >= $2
           THEN now() + make_interval(secs => $3) ELSE NULL END
       WHERE user_id = $1`,
      [user.user_id, MAX_FAILED, lockSeconds()]
    );
    throw unauthenticated('Email or password is incorrect');
  }
  await pool.query(
    'UPDATE users SET failed_logins = 0, locked_until = NULL WHERE user_id = $1 AND (failed_logins <> 0 OR locked_until IS NOT NULL)',
    [user.user_id]
  );

  return {
    access_token: signAccessToken(user),
    refresh_token: await issueRefreshToken(user.user_id),
    expires_in: ACCESS_TTL_SECONDS,
    user: userDto(user),
    tenant: await tenantDto(user.tenant_id),
  };
}

// ---------- refresh ----------
async function refresh(refreshToken) {
  const h = hashToken(refreshToken);
  const { rows } = await pool.query(
    `UPDATE refresh_tokens SET revoked_at = now()
      WHERE token_hash = $1 AND revoked_at IS NULL AND expires_at > now() RETURNING user_id`,
    [h]
  );
  if (!rows.length) {
    const prior = await pool.query('SELECT user_id, revoked_at FROM refresh_tokens WHERE token_hash = $1', [h]);
    if (prior.rows[0] && prior.rows[0].revoked_at) {
      // Reuse of a rotated/revoked token: assume theft and revoke the user's whole token family.
      await pool.query('UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL', [prior.rows[0].user_id]);
    }
    throw unauthenticated('Invalid refresh token');
  }
  const userId = rows[0].user_id;
  const u = await pool.query(
    'SELECT user_id, email, role, tenant_id, club_id, player_id, league_ids FROM users WHERE user_id = $1',
    [userId]
  );
  if (!u.rows[0]) throw unauthenticated('Invalid refresh token');
  return {
    access_token: signAccessToken(u.rows[0]),
    refresh_token: await issueRefreshToken(userId),
    expires_in: ACCESS_TTL_SECONDS,
  };
}

async function logout(refreshToken) {
  await pool.query('UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = $1 AND revoked_at IS NULL', [hashToken(refreshToken)]);
  return {};
}

async function me(principal) {
  const { rows } = await pool.query(
    'SELECT user_id, email, role, tenant_id, club_id, player_id, league_ids FROM users WHERE user_id = $1',
    [principal.user_id]
  );
  if (!rows[0]) throw unauthenticated('Authentication required');
  return { user: userDto(rows[0]), tenant: await tenantDto(rows[0].tenant_id) };
}

// Used by the reset: users are re-seeded, so login throttles start fresh too.
function resetThrottles() {
  loginGate.memory.clear();
}

module.exports = {
  signAccessToken, verifyAccessToken, login, refresh, logout, me, resetThrottles,
  userDto, ACCESS_TTL_SECONDS, hashToken,
};
