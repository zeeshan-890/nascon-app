'use strict';

const { z } = require('zod');
const { Text, issuesToFields, isPlainObject } = require('../../http/validators');

const STAFF_ROLES = ['coach', 'manager', 'physio'];
const Logo = z.string().min(1).max(500).regex(/^(https?:\/\/\S+|\/\S*)$/);
const Venue = z.object({
  name: Text(120),
  lat: z.number().min(-90).max(90),
  lng: z.number().min(-180).max(180),
});
const Socials = z.record(z.string().min(1).max(40), z.string().min(1).max(200));
const Staff = z.array(z.object({ name: Text(80), role: z.enum(STAFF_ROLES) })).max(50);

const PatchSchema = z.object({
  name: Text(80).optional(),
  logo_url: Logo.optional(),
  venue: Venue.optional(),
  socials: Socials.optional(),
  staff: Staff.optional(),
});

/** Validates PATCH /clubs/:id. Returns { fields, value }. */
function validateClubPatch(body) {
  if (!isPlainObject(body)) return { fields: ['body'], value: null };
  const parsed = PatchSchema.safeParse(body);
  const fields = parsed.success ? [] : issuesToFields(parsed.error);
  if (parsed.success) {
    const d = parsed.data;
    if (!Object.values(d).some((v) => v !== undefined)) fields.push('body');
    if (d.socials && Object.keys(d.socials).length > 20) fields.push('socials');
    if (d.staff) {
      const seen = new Set();
      d.staff.forEach((s, i) => {
        const key = `${s.name}|${s.role}`;
        if (seen.has(key)) fields.push(`staff.${i}`);
        seen.add(key);
      });
    }
  }
  return fields.length ? { fields, value: null } : { fields: [], value: parsed.data };
}

module.exports = { STAFF_ROLES, validateClubPatch };
