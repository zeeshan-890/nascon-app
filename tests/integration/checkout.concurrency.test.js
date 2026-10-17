const { describeDb } = require('../helpers/db');
const { resetDb, pool } = require('../helpers/app');
const { oid, body, checkout, inventory, expectError, count, M, L } = require('../helpers/storekit');

afterAll(() => pool.end());

const stockOf = async (v) => (await inventory(v)).body;

describeDb('checkout: concurrency', () => {
  test('burst: 20 parallel buyers on stock 1 -> one 201, nineteen 409 (x5)', async () => {
    for (let round = 0; round < 5; round += 1) {
      await resetDb({ stock: { [M]: 1 } });
      const t0 = Date.now();
      const rs = await Promise.all(Array.from({ length: 20 }, () => checkout(body())));
      expect(Date.now() - t0).toBeLessThan(10000);
      expect(count(rs, 201)).toBe(1);
      expect(count(rs, 409)).toBe(19);
      for (const r of rs.filter((x) => x.status === 409)) expectError(r, 409, 'OUT_OF_STOCK');
      expect(await stockOf(M)).toEqual({ variant_id: M, stock: 0, reserved: 0 });
    }
  });

  test('stock 5 with 20 buyers of qty 1 -> exactly five 201', async () => {
    await resetDb({ stock: { [M]: 5 } });
    const rs = await Promise.all(Array.from({ length: 20 }, () => checkout(body())));
    expect(count(rs, 201)).toBe(5);
    expect(count(rs, 409)).toBe(15);
    expect(await stockOf(M)).toEqual({ variant_id: M, stock: 0, reserved: 0 });
  });

  test('mixed declined/success: stock never negative, reserved 0', async () => {
    await resetDb({ stock: { [M]: 3 } });
    const mk = (token) => checkout(body({ payment: { token } }));
    const rs = await Promise.all([
      ...Array.from({ length: 10 }, () => mk('tok_declined')),
      ...Array.from({ length: 10 }, () => mk('tok_success')),
    ]);
    const ok = count(rs, 201);
    expect(ok).toBeLessThanOrEqual(3);
    expect(ok).toBeGreaterThan(0);
    for (const r of rs) expect([201, 402, 409]).toContain(r.status);
    const inv = await stockOf(M);
    expect(inv.stock).toBe(3 - ok);
    expect(inv.reserved).toBe(0);
  });

  test('multi-item atomicity: A in stock, B out of stock -> 409 naming B, A unchanged', async () => {
    await resetDb({ stock: { [M]: 0, [L]: 5 } });
    const r = await checkout(body({ items: [{ variant_id: L, quantity: 1 }, { variant_id: M, quantity: 1 }] }));
    expectError(r, 409, 'OUT_OF_STOCK');
    expect(r.body.error.details.variant_id).toBe(M);
    expect(await stockOf(L)).toEqual({ variant_id: L, stock: 5, reserved: 0 });
    // reversed order: still the item that lacks stock
    const r2 = await checkout(body({ items: [{ variant_id: M, quantity: 1 }, { variant_id: L, quantity: 1 }] }));
    expectError(r2, 409, 'OUT_OF_STOCK');
    expect(r2.body.error.details.variant_id).toBe(M);
    expect(await stockOf(L)).toEqual({ variant_id: L, stock: 5, reserved: 0 });
  });

  test('reports the first lacking item in request order when both lack stock', async () => {
    await resetDb({ stock: { [M]: 0, [L]: 0 } });
    const r = await checkout(body({ items: [{ variant_id: L, quantity: 1 }, { variant_id: M, quantity: 1 }] }));
    expect(r.body.error.details.variant_id).toBe(L);
  });

  test('opposite request order on the same two variants: no deadlock', async () => {
    await resetDb({ stock: { [M]: 20, [L]: 20 } });
    const ab = [{ variant_id: M, quantity: 1 }, { variant_id: L, quantity: 1 }];
    const ba = [...ab].reverse();
    const rs = await Promise.all(
      Array.from({ length: 20 }, (_, i) => checkout(body({ items: i % 2 ? ba : ab })))
    );
    expect(count(rs, 201)).toBe(20);
    expect(await stockOf(M)).toEqual({ variant_id: M, stock: 0, reserved: 0 });
    expect(await stockOf(L)).toEqual({ variant_id: L, stock: 0, reserved: 0 });
  });

  test('same order fired 5 times in parallel -> one 201, four 200, one deduction', async () => {
    await resetDb({ stock: { [M]: 3 } });
    const b = body();
    const rs = await Promise.all(Array.from({ length: 5 }, () => checkout(b)));
    expect(count(rs, 201)).toBe(1);
    expect(count(rs, 200)).toBe(4);
    expect(new Set(rs.map((r) => JSON.stringify(r.body))).size).toBe(1);
    expect(await stockOf(M)).toEqual({ variant_id: M, stock: 2, reserved: 0 });
  });
});
