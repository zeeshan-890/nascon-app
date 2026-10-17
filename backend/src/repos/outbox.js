// Must be called inside the business transaction (pass the transaction client).
async function enqueue(client, { aggregate_type, aggregate_id, event_type, payload }) {
  await client.query(
    'INSERT INTO outbox (aggregate_type, aggregate_id, event_type, payload) VALUES ($1, $2, $3, $4::jsonb)',
    [aggregate_type, aggregate_id, event_type, JSON.stringify(payload)]
  );
}

module.exports = { enqueue };
