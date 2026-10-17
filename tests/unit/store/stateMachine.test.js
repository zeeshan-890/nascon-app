const { canTransition, restockOn, TRANSITIONS } = require('../../../backend/src/domain/store/orderStateMachine');

const ALL = ['pending', 'paid', 'packed', 'shipped', 'delivered', 'cancelled', 'refunded'];
const PAIRS = ALL.flatMap((a) => ALL.map((b) => [a, b]));
const LEGAL = new Set([
  'pending>paid', 'pending>cancelled', 'paid>packed', 'paid>refunded', 'paid>cancelled',
  'packed>shipped', 'packed>refunded', 'shipped>delivered', 'delivered>refunded',
]);
const RESTOCK = new Set(['paid>cancelled', 'paid>refunded', 'packed>refunded']);

describe('order state machine', () => {
  test.each(PAIRS)('%s -> %s', (a, b) => {
    expect(canTransition(a, b)).toBe(LEGAL.has(`${a}>${b}`));
  });
  test('unknown states are illegal', () => {
    expect(canTransition('bogus', 'paid')).toBe(false);
    expect(canTransition('constructor', 'paid')).toBe(false);
    expect(TRANSITIONS.cancelled).toEqual([]);
  });
  test.each(PAIRS)('restockOn %s -> %s', (a, b) => {
    expect(restockOn(a, b)).toBe(RESTOCK.has(`${a}>${b}`));
  });
});
