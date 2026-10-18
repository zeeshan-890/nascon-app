// Part II contract probes added by the finisher: moderation + DLQ, metrics, guest session/cart-merge, QR.
const crypto = require('crypto');
const { api, resetDb } = require('../helpers/app');
const { moderate } = require('../../backend/day2/lib/moderation');

const sign = (entity, payload) => crypto.createHmac('sha256', process.env.DAY2_SALT).update(`${entity}|${payload}`).digest('hex');
const dbIt = process.env.DB_AVAILABLE === '1' ? it : it.skip;
const msg = (text, sender = 's1', i = 1) => ({ message_id: `m${i}`, sender, text });

describe('moderation rules (pure)', () => {
  const flagged = (t) => moderate([msg(t)]).filtered[0].flagged;
  it.each(['badword', 'BADWORD', 'b@d_w0rd', 'bad_word', 'p0rn', 'p.0.r.n', 'sc@m', 's!c@m', 'fuck', 'f*u*c*k', 'sh!t', 'b!tch', 'a$$h0le',
    'asshole', 'idiot_spam', 'bad​word', 'ｂａｄｗｏｒｄ', 'b@d_w0rd!', 'x b@d_w0rd, y'])('masks %s', (t) => {
    expect(flagged(t)).toBe(true);
  });
  it.each(['Clean sports commentary', 'class', 'classic', 'assist', 'assistant', 'assess', 'scamper', 'bitcoin', 'passion', 'shift',
    'badminton', '1000', '\u{1F600}', 'اردو متن', '你好世界'])('leaves %s', (t) => {
    expect(flagged(t)).toBe(false);
  });
  it.each(['AAAA', 'aAaA', '....', '????', '!!!', '!?!', '\u{1F600}\u{1F600}\u{1F600}\u{1F600}', 'GOAL! AAAA!!!'])('flood %s', (t) => {
    const r = moderate([msg(t)]);
    expect(r.filtered).toEqual([]);
    expect(r.muted).toEqual(['s1']);
  });
  it('exactly three repeats is not a flood', () => {
    expect(moderate([msg('AAA')]).muted).toEqual([]);
  });
  it('keeps the rest of the text and dedupes lists in order', () => {
    const r = moderate([msg('Watch this illegal stream b@d_w0rd now!', 'u1', 1), msg('porn and scam', 'u1', 2), msg('fuck', 'u2', 3), msg('b@d_w0rd AAAA', 'u3', 4)]);
    expect(r.filtered[0].text).toBe('Watch this illegal stream *** now!');
    expect(r.filtered[1].text).toBe('*** and ***');
    expect(r.kicked).toEqual(['u1', 'u2']);
    expect(r.muted).toEqual(['u3']);
    expect(r.flagged).toBe(3);
  });
});

