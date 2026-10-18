// Basic host controls: only the active host can invite, mute, spotlight and kick.
const { api, resetDb, pool } = require('../helpers/app');
const { signAccessToken } = require('../../backend/src/services/authService');

const dbIt = process.env.DB_AVAILABLE === '1' ? it : it.skip;
const tok = (player) => `Bearer ${signAccessToken({ user_id: `u-${player}`, role: 'player', tenant_id: 'tenant-alpha', player_id: player })}`;
const P = ['player-alpha-1', 'player-alpha-2', 'player-beta-1', 'player-beta-2'];

describe('host controls', () => {
  beforeEach(async () => { if (process.env.DB_AVAILABLE === '1') await resetDb(); });

  dbIt('host can invite, mute, spotlight and kick; others and guests cannot', async () => {
    const make = await (await api()).post('/audit/v1/costream/session').send({ session_id: 'cs-session-hc', host_player_id: P[0], participants: P });
    expect(make.status).toBe(201);
    const post = async (action, who, body) => (await api()).post(`/api/v1/costream/cs-session-hc/${action}`).set('Authorization', tok(who)).send(body);
    const inv = await post('invite', P[0], { player_id: 'player-x' });
    expect(inv.status).toBe(201);
    expect(inv.body.invite_token).toMatch(/^invite_player-x_[0-9a-f]{64}$/);
    expect((await post('mute', P[0], { player_id: P[1] })).status).toBe(200);
    expect((await post('spotlight', P[0], { player_id: P[2] })).status).toBe(200);
    expect((await pool.query(`SELECT kind, player_id FROM day2_costream_ctrl ORDER BY kind`)).rows).toEqual([{ kind: 'mute', player_id: P[1] }, { kind: 'spotlight', player_id: P[2] }]);
    expect((await post('unmute', P[0], { player_id: P[1] })).status).toBe(200);
    expect((await post('mute', P[1], { player_id: P[2] })).status).toBe(403);
    const kick = await post('kick', P[0], { player_id: P[3] });
    expect(kick.status).toBe(200);
    expect(kick.body).toMatchObject({ participant_count: 3 });
    expect(kick.body.active_participants).not.toContain(P[3]);
    expect((await post('kick', P[0], { player_id: P[0] })).status).toBe(400);
    expect((await post('kick', P[0], { player_id: 'nobody' })).status).toBe(404);
    const guest = await (await api()).post('/api/v1/costream/cs-session-hc/kick').set('Authorization', 'Bearer guest_anon_tok_7721').send({ player_id: P[1] });
    expect(guest.status).toBe(403);
    expect(guest.body.error.code).toBe('GUEST_WRITE_FORBIDDEN');
    expect((await (await api()).post('/api/v1/costream/nope/kick').set('Authorization', tok(P[0])).send({ player_id: P[1] })).status).toBe(404);
  });
});
