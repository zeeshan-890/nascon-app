const { invalid, isObj } = require('./validate');

const nextPow2 = (n) => {
  let b = 1;
  while (b < n) b *= 2;
  return b;
};

/** Standard seeding order: [1,2] -> flatMap(s => [s, newSize + 1 - s]) until length B. */
function seedOrder(size) {
  let order = [1, 2];
  while (order.length < size) {
    const newSize = order.length * 2;
    order = order.flatMap((s) => [s, newSize + 1 - s]);
  }
  return order;
}

const ref = (matchId, slot) => ({ match_id: matchId, slot });
const slotOf = (i) => (i % 2 === 1 ? 'home' : 'away');

function newMatch(id, bracket, round, index) {
  return {
    match_id: id,
    bracket,
    round,
    index,
    home: null,
    away: null,
    home_seed: null,
    away_seed: null,
    bye: false,
    winner: null,
    loser: null,
    winner_to: null,
    loser_to: null,
  };
}

function newContext() {
  return { matches: new Map(), dead: new Set(), deadMatches: new Set() };
}

function place(ctx, target, team, seed) {
  const m = ctx.matches.get(target.match_id);
  // The conditional second grand final mirrors GF1's teams; it is never filled by routing.
  if (m.conditional) return;
  m[target.slot] = team;
  m[`${target.slot}_seed`] = seed;
}

/** Records a winner (loser may be null for a bye) and routes both teams onward. */
function decide(ctx, m, winner) {
  const winnerIsHome = winner === m.home;
  const other = winnerIsHome ? m.away : m.home;
  m.winner = winner;
  m.loser = other;
  if (m.winner_to) place(ctx, m.winner_to, winner, winnerIsHome ? m.home_seed : m.away_seed);
  if (m.loser_to) {
    if (other === null) ctx.dead.add(`${m.loser_to.match_id}:${m.loser_to.slot}`);
    else place(ctx, m.loser_to, other, winnerIsHome ? m.away_seed : m.home_seed);
  }
}

/**
 * Builds the single-elimination tree of `size` slots with ids `${prefix}${r}M${i}`.
 * Returns an array of rounds, each an array of matches. Round-1 byes are resolved immediately.
 */
function buildTree(ctx, { prefix, bracket, teams, size, finalWinnerTo, loserTo }) {
  const k = Math.log2(size);
  const rounds = [];
  for (let r = 1; r <= k; r += 1) {
    const count = size / 2 ** r;
    const round = [];
    for (let i = 1; i <= count; i += 1) {
      const m = newMatch(`${prefix}${r}M${i}`, bracket, r, i);
      m.winner_to = r < k ? ref(`${prefix}${r + 1}M${Math.ceil(i / 2)}`, slotOf(i)) : finalWinnerTo;
      m.loser_to = loserTo ? loserTo(r, i) : null;
      ctx.matches.set(m.match_id, m);
      round.push(m);
    }
    rounds.push(round);
  }
  const order = seedOrder(size);
  const n = teams.length;
  rounds[0].forEach((m, idx) => {
    const a = order[2 * idx];
    const b = order[2 * idx + 1];
    const lo = Math.min(a, b);
    const hi = Math.max(a, b);
    m.home = teams[lo - 1];
    m.home_seed = lo;
    if (hi <= n) {
      m.away = teams[hi - 1];
      m.away_seed = hi;
    } else {
      m.bye = true;
    }
  });
  return rounds;
}

function resolveRoundOneByes(ctx, firstRound) {
  for (const m of firstRound) if (m.bye) decide(ctx, m, m.home);
}

/**
 * Applies results in passes so that input order is irrelevant. Anything that can never be
 * applied (unknown match, non-participant winner, duplicate, no participants) is a 400.
 */
function applyResults(ctx, results, afterApply) {
  const bad = () => invalid(['params.results']);
  if (!Array.isArray(results)) throw bad();
  const seen = new Set();
  for (const r of results) {
    if (!isObj(r) || typeof r.match_id !== 'string' || typeof r.winner !== 'string'
      || !ctx.matches.has(r.match_id) || seen.has(r.match_id)) throw bad();
    seen.add(r.match_id);
  }
  let pending = results;
  while (pending.length) {
    const next = [];
    for (const r of pending) {
      const m = ctx.matches.get(r.match_id);
      if (m.winner !== null) {
        if (!m.bye || r.winner !== m.winner) throw bad();
      } else if (m.home !== null && m.away !== null && (!m.conditional || m.required === true)) {
        if (r.winner !== m.home && r.winner !== m.away) throw bad();
        decide(ctx, m, r.winner);
        afterApply();
      } else {
        next.push(r);
      }
    }
    if (next.length === pending.length) throw bad();
    pending = next;
  }
}

module.exports = {
  nextPow2, seedOrder, ref, slotOf, newMatch, newContext, decide, buildTree, resolveRoundOneByes, applyResults,
};
