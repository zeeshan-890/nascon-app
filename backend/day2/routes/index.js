// Mounts every other .js file in this folder (except files starting with "_") onto the app, then the
// Part II error middleware. A route file exports an express Router (with absolute paths such as
// '/audit/v1/qr/:id') or a function(router) that registers routes on the router it is given.
const fs = require('fs');
const path = require('path');
const express = require('express');
const { markDay2, errorMiddleware } = require('../lib/errors');

function listModules(dir) {
  return fs.readdirSync(dir)
    .filter((f) => f.endsWith('.js') && f !== 'index.js' && !f.startsWith('_'))
    .sort();
}

function mount(app, dir = __dirname) {
  for (const file of listModules(dir)) {
    const mod = require(path.join(dir, file)); // eslint-disable-line global-require, import/no-dynamic-require
    const router = express.Router();
    router.use(markDay2);
    if (mod && Array.isArray(mod.stack)) router.use(mod);
    else if (typeof mod === 'function') mod(router);
    else continue; // eslint-disable-line no-continue
    app.use(router);
  }
  app.use(errorMiddleware);
}

module.exports = { mount };
