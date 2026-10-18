// Guest Web Player mount (owned by M0): serves the top-level web/ folder at /web/* and returns the same
// single-page shell for every client-side route, so a hard reload works anywhere. No auth, no cookies.
const fs = require('fs');
const path = require('path');
const express = require('express');

const WEB_ROOT = path.resolve(process.env.WEB_DIR || path.join(__dirname, '..', '..', '..', 'web'));
const SHELL_PATHS = [
  '/web/matches/:id/preview', '/web/matches/:id/live', '/web/matches/:id/vod', '/web/matches/:id/notice',
  '/web/players/:id', '/web/clubs/:id', '/web/costream/:id',
];
const PLACEHOLDER = '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
  + '<title>NaSCon Guest Player</title></head><body><div id="app">Loading…</div></body></html>';

// A file extension means "this is an asset request"; anything else is an application route.
const isAssetPath = (p) => /\.[A-Za-z0-9]{1,8}$/.test(p);

function shellSender(root) {
  const index = path.join(root, 'index.html');
  return (req, res) => {
    res.set('Cache-Control', 'no-store');
    res.type('html');
    if (req.method === 'HEAD') return res.status(200).end();
    if (fs.existsSync(index)) return res.status(200).send(fs.readFileSync(index));
    return res.status(200).send(PLACEHOLDER); // never written to disk: the frontend owns web/index.html
  };
}

function createWebRouter(root = WEB_ROOT) {
  const router = express.Router();
  const sendShell = shellSender(root);
  router.use(['/web'], (req, res, next) => { res.removeHeader('Set-Cookie'); next(); });

  for (const p of SHELL_PATHS) router.get(p, sendShell);

  // Static assets: dotfiles denied, no directory index, no redirects, short cache.
  router.use('/web', express.static(root, {
    index: false, dotfiles: 'deny', redirect: false, fallthrough: true,
    setHeaders: (res) => { res.set('Cache-Control', 'public, max-age=60'); },
  }));

  // Everything else under /web: the shell for application routes, 404 for missing asset files.
  router.get(/^\/web(\/.*)?$/, (req, res, next) => {
    let decoded;
    try { decoded = decodeURIComponent(req.path); } catch (e) { return res.status(404).type('text/plain').send('Not found'); }
    if (decoded.includes('\0') || decoded.split('/').includes('..')) return res.status(404).type('text/plain').send('Not found');
    if (isAssetPath(decoded)) return res.status(404).type('text/plain').set('Cache-Control', 'no-store').send('Not found');
    return sendShell(req, res, next);
  });
  return router;
}

module.exports = createWebRouter();
module.exports.createWebRouter = createWebRouter;
module.exports.WEB_ROOT = WEB_ROOT;
