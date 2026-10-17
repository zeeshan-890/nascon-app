const pool = require('../db/pool');
const { withTransaction } = require('../db/tx');
const store = require('../repos/store');
const { releaseOrder } = require('./reservationService');

// Releases held reservations past their expiry and cancels the still-pending orders.
// Needs only Postgres. Returns the number of reservations released.
async function sweepOnce() {
  const orderIds = await store.expiredHeldOrderIds(pool);
  let released = 0;
  for (const orderId of orderIds) {
    const r = await withTransaction((client) =>
      releaseOrder(client, orderId, { actor: 'sweeper', onlyExpired: true, skipLocked: true })
    );
    released += r.released;
  }
  return released;
}

module.exports = { sweepOnce };
