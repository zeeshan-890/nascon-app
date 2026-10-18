const { validateSeasonCreate, validateSeasonPatch } = require('../../../backend/src/domain/competition/seasons');
const { validateClubPatch, STAFF_ROLES } = require('../../../backend/src/domain/competition/clubProfile');

describe('seasons', () => {
  const ok = { name: 'S', start_date: '2026-08-01', end_date: '2027-05-31' };

  test('create', () => {
    expect(validateSeasonCreate(ok)).toEqual({ fields: [], value: ok });
    expect(validateSeasonCreate({ ...ok, season_id: 'x1', tenant_id: 't' }).value).toMatchObject({ season_id: 'x1', tenant_id: 't' });
    expect(validateSeasonCreate({ ...ok, end_date: '2026-08-01' }).fields).toEqual([]); // one-day season
    expect(validateSeasonCreate({ ...ok, end_date: '2026-07-31' }).fields).toEqual(['end_date']);
    expect(validateSeasonCreate({ name: '', start_date: '2026-02-30', end_date: 3 }).fields.sort()).toEqual(['end_date', 'name', 'start_date']);
    expect(validateSeasonCreate(null).fields).toEqual(['body']);
    expect(validateSeasonCreate([ok]).fields).toEqual(['body']);
  });

  test('patch merges dates with the stored season', () => {
    const cur = { start_date: '2026-08-01', end_date: '2027-05-31' };
    expect(validateSeasonPatch({ name: 'N' }, cur).value).toEqual({ name: 'N' });
    expect(validateSeasonPatch({}, cur).fields).toEqual(['body']);
    expect(validateSeasonPatch({ end_date: '2026-01-01' }, cur).fields).toEqual(['end_date']);
    expect(validateSeasonPatch({ start_date: '2028-01-01' }, cur).fields).toEqual(['start_date']);
    expect(validateSeasonPatch({ start_date: '2026-09-01', end_date: '2026-10-01' }, cur).fields).toEqual([]);
    expect(validateSeasonPatch({ name: '' }, cur).fields).toEqual(['name']);
    expect(validateSeasonPatch(5, cur).fields).toEqual(['body']);
  });
});

describe('club profile', () => {
  test('accepts every field', () => {
    const body = {
      name: 'C', logo_url: 'https://x.test/a.png', venue: { name: 'V', lat: -12.5, lng: 180 },
      socials: { ig: '@c' }, staff: STAFF_ROLES.map((role, i) => ({ name: `n${i}`, role })),
    };
    expect(validateClubPatch(body)).toEqual({ fields: [], value: body });
    expect(validateClubPatch({ logo_url: '/static/logo/a.png' }).fields).toEqual([]);
  });

  test('rejections', () => {
    expect(validateClubPatch(null).fields).toEqual(['body']);
    expect(validateClubPatch({}).fields).toEqual(['body']);
    expect(validateClubPatch({ logo_url: 'javascript:1' }).fields).toEqual(['logo_url']);
    expect(validateClubPatch({ venue: { name: 'V', lat: 91, lng: 0 } }).fields).toEqual(['venue.lat']);
    expect(validateClubPatch({ venue: { name: 'V', lat: 0, lng: -181 } }).fields).toEqual(['venue.lng']);
    expect(validateClubPatch({ staff: [{ name: 'a', role: 'owner' }] }).fields).toEqual(['staff.0.role']);
    expect(validateClubPatch({ staff: [{ name: 'a', role: 'coach' }, { name: 'a', role: 'coach' }] }).fields).toEqual(['staff.1']);
    expect(validateClubPatch({ staff: [{ name: 'a', role: 'coach' }, { name: 'a', role: 'physio' }] }).fields).toEqual([]);
    expect(validateClubPatch({ socials: { a: 1 } }).fields).toEqual(['socials.a']);
    const many = Object.fromEntries(Array.from({ length: 21 }, (_, i) => [`k${i}`, 'v']));
    expect(validateClubPatch({ socials: many }).fields).toEqual(['socials']);
  });
});
