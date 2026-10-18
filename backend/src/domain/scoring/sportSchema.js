'use strict';

const TIE_BREAK_TOKENS = ['h2h_points', 'h2h_diff', 'overall_diff', 'scored', 'fair_play', 'draw_lots'];
const POINT_FIELD = { event: 'points', innings: 'runs', round: 'rounds' };
const MODELS = ['event', 'innings', 'round'];
const OVERTIME_POLICIES = ['none', 'extra_time', 'shootout', 'golden_score', 'super_over'];
const PERIOD_KEYS = {
  event: ['count', 'duration_minutes'],
  innings: ['innings_per_team', 'overs_per_innings', 'balls_per_over', 'wickets_per_innings'],
  round: ['best_of'],
};
const SPORT_ID_RE = /^[a-z0-9_-]{2,40}$/;
const EVENT_KEY_RE = /^[a-z0-9_]{1,40}$/;

const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const isInt = (v) => typeof v === 'number' && Number.isInteger(v);
const hasOwn = (o, k) => Object.prototype.hasOwnProperty.call(o, k);

function normaliseTieBreak(arr) {
  if (!Array.isArray(arr)) return TIE_BREAK_TOKENS.slice();
  // draw_lots is always applied last, wherever (or whether) it was listed.
  const out = arr.filter((c) => c !== 'draw_lots');
  out.push('draw_lots');
  return out;
}

function validateSportConfig(body) {
  if (!isObj(body)) return { fields: ['body'], config: null };
  const bad = [];
  const add = (f) => { if (!bad.includes(f)) bad.push(f); };

  if (typeof body.sport_id !== 'string' || !SPORT_ID_RE.test(body.sport_id)) add('sport_id');
  if (typeof body.name !== 'string' || body.name.length < 1 || body.name.length > 80) add('name');
  const model = body.scoring_model;
  const modelOk = MODELS.includes(model);
  if (!modelOk) add('scoring_model');

  const periods = {};
  const events = {};
  if (!isObj(body.periods)) add('periods');
  else if (modelOk) {
    for (const k of PERIOD_KEYS[model]) {
      const v = body.periods[k];
      const ok = model === 'round' ? isInt(v) && v >= 1 && v % 2 === 1 : isInt(v) && v >= 1;
      if (ok) periods[k] = v; else add(`periods.${k}`);
    }
  }

  const se = body.scoring_events;
  if (!isObj(se) || Object.keys(se).length === 0) add('scoring_events');
  else {
    const keys = Object.keys(se);
    if (keys.some((k) => !EVENT_KEY_RE.test(k))) add('scoring_events');
    if (modelOk) {
      for (const key of keys) {
        const val = se[key];
        if (!isObj(val)) { add(`scoring_events.${key}`); continue; }
        const out = {};
        const chk = (member, ok) => {
          if (ok) out[member] = val[member]; else add(`scoring_events.${key}.${member}`);
        };
        if (model === 'event') chk('points', isInt(val.points) && val.points >= 1);
        else if (model === 'round') chk('rounds', isInt(val.rounds) && val.rounds >= 1);
        else {
          chk('runs', isInt(val.runs) && val.runs >= 0);
          chk('legal_ball', typeof val.legal_ball === 'boolean');
          chk('wicket', typeof val.wicket === 'boolean');
        }
        events[key] = out;
      }
    }
  }

  if (typeof body.draw_allowed !== 'boolean') add('draw_allowed');

  const overtime = {};
  if (!isObj(body.overtime)) add('overtime');
  else {
    if (!OVERTIME_POLICIES.includes(body.overtime.policy)) add('overtime.policy');
    else overtime.policy = body.overtime.policy;
    if (hasOwn(body.overtime, 'duration_minutes') && body.overtime.duration_minutes !== undefined) {
      const d = body.overtime.duration_minutes;
      if (isInt(d) && d >= 0) overtime.duration_minutes = d; else add('overtime.duration_minutes');
    }
  }

  let tieBreak;
  if (body.tie_break_order !== undefined) {
    const t = body.tie_break_order;
    if (!Array.isArray(t) || t.some((x) => !TIE_BREAK_TOKENS.includes(x)) || new Set(t).size !== t.length) {
      add('tie_break_order');
    } else tieBreak = t;
  }

  const roster = {};
  if (!isObj(body.roster)) add('roster');
  else {
    const r = body.roster;
    const minOk = isInt(r.min_squad_size) && r.min_squad_size >= 1;
    const maxOk = isInt(r.max_squad_size) && r.max_squad_size >= 1;
    if (!minOk) add('roster.min_squad_size');
    if (!maxOk) add('roster.max_squad_size');
    if (minOk && maxOk && r.min_squad_size > r.max_squad_size) add('roster.min_squad_size');
    roster.min_squad_size = r.min_squad_size;
    roster.max_squad_size = r.max_squad_size;
    if (!isObj(r.required_positions)) add('roster.required_positions');
    else {
      const rp = {};
      for (const k of Object.keys(r.required_positions)) {
        const v = r.required_positions[k];
        if (k.length === 0) add('roster.required_positions');
        else if (!isInt(v) || v < 0) add(`roster.required_positions.${k}`);
        else rp[k] = v;
      }
      roster.required_positions = rp;
    }
  }

  if (bad.length) return { fields: bad, config: null };
  return {
    fields: [],
    config: {
      sport_id: body.sport_id,
      name: body.name,
      scoring_model: model,
      periods,
      scoring_events: events,
      draw_allowed: body.draw_allowed,
      overtime,
      tie_break_order: normaliseTieBreak(tieBreak),
      roster: {
        min_squad_size: roster.min_squad_size,
        max_squad_size: roster.max_squad_size,
        required_positions: roster.required_positions,
      },
    },
  };
}

module.exports = { validateSportConfig, normaliseTieBreak, TIE_BREAK_TOKENS, POINT_FIELD };
