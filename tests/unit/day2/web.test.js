const fs = require('fs');
const os = require('os');
const path = require('path');
const net = require('net');
const express = require('express');
const request = require('supertest');
const { createWebRouter } = require('../../../backend/day2/routes/web');

let root;
let app;

beforeAll(() => {
  root = fs.mkdtempSync(path.join(os.tmpdir(), 'web-'));
  fs.writeFileSync(path.join(root, 'index.html'), '<!doctype html><title>shell</title><div id="app">SHELL</div>');
  fs.mkdirSync(path.join(root, 'assets'));
  fs.writeFileSync(path.join(root, 'assets', 'app.js'), 'console.log("asset")');
  fs.writeFileSync(path.join(root, '.secret'), 'hidden');
  fs.writeFileSync(path.join(path.dirname(root), 'outside-secret.txt'), 'TOP SECRET');
  app = express();
  app.use(createWebRouter(root));
  app.use((req, res) => res.status(404).json({ error: 'outer-404' }));
});

afterAll(() => { fs.rmSync(root, { recursive: true, force: true }); });

const SHELL_ROUTES = [
  '/web/matches/match-m1/preview', '/web/matches/match-m1/live', '/web/matches/match-m1/vod', '/web/matches/match-m1/notice',
  '/web/matches/match-m1/notice?state=postponed', '/web/players/player-alpha-1', '/web/clubs/club-alpha-1', '/web/costream/cs-session-101',
];

describe('shell routes', () => {
  test.each(SHELL_ROUTES)('%s returns the SPA shell with 200 and no-store', async (url) => {
    const r = await request(app).get(url);
    expect(r.status).toBe(200);
    expect(r.text).toContain('SHELL');
    expect(r.headers['content-type']).toMatch(/text\/html/);
    expect(r.headers['cache-control']).toBe('no-store');
  });

  test('/web and /web/ also give the shell', async () => {
    for (const u of ['/web', '/web/']) {
      const r = await request(app).get(u);
      expect(r.status).toBe(200);
      expect(r.text).toContain('SHELL');
    }
  });

  test('unknown /web paths return the shell with 200 (the page shows not-found)', async () => {
    for (const u of ['/web/nothing/here', '/web/matches', '/web/matches/x/unknown', '/web/players']) {
      const r = await request(app).get(u);
      expect(r.status).toBe(200);
      expect(r.text).toContain('SHELL');
    }
  });

  test('HEAD requests work and return no body', async () => {
    const r = await request(app).head('/web/matches/match-m1/live');
    expect(r.status).toBe(200);
    expect(r.text || '').toBe('');
    expect(r.headers['content-type']).toMatch(/text\/html/);
    expect((await request(app).head('/web/assets/app.js')).status).toBe(200);
  });
});

describe('assets', () => {
  test('a real asset is served with a short cache', async () => {
    const r = await request(app).get('/web/assets/app.js');
    expect(r.status).toBe(200);
    expect(r.text).toBe('console.log("asset")');
    expect(r.headers['cache-control']).toBe('public, max-age=60');
  });

  test('a missing asset file is a 404, not the shell', async () => {
    for (const u of ['/web/assets/missing.js', '/web/missing.css', '/web/favicon.ico', '/web/a/b/c.png']) {
      const r = await request(app).get(u);
      expect(r.status).toBe(404);
      expect(r.text).not.toContain('SHELL');
    }
  });

  test('dotfiles are never served', async () => {
    const r = await request(app).get('/web/.secret');
    expect(r.status).toBe(404);
    expect(r.text).not.toContain('hidden');
  });

  test('directories do not list or redirect', async () => {
    const r = await request(app).get('/web/assets');
    expect(r.status).toBe(200); // application route -> shell
    expect(r.headers.location).toBeUndefined();
    expect(r.text).toContain('SHELL');
  });
});

// raw sockets, so the client cannot normalise the path before it reaches the server
function raw(requestLine) {
  return new Promise((resolve, reject) => {
    const server = app.listen(0, () => {
      const socket = net.connect(server.address().port, '127.0.0.1');
      let data = '';
      socket.on('data', (d) => { data += d.toString(); });
      socket.on('end', () => { server.close(); resolve(data); });
      socket.on('error', (e) => { server.close(); reject(e); });
      socket.write(`${requestLine} HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n`);
    });
  });
}

describe('path traversal', () => {
  const attempts = [
    '/web/../outside-secret.txt', '/web/%2e%2e/outside-secret.txt', '/web/%2E%2E/outside-secret.txt',
    '/web/..%2foutside-secret.txt', '/web/assets/../../outside-secret.txt', '/web/assets/%2e%2e/%2e%2e/outside-secret.txt',
    '/web/%252e%252e/outside-secret.txt', '/web/..%5coutside-secret.txt', '/web/....//outside-secret.txt',
    '/web/%00../outside-secret.txt', '/web/assets/..%2f..%2foutside-secret.txt', '/web/;/../outside-secret.txt',
  ];
  test.each(attempts)('%s never leaks a file outside web/', async (url) => {
    const out = await raw(`GET ${url}`);
    expect(out).not.toContain('TOP SECRET');
    expect(out.startsWith('HTTP/1.1 ')).toBe(true);
    const status = Number(out.slice(9, 12));
    expect([200, 400, 403, 404]).toContain(status);
    expect(status).not.toBe(500);
  });

  test('plain "../" is blocked from reading a real file next to the root', async () => {
    const r = await request(app).get('/web/..%2foutside-secret.txt');
    expect(r.text).not.toContain('TOP SECRET');
  });
});

describe('headers', () => {
  test('no Set-Cookie on shell routes, assets or 404s, and no redirects', async () => {
    for (const u of [...SHELL_ROUTES, '/web/assets/app.js', '/web/missing.js', '/web']) {
      const r = await request(app).get(u).redirects(0);
      expect(r.headers['set-cookie']).toBeUndefined();
      expect([200, 404]).toContain(r.status);
    }
  });

  test('the shell needs no authentication (works with and without an Authorization header)', async () => {
    expect((await request(app).get('/web/matches/match-m1/live')).status).toBe(200);
    expect((await request(app).get('/web/matches/match-m1/live').set('Authorization', 'Bearer guest_anon_tok_7721')).status).toBe(200);
  });
});

describe('placeholder shell', () => {
  test('when web/index.html does not exist a minimal placeholder is served, without writing any file', async () => {
    const empty = fs.mkdtempSync(path.join(os.tmpdir(), 'web-empty-'));
    const a = express();
    a.use(createWebRouter(empty));
    const r = await request(a).get('/web/matches/match-m1/live');
    expect(r.status).toBe(200);
    expect(r.text).toContain('<div id="app">');
    expect(fs.readdirSync(empty)).toEqual([]);
    fs.rmSync(empty, { recursive: true, force: true });
  });
});
