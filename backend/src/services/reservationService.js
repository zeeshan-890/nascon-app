const store = require('../repos/store');
const outbox = require('../repos/outbox');

async function enqueueStatus(client, orderId, from, to) {
  await outbox.enqueue(client, {
    aggregate_type: 'order', aggregate_id: orderId, event_type: 'order.status_changed',
    payload: { order_id: orderId, from, to },
  });
}

// Must run inside a transaction. Releases the order's still-held reservations (stock back on the
// shelf, reserved down) and cancels the order if it is still pending. Reservations that are already
// released/committed are left alone, so stock is never restored twice.
// Returns { released, cancelled }.
async function releaseOrder(client, orderId, { actor, onlyExpired = false, skipLocked = false } = {}) {
  const held = await store.lockReservations(client, orderId, { onlyHeld: true, onlyExpired, skipLocked });
  for (const r of held) await store.releaseStock(client, r.variant_id, r.quantity); // rows sorted by variant_id
  await store.setReservationStatus(client, held.map((r) => r.reservation_id), 'released');

  const order = await store.getOrder(client, orderId, { forUpdate: true });
  let cancelled = false;
  if (order && order.status === 'pending') {
    await store.setOrderStatus(client, orderId, 'cancelled');
    await store.addHistory(client, orderId, 'pending', 'cancelled', actor);
    await enqueueStatus(client, orderId, 'pending', 'cancelled');
    cancelled = true;
  }
  return { released: held.length, cancelled };
}

module.exports = { releaseOrder, enqueueStatus };
