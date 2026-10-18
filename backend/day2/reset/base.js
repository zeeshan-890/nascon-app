// Part II base seed (owned by M0): match-m1. Runs inside the reset transaction through the reset hook.
// The score is derived from the event log like every other match, so the seed appends real signed events:
// 1 - 0 now, and a later score event gives 2 - 0 / 1 - 1, match_end completes it, standings stay correct.
const matchesRepo = require('../../src/repos/matches');
const eventService = require('../../src/services/eventService');
const sportsSeed = require('../../seed/sports.json');

const MATCH_ID = 'match-m1';
const LEAGUE_ID = 'league-alpha-1';
const HOME = 'club-alpha-1';
const AWAY = 'club-alpha-2';

async function seed(ctx) {
  const { client } = ctx;
  const sportId = sportsSeed[0].sport_id; // first seeded sport; no sport name is hard-coded
  const config = (await client.query('SELECT config FROM sports WHERE sport_id = $1', [sportId])).rows[0].config;
  const scoreKey = Object.keys(config.scoring_events)[0];
  await matchesRepo.insertFixtures(client, {
    sportId, leagueId: LEAGUE_ID, fixtures: [{ match_id: MATCH_ID, home: HOME, away: AWAY }],
  });
  const ev = (suffix, type, payload) => eventService.appendEventIn(client, {
    event_id: `${MATCH_ID}-seed-${suffix}`, match_id: MATCH_ID, type, payload,
  });
  await ev('start', 'match_start', {});
  await ev('home-1', 'score', { team_id: HOME, event_type: scoreKey });
}

module.exports = { seed, MATCH_ID };
