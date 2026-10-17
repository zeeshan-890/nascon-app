'use strict';

const stats = require('./stats');
const { orderTeams, withLots } = require('./ordering');
const { qualificationStatus } = require('./qualification');

// Pure: builds the whole standings response from projected matches. Nothing is cached.
// input: { league_id, sport_id, teams, scoring_model, periods, points, tie_break_order,
//          qualification_spots, lots_seed, matches: [projection] }
function computeStandings(input) {
  const points = { ...stats.DEFAULT_POINTS, ...(input.points || {}) };
  const teams = input.teams;
  const known = new Set(teams);
  const inLeague = input.matches.filter((m) => known.has(m.home.team_id) && known.has(m.away.team_id));
  const records = stats.recordsOf(inLeague);
  const teamStats = stats.teamStats(teams, records, input.scoring_model, input.periods, points);

  const remaining = new Map(teams.map((t) => [t, 0]));
  for (const m of inLeague) {
    if (stats.isPlayed(m)) continue;
    remaining.set(m.home.team_id, remaining.get(m.home.team_id) + 1);
    remaining.set(m.away.team_id, remaining.get(m.away.team_id) + 1);
  }

  const { order, resolvedBy } = orderTeams(teams, {
    stats: teamStats,
    records,
    model: input.scoring_model,
    periods: input.periods,
    points,
    tieBreak: input.tie_break_order,
    leagueId: input.league_id,
    lotsSeed: input.lots_seed,
  });

  const rows = order.map((t) => teamStats.get(t));
  const status = qualificationStatus(rows, remaining, points.win, input.qualification_spots);

  return {
    league_id: input.league_id,
    sport_id: input.sport_id,
    qualification_spots: input.qualification_spots,
    table: rows.map((s, i) => ({
      rank: i + 1,
      team_id: s.team_id,
      played: s.played,
      won: s.won,
      drawn: s.drawn,
      lost: s.lost,
      scored: s.scored,
      conceded: s.conceded,
      diff: s.diff,
      points: s.points,
      fair_play: s.fair_play,
      status: status.get(s.team_id),
      resolved_by: resolvedBy.has(s.team_id) ? resolvedBy.get(s.team_id) : null,
    })),
    h2h: stats.headToHead(teams, records, points),
  };
}

module.exports = { computeStandings, ...stats, orderTeams, withLots, qualificationStatus };
