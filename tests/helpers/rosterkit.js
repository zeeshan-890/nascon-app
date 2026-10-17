const { api } = require('./app');

let counter = 0;
const uid = (p = 'x') => `${p}-${Date.now().toString(36)}-${(counter += 1)}`;

const P = (player_id, squad_id, position, role = 'starter') => ({ player_id, name: `Name ${player_id}`, position, squad_id, role });

// Default scenario: tenant t1 with squads A (3 players), B (1 player), C (empty), X (other tenant).
function baseSetup(league_id, over = {}) {
  return {
    league: {
      league_id, tenant_id: 't1', sport_id: 'football',
      transfer_window: { open: '2026-01-10', close: '2026-02-10' },
      max_squad_size: 4, required_positions: { gk: 1 },
      ...(over.league || {}),
    },
    squads: over.squads || [
      { squad_id: 'A', club_id: 'ca', tenant_id: 't1', name: 'Alpha' },
      { squad_id: 'B', club_id: 'cb', tenant_id: 't1', name: 'Bravo' },
      { squad_id: 'C', club_id: 'cc', tenant_id: 't1', name: 'Charlie' },
      { squad_id: 'X', club_id: 'cx', tenant_id: 't2', name: 'Xray' },
    ],
    players: over.players || [
      P('gk1', 'A', 'gk', 'captain'), P('df1', 'A', 'df', 'vice_captain'), P('df2', 'A', 'df'),
      P('gk2', 'B', 'gk'),
    ],
    ...(over.matches_in_progress ? { matches_in_progress: over.matches_in_progress } : {}),
  };
}

async function setup(league_id, over) {
  const r = await (await api()).post('/audit/v1/roster/setup').send(baseSetup(league_id, over));
  if (r.status !== 201) throw new Error(`setup failed ${r.status} ${JSON.stringify(r.body)}`);
  return r;
}

function req(league_id, over = {}) {
  return {
    request_id: uid('rq'), league_id, player_id: 'df1', type: 'transfer', from_squad_id: 'A', to_squad_id: 'B',
    effective_date: '2026-01-15', reason: 'because', requested_by: 'mgr-1', ...over,
  };
}

async function transfer(body) {
  return (await api()).post('/audit/v1/roster/transfer').send(body);
}

async function history(player_id, as_of) {
  const q = as_of ? `?as_of=${as_of}` : '';
  return (await api()).get(`/audit/v1/roster/history/${player_id}${q}`);
}

module.exports = { uid, P, baseSetup, setup, req, transfer, history };
