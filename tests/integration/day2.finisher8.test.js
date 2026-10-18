// Basic VOD index.
const { api, resetDb } = require('../helpers/app');

const dbIt = process.env.DB_AVAILABLE === '1' ? it : it.skip;

describe('vod index', () => {
  beforeEach(async () => { if (process.env.DB_AVAILABLE === '1') await resetDb(); });

  dbIt('a finished recording shows up in the athlete archive', async () => {
    const k = await (await api()).post('/audit/v1/stream/key').send({ action: 'generate', player_id: 'player-alpha-1' });
    const rec = await (await api()).post('/internal/media/recording').send({ path: `live/${k.body.stream_key}`, recording_path: '/recordings/a.mp4' });
    expect(rec.status).toBe(201);
    const list = await (await api()).get('/api/v1/athletes/player-alpha-1/vod');
    expect(list.body.items).toHaveLength(1);
    expect(list.body.items[0]).toMatchObject({ stream_key: k.body.stream_key, recording_path: '/recordings/a.mp4' });
    expect((await (await api()).post('/internal/media/recording').send({ path: 'live/unknown' })).status).toBe(404);
    expect((await (await api()).post('/internal/media/recording').send({ path: 'other' })).status).toBe(400);
    expect((await (await api()).get('/api/v1/athletes/nobody/vod')).status).toBe(404);
    expect((await (await api()).get('/api/v1/athletes/player-alpha-2/vod')).body.items).toEqual([]);
  });
});
