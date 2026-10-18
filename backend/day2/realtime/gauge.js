// nascon_active_ws_connections: +1 when a socket connects (Day 1 hub or Part II channels), -1 exactly once
// when it closes or errors, never below 0. reset() zeroes the gauge and orphans sockets counted before it.
const metrics = require('../lib/metrics');

const NAME = 'nascon_active_ws_connections';
let epoch = 0;

function track(ws) {
  if (ws.gaugeEpoch !== undefined) return;
  ws.gaugeEpoch = epoch;
  metrics.inc(NAME);
  const done = () => {
    if (ws.gaugeEpoch === undefined) return;
    const counted = ws.gaugeEpoch === epoch;
    ws.gaugeEpoch = undefined;
    if (counted) metrics.set(NAME, Math.max(0, metrics.get(NAME) - 1));
  };
  ws.once('close', done);
  ws.once('error', done);
}

function reset() {
  epoch += 1;
  metrics.set(NAME, 0);
}

module.exports = { track, reset, NAME };
