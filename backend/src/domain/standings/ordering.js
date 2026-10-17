'use strict';

const { lotsKey } = require('../crypto');
const { accumulate, diffOf, pointsOf } = require('./stats');

const EPS = 1e-9;

function withLots(order) {
  const list = Array.isArray(order) ? order.slice() : [];
  if (list[list.length - 1] !== 'draw_lots') list.push('draw_lots');
  return list;
}

// Orders teams by points, then resolves every group of equal points with the tie-break list.
// ctx: { stats: Map(team -> stats), records, model, periods, points, tieBreak, leagueId, lotsSeed }
// Returns { order: [team_id...], resolvedBy: Map(team_id -> criterion | null) }.
function orderTeams(teams, ctx) {
  const criteria = withLots(ctx.tieBreak);
  const resolvedBy = new Map();
  const keys = new Map();
  const lots = (t) => {
    if (!keys.has(t)) keys.set(t, lotsKey(ctx.lotsSeed, ctx.leagueId, t));
    return keys.get(t);
  };

  function valuesFor(criterion, group, groupAcc) {
    switch (criterion) {
      case 'h2h_points':
        return group.map((t) => pointsOf(groupAcc().get(t), ctx.points));
      case 'h2h_diff':
        return group.map((t) => diffOf(ctx.model, groupAcc().get(t)));
      case 'overall_diff':
        return group.map((t) => ctx.stats.get(t).diff);
      case 'scored':
        return group.map((t) => ctx.stats.get(t).scored);
      case 'fair_play':
        return group.map((t) => ctx.stats.get(t).fair_play);
      default:
        return group.map(lots);
    }
  }

  function resolve(group) {
    const members = new Set(group);
    let cached = null;
    // Mini-league: only the played matches among the members of this group.
    const groupAcc = () => {
      if (!cached) {
        cached = accumulate(ctx.records.filter((r) => members.has(r.team) && members.has(r.opp)), ctx.model, ctx.periods);
      }
      return cached;
    };

    for (const criterion of criteria) {
      const lotsCriterion = criterion === 'draw_lots';
      const vals = valuesFor(criterion, group, groupAcc);
      const idx = group.map((t, i) => i);
      // Best first: higher is better, except drawing of lots where the lower key wins.
      idx.sort((x, y) => {
        if (lotsCriterion) return vals[x] < vals[y] ? -1 : vals[x] > vals[y] ? 1 : 0;
        return vals[y] - vals[x];
      });
      const buckets = [];
      for (const i of idx) {
        const last = buckets[buckets.length - 1];
        const same = last && (lotsCriterion ? vals[last[0]] === vals[i] : Math.abs(vals[last[0]] - vals[i]) <= EPS);
        if (same) last.push(i); else buckets.push([i]);
      }
      if (buckets.length === 1) continue;

      const ordered = [];
      buckets.forEach((bucket, b) => {
        const sub = bucket.map((i) => group[i]);
        const done = sub.length > 1 ? resolve(sub) : sub;
        ordered.push(...done);
        if (b < buckets.length - 1) resolvedBy.set(done[done.length - 1], criterion);
      });
      return ordered;
    }
    // Unreachable in practice (lots keys differ); keeps the order deterministic regardless.
    return group.slice().sort();
  }

  const byPoints = new Map();
  for (const t of teams) {
    const p = ctx.stats.get(t).points;
    if (!byPoints.has(p)) byPoints.set(p, []);
    byPoints.get(p).push(t);
  }
  const order = [];
  for (const p of [...byPoints.keys()].sort((a, b) => b - a)) {
    const group = byPoints.get(p);
    order.push(...(group.length > 1 ? resolve(group) : group));
  }
  return { order, resolvedBy };
}

module.exports = { orderTeams, withLots };
