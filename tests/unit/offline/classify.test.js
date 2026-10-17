const c = require('../../../backend/src/domain/offline/classify');

const action = (over = {}) => ({
  action_id: 'a1', type: 'score_event', queued_at: '2026-01-01T00:00:00Z', base_version: 0, payload: {}, ...over,
});

describe('envelopeFields', () => {
  test('valid envelope', () => {
    expect(c.envelopeFields({ device_id: 'd', actions: [action()] })).toEqual([]);
    expect(c.envelopeFields({ device_id: 'd', actions: Array.from({ length: 100 }, (_, i) => action({ action_id: `a${i}` })) })).toEqual([]);
  });

  test.each([
    [null, ['body']],
    [[], ['body']],
    [{ actions: [action()] }, ['device_id']],
    [{ device_id: 'bad id', actions: [action()] }, ['device_id']],
    [{ device_id: 'd', actions: [] }, ['actions']],
    [{ device_id: 'd', actions: 'x' }, ['actions']],
    [{ device_id: 'd' }, ['actions']],
    [{ device_id: 'd', actions: Array.from({ length: 101 }, (_, i) => action({ action_id: `a${i}` })) }, ['actions']],
    [{ device_id: 'd', actions: [action(), action({ action_id: 'b', type: 'nope' })] }, ['actions.1.type']],
    [{ device_id: 'd', actions: [action({ queued_at: '2026-01-01' })] }, ['actions.0.queued_at']],
    [{ device_id: 'd', actions: [action({ queued_at: '2026-02-30T00:00:00Z' })] }, ['actions.0.queued_at']],
    [{ device_id: 'd', actions: [action({ base_version: -1 })] }, ['actions.0.base_version']],
    [{ device_id: 'd', actions: [action({ base_version: '1' })] }, ['actions.0.base_version']],
    [{ device_id: 'd', actions: [action({ base_version: 1.5 })] }, ['actions.0.base_version']],
    [{ device_id: 'd', actions: [action({ payload: [] })] }, ['actions.0.payload']],
    [{ device_id: 'd', actions: [action({ payload: 'x' })] }, ['actions.0.payload']],
    [{ device_id: 'd', actions: [action({ action_id: '' }), 5] }, ['actions.0.action_id', 'actions.1']],
  ])('%j', (body, expected) => {
    expect(c.envelopeFields(body)).toEqual(expected);
  });
});

test('sortActions is stable and numbers processed_order', () => {
  const list = [
    action({ action_id: 'c', queued_at: '2026-01-01T00:00:05Z' }),
    action({ action_id: 'a', queued_at: '2026-01-01T00:00:01Z' }),
    action({ action_id: 'b', queued_at: '2026-01-01T00:00:01Z' }),
    action({ action_id: 'd', queued_at: '2026-01-01T00:00:01Z' }),
  ];
  const sorted = c.sortActions(list);
  expect(sorted.map((s) => s.action.action_id)).toEqual(['a', 'b', 'd', 'c']);
  expect(sorted.map((s) => s.order)).toEqual([1, 2, 3, 4]);
  expect(sorted.map((s) => s.index)).toEqual([1, 2, 3, 0]);
});

test('resolutionFor', () => {
  expect(c.resolutionFor(3, 3)).toBe('appended');
  expect(c.resolutionFor(1, 3)).toBe('rebased_append');
  expect(c.resolutionFor(4, 3)).toBeNull();
});

test('summarize', () => {
  expect(c.summarize([{ status: 'applied' }, { status: 'applied' }, { status: 'rejected' }, { status: 'duplicate' }]))
    .toEqual({ applied: 2, rejected: 1, duplicate: 1 });
});
