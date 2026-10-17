const pool = require('../db/pool');
const { getRedis } = require('../db/redis');

// Shapes the payload clients receive; the outbox payload carries extra internal fields.
function clientData(eventType, payload) {
  switch (eventType) {
    case 'match.event_appended':
      return {
        match_id: payload.match_id, event_id: payload.event_id, sequence: payload.sequence,
        version: payload.version === undefined ? payload.match_version : payload.version,
        event_type: payload.event_type || payload.type,
      };
    case 'match.status_changed':
      return { match_id: payload.match_id, status: payload.status };
    case 'product.pinned':
      return { match_id: payload.match_id, product_id: payload.product_id };
    default:
      return payload;
  }
}

async function leagueOf(client, row) {
  if (row.payload && row.payload.league_id) return row.payload.league_id;
  const { rows } = await client.query('SELECT league_id FROM matches WHERE match_id = $1', [row.aggregate_id]);
  return rows[0] ? rows[0].league_id : null;
}

// Publishes committed outbox rows to the Redis stream and to the realtime channels (rt:{topic}).
async function relayOnce() {
  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const { rows } = await client.query(
      `SELECT id, aggregate_type, aggregate_id, event_type, payload FROM outbox
       WHERE published_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED`
    );
    if (!rows.length) {
      await client.query('COMMIT');
      return 0;
    }
    const redis = getRedis();
    if (redis.status === 'wait') await redis.connect();
    for (const row of rows) {
      const json = JSON.stringify(row.payload);
      await redis.xadd('domain-events', '*', 'type', row.event_type, 'aggregate_id', row.aggregate_id, 'json', json);
      const topic = `${row.aggregate_type}:${row.aggregate_id}`;
      await redis.publish(`rt:${topic}`, JSON.stringify({
        type: row.event_type, match_id: row.aggregate_id, data: clientData(row.event_type, row.payload),
      }));
      if (row.event_type === 'match.event_appended') {
        const leagueId = await leagueOf(client, row);
        if (leagueId) {
          await redis.publish(`rt:league:${leagueId}`, JSON.stringify({ type: 'standings.updated', data: { league_id: leagueId } }));
        }
      }
    }
    await client.query('UPDATE outbox SET published_at = now() WHERE id = ANY($1::bigint[])', [rows.map((r) => r.id)]);
    await client.query('COMMIT');
    return rows.length;
  } catch (err) {
    await client.query('ROLLBACK').catch(() => {});
    throw err;
  } finally {
    client.release();
  }
}

module.exports = { relayOnce, clientData };
