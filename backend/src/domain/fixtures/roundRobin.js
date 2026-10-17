const { invalid, isObj, isInt } = require('./validate');

const mod = (a, m) => ((a % m) + m) % m;

/** One leg of the cyclic construction; returns rounds of {home, away} index pairs plus the bye index. */
function buildLeg(n) {
  const k = n % 2 === 1 ? n : n - 1;
  const half = (k - 1) / 2;
  const rounds = [];
  for (let r = 0; r < k; r += 1) {
    const matches = [];
    let bye = null;
    for (let i = 0; i < k; i += 1) {
      const j = mod(r - i, k);
      if (i < j) {
        const iHome = mod(j - i, k) >= 1 && mod(j - i, k) <= half;
        matches.push(iHome ? { home: i, away: j, key: i } : { home: j, away: i, key: i });
      } else if (i === j) {
        if (n % 2 === 1) bye = i;
        else matches.push(r % 2 === 0 ? { home: n - 1, away: i, key: i } : { home: i, away: n - 1, key: i });
      }
    }
    matches.sort((a, b) => a.key - b.key);
    rounds.push({ matches, bye });
  }
  return { k, rounds };
}

function parseParams(params, n, roundCount) {
  const fields = [];
  const legs = params.legs === undefined ? 1 : params.legs;
  if (!isInt(legs) || (legs !== 1 && legs !== 2)) fields.push('params.legs');
  const policy = params.withdrawal_policy === undefined ? 'forfeit_remaining' : params.withdrawal_policy;
  if (policy !== 'void_all' && policy !== 'forfeit_remaining') fields.push('params.withdrawal_policy');
  return { legs, policy, fields };
}

function parseWithdrawals(raw, teams, roundCount) {
  if (raw === undefined) return { map: new Map(), bad: false };
  if (!Array.isArray(raw)) return { map: new Map(), bad: true };
  const map = new Map();
  let bad = false;
  for (const w of raw) {
    if (!isObj(w) || !teams.includes(w.team_id) || !isInt(w.after_round)
      || w.after_round < 0 || w.after_round > roundCount || map.has(w.team_id)) {
      bad = true;
    } else {
      map.set(w.team_id, w.after_round);
    }
  }
  return { map, bad };
}

function generateRoundRobin(teams, params = {}) {
  const n = teams.length;
  const leg = buildLeg(n);
  const { legs, policy, fields } = parseParams(params, n);
  const roundCount = leg.k * (legs === 2 ? 2 : 1);
  const { map: withdrawn, bad } = parseWithdrawals(params.withdrawals, teams, leg.k * (legs === 2 ? 2 : 1));
  if (bad) fields.push('params.withdrawals');
  if (fields.length) throw invalid(fields);

  const summary = { total: 0, scheduled: 0, forfeit: 0, voided: 0 };
  const rounds = [];
  for (let l = 1; l <= legs; l += 1) {
    leg.rounds.forEach((src, idx) => {
      const round = (l - 1) * leg.k + idx + 1;
      const matches = src.matches.map((m, mi) => {
        const swap = l === 2;
        const home = teams[swap ? m.away : m.home];
        const away = teams[swap ? m.home : m.away];
        let status = 'scheduled';
        let awardedTo = null;
        const wh = withdrawn.has(home) && (policy === 'void_all' || round > withdrawn.get(home));
        const wa = withdrawn.has(away) && (policy === 'void_all' || round > withdrawn.get(away));
        if (wh && wa) status = 'voided';
        else if (wh || wa) {
          if (policy === 'void_all') status = 'voided';
          else {
            status = 'forfeit';
            awardedTo = wh ? away : home;
          }
        }
        summary.total += 1;
        summary[status] += 1;
        return { match_id: `RR-L${l}-R${round}-M${mi + 1}`, leg: l, home, away, status, awarded_to: awardedTo };
      });
      rounds.push({ round, matches, byes: src.bye === null ? [] : [teams[src.bye]] });
    });
  }
  return { format: 'round_robin', legs, team_count: n, round_count: roundCount, rounds, summary };
}

module.exports = { generateRoundRobin, buildLeg };
