// Helpers for the production admin API tests: fresh logins, minted tokens, league/match shortcuts.
const { http, token, USERS, PASSWORD, pool } = require('./mobilekit');
const { signAccessToken } = require('../../backend/src/services/authService');

let counter = 0;
const uid = (p = 'x') => `${p}-${Date.now().toString(36)}-${(counter += 1)}`;

// A login that reads the user row again (league_ids may have grown since the cached token was issued).
async function freshToken(role) {
  const r = await (await http()).post('/api/v1/auth/login').send({ email: USERS[role], password: PASSWORD });
  if (r.status !== 200) throw new Error(`login ${role} failed ${r.status}`);
  return r.body.access_token;
}

// Token for an arbitrary principal (for example a user of the other tenant).
const mint = (user) => signAccessToken({
  user_id: 'u-minted', role: 'viewer', tenant_id: null, league_ids: [], club_id: null, player_id: null, ...user,
});

// asToken(token).get(path) / .post / .patch; asToken(null) sends no Authorization header.
function asToken(tok) {
  const make = (method) => async (path, body) => {
    let req = (await http())[method](path);
    if (tok) req = req.set('Authorization', `Bearer ${tok}`);
    if (body !== undefined) req = req.send(body);
    return req;
  };
  return { get: make('get'), post: make('post'), patch: make('patch'), delete: make('delete') };
}

const ROLES = ['admin', 'organizer', 'manager', 'player', 'viewer'];
const foreign = (role = 'league_organizer') => mint({ role, tenant_id: 'tenant-beta', league_ids: ['league-beta-1'], club_id: 'club-beta-1' });

const TEAMS4 = ['t1', 't2', 't3', 't4'];

// POST /leagues as admin; throws unless 201.
async function mkLeague(overrides = {}, tok) {
  const body = {
    league_id: uid('lg'), sport_id: 'football', name: 'Test League', kind: 'league', teams: TEAMS4, ...overrides,
  };
  const r = await asToken(tok || await token('admin')).post('/api/v1/leagues', body);
  if (r.status !== 201) throw new Error(`league create failed ${r.status} ${JSON.stringify(r.body)}`);
  return r.body;
}

const ev = (match_id, type, payload = {}) => ({ event_id: uid('e'), match_id, type, payload });

// Plays a stored fixture to the end; `homeGoals`/`awayGoals` score for the respective side.
async function playMatch(tok, match, homeGoals, awayGoals) {
  const api = asToken(tok);
  const id = match.match_id;
  const send = async (type, payload) => {
    const r = await api.post(`/api/v1/matches/${id}/events`, ev(id, type, payload));
    if (r.status !== 201) throw new Error(`event ${type} failed ${r.status} ${JSON.stringify(r.body)}`);
  };
  await send('match_start');
  for (let i = 0; i < homeGoals; i += 1) await send('score', { team_id: match.home_team_id, event_type: 'goal' });
  for (let i = 0; i < awayGoals; i += 1) await send('score', { team_id: match.away_team_id, event_type: 'goal' });
  await send('match_end');
}

module.exports = { uid, freshToken, mint, asToken, foreign, mkLeague, playMatch, ROLES, TEAMS4, pool };
