'use strict';

const { z } = require('zod');
const { Id, Text, IsoDate, issuesToFields, isPlainObject } = require('../../http/validators');

const CreateSchema = z.object({
  season_id: Id.optional(),
  name: Text(80),
  start_date: IsoDate,
  end_date: IsoDate,
  tenant_id: Id.optional(),
});

const PatchSchema = z.object({
  name: Text(80).optional(),
  start_date: IsoDate.optional(),
  end_date: IsoDate.optional(),
});

function run(schema, body) {
  if (!isPlainObject(body)) return { fields: ['body'], data: null };
  const parsed = schema.safeParse(body);
  return { fields: parsed.success ? [] : issuesToFields(parsed.error), data: parsed.success ? parsed.data : null };
}

// ISO dates compare lexicographically.
const endBeforeStart = (start, end) => typeof start === 'string' && typeof end === 'string' && end < start;

function validateSeasonCreate(body) {
  const { fields, data } = run(CreateSchema, body);
  if (data && endBeforeStart(data.start_date, data.end_date)) fields.push('end_date');
  return fields.length ? { fields, value: null } : { fields: [], value: data };
}

/** `current` is the stored season; the merged dates must still be ordered. */
function validateSeasonPatch(body, current) {
  const { fields, data } = run(PatchSchema, body);
  if (data && !fields.length) {
    if (!Object.values(data).some((v) => v !== undefined)) fields.push('body');
    else if (endBeforeStart(data.start_date || current.start_date, data.end_date || current.end_date)) {
      fields.push(data.end_date !== undefined ? 'end_date' : 'start_date');
    }
  }
  return fields.length ? { fields, value: null } : { fields: [], value: data };
}

module.exports = { validateSeasonCreate, validateSeasonPatch };
