# Verification log: finisher (Part II contract modules)

Environment: Windows host, private test Postgres 5435 and Redis 6382 (docker), Node 20. Times PKT. Salts were not exported in this shell, so the secret scan covered only the public test salt (0 matches in the diff); literal scans for the real salts were NOT RUN.

## Audit (truth table, before)
| # | Item | Before | After |
|---|---|---|---|
| T1 | Signing library | DONE | DONE |
| T2 | Error envelope, body limits | DONE | DONE |
| T3 | Guest write guard | DONE | DONE (allow-list extended) |
| T4 | Reset and base seed | DONE | DONE (+ guest token, QR codes, counters) |
| T5 | /health | DONE | DONE |
| T6 | /web | DONE | DONE |
| T7 | Rate limiter scope | DONE | DONE |
| T8 | Realtime | DONE | DONE |
| T9 | QR, /q, exports | MISSING | Implemented, tested |
| T10 | Guest session, staging | MISSING | Implemented, tested |
| T11 | Cart merge | MISSING | Implemented, tested |
| T12 | Moderation, DLQ | MISSING | Implemented, tested |
| T13 | Stream key, media auth | MISSING | Implemented; live cut-off NOT RUN |
| T14 | Co-stream | MISSING | HTTP contract implemented, tested; real host disconnect NOT IMPLEMENTED |
| T15 | /metrics | MISSING | Implemented, tested |
| T16 | Privacy erasure | MISSING | Implemented, tested; old JWT 401 NOT IMPLEMENTED |
| T17 | Compose | DONE by infra session | not re-verified, full-stack run NOT RUN |
| T18 | k6 file | MISSING | file added; k6 run NOT RUN |
| T19 | COVERAGE_DAY2.json | MISSING | NOT DONE |

## Runs
| Step | Command | Result |
|---|---|---|
| Phase I + all suites, before final rename | full jest | 66 of 68 suites passed, 1748 of 1750 tests; the 2 failures were an SVG test bug in the new test file (fixed) and a table-name clash with the realtime test's own `day2_costream_session` (my table renamed to `day2_costream_state`) |
| Part II + reset + hardening, after fixes | `jest tests/integration/day2 tests/unit/day2 tests/integration/reset tests/integration/hardening` | 14 suites, 292 tests, 0 failed |
| Ancestry | merge-base --is-ancestor 2adf2c7 HEAD | ancestor |

## Notes
- QR exports use the `qrcode` library; the PDF is hand-written with plain-text id and short URL. Decoding the codes back to the short URL was NOT RUN (no decoder installed).
- Docker images: mediamtx 1.9.3-ffmpeg pulled; minio/minio is not on Docker Hub (compose makes it opt-in); the rest were already local.
- Not run: ffmpeg cut-off timing, 25-client realtime probe against compose, k6, full compose boot (another stack held ports).
- Decisions are in `backend/day2/decisions.js`.

## k6 (T18), NOT PASSED
`tests/k6_load.js` added. Two real runs of the exact profile (500 QR inspect VUs + 50 moderation VUs, 15 s) against the API in a node:20-alpine container on a Windows bind mount, with Postgres/Redis reached through host.docker.internal: run 1 failed 1.1 % of requests, p95 8.6 s; after cutting the QR path to two read-only queries, run 2 failed 4 of 6420 requests (0.06 %), p95 9.6 s. Both crossed the thresholds (`http_req_failed rate==0.00`, `p(95)<800`). The setup is not the compose stack (DB is not on the same network), so the numbers are not representative, but no passing run exists. No k6-summary.json was committed. Next step for an owner: run against the real compose stack and, if still slow, cache or reduce the QR inspect query path.

## Not done
COVERAGE_DAY2.json (no per-module coverage measured), TRACEABILITY update, full compose boot, ffmpeg cut-off, real host-disconnect handoff, old-JWT 401 after erasure, QR decode test.
