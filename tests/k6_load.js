import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    guest_viewers: { executor: 'constant-vus', vus: 500, duration: '15s', exec: 'guestViewers' },
    chat_posters: { executor: 'constant-vus', vus: 50, duration: '15s', exec: 'chatPosters' },
  },
  thresholds: {
    http_req_failed: ['rate==0.00'],
    http_req_duration: ['p(95)<800'],
  },
};

export function guestViewers() {
  const res = http.get(`${BASE}/audit/v1/qr/match-m1?inspect=true`);
  check(res, {
    'status is 200': (r) => r.status === 200,
    'target_route defined': (r) => r.json('target_route') !== undefined,
  });
  sleep(0.1);
}

export function chatPosters() {
  const payload = JSON.stringify({
    chat_room_id: 'chat-cs-session-101',
    incoming_messages: [{ message_id: 'msg-k6', sender: 'user-k6', text: 'Clean sports commentary' }],
    check_dlq: false,
    simulate_failed_notifications: 0,
  });
  const res = http.post(`${BASE}/audit/v1/moderation/test`, payload, { headers: { 'Content-Type': 'application/json' } });
  check(res, { 'status is 200': (r) => r.status === 200 });
  sleep(0.2);
}
