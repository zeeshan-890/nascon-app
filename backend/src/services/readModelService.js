const pool = require('../db/pool');
const AppError = require('../http/AppError');
const { cached } = require('../db/cache');
const { project } = require('../domain/scoring/projection');
const { effectivePrice } = require('../domain/store/pricing');
const roster = require('../domain/roster');
const rosterRepo = require('../repos/roster');
const sports = require('../repos/sports');
const standingsService = require('./standingsService');
const scoreService = require('./scoreService');

const ADMIN_STATUSES = new Set(['postponed', 'abandoned', 'cancelled']);

// Effective status: an administrative status wins over the one derived from the event log.
const effectiveStatus = (adminStatus, projected) => (ADMIN_STATUSES.has(adminStatus) ? adminStatus : projected);

const pageOf = (q) => {
  const limit = Math.min(Math.max(parseInt(q.limit, 10) || 500, 1), 1000);
  const offset = Math.max(parseInt(q.offset, 10) || 0, 0);
  return { limit, offset };
};

async function listLeagues(tenantScope, query) {
  const { limit, offset } = pageOf(query);
  const { rows } = await pool.query(
    `SELECT l.league_id, COALESCE(l.name, l.league_id) AS name, l.sport_id,
            COALESCE(s.name, l.config->>'season') AS season, count(*) OVER ()::int AS total
     FROM leagues l LEFT JOIN seasons s ON s.season_id = l.season_id
     WHERE ($1::text IS NULL OR l.tenant_id = $1) ORDER BY l.league_id LIMIT $2 OFFSET $3`,
    [tenantScope, limit, offset],
  );
  return {
    total: rows.length ? rows[0].total : 0,
    items: rows.map(({ total, ...r }) => ({ ...r, season: r.season || null })),
  };
}

async function teamNames(leagueId) {
  const { rows } = await pool.query(
    'SELECT team_id, name FROM league_teams WHERE league_id = $1 ORDER BY position, team_id', [leagueId],
  );
  return new Map(rows.map((r) => [r.team_id, r.name || r.team_id]));
}

async function computeFixtures(leagueId) {
  const names = await teamNames(leagueId);
  const { rows: matches } = await pool.query(
    `SELECT m.match_id, m.sport_id, m.league_id, m.home_team_id, m.away_team_id, m.admin_status,
            m.scheduled_at, v.name AS venue_name
     FROM matches m LEFT JOIN venues v ON v.venue_id = m.venue_id
     WHERE m.league_id = $1 ORDER BY m.scheduled_at NULLS LAST, m.match_id`,
    [leagueId],
  );
  const { rows: eventRows } = await pool.query(
    `SELECT match_id, sequence, event_id, type, payload_canonical, signature, compensates
     FROM match_events WHERE match_id = ANY($1::text[]) ORDER BY match_id, sequence`,
    [matches.map((m) => m.match_id)],
  );
  const byMatch = new Map();
  for (const e of eventRows) {
    if (!byMatch.has(e.match_id)) byMatch.set(e.match_id, []);
    byMatch.get(e.match_id).push(e);
  }
  const sportCache = new Map();
  const out = [];
  for (const m of matches) {
    if (!sportCache.has(m.sport_id)) sportCache.set(m.sport_id, await sports.get(pool, m.sport_id));
    const p = project(sportCache.get(m.sport_id), m, byMatch.get(m.match_id) || []);
    out.push({
      match_id: m.match_id,
      home_team_id: m.home_team_id,
      home_name: names.get(m.home_team_id) || m.home_team_id,
      away_team_id: m.away_team_id,
      away_name: names.get(m.away_team_id) || m.away_team_id,
      status: effectiveStatus(m.admin_status, p.status),
      scheduled_at: m.scheduled_at ? new Date(m.scheduled_at).toISOString().replace(/\.\d{3}Z$/, 'Z') : null,
      venue_name: m.venue_name || null,
      home_score: p.home.score || 0,
      away_score: p.away.score || 0,
      version: p.version,
    });
  }
  return out;
}

