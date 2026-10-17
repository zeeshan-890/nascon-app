const {
  snapshot, squadMembers, squadCounts, dueLoanReturns, addDays, isRealDate, todayUtc,
} = require('../../../backend/src/domain/roster');

const setup = [
  { player_id: 'p1', squad_id: 'A', role: 'captain', position: 'gk' },
  { player_id: 'p2', squad_id: 'A', role: 'starter', position: 'df' },
  { player_id: 'p3', squad_id: null, role: 'starter', position: 'df' },
];
let n = 0;
const e = (action, over) => ({
  audit_id: `au-${(n += 1)}`, action, player_id: 'p2', from_squad_id: 'A', to_squad_id: 'B', effective_date: '2026-01-10',
  loan_end_date: null, loan_audit_id: null, ...over,
});
const at = (entries, d, id = 'p2') => snapshot(setup, entries, d).get(id);

describe('dates', () => {
  test('isRealDate', () => {
    expect(isRealDate('2026-02-28')).toBe(true);
    expect(isRealDate('2028-02-29')).toBe(true);
    expect(isRealDate('2026-02-29')).toBe(false);
    expect(isRealDate('2026-2-1')).toBe(false);
    expect(isRealDate(20260101)).toBe(false);
  });
  test('addDays crosses month and year boundaries', () => {
    expect(addDays('2026-01-31', 1)).toBe('2026-02-01');
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
    expect(addDays('2028-02-28', 1)).toBe('2028-02-29');
  });
  test('todayUtc', () => expect(todayUtc(new Date('2026-05-06T23:59:59Z'))).toBe('2026-05-06'));
});

describe('snapshot', () => {
  test('initial state', () => {
    expect(at([], '2026-01-01')).toMatchObject({ squad_id: 'A', registration_type: 'initial', role: 'starter', parent_squad_id: null });
    expect(at([], '2026-01-01', 'p3').squad_id).toBeNull();
  });

  test('entries after the date are not applied', () => {
    expect(at([e('transfer')], '2026-01-09').squad_id).toBe('A');
    expect(at([e('transfer')], '2026-01-10')).toMatchObject({ squad_id: 'B', role: 'substitute', registration_type: 'transfer', start_date: '2026-01-10' });
  });

  test('captain becomes substitute after a transfer', () => {
    expect(at([e('transfer', { player_id: 'p1' })], '2026-02-01', 'p1')).toMatchObject({ squad_id: 'B', role: 'substitute' });
  });

  test('loan: borrower during, lender after the end date even without a loan_return entry', () => {
    const loan = e('loan', { effective_date: '2026-01-10', loan_end_date: '2026-01-20' });
    expect(at([loan], '2026-01-15')).toMatchObject({ squad_id: 'B', parent_squad_id: 'A', registration_type: 'loan', loan_end_date: '2026-01-20' });
    expect(at([loan], '2026-01-20').squad_id).toBe('B');
    expect(at([loan], '2026-01-21')).toMatchObject({ squad_id: 'A', parent_squad_id: null, registration_type: 'transfer', role: 'substitute' });
  });

  test('explicit loan_return gives the same result', () => {
    const loan = e('loan', { loan_end_date: '2026-01-20' });
    const ret = e('loan_return', { from_squad_id: 'B', to_squad_id: 'A', effective_date: '2026-01-21', loan_audit_id: loan.audit_id });
    expect(at([loan, ret], '2026-01-25')).toMatchObject({ squad_id: 'A', parent_squad_id: null });
    expect(at([loan, ret], '2026-01-20')).toMatchObject({ squad_id: 'B' });
  });

  test('transfer -> loan -> loan end -> transfer chain', () => {
    const t1 = e('transfer', { effective_date: '2026-01-05' });
    const loan = e('loan', { from_squad_id: 'B', to_squad_id: 'C', effective_date: '2026-01-10', loan_end_date: '2026-01-20' });
    const t2 = e('transfer', { from_squad_id: 'B', to_squad_id: 'D', effective_date: '2026-01-25' });
    const all = [t1, loan, t2];
    expect(at(all, '2026-01-07').squad_id).toBe('B');
    expect(at(all, '2026-01-12')).toMatchObject({ squad_id: 'C', parent_squad_id: 'B' });
    expect(at(all, '2026-01-22')).toMatchObject({ squad_id: 'B', parent_squad_id: null });
    expect(at(all, '2026-01-25')).toMatchObject({ squad_id: 'D', registration_type: 'transfer' });
  });

  test('a later entry implicitly ends an elapsed loan first', () => {
    const loan = e('loan', { loan_end_date: '2026-01-12' });
    const rel = e('release', { from_squad_id: 'A', to_squad_id: null, effective_date: '2026-01-15' });
    expect(at([loan, rel], '2026-01-16')).toMatchObject({ squad_id: null, registration_type: 'release', parent_squad_id: null });
  });

  test('out-of-order effective dates apply in audit order', () => {
    const late = e('transfer', { to_squad_id: 'C', effective_date: '2026-03-01' });
    const early = e('transfer', { from_squad_id: 'C', to_squad_id: 'D', effective_date: '2026-02-01' });
    expect(at([late, early], '2026-02-15').squad_id).toBe('D');
    expect(at([late, early], '2026-03-02').squad_id).toBe('D');
  });

  test('entries for unknown players and unknown actions are ignored', () => {
    expect(at([e('transfer', { player_id: 'ghost' }), e('mystery')], '2026-05-01').squad_id).toBe('A');
  });
});

describe('squad helpers', () => {
  test('members and counts', () => {
    const st = snapshot(setup, [e('loan', { loan_end_date: '2026-01-20' })], '2026-01-12');
    expect(squadMembers(st, 'A').map((p) => p.player_id)).toEqual(['p1']);
    expect(squadCounts(st, ['A', 'B', 'Z'])).toEqual({ A: 1, B: 1, Z: 0 });
  });

  test('dueLoanReturns only lists elapsed loans without a return', () => {
    const l1 = e('loan', { loan_end_date: '2026-01-20' });
    const l2 = e('loan', { loan_end_date: '2026-02-20' });
    const l3 = e('loan', { loan_end_date: '2026-01-25' });
    const r3 = e('loan_return', { loan_audit_id: l3.audit_id });
    expect(dueLoanReturns([l1, l2, l3, r3], '2026-01-21')).toEqual([l1]);
    expect(dueLoanReturns([l1, l2, l3, r3], '2026-01-20')).toEqual([]);
  });
});
