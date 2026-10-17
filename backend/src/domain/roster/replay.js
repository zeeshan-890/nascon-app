'use strict';

const { addDays } = require('./dates');

function fresh(p) {
  return {
    player_id: p.player_id,
    position: p.position,
    squad_id: p.squad_id === undefined ? null : p.squad_id,
    parent_squad_id: null,
    role: p.role,
    registration_type: 'initial',
    start_date: null,
    loan_end_date: null,
  };
}

// A loan that ended before `date` is over: the player is back with the lender.
function endLoan(s, endDate) {
  s.squad_id = s.parent_squad_id;
  s.parent_squad_id = null;
  s.role = 'substitute';
  s.registration_type = 'transfer';
  s.start_date = addDays(endDate, 1);
  s.loan_end_date = null;
}

function apply(s, e) {
  switch (e.action) {
    case 'transfer':
      s.squad_id = e.to_squad_id;
      s.parent_squad_id = null;
      s.role = 'substitute';
      s.registration_type = 'transfer';
      s.start_date = e.effective_date;
      s.loan_end_date = null;
      break;
    case 'loan':
      s.squad_id = e.to_squad_id;
      s.parent_squad_id = e.from_squad_id;
      s.role = 'substitute';
      s.registration_type = 'loan';
      s.start_date = e.effective_date;
      s.loan_end_date = e.loan_end_date;
      break;
    case 'loan_return':
      s.squad_id = e.to_squad_id;
      s.parent_squad_id = null;
      s.role = 'substitute';
      s.registration_type = 'transfer';
      s.start_date = e.effective_date;
      s.loan_end_date = null;
      break;
    case 'release':
      s.squad_id = null;
      s.parent_squad_id = null;
      s.registration_type = 'release';
      s.start_date = e.effective_date;
      s.loan_end_date = null;
      break;
    default:
      break;
  }
}

// setup: [{player_id, squad_id, role, position}], entries: audit entries in audit_seq order.
// Returns Map(player_id -> state) as of `date` (YYYY-MM-DD).
function snapshot(setup, entries, date) {
  const state = new Map();
  for (const p of setup) state.set(p.player_id, fresh(p));
  for (const e of entries) {
    if (e.effective_date > date) continue;
    const s = state.get(e.player_id);
    if (!s) continue;
    if (s.registration_type === 'loan' && e.effective_date > s.loan_end_date) endLoan(s, s.loan_end_date);
    apply(s, e);
  }
  for (const s of state.values()) {
    if (s.registration_type === 'loan' && date > s.loan_end_date) endLoan(s, s.loan_end_date);
  }
  return state;
}

function squadMembers(state, squadId) {
  const out = [];
  for (const s of state.values()) if (s.squad_id === squadId) out.push(s);
  return out;
}

function squadCounts(state, squadIds) {
  const counts = {};
  for (const id of squadIds) counts[id] = 0;
  for (const s of state.values()) if (s.squad_id !== null && s.squad_id in counts) counts[s.squad_id] += 1;
  return counts;
}

// Loan entries whose borrower return is due before `date` and that have no return yet.
function dueLoanReturns(entries, date) {
  const returned = new Set(entries.filter((e) => e.action === 'loan_return').map((e) => e.loan_audit_id));
  return entries.filter((e) => e.action === 'loan' && e.loan_end_date < date && !returned.has(e.audit_id));
}

module.exports = { snapshot, squadMembers, squadCounts, dueLoanReturns };
