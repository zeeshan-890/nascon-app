const { withTransaction } = require('../db/tx');
const AppError = require('../http/AppError');
const store = require('../repos/store');
const { canTransition, restockOn } = require('../domain/store/orderStateMachine');
const gateway = require('./paymentGateway');
const { enqueueStatus } = require('./reservationService');

// One transaction: lock order, validate, history, restock, outbox. A refund calls the gateway
// after commit; the payment is marked refunded only once the gateway returned.
async function transition(orderId, toStatus, actor) {
  const result = await withTransaction(async (client) => {
    const order = await store.getOrder(client, orderId, { forUpdate: true });
    if (!order) throw new AppError(404, 'NOT_FOUND', 'Order not found', { order_id: orderId });
    const from = order.status;
    if (!canTransition(from, toStatus)) {
      throw new AppError(409, 'ILLEGAL_TRANSITION', `Cannot move order from ${from} to ${toStatus}`, { from, to: toStatus });
    }
    if (restockOn(from, toStatus)) {
      const items = await store.getItems(client, orderId);
      for (const it of items) if (it.category !== 'digital') await store.restock(client, it.variant_id, it.quantity);
    }
    await store.setOrderStatus(client, orderId, toStatus);
    await store.addHistory(client, orderId, from, toStatus, actor || 'system');
    await enqueueStatus(client, orderId, from, toStatus);
    const payment = toStatus === 'refunded' ? await store.getCapturedPayment(client, orderId) : null;
    return { order_id: orderId, from, to: toStatus, payment };
  });
  if (result.payment) {
    const r = await gateway.refund(result.payment.capture_id);
    if (r.ok) await withTransaction((c) => store.setPaymentState(c, orderId, 'refunded'));
  }
  await require('../db/cache').invalidateProducts();
  return { order_id: orderId, from: result.from, to: result.to };
}

module.exports = { transition };
