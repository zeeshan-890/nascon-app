const fs = require('fs');
const path = require('path');
const pool = require('../db/pool');
const logger = require('../logger');
const { withTransaction } = require('../db/tx');
const eventService = require('./eventService');
const rosterService = require('./rosterService');
const matchesRepo = require('../repos/matches');
const sports = require('../repos/sports');

const demo = JSON.parse(fs.readFileSync(path.join(__dirname, '../../seed/demo.json'), 'utf8'));
const sportsSeed = JSON.parse(fs.readFileSync(path.join(__dirname, '../../seed/sports.json'), 'utf8'));

const LEAGUE = 'league-alpha-1';
const TENANT = 'tenant-alpha';
const HOUR = 3600 * 1000;

function offsetMs(f) {
  return (f.days || 0) * 24 * HOUR + (f.hours || 0) * HOUR;
}

// Static data: names, branding, venues, products. Plain SQL, one transaction.
async function seedStatic(seed) {
  await withTransaction(async (client) => {
    await client.query(`UPDATE tenants SET logo_url = '/static/logo/' || tenant_id || '.png'`);
    await client.query(
      `UPDATE leagues SET name = $2,
         config = config || jsonb_build_object('qualification_spots', $3::int, 'season', $4::text, 'lots_seed', $5::int)
       WHERE league_id = $1`,
      [LEAGUE, demo.league.name, demo.league.qualification_spots, demo.league.season, seed],
    );
    for (const [i, t] of demo.teams.entries()) {
      await client.query(
        'INSERT INTO league_teams (league_id, team_id, position, name) VALUES ($1, $2, $3, $4)',
        [LEAGUE, t.team_id, i, t.name],
      );
    }
    for (const v of demo.venues) {
      await client.query(
        'INSERT INTO venues (venue_id, tenant_id, name, lat, lng) VALUES ($1, $2, $3, $4, $5)',
        [v.venue_id, TENANT, v.name, v.lat, v.lng],
      );
    }
    await client.query(`UPDATE clubs SET logo_url = '/static/logo/' || club_id || '.png'`);
    for (const [clubId, c] of Object.entries(demo.clubs)) {
      await client.query('UPDATE clubs SET venue = $2::jsonb, socials = $3::jsonb WHERE club_id = $1', [clubId, JSON.stringify(c.venue), JSON.stringify(c.socials)]);
      for (const s of c.staff) {
        await client.query('INSERT INTO club_staff (club_id, name, role) VALUES ($1, $2, $3)', [clubId, s.name, s.role]);
      }
      const { season, ...stats } = c.stats;
      await client.query('INSERT INTO club_season_stats (club_id, season, stats) VALUES ($1, $2, $3::jsonb)', [clubId, season, JSON.stringify(stats)]);
    }
    await client.query(`UPDATE products SET image_url = '/static/product/' || product_id || '.png'`);
    for (const p of demo.products) {
      await client.query(
        'INSERT INTO products (product_id, tenant_id, club_id, category, name, image_url) VALUES ($1, $2, $3, $4, $5, $6)',
        [p.product_id, TENANT, 'club-alpha-1', p.category, p.name, `/static/product/${p.product_id}.png`],
      );
      await client.query(
        `INSERT INTO variants (variant_id, product_id, size, color, price_minor, currency, stock, reserved)
         VALUES ($1, $2, $3, $4, $5, 'PKR', 100, 0)`,
        [p.variant_id, p.product_id, p.size, p.color, p.price_minor],
      );
    }
  });
}

