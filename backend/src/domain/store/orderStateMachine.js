const TRANSITIONS = {
  pending: ['paid', 'cancelled'],
  paid: ['packed', 'refunded', 'cancelled'],
  packed: ['shipped', 'refunded'],
  shipped: ['delivered'],
  delivered: ['refunded'],
  cancelled: [],
  refunded: [],
};

function canTransition(from, to) {
  return Object.prototype.hasOwnProperty.call(TRANSITIONS, from) && TRANSITIONS[from].includes(to);
}

// Stock goes back on the shelf only when a paid (not yet delivered) order is unwound.
function restockOn(from, to) {
  return (from === 'paid' && (to === 'cancelled' || to === 'refunded')) || (from === 'packed' && to === 'refunded');
}

module.exports = { TRANSITIONS, canTransition, restockOn };
