const zlib = require('zlib');
const crypto = require('crypto');

// Minimal PNG encoder (RGBA, 8 bit) and two procedural pieces of art. No dependencies.
const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n += 1) {
    let c = n;
    for (let k = 0; k < 8; k += 1) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i += 1) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function encodePng(width, height, rgba) {
  const raw = Buffer.alloc((width * 4 + 1) * height);
  for (let y = 0; y < height; y += 1) {
    raw[y * (width * 4 + 1)] = 0;
    rgba.copy(raw, y * (width * 4 + 1) + 1, y * width * 4, (y + 1) * width * 4);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

function hexToRgb(hex) {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex || '');
  if (!m) return null;
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

// Two stable colours derived from the id when none are configured.
function colorsFor(id, configured) {
  const a = configured && hexToRgb(configured.primary);
  const b = configured && hexToRgb(configured.secondary);
  if (a && b) return [a, b];
  const h = crypto.createHash('sha256').update(id).digest();
  return [[40 + (h[0] % 160), 40 + (h[1] % 160), 40 + (h[2] % 160)], [255 - (h[3] % 90), 255 - (h[4] % 120), 80 + (h[5] % 100)]];
}

const SIZE = 256;

function paint(draw) {
  const buf = Buffer.alloc(SIZE * SIZE * 4);
  for (let y = 0; y < SIZE; y += 1) {
    for (let x = 0; x < SIZE; x += 1) {
      const px = draw(x, y);
      if (px) {
        const o = (y * SIZE + x) * 4;
        buf[o] = px[0];
        buf[o + 1] = px[1];
        buf[o + 2] = px[2];
        buf[o + 3] = 255;
      }
    }
  }
  return encodePng(SIZE, SIZE, buf);
}

// Filled circle in the primary colour with a ring and a bar in the secondary colour.
function logoPng(id, configured) {
  const [primary, secondary] = colorsFor(id, configured);
  const c = SIZE / 2;
  return paint((x, y) => {
    const d = Math.hypot(x + 0.5 - c, y + 0.5 - c);
    if (d > 120) return null;
    if (d > 106) return secondary;
    if (Math.abs(y + 0.5 - c) < 14 && d < 80) return secondary;
    return primary;
  });
}

// Rounded square with diagonal stripes.
function productPng(id, configured) {
  const [primary, secondary] = colorsFor(id, configured);
  const r = 36;
  const lo = 16;
  const hi = SIZE - 16;
  return paint((x, y) => {
    if (x < lo || x >= hi || y < lo || y >= hi) return null;
    const cx = Math.min(Math.max(x, lo + r), hi - r);
    const cy = Math.min(Math.max(y, lo + r), hi - r);
    if (Math.hypot(x - cx, y - cy) > r) return null;
    return Math.floor((x + y) / 28) % 2 === 0 ? primary : secondary;
  });
}

module.exports = { encodePng, crc32, colorsFor, logoPng, productPng, SIZE };