const fixtures = (leagueId) => cached(`cache:league:${leagueId}:fixtures`, `league:${leagueId}`, () => computeFixtures(leagueId));

const standings = (leagueId) => cached(`cache:league:${leagueId}:standings`, `league:${leagueId}`, async () => {
  const body = await standingsService.getStandings(leagueId);
  const names = await teamNames(leagueId);
  return { ...body, team_names: Object.fromEntries(names) };
});

async function pinnedProducts(matchId) {
  const { rows } = await pool.query(
    `SELECT p.product_id, p.name, p.image_url,
            (SELECT json_agg(json_build_object('variant_id', v.variant_id, 'price_minor', v.price_minor)) FROM variants v
             WHERE v.product_id = p.product_id) AS variants
     FROM match_pins mp JOIN products p ON p.product_id = mp.product_id
     WHERE mp.match_id = $1 ORDER BY p.product_id`,
    [matchId],
  );
  const ids = rows.flatMap((r) => (r.variants || []).map((v) => v.variant_id));
  const schedules = ids.length ? (await pool.query(
    'SELECT variant_id, price_minor, effective_from FROM price_schedules WHERE variant_id = ANY($1::text[])', [ids])).rows : [];
  const now = new Date();
  return rows.map((r) => {
    const prices = (r.variants || []).map((v) => effectivePrice(v, schedules.filter((s) => s.variant_id === v.variant_id), now));
    return {
      product_id: r.product_id,
      name: r.name,
      price_minor: prices.length ? Math.min(...prices) : 0,
      image_url: r.image_url || `/static/product/${r.product_id}.png`,
    };
  });
}

async function matchDetail(matchId) {
  const score = await scoreService.getScore(matchId);
  const { rows } = await pool.query(
    `SELECT m.admin_status, m.scheduled_at, v.name AS venue_name FROM matches m
     LEFT JOIN venues v ON v.venue_id = m.venue_id WHERE m.match_id = $1`,
    [matchId],
  );
  const extra = rows[0] || {};
  return {
    ...score,
    status: effectiveStatus(extra.admin_status, score.status),
    scheduled_at: extra.scheduled_at ? new Date(extra.scheduled_at).toISOString().replace(/\.\d{3}Z$/, 'Z') : null,
    venue_name: extra.venue_name || null,
    pinned_products: await pinnedProducts(matchId),
  };
}

async function getSport(sportId) {
  const config = await sports.get(pool, sportId);
  if (!config) throw new AppError(404, 'SPORT_NOT_FOUND', 'Sport not found');
  return config;
}

async function listClubs(tenantScope, query) {
  const { limit, offset } = pageOf(query);
  const { rows } = await pool.query(
    `SELECT club_id, name, COALESCE(logo_url, '/static/logo/' || club_id || '.png') AS logo_url, count(*) OVER ()::int AS total
     FROM clubs WHERE ($1::text IS NULL OR tenant_id = $1) ORDER BY club_id LIMIT $2 OFFSET $3`,
    [tenantScope, limit, offset],
  );
  return { total: rows.length ? rows[0].total : 0, items: rows.map(({ total, ...r }) => r) };
}

