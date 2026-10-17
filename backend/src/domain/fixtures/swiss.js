const { FixtureError, invalid, isObj, isInt } = require('./validate');
const { minCostPerfectMatching } = require('./matching');

const RESULTS = ['home', 'away', 'draw'];

function parseHistory(raw, teams, round) {
  const bad = () => invalid(['params.history']);
  if (!Array.isArray(raw) || raw.length !== round - 1) throw bad();
  const index = new Map(teams.map((t, i) => [t, i]));
  return raw.map((h, idx) => {
    if (!isObj(h) || h.round !== idx + 1 || !Array.isArray(h.matches)) throw bad();
    const bye = h.bye === undefined ? null : h.bye;
    const seen = new Set();
    const take = (team) => {
      if (!index.has(team) || seen.has(team)) throw bad();
      seen.add(team);
      return index.get(team);
    };
    if (bye !== null) take(bye);
    const matches = h.matches.map((m) => {
      if (!isObj(m) || !RESULTS.includes(m.result) || m.home === m.away) throw bad();
      return { home: take(m.home), away: take(m.away), result: m.result };
    });
    return { bye: bye === null ? null : index.get(bye), matches };
  });
}

/** Scores in half-points (win 2, draw 1, bye 2) and Buchholz (half-points) per team index. */
function computeStandings(teams, history) {
  const n = teams.length;
  const score = new Array(n).fill(0);
  for (const h of history) {
    if (h.bye !== null) score[h.bye] += 2;
    for (const m of h.matches) {
      if (m.result === 'home') score[m.home] += 2;
      else if (m.result === 'away') score[m.away] += 2;
      else {
        score[m.home] += 1;
        score[m.away] += 1;
      }
    }
  }
  const buchholz = new Array(n).fill(0);
  for (const h of history) {
    for (const m of h.matches) {
      buchholz[m.home] += score[m.away];
      buchholz[m.away] += score[m.home];
    }
  }
  const order = teams.map((_, i) => i)
    .sort((a, b) => score[b] - score[a] || buchholz[b] - buchholz[a] || a - b);
  const rank = new Array(n);
  order.forEach((t, r) => { rank[t] = r + 1; });
  const standings = order.map((t, r) => ({
    rank: r + 1, team_id: teams[t], score: score[t] / 2, buchholz: buchholz[t] / 2,
  }));
  return { score, rank, standings };
}

function parseParams(params, n) {
  const fields = [];
  const defaultTotal = Math.ceil(Math.log2(n));
  const totalRounds = params.total_rounds === undefined ? defaultTotal : params.total_rounds;
  if (!isInt(totalRounds) || totalRounds < 1 || totalRounds > n - 1) fields.push('params.total_rounds');
  const round = params.round === undefined ? 1 : params.round;
  if (!isInt(round) || round < 1 || (!fields.length && round > totalRounds)) fields.push('params.round');
  if (params.history !== undefined && !Array.isArray(params.history)) fields.push('params.history');
  if (fields.length) throw invalid(fields);
  return { totalRounds, round, history: params.history === undefined ? [] : params.history };
}

function pairRoundOne(teams) {
  const n = teams.length;
  const m = Math.floor(n / 2);
  const pairings = [];
  for (let i = 1; i <= m; i += 1) {
    pairings.push({ match_id: `SW-R1-M${i}`, home: teams[i - 1], away: teams[i + m - 1] });
  }
  return { pairings, bye: n % 2 === 1 ? teams[n - 1] : null };
}

function pairLaterRound(teams, history, standing, round) {
  const n = teams.length;
  const { score, rank } = standing;
  const met = new Set();
  const hadBye = new Set();
  for (const h of history) {
    if (h.bye !== null) hadBye.add(h.bye);
    for (const m of h.matches) {
      met.add(`${Math.min(m.home, m.away)}-${Math.max(m.home, m.away)}`);
    }
  }
  const everyone = teams.map((_, i) => i);
  const candidates = n % 2 === 1
    ? everyone.filter((i) => !hadBye.has(i)).sort((a, b) => score[a] - score[b] || b - a)
    : [null];

  for (const byeIdx of candidates) {
    const rest = everyone.filter((i) => i !== byeIdx);
    const edges = [];
    for (let a = 0; a < rest.length; a += 1) {
      for (let b = a + 1; b < rest.length; b += 1) {
        const x = rest[a];
        const y = rest[b];
        if (!met.has(`${x}-${y}`)) edges.push([a, b, Math.abs(score[x] - score[y])]);
      }
    }
    const pairs = minCostPerfectMatching(rest.length, edges);
    if (pairs) {
      const matched = pairs.map(([a, b]) => {
        const x = rest[a];
        const y = rest[b];
        return rank[x] < rank[y] ? [x, y] : [y, x];
      }).sort((p, q) => rank[p[0]] - rank[q[0]]);
      return {
        pairings: matched.map(([h, a], i) => ({ match_id: `SW-R${round}-M${i + 1}`, home: teams[h], away: teams[a] })),
        bye: byeIdx === null ? null : teams[byeIdx],
      };
    }
  }
  throw new FixtureError('NO_VALID_PAIRING', 'No valid pairing exists for this round', { round });
}

function generateSwiss(teams, params = {}) {
  const n = teams.length;
  const { totalRounds, round, history: rawHistory } = parseParams(params, n);
  const history = parseHistory(rawHistory, teams, round);
  const standing = computeStandings(teams, history);
  const { pairings, bye } = round === 1
    ? pairRoundOne(teams)
    : pairLaterRound(teams, history, standing, round);
  return {
    format: 'swiss', round, total_rounds: totalRounds, pairings, bye, standings: standing.standings,
  };
}

module.exports = { generateSwiss, computeStandings };
