const { invalid } = require('./validate');
const {
  nextPow2, ref, slotOf, newMatch, newContext, decide, buildTree, resolveRoundOneByes, applyResults,
} = require('./bracketCommon');

function generateDoubleElim(teams, params = {}) {
  if (params.bracket_reset !== undefined && typeof params.bracket_reset !== 'boolean') {
    throw invalid(['params.bracket_reset']);
  }
  const reset = params.bracket_reset === true;
  const size = nextPow2(teams.length);
  const k = Math.log2(size);
  const ctx = newContext();

  const winners = buildTree(ctx, {
    prefix: 'WR',
    bracket: 'winners',
    teams,
    size,
    finalWinnerTo: ref('GF1', 'home'),
    loserTo: (r, i) => (r === 1 ? ref(`LR1M${Math.ceil(i / 2)}`, slotOf(i)) : ref(`LR${2 * r - 2}M${i}`, 'away')),
  });

  const losers = [];
  for (let round = 1; round <= 2 * (k - 1); round += 1) {
    const j = Math.ceil(round / 2);
    const count = size / 2 ** (j + 1);
    const list = [];
    for (let i = 1; i <= count; i += 1) {
      const m = newMatch(`LR${round}M${i}`, 'losers', round, i);
      if (round === 2 * (k - 1)) m.winner_to = ref('GF1', 'away');
      else if (round % 2 === 1) m.winner_to = ref(`LR${round + 1}M${i}`, 'home');
      else m.winner_to = ref(`LR${round + 1}M${Math.ceil(i / 2)}`, slotOf(i));
      ctx.matches.set(m.match_id, m);
      list.push(m);
    }
    losers.push(list);
  }

  const gf1 = newMatch('GF1', 'grand_final', 1, 1);
  const grand = [gf1];
  let gf2 = null;
  if (reset) {
    gf1.winner_to = ref('GF2', 'home');
    gf1.loser_to = ref('GF2', 'away');
    gf2 = newMatch('GF2', 'grand_final', 2, 1);
    gf2.conditional = true;
    grand.push(gf2);
  }
  for (const m of grand) ctx.matches.set(m.match_id, m);
  for (const m of ctx.matches.values()) {
    m.conditional = m.conditional === true;
    m.required = m.conditional ? null : true;
  }

  const loserMatches = losers.flat();
  // Dead-slot pass: a losers match fed by a bye's missing loser either auto-advances its other
  // team or, when both slots are dead, is itself dead and kills the slot it feeds.
  function settle() {
    let changed = true;
    while (changed) {
      changed = false;
      for (const m of loserMatches) {
        if (m.winner === null && !ctx.deadMatches.has(m.match_id)) {
          const homeDead = ctx.dead.has(`${m.match_id}:home`);
          const awayDead = ctx.dead.has(`${m.match_id}:away`);
          if (homeDead && awayDead) {
            ctx.deadMatches.add(m.match_id);
            m.bye = true;
            ctx.dead.add(`${m.winner_to.match_id}:${m.winner_to.slot}`);
            changed = true;
          } else if ((homeDead && m.away !== null) || (awayDead && m.home !== null)) {
            m.bye = true;
            decide(ctx, m, homeDead ? m.away : m.home);
            changed = true;
          }
        }
      }
    }
    if (gf2) {
      gf2.home = gf1.home;
      gf2.away = gf1.away;
      gf2.home_seed = gf1.home_seed;
      gf2.away_seed = gf1.away_seed;
      gf2.required = gf1.winner === null ? null : gf1.winner === gf1.away;
    }
  }

  resolveRoundOneByes(ctx, winners[0]);
  settle();
  if (params.results !== undefined) applyResults(ctx, params.results, settle);

  return {
    format: 'double_elim',
    bracket_size: size,
    bracket_reset: reset,
    winners: winners.map((matches, idx) => ({ round: idx + 1, matches })),
    losers: losers.map((matches, idx) => ({ round: idx + 1, matches })),
    grand_final: grand,
  };
}

module.exports = { generateDoubleElim };
