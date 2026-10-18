// Called by the Phase I reset service inside its transaction. Every other .js file here (not starting
// with "_") may export reset(ctx) and/or seed(ctx). All reset() hooks run first, then all seed() hooks,
// each pass in alphabetical file order. ctx = { client, seed, stock } (client is the open transaction).
const fs = require('fs');
const path = require('path');

function listModules(dir) {
  return fs.readdirSync(dir)
    .filter((f) => f.endsWith('.js') && f !== 'index.js' && !f.startsWith('_'))
    .sort()
    .map((f) => require(path.join(dir, f))); // eslint-disable-line global-require, import/no-dynamic-require
}

async function run(ctx, dir = __dirname) {
  const modules = listModules(dir);
  for (const m of modules) if (m && typeof m.reset === 'function') await m.reset(ctx); // eslint-disable-line no-await-in-loop
  for (const m of modules) if (m && typeof m.seed === 'function') await m.seed(ctx); // eslint-disable-line no-await-in-loop
}

module.exports = { run };
