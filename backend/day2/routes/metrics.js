// GET /metrics (NFR-6): Prometheus text format. Public, never rate limited, never fails.
const metrics = require('../lib/metrics');
const dlq = require('../lib/dlq');
const media = require('../lib/media');

const HELP = {
  nascon_active_ws_connections: ['gauge', 'Open WebSocket connections.'],
  nascon_active_streams_total: ['gauge', 'Live publishers on the media server.'],
  nascon_stream_bitrate_kbps: ['gauge', 'Aggregate ingest bitrate in kilobits per second.'],
  nascon_dlq_depth: ['gauge', 'Events currently dead-lettered.'],
  nascon_qr_scans_total: ['counter', 'Counted QR scans since the last reset.'],
};
const lastBytes = new Map();
const withTimeout = (p, ms) => Promise.race([p, new Promise((resolve) => setTimeout(() => resolve(null), ms))]);

async function refresh() {
  await Promise.all([
    dlq.depth().catch(() => null),
    media.listPublishers().then((items) => {
      if (!items) return;
      metrics.set('nascon_active_streams_total', items.length);
      const now = Date.now();
      let kbps = 0;
      for (const c of items) {
        const prev = lastBytes.get(c.id);
        if (prev && now > prev.t) kbps += ((c.bytesReceived - prev.b) * 8) / 1000 / ((now - prev.t) / 1000);
        lastBytes.set(c.id, { b: c.bytesReceived, t: now });
      }
      metrics.set('nascon_stream_bitrate_kbps', Math.max(0, Math.round(kbps)));
    }).catch(() => null),
  ]);
}

function render() {
  return `${Object.entries(HELP).map(([name, [type, help]]) => {
    const v = Number(metrics.get(name));
    return `# HELP ${name} ${help}\n# TYPE ${name} ${type}\n${name} ${Number.isFinite(v) && v >= 0 ? Math.round(v) : 0}`;
  }).join('\n')}\n`;
}

module.exports = (router) => {
  router.get('/metrics', async (req, res) => {
    await withTimeout(refresh(), 1000); // falls back to the last known values
    const body = Buffer.from(render(), 'utf8');
    res.writeHead(200, { 'Content-Type': 'text/plain; version=0.0.4', 'Content-Length': body.length, 'Cache-Control': 'no-store' });
    res.end(body);
  });
};
