async function findById(db, eventId) {
  const { rows } = await db.query('SELECT * FROM match_events WHERE event_id = $1', [eventId]);
  return rows[0] || null;
}

async function listByMatch(db, matchId) {
  const { rows } = await db.query(
    `SELECT sequence, event_id, type, payload_canonical, signature, compensates
     FROM match_events WHERE match_id = $1 ORDER BY sequence`,
    [matchId]
  );
  return rows;
}

async function insert(db, e) {
  await db.query(
    `INSERT INTO match_events
       (event_id, match_id, sequence, type, payload, payload_canonical, signature, request_canonical, compensates, response)
     VALUES ($1, $2, $3, $4, $5::jsonb, $6, $7, $8, $9, $10::json)`,
    [
      e.event_id, e.match_id, e.sequence, e.type, JSON.stringify(e.payload), e.payload_canonical,
      e.signature, e.request_canonical, e.compensates || null, JSON.stringify(e.response),
    ]
  );
}

module.exports = { findById, listByMatch, insert };
