const fs = require('fs');
const os = require('os');
const path = require('path');
const express = require('express');
const request = require('supertest');
const { mount } = require('../../../backend/day2/routes');
const reset = require('../../../backend/day2/reset');
const metrics = require('../../../backend/day2/lib/metrics');

function tmpDir(files) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'day2-'));
  for (const [name, body] of Object.entries(files)) fs.writeFileSync(path.join(dir, name), body);
  return dir;
}

describe('route loader', () => {
  test('mounts every module except index.js and "_" files, then the error middleware', async () => {
    const dir = tmpDir({
      'index.js': 'throw new Error("index must not be loaded")',
      '_helper.js': 'throw new Error("underscore files must not be loaded")',
      'alpha.js': `const r = require('express').Router(); r.get('/alpha', (q, s) => s.json({ from: 'alpha', marked: q.day2 === true })); module.exports = r;`,
      'beta.js': `module.exports = (router) => { router.get('/beta', (q, s) => s.json({ from: 'beta' })); router.get('/beta/boom', () => { throw new Error('x'); }); };`,
      'notes.txt': 'ignored',
    });
    const app = express();
    mount(app, dir);
    expect((await request(app).get('/alpha')).body).toEqual({ from: 'alpha', marked: true });
    expect((await request(app).get('/beta')).body).toEqual({ from: 'beta' });
    const boom = await request(app).get('/beta/boom');
    expect(boom.status).toBe(500);
    expect(boom.body.error.code).toBe('INTERNAL_ERROR');
  });

  test('the real routes folder is empty of modules and mounting it is harmless', () => {
    const app = express();
    expect(() => mount(app)).not.toThrow();
  });
});

describe('reset hook', () => {
  test('all reset() hooks run first, then all seed() hooks, each in alphabetical file order', async () => {
    global.__day2Order = [];
    const mk = (n, hooks) => `module.exports = { ${hooks.map((h) => `${h}: async (ctx) => { global.__day2Order.push('${n}.${h}:' + ctx.seed); }`).join(', ')} };`;
    const dir = tmpDir({
      'index.js': 'throw new Error("index must not be loaded")',
      '_skip.js': 'throw new Error("underscore files must not be loaded")',
      'b.js': mk('b', ['reset', 'seed']),
      'a.js': mk('a', ['seed', 'reset']),
      'c.js': mk('c', ['seed']),
    });
    await reset.run({ seed: 7 }, dir);
    expect(global.__day2Order).toEqual(['a.reset:7', 'b.reset:7', 'a.seed:7', 'b.seed:7', 'c.seed:7']);
    delete global.__day2Order;
  });

  test('an empty folder does nothing (the real folder now holds the M0 base seed)', async () => {
    await expect(reset.run({ seed: 1 }, tmpDir({}))).resolves.toBeUndefined();
  });
});

describe('metrics', () => {
  test('the five required names default to 0', () => {
    for (const n of ['nascon_active_ws_connections', 'nascon_active_streams_total', 'nascon_stream_bitrate_kbps', 'nascon_dlq_depth', 'nascon_qr_scans_total']) {
      expect(metrics.get(n)).toBe(0);
    }
  });
  test('inc and set', () => {
    metrics.inc('nascon_qr_scans_total');
    metrics.inc('nascon_qr_scans_total', 4);
    expect(metrics.get('nascon_qr_scans_total')).toBe(5);
    metrics.set('nascon_dlq_depth', 3);
    expect(metrics.get('nascon_dlq_depth')).toBe(3);
    metrics.set('nascon_dlq_depth', 0);
    metrics.set('nascon_qr_scans_total', 0);
  });
});
