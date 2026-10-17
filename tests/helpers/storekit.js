const { api } = require('./app');

let counter = 0;
const oid = (p = 'ord') => `${p}-${Date.now().toString(36)}-${(counter += 1)}`;

const M = 'var-jersey-001-M';
const L = 'var-jersey-001-L';

function body(over = {}) {
  return {
    order_id: oid(),
    customer_id: 'cust-1',
    items: [{ variant_id: M, quantity: 1 }],
    discount_code: null,
    payment: { token: 'tok_success' },
    ...over,
  };
}

async function checkout(b) {
  return (await api()).post('/audit/v1/checkout').send(b);
}

async function inventory(variantId) {
  return (await api()).get(`/audit/v1/inventory/${variantId}`);
}

function expectError(res, status, code) {
  expect(res.status).toBe(status);
  expect(res.body.error.code).toBe(code);
  expect(typeof res.body.error.message).toBe('string');
  expect(res.body.error.details).toEqual(expect.any(Object));
}

const count = (rs, status) => rs.filter((r) => r.status === status).length;

module.exports = { oid, body, checkout, inventory, expectError, count, M, L };
