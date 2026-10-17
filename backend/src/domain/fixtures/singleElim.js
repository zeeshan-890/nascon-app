const { invalid } = require('./validate');
const {
  nextPow2, ref, newMatch, newContext, buildTree, resolveRoundOneByes, applyResults,
} = require('./bracketCommon');

function generateSingleElim(teams, params = {}) {
  const size = nextPow2(teams.length);
  const k = Math.log2(size);
  const withThird = params.third_place_match === true && size >= 4;
  if (params.third_place_match !== undefined && typeof params.third_place_match !== 'boolean') {
    throw invalid(['params.third_place_match']);
  }
  const ctx = newContext();
  const rounds = buildTree(ctx, {
    prefix: 'R',
    bracket: 'main',
    teams,
    size,
    finalWinnerTo: null,
    loserTo: withThird && k > 1
      ? (r, i) => (r === k - 1 ? ref('TP', i === 1 ? 'home' : 'away') : null)
      : null,
  });
  if (withThird) {
    const tp = newMatch('TP', 'third_place', k, 1);
    ctx.matches.set('TP', tp);
    rounds[k - 1].push(tp);
  }
  resolveRoundOneByes(ctx, rounds[0]);
  if (params.results !== undefined) applyResults(ctx, params.results, () => {});
  return {
    format: 'single_elim',
    bracket_size: size,
    rounds: rounds.map((matches, idx) => ({ round: idx + 1, matches })),
  };
}

module.exports = { generateSingleElim };
