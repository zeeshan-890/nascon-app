'use strict';

// Pure: promotion and relegation places from a standings table (rows ordered by rank).
// config: { promotion?: { count, to }, relegation?: { count, to } }.
function promotionRelegation(table, config, final) {
  const rows = Array.isArray(table) ? table : [];
  const pick = (rule, from) => {
    if (!rule) return null;
    const count = Math.min(rule.count, rows.length);
    const chosen = from === 'top' ? rows.slice(0, count) : rows.slice(rows.length - count);
    return { count: rule.count, to: rule.to, teams: chosen.map((r) => ({ team_id: r.team_id, rank: r.rank })) };
  };
  return {
    final: Boolean(final),
    promotion: pick(config.promotion, 'top'),
    relegation: pick(config.relegation, 'bottom'),
  };
}

module.exports = { promotionRelegation };
