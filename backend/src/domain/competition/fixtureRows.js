'use strict';

// Pure: flattens the output of a domain/fixtures generator into the rows persisted in `matches`.
// Only matches that can be played now are persisted: byes, voided matches and bracket matches whose
// participants are not known yet (or already decided) are skipped and counted in `skipped`.

const isMatch = (v) => v && typeof v === 'object' && !Array.isArray(v) && typeof v.match_id === 'string' && 'home' in v && 'away' in v;

function collectMatches(node, out = []) {
  if (Array.isArray(node)) node.forEach((n) => collectMatches(n, out));
  else if (isMatch(node)) out.push(node);
  else if (node && typeof node === 'object') Object.values(node).forEach((n) => collectMatches(n, out));
  return out;
}

const ref = (r, id) => (r ? { match_id: id(r.match_id), slot: r.slot } : null);

function roundRobinRows(output, id) {
  const rows = [];
  let voided = 0;
  for (const round of output.rounds) {
    for (const m of round.matches) {
      if (m.status === 'voided') {
        voided += 1;
        continue;
      }
      const slot = { stage: 'round_robin', leg: m.leg };
      if (m.status === 'forfeit') {
        slot.status = 'forfeit';
        slot.awarded_to = m.awarded_to;
      }
      rows.push({ match_id: id(m.match_id), home: m.home, away: m.away, round: round.round, bracket_slot: slot });
    }
  }
  return { rows, skipped: { byes: output.rounds.reduce((n, r) => n + (r.byes || []).length, 0), voided, pending: 0 } };
}

function swissRows(output, id) {
  const rows = output.pairings.map((p) => ({
    match_id: id(p.match_id), home: p.home, away: p.away, round: output.round,
    bracket_slot: { stage: 'swiss', total_rounds: output.total_rounds },
  }));
  return { rows, skipped: { byes: output.bye ? 1 : 0, voided: 0, pending: 0 } };
}

function bracketRows(output, id, stage) {
  const rows = [];
  const skipped = { byes: 0, voided: 0, pending: 0 };
  for (const m of collectMatches(output)) {
    if (m.bye) skipped.byes += 1;
    else if (m.home === null || m.away === null || m.winner !== null || (m.conditional && m.required !== true)) skipped.pending += 1;
    else {
      rows.push({
        match_id: id(m.match_id),
        home: m.home,
        away: m.away,
        round: m.round === undefined ? null : m.round,
        bracket_slot: {
          stage,
          bracket: m.bracket,
          index: m.index,
          home_seed: m.home_seed,
          away_seed: m.away_seed,
          winner_to: ref(m.winner_to, id),
          loser_to: ref(m.loser_to, id),
        },
      });
    }
  }
  return { rows, skipped };
}

/**
 * format: round_robin | swiss | single_elim | double_elim. `id` maps a generator match id to the stored one;
 * `stage` overrides the bracket stage name for single/double elimination (e.g. 'knockout').
 */
function fixtureRows(format, output, id, stage) {
  switch (format) {
    case 'round_robin': return roundRobinRows(output, id);
    case 'swiss': return swissRows(output, id);
    default: return bracketRows(output, id, stage || format);
  }
}

// Same bracket with every match id (and routing reference) mapped through `id`.
function renameBracket(output, id) {
  const walk = (n) => {
    if (Array.isArray(n)) return n.map(walk);
    if (isMatch(n)) {
      return { ...n, match_id: id(n.match_id), winner_to: ref(n.winner_to, id), loser_to: ref(n.loser_to, id) };
    }
    if (n && typeof n === 'object') return Object.fromEntries(Object.entries(n).map(([k, v]) => [k, walk(v)]));
    return n;
  };
  return walk(output);
}

module.exports = { fixtureRows, renameBracket, collectMatches };
