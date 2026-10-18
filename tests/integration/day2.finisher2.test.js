// Part II contract probes: stream key lifecycle, co-stream handoff, privacy erasure.
const crypto = require('crypto');
const { api, resetDb, pool } = require('../helpers/app');

const sign = (entity, payload) => crypto.createHmac('sha256', process.env.DAY2_SALT).update(`${entity}|${payload}`).digest('hex');
const dbIt = process.env.DB_AVAILABLE === '1' ? it : it.skip;
const key = async (body) => (await api()).post('/audit/v1/stream/key').send(body);
const cs = async (body) => (await api()).post('/audit/v1/costream/session').send(body);
const P = ['player-alpha-1', 'player-alpha-2', 'player-beta-1', 'player-beta-2'];

describe('stream key', () => {
  beforeEach(async () => { if (process.env.DB_AVAILABLE === '1') await resetDb(); });

  dbIt('generate, rotate, revoke lifecycle with signatures', async () => {
    const g = await key({ action: 'generate', player_id: 'player-alpha-1' });
    expect(g.status).toBe(201);
    expect(g.body.stream_key).toMatch(/^sk_live_[a-f0-9]{16}$/);
    expect(g.body).toMatchObject({ label: 'default', status: 'active', playback_url: `http://localhost:8888/live/${g.body.stream_key}/index.m3u8` });
    expect(g.body.ingest_endpoints.rtmp).toBe(`rtmp://localhost:1935/live/${g.body.stream_key}`);
    const r = await key({ action: 'rotate', player_id: 'player-alpha-1', label: 'OBS-Main-Desk', stream_key_override: 'sk_live_9f83a1b2c3d4e5f6' });
    expect(r.status).toBe(200);
    expect(r.body.stream_key).toBe('sk_live_9f83a1b2c3d4e5f6');
    expect(r.body.signature).toBe(sign('sk_live_9f83a1b2c3d4e5f6', '{"action":"rotate","player_id":"player-alpha-1","status":"active","stream_key":"sk_live_9f83a1b2c3d4e5f6"}'));
    const old = await (await api()).post('/api/v1/streams/activate').send({ stream_key: g.body.stream_key });
    expect(old.status).toBe(409);
    expect(old.body.error.code).toBe('KEY_REVOKED');
    expect((await (await api()).post('/api/v1/streams/activate').send({ stream_key: 'sk_live_unknown' })).body.error.code).toBe('STREAM_NOT_FOUND');
    const v = await key({ action: 'revoke', player_id: 'player-alpha-1' });
    expect(v.status).toBe(200);
    expect(v.body).toMatchObject({ status: 'revoked', ingest_endpoints: null, playback_url: null, stream_key: 'sk_live_9f83a1b2c3d4e5f6' });
    expect((await key({ action: 'revoke', player_id: 'player-alpha-1' })).status).toBe(200);
    expect((await key({ action: 'revoke', player_id: 'player-alpha-2' })).body.error.code).toBe('STREAM_NOT_FOUND');
    const hook = await (await api()).post('/internal/media/auth').send({ action: 'publish', path: 'live/sk_live_9f83a1b2c3d4e5f6' });
    expect(hook.status).toBe(409);
    expect((await (await api()).post('/internal/media/auth').send({ action: 'read', path: 'live/anything' })).status).toBe(200);
  });

  dbIt('validation and ownership', async () => {
    expect((await key({ player_id: 'player-alpha-1' })).status).toBe(400);
    expect((await key({ action: 'delete', player_id: 'player-alpha-1' })).status).toBe(400);
    expect((await key({ action: 'generate' })).status).toBe(400);
    expect((await key({ action: 'generate', player_id: 'nobody' })).body.error.code).toBe('PLAYER_NOT_FOUND');
    expect((await key({ action: 'generate', player_id: 'player-alpha-1', label: '' })).status).toBe(400);
    expect((await key({ action: 'generate', player_id: 'player-alpha-1', label: 'x'.repeat(65) })).status).toBe(400);
    expect((await key({ action: 'generate', player_id: 'player-alpha-1', label: 'x'.repeat(64) })).status).toBe(201);
    expect((await key({ action: 'generate', player_id: 'player-alpha-1', stream_key_override: 'a b' })).status).toBe(400);
    expect((await key({ action: 'rotate', player_id: 'player-alpha-2' })).status).toBe(200);
    await key({ action: 'rotate', player_id: 'player-alpha-1', stream_key_override: 'sk_live_dup' });
    const dup = await key({ action: 'rotate', player_id: 'player-beta-1', stream_key_override: 'sk_live_dup' });
    expect(dup.status).toBe(400);
    expect(dup.body.error.details.fields).toContain('stream_key_override');
    const guest = await (await api()).post('/audit/v1/stream/key').set('Authorization', 'Bearer guest_anon_tok_7721').send({ action: 'generate', player_id: 'player-alpha-1' });
    expect(guest.status).toBe(403);
  });

  dbIt('twenty simultaneous generates leave one active key', async () => {
    const rs = await Promise.all(Array.from({ length: 20 }, () => key({ action: 'generate', player_id: 'player-alpha-1' })));
    expect(rs.every((r) => r.status === 201)).toBe(true);
    const active = await pool.query(`SELECT count(*)::int AS c FROM day2_stream_key WHERE player_id = 'player-alpha-1' AND status = 'active'`);
    expect(active.rows[0].c).toBe(1);
  });
});

