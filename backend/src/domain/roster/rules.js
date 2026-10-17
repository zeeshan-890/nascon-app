'use strict';

const { squadMembers } = require('./replay');

// Checks 3-8 of a transfer request, in the exact order of the spec. Check 1 (schema) and
// check 2 (not found) are handled by the caller. Returns {status, code, message} or null.
function violation(code, message) {
  return { status: 409, code, message };
}

function checkCrossTenant(league, fromSquad, toSquad, type) {
  if (fromSquad.tenant_id !== league.tenant_id || (type !== 'release' && toSquad.tenant_id !== league.tenant_id)) {
    return violation('CROSS_TENANT_TRANSFER', 'Squad belongs to a different tenant than the league');
  }
  return null;
}

// `state` is the snapshot at effective_date (after due loan returns were applied).
function checkRules({ league, req, state }) {
  const { type, player_id: playerId, from_squad_id: from, to_squad_id: to, effective_date: date } = req;
  const player = state.get(playerId);

  if (!player || player.squad_id !== from) {
    return violation('NOT_IN_SQUAD', 'Player is not in the from squad on the effective date');
  }
  if (type !== 'release' && (date < league.window_open || date > league.window_close)) {
    return violation('TRANSFER_WINDOW_CLOSED', 'Effective date is outside the transfer window');
  }
  if (player.registration_type === 'loan') {
    return violation('PLAYER_ON_LOAN', 'Player is currently on loan');
  }
  if (type !== 'release' && squadMembers(state, to).length + 1 > league.max_squad_size) {
    return violation('SQUAD_FULL', 'Destination squad is full');
  }
  const required = Object.prototype.hasOwnProperty.call(league.required_positions, player.position)
    ? league.required_positions[player.position]
    : 0;
  const remaining = squadMembers(state, from).filter((p) => p.player_id !== playerId && p.position === player.position).length;
  if (remaining < required) {
    return violation('MIN_POSITION_VIOLATION', 'Squad would fall below its required players for this position');
  }
  return null;
}

// Deferred when the from squad is playing a live match; lowest match_id wins.
function blockingMatch(matches, fromSquadId) {
  const ids = matches
    .filter((m) => m.home_squad_id === fromSquadId || m.away_squad_id === fromSquadId)
    .map((m) => m.match_id)
    .sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  return ids.length ? ids[0] : null;
}

function buildRegistration(req, leagueId, state) {
  if (req.type === 'release') return null;
  const s = state;
  return {
    player_id: req.player_id,
    league_id: leagueId,
    squad_id: s.squad_id,
    parent_squad_id: s.parent_squad_id,
    registration_type: s.registration_type,
    start_date: s.start_date,
    loan_end_date: s.loan_end_date,
    role: s.role,
  };
}

module.exports = { checkRules, checkCrossTenant, blockingMatch, buildRegistration };
