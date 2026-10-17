// Pure in-memory sliding-window log, mirroring the Redis Lua script exactly:
// only allowed hits are recorded; entries older than windowMs are dropped.
function createSlidingWindow({ limit, windowMs, now = Date.now, maxKeys = 100000 }) {
  const hits = new Map();

  function prune(arr, t) {
    const cutoff = t - windowMs;
    let i = 0;
    while (i < arr.length && arr[i] <= cutoff) i += 1;
    if (i > 0) arr.splice(0, i);
  }

  function cleanup() {
    const t = now();
    for (const [key, arr] of hits) {
      prune(arr, t);
      if (arr.length === 0) hits.delete(key);
    }
  }

  function hit(key) {
    const t = now();
    let arr = hits.get(key);
    if (!arr) {
      if (hits.size >= maxKeys) {
        cleanup();
        if (hits.size >= maxKeys) return { allowed: true, remaining: limit - 1, retryMs: 0 };
      }
      arr = [];
    } else {
      prune(arr, t);
    }
    if (arr.length < limit) {
      arr.push(t);
      hits.set(key, arr);
      return { allowed: true, remaining: limit - arr.length, retryMs: 0 };
    }
    const oldest = arr.length ? arr[0] : t;
    return { allowed: false, remaining: 0, retryMs: Math.max(1, oldest + windowMs - t) };
  }

  return { hit, cleanup, size: () => hits.size, clear: () => hits.clear() };
}

module.exports = { createSlidingWindow };