describe('Part II contract probes (database)', () => {
  beforeEach(async () => { if (process.env.DB_AVAILABLE === '1') await resetDb(); });

  dbIt('moderation spec example with signature and DLQ', async () => {
    const body = {
      chat_room_id: 'chat-cs-session-101',
      incoming_messages: [msg('Clean sports commentary', 'user-guest-1', 1), msg('Watch this illegal stream b@d_w0rd now!', 'user-spammer', 2), msg('GOAL! AAAA!!!', 'user-flooder', 3)],
      check_dlq: true,
      simulate_failed_notifications: 0,
    };
    const r = await (await api()).post('/audit/v1/moderation/test').set('Authorization', 'Bearer guest_anon_tok_7721').send(body);
    expect(r.status).toBe(200);
    expect(r.body.kicked_users).toEqual(['user-spammer']);
    expect(r.body.muted_users).toEqual(['user-flooder']);
    expect(r.body.dlq_status).toEqual({ queue_depth: 0, undelivered_events: [] });
    expect(r.body.signature).toBe(sign('chat-cs-session-101', '{"chat_room_id":"chat-cs-session-101","flagged_count":1,"kicked_users":["user-spammer"],"queue_depth":0}'));
  });

  dbIt('dlq depth accumulates and validation errors use the envelope', async () => {
    const a = await (await api()).post('/audit/v1/moderation/test').send({ chat_room_id: 'r', incoming_messages: [], check_dlq: true, simulate_failed_notifications: 3 });
    expect(a.body.dlq_status.queue_depth).toBe(3);
    expect(a.body.dlq_status.undelivered_events[0]).toMatchObject({ attempts: 3, status: 'dead_lettered' });
    const b = await (await api()).post('/audit/v1/moderation/test').send({ chat_room_id: 'r', incoming_messages: [], simulate_failed_notifications: 3 });
    expect(b.body.dlq_status.queue_depth).toBe(6);
    const bad = await (await api()).post('/audit/v1/moderation/test').send({ chat_room_id: 'r', incoming_messages: [], simulate_failed_notifications: 1001 });
    expect(bad.status).toBe(400);
    expect(bad.body.error.code).toBe('VALIDATION_ERROR');
    const m = await (await api()).get('/metrics');
    expect(m.text).toMatch(/nascon_dlq_depth 6\n/);
  });

  dbIt('metrics format', async () => {
    const r = await (await api()).get('/metrics');
    expect(r.status).toBe(200);
    expect(r.headers['content-type']).toBe('text/plain; version=0.0.4');
    for (const n of ['nascon_active_ws_connections', 'nascon_active_streams_total', 'nascon_stream_bitrate_kbps', 'nascon_dlq_depth', 'nascon_qr_scans_total']) {
      expect(r.text).toContain(`# HELP ${n} `);
      expect(r.text).toContain(`# TYPE ${n} `);
      expect(r.text).toMatch(new RegExp(`^${n} \\d+$`, 'm'));
    }
    expect(r.text.endsWith('\n')).toBe(true);
  });

  dbIt('guest session, staging and cart merge incl. replay and conflicts', async () => {
    const s = await (await api()).post('/api/v1/guest/session').send({});
    expect(s.status).toBe(201);
    expect(s.body.guest_token).toMatch(/^guest_anon_tok_[0-9a-f]{16}$/);
    const put = await (await api()).put('/api/v1/guest/cart').set('Authorization', `Bearer ${s.body.guest_token}`).send({ items: [{ variant_id: 'var-jersey-001-M', quantity: 2 }] });
    expect(put.status).toBe(200);
    const get = await (await api()).get('/api/v1/guest/cart').set('X-Guest-Token', s.body.guest_token);
    expect(get.body.items).toEqual([{ variant_id: 'var-jersey-001-M', quantity: 2 }]);

    const req = { guest_token: 'guest_anon_tok_7721', registered_user_id: 'player-alpha-1', guest_cart: [{ variant_id: 'var-jersey-001-L', quantity: 1 }], test_write_block: true };
    const r1 = await (await api()).post('/audit/v1/guest/cart-merge').send(req);
    expect(r1.status).toBe(200);
    expect(r1.body).toMatchObject({ write_block_verified: true, write_rejection_code: 403, cart_merged: true });
    expect(r1.body.active_cart).toEqual({ user_id: 'player-alpha-1', currency: 'PKR', total_items: 1, subtotal_minor: 450000, items: [{ variant_id: 'var-jersey-001-L', quantity: 1, unit_price_minor: 450000 }] });
    expect(r1.body.signature).toBe(sign('guest_anon_tok_7721', '{"cart_merged":true,"guest_token":"guest_anon_tok_7721","registered_user_id":"player-alpha-1","total_items":1,"write_rejection_code":403}'));
    const r2 = await (await api()).post('/audit/v1/guest/cart-merge').send(req);
    expect(r2.status).toBe(200);
    expect(r2.body).toEqual(r1.body);
    const other = await (await api()).post('/audit/v1/guest/cart-merge').send({ ...req, registered_user_id: 'player-alpha-2' });
    expect(other.status).toBe(409);
    expect(other.body.error.code).toBe('GUEST_TOKEN_ALREADY_MERGED');
    const nouser = await (await api()).post('/audit/v1/guest/cart-merge').send({ ...req, guest_token: 'guest_x1', registered_user_id: 'nobody' });
    expect(nouser.body.error.code).toBe('PLAYER_NOT_FOUND');
    const novar = await (await api()).post('/audit/v1/guest/cart-merge').send({ ...req, guest_token: 'guest_x2', guest_cart: [{ variant_id: 'zzz', quantity: 1 }] });
    expect(novar.body.error.code).toBe('VARIANT_NOT_FOUND');
    const big = await (await api()).post('/audit/v1/guest/cart-merge').send({ ...req, guest_token: 'guest_x3', guest_cart: [{ variant_id: 'var-jersey-001-M', quantity: 99999 }] });
    expect(big.status).toBe(409);
    expect(big.body.error.details.variant_id).toBe('var-jersey-001-M');
    const noblock = await (await api()).post('/audit/v1/guest/cart-merge').send({ ...req, guest_token: 'guest_x4', test_write_block: false });
    expect(noblock.body).toMatchObject({ write_block_verified: false, write_rejection_code: 0 });
    for (const q of [0, -1, 1.5, '1']) {
      const bad = await (await api()).post('/audit/v1/guest/cart-merge').send({ ...req, guest_token: 'guest_x5', guest_cart: [{ variant_id: 'var-jersey-001-L', quantity: q }] });
      expect(bad.status).toBe(400);
    }
  });

  dbIt('concurrent identical merges are all 200 with one add', async () => {
    const req = { guest_token: 'guest_anon_tok_7721', registered_user_id: 'player-alpha-1', guest_cart: [{ variant_id: 'var-jersey-001-L', quantity: 1 }] };
    const rs = await Promise.all(Array.from({ length: 20 }, async () => (await api()).post('/audit/v1/guest/cart-merge').send(req)));
    expect(rs.map((r) => r.status)).toEqual(Array(20).fill(200));
    expect(new Set(rs.map((r) => r.body.active_cart.total_items))).toEqual(new Set([1]));
  });

  dbIt('QR metadata, scan counting, redirect, state changes and exports', async () => {
    const a = await (await api()).get('/audit/v1/qr/match-m1');
    expect(a.status).toBe(200);
    expect(Object.keys(a.body).sort()).toEqual(['deep_link_uri', 'entity_id', 'entity_status', 'entity_type', 'export_formats', 'scan_count', 'short_url', 'signature', 'target_route'].sort());
    expect(a.body).toMatchObject({ short_url: 'http://localhost:8080/q/m1-live', target_route: '/web/matches/match-m1/live', scan_count: 1, entity_status: 'in_progress' });
    expect(a.body.signature).toBe(sign('match-m1', '{"entity_id":"match-m1","short_url":"http://localhost:8080/q/m1-live","target_route":"/web/matches/match-m1/live"}'));
    const b = await (await api()).get('/audit/v1/qr/match-m1');
    expect(b.body.scan_count).toBe(2);
    const i = await (await api()).get('/audit/v1/qr/match-m1?inspect=true');
    expect(i.body.scan_count).toBe(2);
    const p = await (await api()).get('/audit/v1/qr/player-alpha-1?inspect=true');
    expect(p.body).toMatchObject({ short_url: 'http://localhost:8080/q/p-alpha-1', target_route: '/web/players/player-alpha-1' });
    const c = await (await api()).get('/audit/v1/qr/club-alpha-1?inspect=true');
    expect(c.body.target_route).toBe('/web/clubs/club-alpha-1');
    for (const id of ['match-zzz', 'foo-1', 'x'.repeat(500), '..%2F..%2Fetc', 'MATCH-M1']) {
      const nf = await (await api()).get(`/audit/v1/qr/${id}`);
      expect(nf.status).toBe(404);
      expect(nf.body.error.code).toBe('QR_NOT_FOUND');
    }
    const red = await (await api()).get('/q/m1-live');
    expect(red.status).toBe(302);
    expect(red.headers.location).toBe('/web/matches/match-m1/live');
    expect(red.headers['set-cookie']).toBeUndefined();
    expect((await (await api()).get('/q/nope')).body.error.code).toBe('QR_NOT_FOUND');
    expect((await (await api()).get('/audit/v1/qr/match-m1?inspect=true')).body.scan_count).toBe(3);

    const raw = (res, cb) => { const d = []; res.on('data', (x) => d.push(x)); res.on('end', () => cb(null, Buffer.concat(d))); };
    const svg = await (await api()).get('/audit/v1/qr/match-m1/export.svg').buffer(true).parse(raw);
    svg.text = svg.body.toString('utf8');
    expect(svg.headers['content-type']).toMatch(/^image\/svg\+xml/);
    expect(svg.text.startsWith('<svg') || svg.text.startsWith('<?xml')).toBe(true);
    const png = await (await api()).get('/audit/v1/qr/match-m1/export.png').buffer(true).parse((res, cb) => { const d = []; res.on('data', (x) => d.push(x)); res.on('end', () => cb(null, Buffer.concat(d))); });
    expect(png.headers['content-type']).toBe('image/png');
    expect(png.body.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a');
    expect(png.body.readUInt32BE(16)).toBeGreaterThanOrEqual(512);
    const pdf = await (await api()).get('/audit/v1/qr/match-m1/export.pdf').buffer(true).parse((res, cb) => { const d = []; res.on('data', (x) => d.push(x)); res.on('end', () => cb(null, Buffer.concat(d))); });
    expect(pdf.headers['content-type']).toBe('application/pdf');
    expect(pdf.body.subarray(0, 5).toString()).toBe('%PDF-');
    expect(pdf.body.toString('latin1')).toContain('http://localhost:8080/q/m1-live');
    expect((await (await api()).get('/audit/v1/qr/match-m1/export.gif')).status).toBe(400);
    expect((await (await api()).get('/audit/v1/qr/match-m1?inspect=true')).body.scan_count).toBe(3);

    const { pool } = require('../helpers/app');
    await pool.query(`UPDATE matches SET admin_status = 'postponed' WHERE match_id = 'match-m1'`);
    const n = await (await api()).get('/audit/v1/qr/match-m1?inspect=true');
    expect(n.body.short_url).toBe(a.body.short_url);
    expect(n.body.target_route).toBe('/web/matches/match-m1/notice?state=postponed');
    expect(n.body.signature).not.toBe(a.body.signature);
    const buckets = await pool.query('SELECT * FROM day2_qr_bucket');
    expect(Object.keys(buckets.rows[0]).sort()).toEqual(['count', 'device', 'entity_id', 'hour_utc']);
    await resetDb();
    expect((await (await api()).get('/audit/v1/qr/match-m1?inspect=true')).body).toMatchObject({ scan_count: 0, entity_status: 'in_progress' });
  });

  dbIt('hundred concurrent scans count exactly', async () => {
    await Promise.all(Array.from({ length: 100 }, async () => (await api()).get('/q/m1-live')));
    expect((await (await api()).get('/audit/v1/qr/match-m1?inspect=true')).body.scan_count).toBe(100);
  });

  dbIt('guest bearer is still rejected on Phase I write routes', async () => {
    for (const path of ['/api/v1/chat', '/audit/v1/checkout', '/audit/v1/events']) {
      const r = await (await api()).post(path).set('Authorization', 'Bearer guest_anon_tok_7721').send({});
      expect(r.status).toBe(403);
      expect(r.body.error.code).toBe('GUEST_WRITE_FORBIDDEN');
    }
  });
});