describe('co-stream', () => {
  beforeEach(async () => { if (process.env.DB_AVAILABLE === '1') await resetDb(); });
  const spec = { session_id: 'cs-session-101', host_player_id: 'player-alpha-1', participants: P, layout: 'grid_4', simulate_host_drop: true };

  dbIt('spec example, replay, replace and read', async () => {
    const r = await cs(spec);
    expect(r.status).toBe(201);
    expect(r.body).toMatchObject({
      session_id: 'cs-session-101', status: 'active', active_host: 'player-alpha-2', handoff_occurred: true, participant_count: 3,
      active_participants: ['player-alpha-2', 'player-beta-1', 'player-beta-2'], layout: 'grid_4',
      composite_playback_url: 'http://localhost:8888/costream/cs-session-101/index.m3u8', chat_room_id: 'chat-cs-session-101',
    });
    expect(r.body.signature).toBe(sign('cs-session-101', '{"active_host":"player-alpha-2","handoff_occurred":true,"layout":"grid_4","participant_count":3,"session_id":"cs-session-101","status":"active"}'));
    const again = await cs(spec);
    expect(again.status).toBe(200);
    expect(again.body).toEqual(r.body);
    const replaced = await cs({ ...spec, simulate_host_drop: false });
    expect(replaced.status).toBe(200);
    expect(replaced.body).toMatchObject({ active_host: 'player-alpha-1', handoff_occurred: false, participant_count: 4 });
    expect((await (await api()).get('/api/v1/costream/cs-session-101')).body).toEqual(replaced.body);
    expect((await (await api()).get('/api/v1/costream/nope')).status).toBe(404);
    const qr = await (await api()).get('/audit/v1/qr/cs-session-101?inspect=true');
    expect(qr.body.target_route).toBe('/web/costream/cs-session-101');
  });

  dbIt('terminated session signs a null host', async () => {
    const r = await cs({ session_id: 'cs-session-102', host_player_id: 'player-alpha-1', participants: ['player-alpha-1'], layout: 'solo', simulate_host_drop: true });
    expect(r.status).toBe(201);
    expect(r.body).toMatchObject({ status: 'terminated', active_host: null, handoff_occurred: false, participant_count: 0, active_participants: [] });
    expect(r.body.signature).toBe(sign('cs-session-102', '{"active_host":null,"handoff_occurred":false,"layout":"solo","participant_count":0,"session_id":"cs-session-102","status":"terminated"}'));
  });

  dbIt('validation order and layout capacity', async () => {
    const ok = { session_id: 'cs-session-103', host_player_id: 'player-alpha-1' };
    expect((await cs({ ...ok, participants: [...P, 'ghost-1'] })).body.error.code).toBe('PLAYER_NOT_FOUND');
    await pool.query(`INSERT INTO players (player_id, name) VALUES ('player-x', 'X')`);
    const full = await cs({ ...ok, participants: [...P, 'player-x'] });
    expect(full.status).toBe(409);
    expect(full.body.error.code).toBe('COSTREAM_SESSION_FULL');
    expect((await cs({ ...ok, participants: ['player-alpha-1', 'player-alpha-1'] })).status).toBe(400);
    const nohost = await cs({ ...ok, host_player_id: 'player-beta-1', participants: ['player-alpha-1'] });
    expect(nohost.status).toBe(400);
    expect(nohost.body.error.details.fields).toContain('host_player_id');
    expect((await cs({ ...ok, host_player_id: 'ghost', participants: ['ghost'] })).status).toBe(404);
    expect((await cs({ ...ok, participants: P, layout: 'grid_3' })).status).toBe(400);
    expect((await cs({ ...ok, participants: ['player-alpha-1', 'player-alpha-2'], layout: 'solo' })).body.error.code).toBe('UNSUPPORTED_LAYOUT');
    expect((await cs({ ...ok, participants: P.slice(0, 3), layout: 'grid_2' })).body.error.code).toBe('UNSUPPORTED_LAYOUT');
    expect((await cs({ ...ok, participants: P.slice(0, 3), layout: 'grid_2', simulate_host_drop: true })).status).toBe(201);
    expect((await cs({ ...ok, session_id: 'cs-session-104', participants: P.slice(0, 2), layout: 'solo', simulate_host_drop: true })).status).toBe(201);
    expect((await cs({ ...ok, session_id: 'cs-session-105', participants: P })).body.layout).toBe('grid_4');
    expect((await cs({ ...ok, participants: P, simulate_host_drop: 'yes' })).status).toBe(400);
    expect((await cs({ host_player_id: 'player-alpha-1', participants: P })).status).toBe(400);
    expect((await cs({ ...ok, participants: [] })).status).toBe(400);
  });

  dbIt('twenty identical simultaneous requests: one 201, rest 200', async () => {
    const rs = await Promise.all(Array.from({ length: 20 }, () => cs(spec)));
    expect(rs.filter((r) => r.status === 201)).toHaveLength(1);
    expect(rs.filter((r) => r.status === 200)).toHaveLength(19);
    expect(new Set(rs.map((r) => JSON.stringify(r.body))).size).toBe(1);
  });
});

