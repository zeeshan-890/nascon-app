const { buildReceiptPayload, checkReceiptConsistency } = require('../../../backend/src/domain/store/receipt');
const { generateReceiptPdf, sanitize, receiptLines } = require('../../../backend/src/domain/store/receiptPdf');
const { extractPdfText } = require('../../helpers/pdfText');

const mk = (order_id, customer_id = 'cust-1') => buildReceiptPayload(
  { order_id, customer_id, subtotal_minor: 450000, discount_minor: 0, total_minor: 450000 },
  [{ variant_id: 'var-jersey-001-M', quantity: 1, unit_price_minor: 450000, extra: 'x' }],
  '2026-01-01T10:00:00Z'
);
const text = async (p) => extractPdfText(await generateReceiptPdf(p));

describe('receipt payload', () => {
  test('exact keys and consistency', () => {
    const p = mk('o1');
    expect(Object.keys(p)).toEqual(['order_id', 'customer_id', 'items', 'subtotal_minor', 'discount_minor', 'total_minor', 'currency', 'paid_at']);
    expect(Object.keys(p.items[0])).toEqual(['variant_id', 'quantity', 'unit_price_minor']);
    expect(p.currency).toBe('PKR');
    expect(checkReceiptConsistency(p)).toEqual([]);
  });
  test('inconsistent payloads reported', () => {
    const p = mk('o1');
    expect(checkReceiptConsistency({ ...p, subtotal_minor: 1 })).toHaveLength(2);
    expect(checkReceiptConsistency({ ...p, total_minor: 1 })).toHaveLength(1);
  });
});

describe('receipt pdf', () => {
  test('starts with %PDF and contains order id and total', async () => {
    const buf = await generateReceiptPdf(mk('order-1'));
    expect(buf.subarray(0, 4).toString()).toBe('%PDF');
    const t = await extractPdfText(buf);
    expect(t).toContain('order-1');
    expect(t).toContain('PKR 4500.00');
  });
  test('generation under 100 ms once warm', async () => {
    await generateReceiptPdf(mk('warm'));
    const t0 = Date.now();
    await generateReceiptPdf(mk('o2'));
    expect(Date.now() - t0).toBeLessThan(100);
  });
  test('64-char hyphenated order id stays intact', async () => {
    const id = 'abcdef0-abcdef1-abcdef2-abcdef3-abcdef4-abcdef5-abcdef6-abcdef7z';
    expect(id).toHaveLength(64);
    expect(await text(mk(id))).toContain(id);
  });
  test('405000 prints as PKR 4050.00', async () => {
    const p = { ...mk('o3'), total_minor: 405000, discount_minor: 45000 };
    const t = await text(p);
    expect(t).toContain('Total: PKR 4050.00');
    expect(t).toContain('Discount: PKR 450.00');
  });
  test('urdu, emoji, newline, tab do not crash', async () => {
    const p = mk('o4', 'گول\u{1F600}\nline2\ttab');
    const t = await text(p);
    expect(t).toContain('Customer: ');
    expect(t).toContain('?');
    expect(p.customer_id).toContain('گول');
  });
  test('sanitize keeps latin-1, replaces control chars, truncates customer at 120', () => {
    expect(sanitize('café')).toBe('café');
    expect(sanitize('a\u0000b')).toBe('a?b');
    const lines = receiptLines(mk('o5', 'x'.repeat(300)));
    expect(lines[2]).toBe(`Customer: ${'x'.repeat(120)}`);
  });
});
