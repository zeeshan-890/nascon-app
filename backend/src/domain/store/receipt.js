function buildReceiptPayload(order, items, paidAt) {
  return {
    order_id: order.order_id,
    customer_id: order.customer_id,
    items: items.map((i) => ({ variant_id: i.variant_id, quantity: i.quantity, unit_price_minor: i.unit_price_minor })),
    subtotal_minor: order.subtotal_minor,
    discount_minor: order.discount_minor,
    total_minor: order.total_minor,
    currency: 'PKR',
    paid_at: paidAt,
  };
}

// Returns a list of problems; empty means the payload is internally consistent.
function checkReceiptConsistency(payload) {
  const problems = [];
  const sum = payload.items.reduce((s, i) => s + i.quantity * i.unit_price_minor, 0);
  if (sum !== payload.subtotal_minor) problems.push('items do not add up to subtotal');
  if (payload.subtotal_minor - payload.discount_minor !== payload.total_minor) problems.push('subtotal - discount != total');
  return problems;
}

module.exports = { buildReceiptPayload, checkReceiptConsistency };