describe('privacy erasure', () => {
  afterAll(async () => { if (process.env.DB_AVAILABLE === '1') await resetDb(); });
  beforeEach(async () => { if (process.env.DB_AVAILABLE === '1') await resetDb(); });

  dbIt('anonymizes in place, is idempotent and restores on reset', async () => {
    await key({ action: 'generate', player_id: 'player-alpha-1' });
    const r = await (await api()).delete('/audit/v1/privacy/user/player-alpha-1');
    expect(r.status).toBe(200);
    expect(r.body).toEqual({ user_id: 'player-alpha-1', status: 'anonymized', pii_retained: false, tokens_wiped: true });
    expect((await (await api()).delete('/audit/v1/privacy/user/player-alpha-1')).body).toEqual(r.body);
    expect((await (await api()).delete('/audit/v1/privacy/user/nobody')).body.error.code).toBe('PLAYER_NOT_FOUND');
    const left = await pool.query(`SELECT (SELECT count(*) FROM players WHERE name ILIKE '%Alpha Player 1%') + (SELECT count(*) FROM users WHERE email = 'player@nascon.test') AS c`);
    expect(Number(left.rows[0].c)).toBe(0);
    expect((await pool.query(`SELECT count(*)::int AS c FROM day2_stream_key WHERE player_id = 'player-alpha-1' AND status = 'active'`)).rows[0].c).toBe(0);
    expect((await (await api()).get('/audit/v1/qr/player-alpha-1?inspect=true')).status).toBe(200);
    await resetDb();
    expect((await pool.query(`SELECT name FROM players WHERE player_id = 'player-alpha-1'`)).rows[0].name).toBe('Alpha Player 1');
  });

  dbIt('a token issued before erasure is rejected afterwards, and works again after reset', async () => {
    const { signAccessToken } = require('../../backend/src/services/authService');
    const token = signAccessToken({ user_id: 'user-player', role: 'player', tenant_id: 'tenant-alpha', club_id: 'club-alpha-1', player_id: 'player-alpha-1' });
    const before = await (await api()).get('/api/v1/me').set('Authorization', `Bearer ${token}`);
    expect(before.status).toBe(200);
    await (await api()).delete('/audit/v1/privacy/user/player-alpha-1');
    const after = await (await api()).get('/api/v1/me').set('Authorization', `Bearer ${token}`);
    expect(after.status).toBe(401);
    expect(after.body.error.code).toBe('UNAUTHENTICATED');
    const other = signAccessToken({ user_id: 'user-viewer', role: 'viewer', tenant_id: 'tenant-alpha' });
    expect((await (await api()).get('/api/v1/me').set('Authorization', `Bearer ${other}`)).status).toBe(200);
    await resetDb();
    expect((await (await api()).get('/api/v1/me').set('Authorization', `Bearer ${token}`)).status).toBe(200);
  });
});
