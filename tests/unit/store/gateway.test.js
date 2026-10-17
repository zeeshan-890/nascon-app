const gw = require('../../../backend/src/services/paymentGateway');

function fakeDb() {
  let n = 0;
  return { query: jest.fn(async () => ({ rows: [{ n: (n += 1) }] })) };
}

describe('mock gateway', () => {
  test('tok_success authorizes and captures with matching numbers', async () => {
    const db = fakeDb();
    const a = await gw.authorize('tok_success', db);
    expect(a).toEqual({ ok: true, authorization_id: 'auth_1' });
    expect(await gw.capture(a.authorization_id, 'tok_success')).toEqual({ ok: true, capture_id: 'cap_1' });
  });
  test('tok_declined declines and consumes no id', async () => {
    const db = fakeDb();
    expect((await gw.authorize('tok_declined', db)).ok).toBe(false);
    expect(db.query).not.toHaveBeenCalled();
    expect((await gw.authorize('tok_success', db)).authorization_id).toBe('auth_1');
  });
  test('tok_capture_fail authorizes, capture fails, void ok', async () => {
    const db = fakeDb();
    const a = await gw.authorize('tok_capture_fail', db);
    expect(a.ok).toBe(true);
    expect((await gw.capture(a.authorization_id, 'tok_capture_fail')).ok).toBe(false);
    expect((await gw.voidAuthorization(a.authorization_id)).ok).toBe(true);
  });
  test('unknown token declines; refund ok', async () => {
    expect((await gw.authorize('nope', fakeDb())).ok).toBe(false);
    expect((await gw.refund('cap_1')).ok).toBe(true);
  });
});
