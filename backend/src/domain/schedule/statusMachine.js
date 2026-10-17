// Administrative match lifecycle. `completed`, `abandoned` and `cancelled` are terminal.
const STATUSES = ['upcoming', 'in_progress', 'completed', 'postponed', 'abandoned', 'cancelled'];

const TRANSITIONS = {
  upcoming: ['in_progress', 'postponed', 'cancelled'],
  postponed: ['upcoming', 'cancelled'],
  in_progress: ['completed', 'abandoned', 'postponed'],
  completed: [],
  abandoned: [],
  cancelled: [],
};

function canTransition(from, to) {
  return Boolean(TRANSITIONS[from] && TRANSITIONS[from].includes(to));
}

// What a legal transition does: append a lifecycle event, and/or change the administrative status.
function effectOf(from, to) {
  if (from === 'upcoming' && to === 'in_progress') return { event: 'match_start', admin: undefined };
  if (from === 'in_progress' && to === 'completed') return { event: 'match_end', admin: undefined };
  if (to === 'postponed' || to === 'abandoned' || to === 'cancelled') return { event: null, admin: to };
  // postponed -> upcoming clears the administrative status
  return { event: null, admin: null };
}

module.exports = { STATUSES, TRANSITIONS, canTransition, effectOf };
