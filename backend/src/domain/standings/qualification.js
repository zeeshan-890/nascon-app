'use strict';

// Points-only qualification status; tie-breaks are ignored.
// rows: [{team_id, points}], remaining: Map(team -> remaining fixtures), win: points for a win, spots: k.
function qualificationStatus(rows, remaining, win, spots) {
  const max = new Map(rows.map((r) => [r.team_id, r.points + win * (remaining.get(r.team_id) || 0)]));
  const status = new Map();
  for (const t of rows) {
    const others = rows.filter((o) => o.team_id !== t.team_id);
    const canCatch = others.filter((o) => max.get(o.team_id) >= t.points).length;
    const ahead = others.filter((o) => o.points > max.get(t.team_id)).length;
    if (canCatch < spots) status.set(t.team_id, 'clinched');
    else if (ahead >= spots) status.set(t.team_id, 'eliminated');
    else status.set(t.team_id, 'still_possible');
  }
  return status;
}

module.exports = { qualificationStatus };
