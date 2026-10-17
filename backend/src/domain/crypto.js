const nodeCrypto = require('crypto');
const config = require('../config');

function cmpCodePoints(a, b) {
  const ca = Array.from(a);
  const cb = Array.from(b);
  const n = Math.min(ca.length, cb.length);
  for (let i = 0; i < n; i += 1) {
    const d = ca[i].codePointAt(0) - cb[i].codePointAt(0);
    if (d !== 0) return d;
  }
  return ca.length - cb.length;
}

function canonical(value) {
  if (value === null || value === undefined) return 'null';
  if (Array.isArray(value)) {
    return `[${value.map((v) => canonical(v)).join(',')}]`;
  }
  if (typeof value === 'object') {
    const keys = Object.keys(value)
      .filter((k) => value[k] !== undefined)
      .sort(cmpCodePoints);
    return `{${keys.map((k) => `${JSON.stringify(k)}:${canonical(value[k])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

function signWith(salt, id, payloadObj) {
  return nodeCrypto
    .createHmac('sha256', Buffer.from(salt, 'utf8'))
    .update(Buffer.from(`${id}|${canonical(payloadObj)}`, 'utf8'))
    .digest('hex');
}

const sign = (id, payloadObj) => signWith(config.salt, id, payloadObj);

function lotsKey(seed, leagueId, teamId) {
  return nodeCrypto.createHash('sha256').update(`${seed}|${leagueId}|${teamId}`).digest('hex');
}

module.exports = { canonical, signWith, sign, lotsKey };
