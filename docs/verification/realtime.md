# Verification log: realtime (Part II, session 3)

Environment: Windows host, API/worker on Node 20+, test Postgres 5433 and Redis 6380 (docker). Times in PKT.

| Step | Command | Result | Status |
|---|---|---|---|
| 1.1 Inspect Day 1 hub | read `src/realtime/hub.js`, `outboxRelayService.js`, `eventService.js` | `/ws?token=` only (4401 without), `{"subscribe":"match:<id>"}`, frames `{type:"match.event_appended",match_id,data:{match_id,event_id,sequence,version,event_type}}` and `{type:"match.status_changed",match_id,data:{match_id,status}}`; events reach the hub only through the outbox, polled by the worker every 250 ms, then Redis `rt:match:<id>` | PASS |
| 1.2 Baseline latency | 25 clients on Day 1 `/ws` + worker relay, 10 score events (`scratchpad/baseline.js`) | 250/250 delivered; p50 206.5 ms, p95 273.3 ms, max 274.4 ms (from sending the POST) -> not comfortably under 300 ms, so the direct publish in step 3 is needed | PASS |
| 2 Channels | `jest tests/integration/day2.realtime.test.js` | 16/16: 4404 unknown (WS + SSE 404 `MATCH_NOT_FOUND`), snapshot, token ignored, guest write frame, member write refused, subscription cap, oversize payload 1009, SSE headers + Last-Event-ID replay, rooms, gauge, 1000 connect/disconnect cycles | PASS |
| 3 Direct publish | same file | 100 events in order, no loss, no duplicates even with the relay delivering them again; relay-only delivery works once; reconnect corrected by snapshot; reset sends fresh snapshots; status changes pushed | PASS |
| 3 Gate: NFR-1 | `jest tests/integration/day2.realtime.nfr1.test.js` (in-process, direct publish, no worker) | 25 clients x 20 events, 0 missing; from request p50 34.5 / p95 60.7 / max 62.6 ms; after HTTP response p50 4.8 / p95 8.3 / max 10.2 ms | PASS |
| 3 Gate: NFR-1 against compose stack | `NFR1_BASE_URL=http://localhost:8080 jest ...nfr1` | compose stack not started in this session (port 8080 was occupied by another project) | NOT RUN |
| 4 Docs | `docs/part2-interfaces.md` section "Realtime channels (NFR-1)" | written | PASS |
| 2.3/2.6 timed heartbeats | SSE `: keep-alive` every 15 s and WS ping every 25 s are implemented but no test waits that long | not exercised | NOT RUN |
| Full suite | `jest` (whole repo, test DB 5433 + Redis 6380 reachable) | 67 suites, 1698 tests, 0 failed (the 2 realtime Phase I tests pass because Redis was reachable) | PASS |
| Ancestry | `git merge-base --is-ancestor 2adf2c75... HEAD` | ancestor | PASS |
| Secret scan | grep staged diff for the public test salts and (if exported) the real ones | no matches | PASS |
| Push 1 | commit a9dd07a, on origin/main at 12:51 PKT | confirmed by `git fetch` (origin/main = a9dd07a) | PASS |
| Coverage | `jest day2.realtime*.test.js --coverage` over `backend/day2/realtime/**` + `routes/realtime.js` | 87.57 % lines (gauge 100, index 87.5, streams 89.0, wsServer 84.8, routes/realtime 82.8); 17/17 tests | PASS |
| Re-run NFR-1 under load | same probe, machine busy | 0 missing; from request p50 74.6 / p95 153.4 / max 154.7 ms (target p95 < 250) | PASS |
| Shared-DB caveat | a run at 13:10 failed 3-9 tests with 404s on `demo-m-live` | another session's full jest run (started 13:02) was resetting the same test database; re-run after it ended: 17/17 | NOTE |
| Phase I suite BEFORE changes | not run before the first edit; only the after-run (67 suites, 1698 passed) exists | NOT RUN |
| Secret scan scope | greps for the public test salts and for any exported DAY1_SALT/DAY2_SALT; the real salts were not exported in this shell, so they were not searched for literally | PARTIAL |
