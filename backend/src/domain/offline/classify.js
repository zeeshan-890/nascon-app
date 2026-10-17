const { z } = require('zod');
const { Id, Int, IsoTimestamp, isPlainObject, issuesToFields } = require('../../http/validators');

const TYPES = ['score_event', 'roster_transfer'];
const MAX_ACTIONS = 100;

const Action = z.object({
  action_id: Id,
  type: z.enum(TYPES),
  queued_at: IsoTimestamp,
  base_version: Int.refine((n) => n >= 0),
  payload: z.custom(isPlainObject, 'must be an object'),
});

/** Returns the list of offending field paths of the envelope (empty when valid). */
function envelopeFields(body) {
  if (!isPlainObject(body)) return ['body'];
  const fields = [];
  if (!Id.safeParse(body.device_id).success) fields.push('device_id');
  if (!Array.isArray(body.actions) || body.actions.length < 1 || body.actions.length > MAX_ACTIONS) {
    fields.push('actions');
    return fields;
  }
  body.actions.forEach((a, i) => {
    const r = Action.safeParse(a);
    if (r.success) return;
    const bad = isPlainObject(a) ? issuesToFields(r.error, `actions.${i}`) : [`actions.${i}`];
    for (const f of bad) if (!fields.includes(f)) fields.push(f);
  });
  return fields;
}

/** Stable ascending sort by queued_at; ties keep array order. Returns [{action, index, order}]. */
function sortActions(actions) {
  return actions
    .map((action, index) => ({ action, index }))
    .sort((a, b) => {
      if (a.action.queued_at !== b.action.queued_at) return a.action.queued_at < b.action.queued_at ? -1 : 1;
      return a.index - b.index;
    })
    .map((x, i) => ({ ...x, order: i + 1 }));
}

/** 'appended' | 'rebased_append', or null when base_version is ahead of the server. */
function resolutionFor(baseVersion, currentVersion) {
  if (baseVersion > currentVersion) return null;
  return baseVersion === currentVersion ? 'appended' : 'rebased_append';
}

function summarize(results) {
  const summary = { applied: 0, rejected: 0, duplicate: 0 };
  for (const r of results) summary[r.status] += 1;
  return summary;
}

module.exports = { TYPES, MAX_ACTIONS, envelopeFields, sortActions, resolutionFor, summarize };
