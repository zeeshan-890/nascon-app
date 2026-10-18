// Reset seed: fixed QR short codes with scan_count 0.
async function seed({ client }) {
  await client.query(
    `INSERT INTO day2_qr_code (entity_id, code) VALUES ('match-m1', 'm1-live'), ('player-alpha-1', 'p-alpha-1'), ('club-alpha-1', 'c-alpha-1')`,
  );
}

module.exports = { seed };
