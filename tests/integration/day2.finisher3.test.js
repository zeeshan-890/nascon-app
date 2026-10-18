// Metrics media polling and publisher kick against a fake MediaMTX control API.
const http = require('http');
const { api, resetDb } = require('../helpers/app');

const dbIt = process.env.DB_AVAILABLE === '1' ? it : it.skip;

describe('media control integration', () => {
  let server;
  let bytes = 0;
  const kicked = [];
  beforeAll(async () => {
    server = http.createServer((req, res) => {
      res.setHeader('content-type', 'application/json');
      if (req.method === 'GET' && req.url.startsWith('/v3/rtmpconns/list')) {
        bytes += 125000;
        res.end(JSON.stringify({ items: [{ id: 'c1', path: 'live/sk_live_abc', bytesReceived: bytes }, { id: 'c2', path: 'other', bytesReceived: 1 }] }));
      } else if (req.method === 'GET') res.end(JSON.stringify({ items: [] }));
      else { kicked.push(req.url); res.end('{}'); }
    });
    await new Promise((r) => server.listen(0, r));
    process.env.MEDIA_CONTROL_URL = `http://127.0.0.1:${server.address().port}`;
  });
  afterAll(async () => { delete process.env.MEDIA_CONTROL_URL; await new Promise((r) => server.close(r)); });

  dbIt('metrics reflect live publishers and bitrate', async () => {
    await resetDb();
    await (await api()).get('/metrics');
    await new Promise((r) => setTimeout(r, 50));
    const m = await (await api()).get('/metrics');
    expect(m.text).toMatch(/^nascon_active_streams_total 1$/m);
    expect(Number(/^nascon_stream_bitrate_kbps (\d+)$/m.exec(m.text)[1])).toBeGreaterThan(0);
  });

  dbIt('revoking a key kicks its publisher', async () => {
    await resetDb();
    await (await api()).post('/audit/v1/stream/key').send({ action: 'rotate', player_id: 'player-alpha-1', stream_key_override: 'sk_live_abc' });
    const r = await (await api()).post('/audit/v1/stream/key').send({ action: 'revoke', player_id: 'player-alpha-1' });
    expect(r.status).toBe(200);
    expect(kicked).toContain('/v3/rtmpconns/kick/c1');
    expect(kicked).not.toContain('/v3/rtmpconns/kick/c2');
  });
});
