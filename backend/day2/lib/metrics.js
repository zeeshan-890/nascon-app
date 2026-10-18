// In-process counters/gauges behind GET /metrics (NFR-6). The five required names always exist.
const values = new Map([
  ['nascon_active_ws_connections', 0],
  ['nascon_active_streams_total', 0],
  ['nascon_stream_bitrate_kbps', 0],
  ['nascon_dlq_depth', 0],
  ['nascon_qr_scans_total', 0],
]);

const get = (name) => values.get(name) || 0;
const set = (name, v) => { values.set(name, Number(v)); };
const inc = (name, n = 1) => { values.set(name, get(name) + Number(n)); };

module.exports = { inc, set, get };
