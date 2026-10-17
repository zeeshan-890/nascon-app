// Iterative (explicit stack) body inspection: no recursion, bounded work.
const MAX_DEPTH = 32;
const MAX_NODES = 10000;

function inspectBody(root, { maxDepth = MAX_DEPTH, maxNodes = MAX_NODES } = {}) {
  const stack = [[root, 1]];
  let nodes = 0;
  while (stack.length) {
    const [value, depth] = stack.pop();
    nodes += 1;
    if (nodes > maxNodes) return { ok: false, reason: 'nodes' };
    if (typeof value === 'string') {
      if (value.includes('\u0000')) return { ok: false, reason: 'nul' };
    } else if (value !== null && typeof value === 'object') {
      if (depth > maxDepth) return { ok: false, reason: 'depth' };
      if (Array.isArray(value)) {
        for (let i = 0; i < value.length; i += 1) stack.push([value[i], depth + 1]);
      } else {
        for (const key of Object.keys(value)) {
          if (key.includes('\u0000')) return { ok: false, reason: 'nul' };
          stack.push([value[key], depth + 1]);
        }
      }
    }
  }
  return { ok: true };
}

module.exports = { inspectBody, MAX_DEPTH, MAX_NODES };
