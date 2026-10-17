'use strict';

const DEFAULT_POINTS = { win: 3, draw: 1, loss: 0 };

// A fixture counts only once it is completed with a decided outcome; everything else is remaining.
function isPlayed(m) {
  return m.status === 'completed' && (m.outcome === 'home_win' || m.outcome === 'away_win' || m.outcome === 'draw');
}

function resultFor(outcome, isHome) {
  if (outcome === 'draw') return 'D';
  return (outcome === 'home_win') === isHome ? 'W' : 'L';
}

// One record per team per played match (two per match), from that team's point of view.
function recordsOf(matches) {
  const out = [];
  for (const m of matches) {
    if (!isPlayed(m)) continue;
    const sides = [[m.home, m.away, true], [m.away, m.home, false]];
    for (const [me, opp, isHome] of sides) {
      out.push({
        team: me.team_id,
        opp: opp.team_id,
        result: resultFor(m.outcome, isHome),
        scored: me.score,
        conceded: opp.score,
        balls: me.balls || 0,
        wickets: me.wickets || 0,
        oppBalls: opp.balls || 0,
        oppWickets: opp.wickets || 0,
        fair_play: me.fair_play || 0,
      });
    }
  }
  return out;
}

function pointsFor(result, points) {
  if (result === 'W') return points.win;
  if (result === 'D') return points.draw;
  return points.loss;
}

// Overs a batting side used in one match; an all-out innings counts as the full allocation.
function oversFaced(balls, wickets, periods) {
  const bpo = periods.balls_per_over;
  const ipt = periods.innings_per_team;
  const full = ipt * periods.overs_per_innings;
  if (wickets >= ipt * periods.wickets_per_innings) return full;
  return bpo > 0 ? balls / bpo : 0;
}

function emptyAcc() {
  return { played: 0, won: 0, drawn: 0, lost: 0, scored: 0, conceded: 0, fair_play: 0, oversFaced: 0, oversBowled: 0 };
}

// Sums records per team; `model`/`periods` are only needed for the innings overs totals.
function accumulate(records, model, periods) {
  const acc = new Map();
  for (const r of records) {
    let a = acc.get(r.team);
    if (!a) { a = emptyAcc(); acc.set(r.team, a); }
    a.played += 1;
    if (r.result === 'W') a.won += 1; else if (r.result === 'D') a.drawn += 1; else a.lost += 1;
    a.scored += r.scored;
    a.conceded += r.conceded;
    a.fair_play += r.fair_play;
    if (model === 'innings') {
      a.oversFaced += oversFaced(r.balls, r.wickets, periods);
      a.oversBowled += oversFaced(r.oppBalls, r.oppWickets, periods);
    }
  }
  return acc;
}

function round3(v) {
  const r = Number(v.toFixed(3));
  return r === 0 ? 0 : r;
}

// Score/point/round difference, or net run rate (3 decimals) for the innings model.
function diffOf(model, a) {
  if (!a) return 0;
  if (model !== 'innings') return a.scored - a.conceded;
  const forRate = a.oversFaced > 0 ? a.scored / a.oversFaced : 0;
  const againstRate = a.oversBowled > 0 ? a.conceded / a.oversBowled : 0;
  return round3(forRate - againstRate);
}

const pointsOf = (a, points) => (a ? a.won * points.win + a.drawn * points.draw + a.lost * points.loss : 0);

function teamStats(teams, records, model, periods, points) {
  const acc = accumulate(records, model, periods);
  const out = new Map();
  for (const t of teams) {
    const a = acc.get(t) || emptyAcc();
    out.set(t, {
      team_id: t, played: a.played, won: a.won, drawn: a.drawn, lost: a.lost, scored: a.scored, conceded: a.conceded,
      diff: diffOf(model, a), points: pointsOf(a, points), fair_play: a.fair_play,
    });
  }
  return out;
}

// h2h[a][b] for every ordered pair of distinct teams, zeros when they never met.
function headToHead(teams, records, points) {
  const h2h = {};
  for (const a of teams) {
    h2h[a] = {};
    for (const b of teams) if (a !== b) h2h[a][b] = { played: 0, points: 0, scored: 0, conceded: 0 };
  }
  for (const r of records) {
    const cell = h2h[r.team] && h2h[r.team][r.opp];
    if (!cell) continue;
    cell.played += 1;
    cell.points += pointsFor(r.result, points);
    cell.scored += r.scored;
    cell.conceded += r.conceded;
  }
  return h2h;
}

module.exports = {
  DEFAULT_POINTS, isPlayed, recordsOf, pointsFor, oversFaced, accumulate, diffOf, pointsOf, teamStats, headToHead, round3,
};
