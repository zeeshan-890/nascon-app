const { createSlidingWindow } = require('../../../backend/src/domain/rbac/slidingWindow');

function clock(start = 1000000) {
  let t = start;
  const now = () => t;
  now.advance = (ms) => { t += ms; };
  return now;
}

describe('in-memory sliding window', () => {
  test('100 allowed, 101st denied with correct retry', () => {
    const now = clock();
    const w = createSlidingWindow({ limit: 100, windowMs: 10000, now });
    for (let i = 1; i <= 100; i += 1) {
      const r = w.hit('ip');
      expect(r.allowed).toBe(true);
      expect(r.remaining).toBe(100 - i);
      now.advance(10);
    }
    const denied = w.hit('ip');
    expect(denied.allowed).toBe(false);
    expect(denied.remaining).toBe(0);
    expect(denied.retryMs).toBe(9000);
  });

  test('only allowed hits are recorded (denials do not extend the window)', () => {
    const now = clock();
    const w = createSlidingWindow({ limit: 2, windowMs: 1000, now });
    w.hit('a'); w.hit('a');
    for (let i = 0; i < 50; i += 1) { now.advance(10); expect(w.hit('a').allowed).toBe(false); }
    now.advance(500);
    expect(w.hit('a').allowed).toBe(true);
  });

  test('window slides: hits expire one by one', () => {
    const now = clock();
    const w = createSlidingWindow({ limit: 3, windowMs: 1000, now });
    w.hit('a'); now.advance(400); w.hit('a'); now.advance(400); w.hit('a');
    expect(w.hit('a').allowed).toBe(false);
    now.advance(200);
    expect(w.hit('a').allowed).toBe(true);
    expect(w.hit('a').allowed).toBe(false);
  });

  test('empty after the full window (11 s recovery)', () => {
    const now = clock();
    const w = createSlidingWindow({ limit: 100, windowMs: 10000, now });
    for (let i = 0; i < 150; i += 1) w.hit('ip');
    expect(w.hit('ip').allowed).toBe(false);
    now.advance(11000);
    const r = w.hit('ip');
    expect(r.allowed).toBe(true);
    expect(r.remaining).toBe(99);
  });

  test('keys are independent', () => {
    const w = createSlidingWindow({ limit: 1, windowMs: 1000, now: clock() });
    expect(w.hit('a').allowed).toBe(true);
    expect(w.hit('a').allowed).toBe(false);
    expect(w.hit('b').allowed).toBe(true);
  });

  test('cleanup removes expired keys and keeps live ones', () => {
    const now = clock();
    const w = createSlidingWindow({ limit: 5, windowMs: 1000, now });
    w.hit('old'); now.advance(1500); w.hit('new');
    expect(w.size()).toBe(2);
    w.cleanup();
    expect(w.size()).toBe(1);
    now.advance(1500);
    w.cleanup();
    expect(w.size()).toBe(0);
  });

  test('maxKeys bounds memory (sweeps, then fails open without recording)', () => {
    const now = clock();
    const w = createSlidingWindow({ limit: 5, windowMs: 1000, now, maxKeys: 3 });
    w.hit('a'); w.hit('b'); w.hit('c');
    expect(w.hit('d').allowed).toBe(true);
    expect(w.size()).toBe(3);
    now.advance(2000);
    expect(w.hit('e').allowed).toBe(true);
    expect(w.size()).toBe(1);
  });

  test('clear', () => {
    const w = createSlidingWindow({ limit: 1, windowMs: 1000, now: clock() });
    w.hit('a'); w.clear();
    expect(w.size()).toBe(0);
  });

  test('retryMs is at least 1', () => {
    const now = clock();
    const w = createSlidingWindow({ limit: 1, windowMs: 1000, now });
    w.hit('a'); now.advance(999);
    expect(w.hit('a').retryMs).toBe(1);
  });
});
