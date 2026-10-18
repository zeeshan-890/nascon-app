// FR-3.1 follow/unfollow: idempotent, concurrency-safe, no follower-count drift.
const { api, resetDb } = require('../helpers/app');
const { signAccessToken } = require('../../backend/src/services/authService');

const dbIt = process.env.DB_AVAILABLE === '1' ? it : it.skip;
const tok = (id, role = 'viewer') => `Bearer ${signAccessToken({ user_id: id, role, tenant_id: 'tenant-alpha' })}`;

describe('follow graph', () => {
  beforeEach(async () => { if (process.env.DB_AVAILABLE === '1') await resetDb(); });

  dbIt('follow is idempotent, unfollow restores the count, bad input is rejected', async () => {
    const f1 = await (await api()).post('/api/v1/athletes/player-alpha-1/follow').set('Authorization', tok('u1'));
    expect(f1.body).toEqual({ athlete_id: 'player-alpha-1', following: true, follower_count: 1 });
    expect((await (await api()).post('/api/v1/athletes/player-alpha-1/follow').set('Authorization', tok('u1'))).body.follower_count).toBe(1);
    expect((await (await api()).post('/api/v1/athletes/player-alpha-1/follow').set('Authorization', tok('u2'))).body.follower_count).toBe(2);
    expect((await (await api()).delete('/api/v1/athletes/player-alpha-1/follow').set('Authorization', tok('u1'))).body).toEqual({ athlete_id: 'player-alpha-1', following: false, follower_count: 1 });
    expect((await (await api()).delete('/api/v1/athletes/player-alpha-1/follow').set('Authorization', tok('u1'))).body.follower_count).toBe(1);
    expect((await (await api()).get('/api/v1/athletes/player-alpha-1/followers')).body.follower_count).toBe(1);
    expect((await (await api()).post('/api/v1/athletes/nobody/follow').set('Authorization', tok('u1'))).body.error.code).toBe('PLAYER_NOT_FOUND');
    expect((await (await api()).post('/api/v1/athletes/player-alpha-1/follow')).status).toBe(401);
    const guest = await (await api()).post('/api/v1/athletes/player-alpha-1/follow').set('Authorization', 'Bearer guest_anon_tok_7721');
    expect(guest.status).toBe(403);
    expect(guest.body.error.code).toBe('GUEST_WRITE_FORBIDDEN');
  });

  dbIt('toggle bursts leave the count equal to the final state', async () => {
    const calls = [];
    for (let i = 0; i < 30; i += 1) {
      calls.push((async () => (await api()).post('/api/v1/athletes/player-alpha-2/follow').set('Authorization', tok('burst')))());
      calls.push((async () => (await api()).delete('/api/v1/athletes/player-alpha-2/follow').set('Authorization', tok('burst')))());
    }
    await Promise.all(calls);
    const n = (await (await api()).get('/api/v1/athletes/player-alpha-2/followers')).body.follower_count;
    expect([0, 1]).toContain(n);
    await (await api()).post('/api/v1/athletes/player-alpha-2/follow').set('Authorization', tok('burst'));
    expect((await (await api()).get('/api/v1/athletes/player-alpha-2/followers')).body.follower_count).toBe(1);
  });
});
