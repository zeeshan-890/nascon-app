const { describeDb } = require('../helpers/db');
const { api, resetDb, pool } = require('../helpers/app');
const { expectError } = require('../helpers/evtkit');
const { uid, setup, req, transfer, history } = require('../helpers/rosterkit');

afterAll(() => pool.end());

const auditNumber = (id) => Number(id.replace('au-', ''));

describeDb('roster audit ids are gap-free', () => {
  beforeEach(() => resetDb());

  test('a rejected request dated after a loan ended consumes no audit id', async () => {
    const lg = uid('lg');
    await setup(lg);
    const loan = await transfer(req(lg, { player_id: 'df1', type: 'loan', to_squad_id: 'C', effective_date: '2026-01-12', loan_end_date: '2026-01-20' }));
    expect(loan.status).toBe(201);
    expect(loan.body.audit_entry.audit_id).toBe('au-0001');

    // The loan has ended by 2026-06-01, but df1 is not in squad B -> rejected (NOT_IN_SQUAD).
    const rejected = await transfer(req(lg, { player_id: 'df1', from_squad_id: 'B', to_squad_id: 'A', effective_date: '2026-06-01' }));
    expectError(rejected, 409, 'NOT_IN_SQUAD');

    // The next accepted request gets the very next number: au-0002 (no gap, no hidden loan_return).
    const next = await transfer(req(lg, { player_id: 'df2', type: 'release', from_squad_id: 'A', to_squad_id: null, effective_date: '2026-01-14' }));
    expect(next.status).toBe(201);
    expect(next.body.audit_entry.audit_id).toBe('au-0002');
  });

  test('history never lists an entry written by a rejected request', async () => {
    const lg = uid('lg');
    await setup(lg);
    await transfer(req(lg, { player_id: 'df1', type: 'loan', to_squad_id: 'C', effective_date: '2026-01-12', loan_end_date: '2026-01-20' }));
    expectError(await transfer(req(lg, { player_id: 'df1', from_squad_id: 'B', to_squad_id: 'A', effective_date: '2026-06-01' })), 409, 'NOT_IN_SQUAD');
    const h = await history('df1', '2026-01-15');
    expect(h.body.history.map((e) => e.action)).toEqual(['loan']);
  });

  test('parallel history calls write exactly one loan_return and keep ids consecutive', async () => {
    const lg = uid('lg');
    await setup(lg);
    const loan = await transfer(req(lg, { player_id: 'df1', type: 'loan', to_squad_id: 'C', effective_date: '2026-01-12', loan_end_date: '2026-01-20' }));
    expect(loan.status).toBe(201);
    const calls = await Promise.all(Array.from({ length: 12 }, () => history('df1', '2026-03-01')));
    for (const c of calls) expect(c.status).toBe(200);
    const ids = new Set();
    for (const c of calls) {
      const returns = c.body.history.filter((e) => e.action === 'loan_return');
      expect(returns).toHaveLength(1);
      ids.add(returns[0].audit_id);
    }
    expect([...ids]).toEqual(['au-0002']);
    const next = await transfer(req(lg, { player_id: 'df2', from_squad_id: 'A', to_squad_id: 'B', effective_date: '2026-01-15' }));
    expect(next.status).toBe(201);
    expect(auditNumber(next.body.audit_entry.audit_id)).toBe(3);
  });

  test('audit numbering restarts at au-0001 after /reset', async () => {
    const lg = uid('lg');
    await setup(lg);
    expect((await transfer(req(lg))).body.audit_entry.audit_id).toBe('au-0001');
    await resetDb();
    const lg2 = uid('lg');
    await setup(lg2);
    expect((await transfer(req(lg2))).body.audit_entry.audit_id).toBe('au-0001');
  });
});
