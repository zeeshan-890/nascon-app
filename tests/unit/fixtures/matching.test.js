const { maxWeightMatching, minCostPerfectMatching } = require('../../../backend/src/domain/fixtures/matching');

function rng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

// Exhaustive min-cost perfect matching; returns null when infeasible.
function brute(n, edges) {
  const cost = new Map(edges.map(([u, v, d]) => [u * 100 + v, d]));
  const get = (a, b) => cost.get(Math.min(a, b) * 100 + Math.max(a, b));
  function rec(mask) {
    if (mask === (1 << n) - 1) return 0;
    let a = 0;
    while (mask & (1 << a)) a += 1;
    let best = null;
    for (let b = a + 1; b < n; b += 1) {
      if (mask & (1 << b)) continue;
      const c = get(a, b);
      if (c === undefined) continue;
      const rest = rec(mask | (1 << a) | (1 << b));
      if (rest !== null && (best === null || rest + c < best)) best = rest + c;
    }
    return best;
  }
  return rec(0);
}

describe('minCostPerfectMatching vs brute force', () => {
  test('1000 random graphs, n <= 10', () => {
    const rand = rng(12345);
    for (let t = 0; t < 1000; t += 1) {
      const n = 2 * (1 + Math.floor(rand() * 5));
      const score = Array.from({ length: n }, () => Math.floor(rand() * 9));
      const keep = 0.3 + rand() * 0.7;
      const edges = [];
      for (let i = 0; i < n; i += 1) {
        for (let j = i + 1; j < n; j += 1) {
          if (rand() < keep) edges.push([i, j, Math.abs(score[i] - score[j])]);
        }
      }
      const expected = brute(n, edges);
      const got = minCostPerfectMatching(n, edges);
      if (expected === null) {
        expect(got).toBeNull();
      } else {
        expect(got).not.toBeNull();
        expect(new Set(got.flat()).size).toBe(n);
        const lookup = new Map(edges.map(([u, v, d]) => [`${u}-${v}`, d]));
        const total = got.reduce((s, [u, v]) => s + lookup.get(`${u}-${v}`), 0);
        expect(total).toBe(expected);
      }
    }
  });

  test('infeasible cases', () => {
    expect(minCostPerfectMatching(3, [[0, 1, 0], [1, 2, 0]])).toBeNull();
    expect(minCostPerfectMatching(4, [[0, 1, 0], [1, 2, 0]])).toBeNull();
    expect(minCostPerfectMatching(4, [[0, 1, 0], [0, 2, 0], [0, 3, 0]])).toBeNull();
    expect(minCostPerfectMatching(6, [[0, 1, 0], [1, 2, 0], [0, 2, 0], [3, 4, 0], [4, 5, 0], [3, 5, 0]])).toBeNull();
    expect(minCostPerfectMatching(4, [])).toBeNull();
    expect(minCostPerfectMatching(0, [])).toEqual([]);
  });

  test('K4 minus a perfect matching is feasible', () => {
    const edges = [[0, 2, 1], [0, 3, 1], [1, 2, 1], [1, 3, 1]];
    expect(minCostPerfectMatching(4, edges)).toHaveLength(2);
  });

  test('64-vertex sparse graph stays perfect and valid', () => {
    const rand = rng(99);
    const n = 64;
    const edges = [];
    for (let i = 0; i < n; i += 1) for (let j = i + 1; j < n; j += 1) if (rand() < 0.15) edges.push([i, j, Math.floor(rand() * 7)]);
    for (let i = 0; i < n; i += 2) edges.push([i, i + 1, 6]);
    const got = minCostPerfectMatching(n, edges);
    expect(got).not.toBeNull();
    expect(new Set(got.flat()).size).toBe(n);
  });

  test('maxWeightMatching cardinality modes and empty edges', () => {
    expect(maxWeightMatching([], false, 3)).toEqual([-1, -1, -1]);
    expect(maxWeightMatching([[0, 1, 5], [1, 2, 11], [2, 3, 5]])).toEqual([-1, 2, 1, -1]);
    expect(maxWeightMatching([[0, 1, 5], [1, 2, 11], [2, 3, 5]], true)).toEqual([1, 0, 3, 2]);
  });
});
