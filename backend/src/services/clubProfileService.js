const { withTransaction } = require('../db/tx');
const cache = require('../db/cache');
const AppError = require('../http/AppError');
const outbox = require('../repos/outbox');
const { validateClubPatch } = require('../domain/competition/clubProfile');
const read = require('./readModelService');

// Partial update of a club profile; `staff` and `socials` replace the stored value. Returns the GET /clubs/:id payload.
async function patchClub(clubId, body) {
  const { fields, value } = validateClubPatch(body);
  if (fields.length) throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields });
  await withTransaction(async (client) => {
    const { rows } = await client.query('SELECT club_id FROM clubs WHERE club_id = $1 FOR UPDATE', [clubId]);
    if (!rows[0]) throw new AppError(404, 'NOT_FOUND', 'Club not found');
    const sets = [];
    const params = [clubId];
    const set = (column, v, cast = '') => {
      params.push(v);
      sets.push(`${column} = $${params.length}${cast}`);
    };
    if (value.name !== undefined) set('name', value.name);
    if (value.logo_url !== undefined) set('logo_url', value.logo_url);
    if (value.venue !== undefined) set('venue', JSON.stringify(value.venue), '::jsonb');
    if (value.socials !== undefined) set('socials', JSON.stringify(value.socials), '::jsonb');
    if (sets.length) await client.query(`UPDATE clubs SET ${sets.join(', ')} WHERE club_id = $1`, params);
    if (value.staff !== undefined) {
      await client.query('DELETE FROM club_staff WHERE club_id = $1', [clubId]);
      if (value.staff.length) {
        await client.query(
          'INSERT INTO club_staff (club_id, name, role) SELECT $1, s.name, s.role FROM unnest($2::text[], $3::text[]) AS s(name, role)',
          [clubId, value.staff.map((s) => s.name), value.staff.map((s) => s.role)],
        );
      }
    }
    await outbox.enqueue(client, {
      aggregate_type: 'club', aggregate_id: clubId, event_type: 'club.updated',
      payload: { club_id: clubId, fields: Object.keys(value).filter((k) => value[k] !== undefined) },
    });
  });
  await cache.invalidate('clubs-roster');
  return read.clubDetail(clubId);
}

module.exports = { patchClub };
