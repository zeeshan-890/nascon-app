const { effectivePrice, computeTotals, formatPkr } = require('../../../backend/src/domain/store/pricing');

describe('pricing', () => {
  test('discount floors on odd subtotals', () => {
    const t = computeTotals([{ quantity: 1, unit_price_minor: 999 }], 10);
    expect(t).toEqual({ subtotal_minor: 999, discount_minor: 99, total_minor: 900 });
    expect(computeTotals([{ quantity: 3, unit_price_minor: 333 }], 15).discount_minor).toBe(149);
  });
  test('WELCOME10 on 450000 and quantity 3', () => {
    expect(computeTotals([{ quantity: 1, unit_price_minor: 450000 }], 10)).toEqual({ subtotal_minor: 450000, discount_minor: 45000, total_minor: 405000 });
    expect(computeTotals([{ quantity: 3, unit_price_minor: 450000 }], 10)).toEqual({ subtotal_minor: 1350000, discount_minor: 135000, total_minor: 1215000 });
  });
  test('no discount', () => {
    expect(computeTotals([{ quantity: 2, unit_price_minor: 5 }], 0).total_minor).toBe(10);
    expect(computeTotals([{ quantity: 2, unit_price_minor: 5 }]).discount_minor).toBe(0);
  });
  test('formatPkr', () => {
    expect(formatPkr(450000)).toBe('PKR 4500.00');
    expect(formatPkr(405000)).toBe('PKR 4050.00');
    expect(formatPkr(5)).toBe('PKR 0.05');
    expect(formatPkr(0)).toBe('PKR 0.00');
  });
  test('scheduled price: latest effective schedule wins, future ignored', () => {
    const v = { price_minor: 100 };
    const now = new Date('2026-06-01T00:00:00Z');
    const s = [
      { price_minor: 200, effective_from: '2026-01-01T00:00:00Z' },
      { price_minor: 300, effective_from: new Date('2026-05-01T00:00:00Z') },
      { price_minor: 999, effective_from: '2027-01-01T00:00:00Z' },
    ];
    expect(effectivePrice(v, s, now)).toBe(300);
    expect(effectivePrice(v, [s[2]], now)).toBe(100);
    expect(effectivePrice(v, undefined, now)).toBe(100);
  });
});
