const { api } = require('./app');

let counter = 0;
const uid = (p = 'x') => `${p}-${Date.now().toString(36)}-${(counter += 1)}`;

async function post(body) {
  return (await api()).post('/audit/v1/events').send(body);
}
async function getScore(id) {
  return (await api()).get(`/audit/v1/score/${id}`);
}
async function createSport(def) {
  const base = {
    name: def.sport_id,
    draw_allowed: true,
    overtime: { policy: 'none' },
    roster: { min_squad_size: 1, max_squad_size: 20, required_positions: {} },
  };
  const r = await (await api()).post('/audit/v1/sports').send({ ...base, ...def });
  if (r.status !== 201) throw new Error(`sport create failed ${r.status} ${JSON.stringify(r.body)}`);
  return r;
}

// Builds a match helper bound to one match id.
function mk(match_id, sport_id, home = 'H', away = 'A') {
  const m = { match_id, sport_id, home, away };
  m.send = (type, payload, event_id) => post({ event_id: event_id || uid('e'), match_id, type, payload });
  m.init = (event_id, extra = {}) =>
    post({ event_id: event_id || uid('init'), match_id, type: 'match_init',
      payload: { sport_id, home_team_id: home, away_team_id: away, ...extra } });
  m.start = (id) => m.send('match_start', {}, id);
  m.end = (id) => m.send('match_end', {}, id);
  m.score = (team, event_type, id) => m.send('score', { team_id: team, event_type }, id);
  m.discipline = (team, points, id) => m.send('discipline', { team_id: team, points }, id);
  m.tiebreak = (team, id) => m.send('tiebreak_winner', { team_id: team }, id);
  m.comp = (target, reason = 'oops', id) => m.send('compensation', { compensates: target, reason }, id);
  m.get = () => getScore(match_id);
  m.begin = async () => {
    const a = await m.init();
    const b = await m.start();
    if (a.status !== 201 || b.status !== 201) throw new Error(`begin failed ${a.status} ${b.status}`);
  };
  return m;
}

function expectError(res, status, code) {
  expect(res.status).toBe(status);
  expect(res.body.error.code).toBe(code);
  expect(typeof res.body.error.message).toBe('string');
  expect(res.body.error.details).toEqual(expect.any(Object));
}

module.exports = { uid, post, getScore, createSport, mk, expectError };