// Fixtures go through the real event service so signatures and sequences are genuine.
async function seedMatches(sport) {
  const scoreKey = Object.keys(sport.scoring_events || {})[0];
  const teams = demo.teams.map((t) => t.team_id);
  const now = Date.now();
  await withTransaction(async (client) => {
    await matchesRepo.insertFixtures(client, {
      sportId: sportsSeed[0].sport_id,
      leagueId: LEAGUE,
      fixtures: demo.fixtures.map((f) => ({ match_id: f.match_id, home: teams[f.home], away: teams[f.away] })),
    });
    for (const f of demo.fixtures) {
      await client.query(
        'UPDATE matches SET scheduled_at = $2, venue_id = $3 WHERE match_id = $1',
        [f.match_id, new Date(now + offsetMs(f)).toISOString(), demo.venues[f.venue].venue_id],
      );
    }
  });
  for (const f of demo.fixtures) {
    if (f.state === 'upcoming') continue;
    const ev = (suffix, type, payload) => eventService.appendEvent({
      event_id: `${f.match_id}-ev-${suffix}`, match_id: f.match_id, type, payload,
    });
    await ev('start', 'match_start', {});
    const [h, a] = f.score;
    for (let i = 0; i < h; i += 1) await ev(`h${i}`, 'score', { team_id: teams[f.home], event_type: scoreKey });
    for (let i = 0; i < a; i += 1) await ev(`a${i}`, 'score', { team_id: teams[f.away], event_type: scoreKey });
    if (f.state === 'completed') await ev('end', 'match_end', {});
  }
}

async function seedRoster(sport) {
  const required = (sport.roster && sport.roster.required_positions) || {};
  const keys = Object.keys(required);
  const positions = [];
  // Each required position gets one more player than its minimum, so a transfer never breaks it.
  for (const k of keys) for (let n = 0; n < required[k] + 1; n += 1) positions.push(k);
  while (positions.length < 8) positions.push(demo.filler_positions[positions.length % demo.filler_positions.length]);
  positions.length = 8;

  const squads = demo.teams.filter((t) => t.squad_id);
  const players = [];
  squads.forEach((sq, si) => {
    for (let i = 0; i < 8; i += 1) {
      const seeded = i === 0 ? `player-alpha-${si + 1}` : null;
      players.push({
        player_id: seeded || `demo-pl-${si + 1}-${i + 1}`,
        name: seeded ? undefined : demo.players[(si * 8 + i) % demo.players.length],
        position: positions[i],
        squad_id: sq.squad_id,
        role: i === 1 ? 'captain' : i < 5 ? 'starter' : 'substitute',
      });
    }
  });
  const { rows } = await pool.query('SELECT player_id, name FROM players');
  const known = new Map(rows.map((r) => [r.player_id, r.name]));
  for (const p of players) p.name = p.name || known.get(p.player_id) || p.player_id;

  await rosterService.setup({
    league: {
      league_id: LEAGUE,
      tenant_id: TENANT,
      sport_id: sportsSeed[0].sport_id,
      transfer_window: { open: '2026-10-01', close: '2026-10-31' },
      max_squad_size: 25,
      required_positions: required,
    },
    squads: squads.map((s) => ({ squad_id: s.squad_id, club_id: s.club_id, tenant_id: TENANT, name: s.name })),
    players,
  });
  for (const p of players) {
    const squad = squads.find((s) => s.squad_id === p.squad_id);
    await pool.query(
      'UPDATE players SET club_id = COALESCE(club_id, $2), tenant_id = COALESCE(tenant_id, $3) WHERE player_id = $1',
      [p.player_id, squad.club_id, TENANT],
    );
  }
}

async function seedPins() {
  await pool.query(
    'INSERT INTO match_pins (match_id, product_id) VALUES ($1, $2) ON CONFLICT DO NOTHING',
    ['demo-m-live', 'demo-prod-cap'],
  );
}

// Never throws: a broken demo seed must not make /reset or boot fail.
async function seedDemoData(seed) {
  try {
    const sport = await sports.get(pool, sportsSeed[0].sport_id);
    await seedStatic(seed);
    await seedMatches(sport);
    await seedRoster(sport);
    await seedPins();
    return true;
  } catch (err) {
    logger.error({ err: err.message }, 'demo data seeding failed');
    return false;
  }
}

module.exports = { seedDemoData };
