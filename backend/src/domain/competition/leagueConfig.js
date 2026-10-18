'use strict';

const { z } = require('zod');
const { Id, Int, PosInt, NonNegInt, IsoDate, Text, issuesToFields, isPlainObject } = require('../../http/validators');
const { TIE_BREAK_TOKENS, normaliseTieBreak } = require('../scoring/sportSchema');

const KINDS = ['league', 'division', 'knockout', 'hybrid'];
const MAX_TEAMS = 128;
const CONFIG_KEYS = [
  'points', 'tie_break_order', 'qualification_spots', 'lots_seed', 'transfer_window', 'max_squad_size',
  'required_positions', 'promotion', 'relegation', 'hybrid',
];

const Points = z.object({ win: Int.optional(), draw: Int.optional(), loss: Int.optional() });
const PromoRel = z.object({ count: PosInt, to: Id });
const TransferWindow = z.object({ open: IsoDate, close: IsoDate });
const Hybrid = z.object({ groups: PosInt, advance_per_group: PosInt });
const RequiredPositions = z.record(z.string().min(1).max(40), NonNegInt);

const ConfigShape = {
  points: Points,
  tie_break_order: z.array(z.enum(TIE_BREAK_TOKENS)),
  qualification_spots: PosInt,
  lots_seed: Int,
  transfer_window: TransferWindow,
  max_squad_size: PosInt,
  required_positions: RequiredPositions,
  promotion: PromoRel,
  relegation: PromoRel,
  hybrid: Hybrid,
};

const withOptional = (nullableKeys) => Object.fromEntries(
  Object.entries(ConfigShape).map(([k, v]) => [k, nullableKeys.includes(k) ? v.nullable().optional() : v.optional()]),
);

const CreateSchema = z.object({
  league_id: Id,
  sport_id: Id,
  name: Text(80),
  kind: z.enum(KINDS),
  season_id: Id.optional(),
  parent_league_id: Id.optional(),
  tenant_id: Id.optional(),
  teams: z.array(Id).min(2).max(MAX_TEAMS),
  ...withOptional([]),
});

const PatchSchema = z.object({
  name: Text(80).optional(),
  season_id: Id.nullable().optional(),
  ...withOptional(['transfer_window', 'max_squad_size', 'promotion', 'relegation']),
});

const dupes = (arr) => new Set(arr).size !== arr.length;

// Cross-field rules on a merged config. Returns the offending field names.
function configErrors(config, { kind, teamCount, leagueId }) {
  const bad = [];
  if (Array.isArray(config.tie_break_order) && dupes(config.tie_break_order)) bad.push('tie_break_order');
  if (Number.isInteger(config.qualification_spots) && config.qualification_spots > teamCount) bad.push('qualification_spots');
  const tw = config.transfer_window;
  if (tw && tw.close < tw.open) bad.push('transfer_window.close');
  for (const key of ['promotion', 'relegation']) {
    if (config[key] && leagueId && config[key].to === leagueId) bad.push(`${key}.to`);
  }
  const p = config.promotion ? config.promotion.count : 0;
  const r = config.relegation ? config.relegation.count : 0;
  if (p > teamCount) bad.push('promotion.count');
  else if (r > teamCount) bad.push('relegation.count');
  else if (p + r > teamCount) bad.push('relegation.count');
  if (kind === 'hybrid') {
    const h = config.hybrid;
    if (!h) bad.push('hybrid');
    else {
      const smallest = Math.floor(teamCount / h.groups);
      if (smallest < 2) bad.push('hybrid.groups');
      else if (h.advance_per_group > smallest || h.groups * h.advance_per_group < 2) bad.push('hybrid.advance_per_group');
    }
  } else if (config.hybrid) {
    bad.push('hybrid');
  }
  return bad;
}

function collect(schema, body) {
  if (!isPlainObject(body)) return { fields: ['body'], data: null };
  const parsed = schema.safeParse(body);
  return { fields: parsed.success ? [] : issuesToFields(parsed.error), data: parsed.success ? parsed.data : null };
}

