'use strict';

const { isRealDate } = require('./dates');

const ID_RE = /^[A-Za-z0-9_.:-]{1,64}$/;
const ROLES = ['captain', 'vice_captain', 'starter', 'substitute'];
const TYPES = ['transfer', 'loan', 'release'];

const isObj = (v) => v !== null && typeof v === 'object' && !Array.isArray(v);
const isId = (v) => typeof v === 'string' && ID_RE.test(v);
const isText = (v) => typeof v === 'string' && v.length >= 1 && v.length <= 200;
const isInt = (v) => Number.isSafeInteger(v);

function collector() {
  const fields = [];
  return { fields, bad: (f) => { if (!fields.includes(f)) fields.push(f); } };
}

// Returns {fields} listing every bad dotted path (empty when the body is valid).
function validateSetup(body) {
  const { fields, bad } = collector();
  if (!isObj(body)) return { fields: ['body'] };

  const league = body.league;
  if (!isObj(league)) bad('league');
  else {
    if (!isId(league.league_id)) bad('league.league_id');
    if (!isId(league.tenant_id)) bad('league.tenant_id');
    if (!isText(league.sport_id)) bad('league.sport_id');
    const w = league.transfer_window;
    if (!isObj(w)) bad('league.transfer_window');
    else {
      const openOk = isRealDate(w.open);
      const closeOk = isRealDate(w.close);
      if (!openOk) bad('league.transfer_window.open');
      if (!closeOk) bad('league.transfer_window.close');
      if (openOk && closeOk && w.open > w.close) bad('league.transfer_window');
    }
    if (league.max_squad_size !== undefined && league.max_squad_size !== null
      && !(isInt(league.max_squad_size) && league.max_squad_size >= 1)) bad('league.max_squad_size');
    const rp = league.required_positions;
    if (rp !== undefined && rp !== null) {
      if (!isObj(rp)) bad('league.required_positions');
      else {
        for (const k of Object.keys(rp)) if (!(isInt(rp[k]) && rp[k] >= 0)) bad(`league.required_positions.${k}`);
      }
    }
  }

  const squadIds = new Set();
  if (!Array.isArray(body.squads)) bad('squads');
  else {
    const seen = new Set();
    body.squads.forEach((s, i) => {
      if (!isObj(s)) { bad(`squads.${i}`); return; }
      if (!isId(s.squad_id)) bad(`squads.${i}.squad_id`);
      else if (seen.has(s.squad_id)) bad(`squads.${i}.squad_id`);
      else { seen.add(s.squad_id); squadIds.add(s.squad_id); }
      if (!isId(s.club_id)) bad(`squads.${i}.club_id`);
      if (!isId(s.tenant_id)) bad(`squads.${i}.tenant_id`);
      if (!isText(s.name)) bad(`squads.${i}.name`);
    });
  }

  if (!Array.isArray(body.players)) bad('players');
  else {
    const seen = new Set();
    body.players.forEach((p, i) => {
      if (!isObj(p)) { bad(`players.${i}`); return; }
      if (!isId(p.player_id)) bad(`players.${i}.player_id`);
      else if (seen.has(p.player_id)) bad(`players.${i}.player_id`);
      else seen.add(p.player_id);
      if (!isText(p.name)) bad(`players.${i}.name`);
      if (!isText(p.position)) bad(`players.${i}.position`);
      if (!ROLES.includes(p.role)) bad(`players.${i}.role`);
      if (p.squad_id !== null && !(isId(p.squad_id) && squadIds.has(p.squad_id))) bad(`players.${i}.squad_id`);
    });
  }

  const mip = body.matches_in_progress;
  if (mip !== undefined && mip !== null) {
    if (!Array.isArray(mip)) bad('matches_in_progress');
    else {
      const seen = new Set();
      mip.forEach((m, i) => {
        if (!isObj(m)) { bad(`matches_in_progress.${i}`); return; }
        if (!isId(m.match_id)) bad(`matches_in_progress.${i}.match_id`);
        else if (seen.has(m.match_id)) bad(`matches_in_progress.${i}.match_id`);
        else seen.add(m.match_id);
        if (!(isId(m.home_squad_id) && squadIds.has(m.home_squad_id))) bad(`matches_in_progress.${i}.home_squad_id`);
        if (!(isId(m.away_squad_id) && squadIds.has(m.away_squad_id))) bad(`matches_in_progress.${i}.away_squad_id`);
      });
    }
  }
  return { fields };
}

function validateTransfer(body) {
  const { fields, bad } = collector();
  if (!isObj(body)) return { fields: ['body'] };
  for (const k of ['request_id', 'league_id', 'player_id', 'from_squad_id']) if (!isId(body[k])) bad(k);
  const typeOk = TYPES.includes(body.type);
  if (!typeOk) bad('type');
  if (!isRealDate(body.effective_date)) bad('effective_date');
  const hasEnd = body.loan_end_date !== undefined && body.loan_end_date !== null;
  if (hasEnd && !isRealDate(body.loan_end_date)) bad('loan_end_date');
  if (typeOk && body.type !== 'release') {
    if (!isId(body.to_squad_id)) bad('to_squad_id');
    else if (body.to_squad_id === body.from_squad_id) bad('to_squad_id');
  }
  if (body.type === 'loan') {
    if (!hasEnd) bad('loan_end_date');
    else if (isRealDate(body.loan_end_date) && isRealDate(body.effective_date) && body.loan_end_date <= body.effective_date) {
      bad('loan_end_date');
    }
  }
  if (!isText(body.reason)) bad('reason');
  if (!isText(body.requested_by)) bad('requested_by');
  return { fields };
}

// The body the idempotency check compares: known fields only, absent values as null.
function transferFingerprint(b) {
  const f = (v) => (v === undefined ? null : v);
  return {
    request_id: b.request_id, league_id: b.league_id, player_id: b.player_id, type: b.type,
    from_squad_id: b.from_squad_id, to_squad_id: f(b.to_squad_id), effective_date: b.effective_date,
    loan_end_date: f(b.loan_end_date), reason: b.reason, requested_by: b.requested_by,
  };
}

module.exports = { validateSetup, validateTransfer, transferFingerprint, ID_RE, ROLES, TYPES };
