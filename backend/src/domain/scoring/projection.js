'use strict';

const { POINT_FIELD } = require('./sportSchema');

function payloadOf(e) {
  if (typeof e.payload_canonical === 'string') {
    try { return JSON.parse(e.payload_canonical); } catch (_) { /* fall through */ }
  }
  return e.payload || {};
}

function targetOf(e, payload) {
  if (e.compensates) return e.compensates;
  return (payload && payload.compensates) || null;
}

function teamTotals(sportConfig, match, events) {
  const model = sportConfig.scoring_model;
  const pointField = POINT_FIELD[model];
  const rules = sportConfig.scoring_events || {};

  const compensatedBy = new Map();
  const payloads = new Array(events.length);
  for (let i = 0; i < events.length; i++) {
    const e = events[i];
    payloads[i] = payloadOf(e);
    if (e.type === 'compensation') {
      const t = targetOf(e, payloads[i]);
      if (t && !compensatedBy.has(t)) compensatedBy.set(t, e.event_id);
    }
  }

  const mk = (id) => ({ team_id: id, score: 0, wickets: 0, balls: 0, fair_play: 0 });
  const home = mk(match.home_team_id);
  const away = mk(match.away_team_id);
  let hasStart = false;
  let hasEnd = false;
  let tiebreakWinnerTeamId = null;

  for (let i = 0; i < events.length; i++) {
    const e = events[i];
    const type = e.type;
    if (type === 'match_start') hasStart = true;
    else if (type === 'match_end') hasEnd = true;
    if (type === 'compensation' || compensatedBy.has(e.event_id)) continue;
    if (type !== 'score' && type !== 'discipline' && type !== 'tiebreak_winner') continue;
    const p = payloads[i];
    if (type === 'tiebreak_winner') {
      tiebreakWinnerTeamId = p.team_id === undefined ? null : p.team_id;
      continue;
    }
    let side = null;
    if (p.team_id === match.home_team_id) side = home;
    else if (p.team_id === match.away_team_id) side = away;
    if (!side) continue;
    if (type === 'discipline') {
      if (typeof p.points === 'number') side.fair_play += p.points;
    } else {
      const rule = Object.prototype.hasOwnProperty.call(rules, p.event_type) ? rules[p.event_type] : null;
      if (!rule) continue;
      side.score += rule[pointField] || 0;
      if (model === 'innings') {
        if (rule.legal_ball) side.balls += 1;
        if (rule.wicket) side.wickets += 1;
      }
    }
  }

  let status = 'upcoming';
  if (hasEnd) status = 'completed';
  else if (hasStart) status = 'in_progress';
  return { home, away, tiebreakWinnerTeamId, status, compensatedBy };
}

function project(sportConfig, match, events) {
  const t = teamTotals(sportConfig, match, events);
  const innings = sportConfig.scoring_model === 'innings';
  const bpo = innings && sportConfig.periods ? sportConfig.periods.balls_per_over : null;

  const side = (s) => ({
    team_id: s.team_id,
    score: s.score,
    wickets: innings ? s.wickets : null,
    balls: innings ? s.balls : null,
    overs: innings && bpo ? `${Math.floor(s.balls / bpo)}.${s.balls % bpo}` : null,
    fair_play: s.fair_play,
  });

  let winner = null;
  if (t.home.score > t.away.score) winner = match.home_team_id;
  else if (t.away.score > t.home.score) winner = match.away_team_id;
  else winner = t.tiebreakWinnerTeamId || null;

  let outcome;
  if (t.status !== 'completed') outcome = 'pending';
  else if (winner !== null) outcome = winner === match.home_team_id ? 'home_win' : 'away_win';
  else outcome = sportConfig.draw_allowed ? 'draw' : 'undecided';

  const out = new Array(events.length);
  for (let i = 0; i < events.length; i++) {
    const e = events[i];
    const by = t.compensatedBy.get(e.event_id) || null;
    out[i] = {
      sequence: e.sequence,
      event_id: e.event_id,
      type: e.type,
      payload: payloadOf(e),
      signature: e.signature,
      compensated: by !== null,
      compensated_by: by,
    };
  }

  return {
    match_id: match.match_id,
    sport_id: match.sport_id,
    league_id: match.league_id || null,
    status: t.status,
    outcome,
    winner_team_id: winner,
    tiebreak_winner_team_id: t.tiebreakWinnerTeamId,
    home: side(t.home),
    away: side(t.away),
    version: events.length,
    events: out,
  };
}

module.exports = { teamTotals, project };
