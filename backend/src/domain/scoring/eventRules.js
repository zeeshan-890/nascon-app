'use strict';

const { teamTotals } = require('./projection');

const fail = (status, code, message, details) => ({ ok: false, status, code, message, details: details || {} });
const TARGETABLE = ['score', 'discipline', 'tiebreak_winner'];

function validateAppend(sportConfig, match, events, request) {
  const type = request && request.type;
  const payload = (request && request.payload) || {};

  if (TARGETABLE.includes(type)) {
    if (payload.team_id !== match.home_team_id && payload.team_id !== match.away_team_id) {
      return fail(400, 'VALIDATION_ERROR', 'The team must be one of the two teams playing this match.', { fields: ['payload.team_id'] });
    }
  }
  if (type === 'score') {
    const se = sportConfig.scoring_events || {};
    if (typeof payload.event_type !== 'string' || !Object.prototype.hasOwnProperty.call(se, payload.event_type)) {
      return fail(400, 'UNKNOWN_EVENT_TYPE', 'This scoring event type is not defined for this sport.', {
        event_type: payload.event_type === undefined ? null : payload.event_type,
      });
    }
  }

  const totals = teamTotals(sportConfig, match, events);

  if (type === 'compensation') {
    const target = events.find((e) => e.event_id === payload.compensates);
    if (!target || !TARGETABLE.includes(target.type)) {
      return fail(409, 'INVALID_COMPENSATION', 'The event to compensate does not exist or cannot be compensated.', {
        compensates: payload.compensates === undefined ? null : payload.compensates,
      });
    }
    if (totals.compensatedBy.has(target.event_id)) {
      return fail(409, 'INVALID_COMPENSATION', 'That event has already been compensated.', {
        compensates: target.event_id,
        compensated_by: totals.compensatedBy.get(target.event_id),
      });
    }
    return { ok: true };
  }

  const status = totals.status;
  if (type === 'match_start') {
    if (status !== 'upcoming') {
      return fail(409, 'ILLEGAL_TRANSITION', 'The match can only be started when it is upcoming.', { status });
    }
  } else if (TARGETABLE.includes(type)) {
    if (status !== 'in_progress') {
      return fail(409, 'MATCH_NOT_IN_PROGRESS', 'The match is not in progress.', { status });
    }
  } else if (type === 'match_end') {
    if (status !== 'in_progress') {
      return fail(409, 'ILLEGAL_TRANSITION', 'The match can only be ended while it is in progress.', { status });
    }
    if (totals.home.score === totals.away.score && !sportConfig.draw_allowed && !totals.tiebreakWinnerTeamId) {
      return fail(409, 'TIE_NOT_ALLOWED', 'Scores are level and draws are not allowed; record a tiebreak winner first.', {});
    }
  }

  if (type === 'score') {
    const p = sportConfig.periods || {};
    if (sportConfig.scoring_model === 'innings') {
      const side = payload.team_id === match.home_team_id ? totals.home : totals.away;
      const maxBalls = p.innings_per_team * p.overs_per_innings * p.balls_per_over;
      const maxWickets = p.innings_per_team * p.wickets_per_innings;
      if (side.balls >= maxBalls || side.wickets >= maxWickets) {
        return fail(409, 'INNINGS_COMPLETE', 'This team has no balls or wickets remaining.', {
          team_id: side.team_id, balls: side.balls, wickets: side.wickets,
        });
      }
    } else if (sportConfig.scoring_model === 'round') {
      const needed = (p.best_of + 1) / 2;
      if (totals.home.score >= needed || totals.away.score >= needed) {
        return fail(409, 'SERIES_ALREADY_DECIDED', 'The series has already been decided.', { rounds_needed: needed });
      }
    }
  }
  return { ok: true };
}

module.exports = { validateAppend };
