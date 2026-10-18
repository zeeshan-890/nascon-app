const { describeDb } = require('../helpers/db');
const pool = require('../../backend/src/db/pool');
const { withLock, once } = require('../../backend/day2/lib/locks');

afterAll(() => pool.end());

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

describeDb('day2 locks', () => {
  test('20 concurrent withLock calls on one key run one at a time', async () => {
    let running = 0;
    let maxRunning = 0;
    let done = 0;
    await Promise.all(Array.from({ length: 20 }, () => withLock('same-key', async () => {
      running += 1;
      maxRunning = Math.max(maxRunning, running);
      await sleep(5);
      running -= 1;
      done += 1;
    })));
    expect(maxRunning).toBe(1);
    expect(done).toBe(20);
  });

  test('different keys do not block each other', async () => {
    let both = false;
    let aRunning = false;
    await Promise.all([
      withLock('key-a', async () => { aRunning = true; await sleep(60); aRunning = false; }),
      withLock('key-b', async () => { await sleep(10); both = aRunning; }),
    ]);
    expect(both).toBe(true);
  });

  test('a failing body releases the lock and rethrows', async () => {
    await expect(withLock('fail-key', async () => { throw new Error('bad'); })).rejects.toThrow('bad');
    await expect(withLock('fail-key', async () => 'next')).resolves.toBe('next');
  });

  test('the return value is passed through', async () => {
    await expect(withLock('ret-key', async () => 42)).resolves.toBe(42);
  });

  test('once: 20 concurrent callers run the body one time and share its result', async () => {
    let runs = 0;
    const results = await Promise.all(Array.from({ length: 20 }, () => once('once-key', async () => {
      runs += 1;
      await sleep(10);
      return { n: runs };
    })));
    expect(runs).toBe(1);
    expect(new Set(results.map((r) => r.n))).toEqual(new Set([1]));
    await expect(once('once-key', async () => ({ n: 99 }))).resolves.toEqual({ n: 1 });
  });

  test('once: a failed run is forgotten so a retry can succeed', async () => {
    await expect(once('once-fail', async () => { throw new Error('first'); })).rejects.toThrow('first');
    await expect(once('once-fail', async () => 'ok')).resolves.toBe('ok');
  });
});
