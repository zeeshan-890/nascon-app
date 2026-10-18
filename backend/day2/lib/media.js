// Best-effort client for the media server control API (MediaMTX v3). Never throws; unset URL = no media server.
const baseUrl = () => process.env.MEDIA_CONTROL_URL || '';

async function call(method, path, timeoutMs = 800) {
  const base = baseUrl();
  if (!base) return null;
  try {
    const r = await fetch(`${base}${path}`, { method, signal: AbortSignal.timeout(timeoutMs) });
    if (!r.ok) return null;
    const text = await r.text();
    return text ? JSON.parse(text) : {};
  } catch (e) {
    return null;
  }
}

// Kicks every RTMP / WebRTC publisher currently on live/<key>.
async function kickKey(streamKey) {
  const path = `live/${streamKey}`;
  const kinds = ['rtmpconns', 'webrtcsessions'];
  await Promise.all(kinds.map(async (kind) => {
    const data = await call('GET', `/v3/${kind}/list?itemsPerPage=1000`);
    const items = (data && data.items) || [];
    await Promise.all(items.filter((c) => c.path === path).map((c) => call('POST', `/v3/${kind}/kick/${encodeURIComponent(c.id)}`)));
  }));
}

async function listPublishers() {
  const data = await call('GET', '/v3/rtmpconns/list?itemsPerPage=1000', 500);
  return data ? (data.items || []).filter((c) => typeof c.path === 'string' && c.path.startsWith('live/')) : null;
}

module.exports = { kickKey, listPublishers };
