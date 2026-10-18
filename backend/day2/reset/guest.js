// Reset seed: the public guest token used by the autograder, valid, unmerged, with an empty registered cart.
async function seed({ client }) {
  await client.query(
    `INSERT INTO day2_guest_token (token, role, expires_at) VALUES ('guest_anon_tok_7721', 'guest', now() + interval '2 hours')`,
  );
}

module.exports = { seed };