async function clubRoster(clubId) {
  const { rows: squads } = await pool.query(
    'SELECT league_id, squad_id, name, club_id FROM roster_squads WHERE club_id = $1 ORDER BY league_id, squad_id', [clubId],
  );
  const today = roster.todayUtc();
  const list = [];
  const squadSet = new Map();
  for (const leagueId of [...new Set(squads.map((s) => s.league_id))]) {
    const mine = new Set(squads.filter((s) => s.league_id === leagueId).map((s) => s.squad_id));
    for (const s of squads.filter((x) => x.league_id === leagueId)) if (!squadSet.has(s.squad_id)) squadSet.set(s.squad_id, s);
    const [initial, audit] = await Promise.all([rosterRepo.listInitial(pool, leagueId), rosterRepo.listAudit(pool, leagueId)]);
    const state = roster.snapshot(initial, audit, today);
    for (const s of state.values()) {
      if (s.squad_id && mine.has(s.squad_id)) {
        list.push({
          player_id: s.player_id,
          position: s.position || null,
          role: s.role,
          squad_id: s.squad_id,
          league_id: leagueId,
          on_loan: s.registration_type === 'loan',
          audit_version: audit.filter((e) => e.player_id === s.player_id).length,
        });
      }
    }
  }
  const names = list.length ? new Map((await pool.query(
    'SELECT player_id, name FROM players WHERE player_id = ANY($1::text[])', [list.map((p) => p.player_id)])).rows.map((r) => [r.player_id, r.name])) : new Map();
  return {
    roster: list.map((p) => ({
      player_id: p.player_id,
      name: names.get(p.player_id) || p.player_id,
      position: p.position,
      role: p.role,
      squad_id: p.squad_id,
      league_id: p.league_id,
      on_loan: p.on_loan,
      audit_version: p.audit_version,
    })),
    squads: [...squadSet.values()].map((s) => ({ squad_id: s.squad_id, name: s.name, club_id: s.club_id })),
  };
}

async function computeClub(clubId) {
  const { rows } = await pool.query(
    'SELECT club_id, name, tenant_id, logo_url, venue, socials FROM clubs WHERE club_id = $1', [clubId],
  );
  const c = rows[0];
  const [{ rows: staff }, { rows: stats }, r] = await Promise.all([
    pool.query('SELECT name, role FROM club_staff WHERE club_id = $1 ORDER BY name', [clubId]),
    pool.query('SELECT season, stats FROM club_season_stats WHERE club_id = $1 ORDER BY season DESC', [clubId]),
    clubRoster(clubId),
  ]);
  const venue = c.venue || {};
  return {
    club_id: c.club_id,
    name: c.name,
    tenant_id: c.tenant_id,
    logo_url: c.logo_url || `/static/logo/${c.club_id}.png`,
    venue: { name: venue.name || null, lat: venue.lat === undefined ? null : venue.lat, lng: venue.lng === undefined ? null : venue.lng },
    socials: c.socials || {},
    staff,
    stats: stats.map((s) => ({
      season: s.season,
      played: 0, won: 0, drawn: 0, lost: 0, scored: 0, conceded: 0,
      ...s.stats,
    })),
    roster: r.roster,
    squads: r.squads,
  };
}

const clubDetail = (clubId) => cached(`cache:club:${clubId}`, 'clubs-roster', () => computeClub(clubId));

async function computeProducts(tenantScope) {
  const { rows: products } = await pool.query(
    `SELECT product_id, name, category, image_url FROM products
     WHERE ($1::text IS NULL OR tenant_id = $1) ORDER BY product_id`,
    [tenantScope],
  );
  const ids = products.map((p) => p.product_id);
  const { rows: variants } = await pool.query(
    `SELECT variant_id, product_id, size, color, price_minor, stock FROM variants
     WHERE product_id = ANY($1::text[]) ORDER BY variant_id`,
    [ids],
  );
  const { rows: schedules } = await pool.query(
    'SELECT variant_id, price_minor, effective_from FROM price_schedules WHERE variant_id = ANY($1::text[])',
    [variants.map((v) => v.variant_id)],
  );
  const now = new Date();
  return products.map((p) => ({
    product_id: p.product_id,
    name: p.name,
    category: p.category || null,
    image_url: p.image_url || `/static/product/${p.product_id}.png`,
    variants: variants.filter((v) => v.product_id === p.product_id).map((v) => ({
      variant_id: v.variant_id,
      size: v.size || null,
      color: v.color || null,
      price_minor: effectivePrice(v, schedules.filter((s) => s.variant_id === v.variant_id), now),
      stock: v.stock,
    })),
  }));
}

const products = (tenantScope) => cached(`cache:products:${tenantScope || 'all'}`, 'products', () => computeProducts(tenantScope), 30);

module.exports = {
  effectiveStatus, pageOf, listLeagues, fixtures, standings, matchDetail, getSport, listClubs, clubDetail, products,
};
