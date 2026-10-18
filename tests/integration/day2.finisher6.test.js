// Real chat route: room sequence numbers, moderation, rate limit, guest block.
const { api, resetDb } = require('../helpers/app');
const { signAccessToken } = require('../../backend/src/services/authService');

const dbIt = process.env.DB_AVAILABLE === '1' ? it : it.skip;
const tok = (id) => `Bearer ${signAccessToken({ user_id: id, role: 'viewer', tenant_id: 'tenant-alpha' })}`;
const say = async (user, text, room = 'chat-cs-session-101') => (await api()).post('/api/v1/chat').set('Authorization', tok(user)).send({ chat_room_id: room, text });

describe('chat route', () => {
  beforeEach(async () => { if (process.env.DB_AVAILABLE === '1') await resetDb(); });

  dbIt('gapless sequence across 30 simultaneous senders, masking, flood, guest and auth', async () => {
    const rs = await Promise.all(Array.from({ length: 30 }, (_, i) => say(`sender-${i}`, `hello ${i}`)));
    expect(rs.every((r) => r.status === 201)).toBe(true);
    expect(rs.map((r) => r.body.seq).sort((a, b) => a - b)).toEqual(Array.from({ length: 30 }, (_, i) => i + 1));
    const masked = await say('masker', 'this is b@d_w0rd ok');
    expect(masked.body).toMatchObject({ text: 'this is *** ok', flagged: true, reason: 'profanity_leetspeak', seq: 31 });
    const flood = await say('flooder', 'GOAL! AAAA!!!');
    expect(flood.status).toBe(200);
    expect(flood.body).toMatchObject({ delivered: false, reason: 'flood_character_repeat' });
    const limited = [];
    for (let i = 0; i < 5; i += 1) limited.push((await say('chatty', `m${i}`)).body);
    expect(limited.filter((b) => b.delivered)).toHaveLength(3);
    expect(limited[4]).toMatchObject({ delivered: false, reason: 'rate_limit_exceeded' });
    const guest = await (await api()).post('/api/v1/chat').set('Authorization', 'Bearer guest_anon_tok_7721').send({ chat_room_id: 'r', text: 'hi' });
    expect(guest.status).toBe(403);
    expect(guest.body.error.code).toBe('GUEST_WRITE_FORBIDDEN');
    expect((await (await api()).post('/api/v1/chat').send({ chat_room_id: 'r', text: 'hi' })).status).toBe(401);
    expect((await say('v', '')).status).toBe(400);
  });
});