const merge = (fields, extra) => {
  for (const f of extra) if (!fields.includes(f)) fields.push(f);
  return fields;
};

// Applies defaults to the validated config keys. `defaults`: { tieBreak, lotsSeed } from the sport and settings.
function buildConfig(data, defaults) {
  const config = {};
  for (const k of CONFIG_KEYS) if (data[k] !== undefined) config[k] = data[k];
  config.points = { win: 3, draw: 1, loss: 0, ...(data.points || {}) };
  config.tie_break_order = normaliseTieBreak(data.tie_break_order || defaults.tieBreak);
  config.qualification_spots = data.qualification_spots !== undefined ? data.qualification_spots : 1;
  config.lots_seed = data.lots_seed !== undefined ? data.lots_seed : defaults.lotsSeed;
  return config;
}

/** Validates a POST /leagues body. Returns { fields, value } where value has the normalised config. */
function validateLeagueCreate(body, defaults) {
  const { fields, data } = collect(CreateSchema, body);
  const raw = isPlainObject(body) ? body : {};
  const teams = Array.isArray(raw.teams) ? raw.teams.filter((t) => typeof t === 'string') : [];
  const extra = [];
  if (dupes(teams)) extra.push('teams');
  if (Array.isArray(raw.tie_break_order) && dupes(raw.tie_break_order)) extra.push('tie_break_order');
  if (typeof raw.parent_league_id === 'string' && raw.parent_league_id === raw.league_id) extra.push('parent_league_id');
  merge(fields, extra);
  if (fields.length || !data) return { fields, value: null };
  const config = buildConfig(data, defaults);
  merge(fields, configErrors(config, { kind: data.kind, teamCount: data.teams.length, leagueId: data.league_id }));
  if (fields.length) return { fields, value: null };
  return {
    fields: [],
    value: {
      league_id: data.league_id,
      sport_id: data.sport_id,
      name: data.name,
      kind: data.kind,
      season_id: data.season_id || null,
      parent_league_id: data.parent_league_id || null,
      tenant_id: data.tenant_id || null,
      teams: data.teams,
      config,
    },
  };
}

/**
 * Validates a PATCH body against the current league ({ league_id, kind, name, season_id, config, teamCount }).
 * Returns { fields, value: { name, season_id, config, seasonChanged, hybridChanged } }.
 */
function validateLeaguePatch(body, current) {
  const { fields, data } = collect(PatchSchema, body);
  if (data && !Object.values(data).some((v) => v !== undefined)) fields.push('body');
  if (isPlainObject(body) && Array.isArray(body.tie_break_order) && dupes(body.tie_break_order)) merge(fields, ['tie_break_order']);
  if (fields.length || !data) return { fields, value: null };

  const config = { ...current.config };
  for (const k of CONFIG_KEYS) {
    if (data[k] === undefined) continue;
    if (data[k] === null) delete config[k];
    else if (k === 'points') config.points = { ...(current.config.points || {}), ...data.points };
    else if (k === 'tie_break_order') config.tie_break_order = normaliseTieBreak(data.tie_break_order);
    else config[k] = data[k];
  }
  // Only the keys being patched are judged: an older inconsistency elsewhere must not block an unrelated edit.
  const touched = new Set(CONFIG_KEYS.filter((k) => data[k] !== undefined));
  if (touched.has('promotion') || touched.has('relegation')) {
    touched.add('promotion');
    touched.add('relegation');
  }
  const found = configErrors(config, { kind: current.kind, teamCount: current.teamCount, leagueId: current.league_id });
  merge(fields, found.filter((f) => touched.has(f.split('.')[0])));
  if (fields.length) return { fields, value: null };
  return {
    fields: [],
    value: {
      name: data.name !== undefined ? data.name : current.name,
      season_id: data.season_id !== undefined ? data.season_id : current.season_id,
      config,
      seasonChanged: data.season_id !== undefined && data.season_id !== current.season_id,
      hybridChanged: data.hybrid !== undefined,
    },
  };
}

module.exports = { KINDS, CONFIG_KEYS, MAX_TEAMS, validateLeagueCreate, validateLeaguePatch, configErrors };
