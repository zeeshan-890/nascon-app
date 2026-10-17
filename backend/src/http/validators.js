const { z } = require('zod');
const AppError = require('./AppError');

const isPlainObject = (v) => typeof v === 'object' && v !== null && !Array.isArray(v);

const Id = z.string().regex(/^[A-Za-z0-9_.:-]{1,64}$/);
const SportId = z.string().regex(/^[a-z0-9_-]{2,40}$/);
const EventKey = z.string().regex(/^[a-z0-9_]{1,40}$/);
const Int = z.number().refine((n) => Number.isInteger(n) && Number.isSafeInteger(n));
const PosInt = Int.refine((n) => n >= 1);
const NonNegInt = Int.refine((n) => n >= 0);
const Bool = z.boolean();

function validDate(y, m, d) {
  const dt = new Date(Date.UTC(y, m - 1, d));
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === m - 1 && dt.getUTCDate() === d;
}

const IsoDate = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}$/)
  .refine((s) => {
    const [y, m, d] = s.split('-').map(Number);
    return validDate(y, m, d);
  });

const IsoTimestamp = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/)
  .refine((s) => {
    const [y, m, d] = s.slice(0, 10).split('-').map(Number);
    const hh = Number(s.slice(11, 13));
    const mm = Number(s.slice(14, 16));
    const ss = Number(s.slice(17, 19));
    return validDate(y, m, d) && hh <= 23 && mm <= 59 && ss <= 59;
  });

const Text = (max) => z.string().min(1).max(max);
const PlainObject = z.custom(isPlainObject);

function issuesToFields(zodError, prefix = '') {
  const out = [];
  for (const issue of zodError.issues) {
    const parts = [];
    if (prefix) parts.push(prefix);
    parts.push(...issue.path.map(String));
    const p = parts.join('.');
    const f = p === '' ? 'body' : p;
    if (!out.includes(f)) out.push(f);
  }
  return out;
}

function validationError(fields) {
  return new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields });
}

function parseOr400(schema, value) {
  const r = schema.safeParse(value);
  if (r.success) return r.data;
  throw validationError(issuesToFields(r.error));
}

function requireObjectBody(body) {
  if (!isPlainObject(body)) throw validationError(['body']);
  return body;
}

const nowIso = () => new Date().toISOString().replace(/\.\d{3}Z$/, 'Z');

module.exports = {
  Id, SportId, EventKey, Int, PosInt, NonNegInt, Bool, IsoDate, IsoTimestamp,
  Text, PlainObject, isPlainObject, issuesToFields, parseOr400, requireObjectBody, nowIso,
};
