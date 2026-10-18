// Probes the test database and Redis once; dependent integration tests skip themselves when unreachable.
const net = require('net');
const { Client } = require('../backend/node_modules/pg');

// Minimal RESP PING with a hard timeout; never hangs and needs no client library.
function probeRedis(url, timeoutMs = 1500) {
  return new Promise((resolve) => {
    let u;
    try { u = new URL(url); } catch (e) { return resolve(false); }
    let done = false;
    const sock = net.connect({ host: u.hostname || 'localhost', port: Number(u.port) || 6379 });
    const finish = (ok) => { if (done) return; done = true; sock.destroy(); resolve(ok); };
    sock.setTimeout(timeoutMs, () => finish(false));
    sock.on('error', () => finish(false));
    sock.on('connect', () => {
      if (u.password) sock.write(`*2\r\n$4\r\nAUTH\r\n$${Buffer.byteLength(decodeURIComponent(u.password))}\r\n${decodeURIComponent(u.password)}\r\n`);
      sock.write('*1\r\n$4\r\nPING\r\n');
    });
    sock.on('data', (d) => { const s = d.toString(); if (s.includes('PONG')) finish(true); else if (s.startsWith('-') && !s.includes('PONG')) finish(false); });
  });
}

module.exports = async () => {
  const url = process.env.DATABASE_URL || 'postgres://nascon:nascon@localhost:5433/nascon';
  process.env.DATABASE_URL = url;
  const client = new Client({ connectionString: url, connectionTimeoutMillis: 1500 });
  try {
    await client.connect();
    await client.end();
    process.env.DB_AVAILABLE = '1';
  } catch (e) {
    process.env.DB_AVAILABLE = '0';
  }
  const redisUrl = process.env.REDIS_URL || 'redis://localhost:6380';
  process.env.REDIS_URL = redisUrl;
  process.env.REDIS_AVAILABLE = (await probeRedis(redisUrl)) ? '1' : '0';
};
