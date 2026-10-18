'use strict';

// Pure helpers for hybrid (group stage -> knockout) leagues.

/** Teams are dealt to groups in position order: team i goes to group i % groups. */
function splitGroups(teams, groups) {
  const out = Array.from({ length: groups }, () => []);
  teams.forEach((t, i) => out[i % groups].push(t));
  return out;
}

/**
 * tables: one standings table per group (rows with team_id, rank, points, diff, scored), in group order.
 * Returns the advancing teams in seed order: every group winner first, then every runner-up, and so on.
 * Within one rank, better points, score difference and scored come first, then group order.
 */
function seedFromGroups(tables, advance) {
  const seeds = [];
  for (let rank = 1; rank <= advance; rank += 1) {
    const tier = tables
      .map((table, group) => ({ group, row: table.find((r) => r.rank === rank) }))
      .filter((x) => x.row)
      .sort((a, b) => b.row.points - a.row.points || b.row.diff - a.row.diff || b.row.scored - a.row.scored || a.group - b.group);
    for (const { group, row } of tier) seeds.push({ seed: seeds.length + 1, team_id: row.team_id, group: group + 1, rank });
  }
  return seeds;
}

module.exports = { splitGroups, seedFromGroups };
