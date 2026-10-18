// Clears the in-process Part II counters on reset; database state is truncated by the Phase I reset itself.
const metrics = require('../lib/metrics');

async function reset() {
  require('../lib/erased').clear();
  for (const n of ['nascon_active_streams_total', 'nascon_stream_bitrate_kbps', 'nascon_dlq_depth', 'nascon_qr_scans_total']) metrics.set(n, 0);
}

module.exports = { reset };
