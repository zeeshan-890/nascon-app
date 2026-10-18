# NaSCon '26 Multi-Sport League Platform

Backend for a multi-tenant, multi-sport league and club-operations platform: sports and league
configuration, an append-only match event store with score projection, fixture generation,
standings, roster management, a store with concurrency-safe checkout, role-based access control,
offline sync, and the mobile API with live updates over WebSocket.

## Prerequisites

- Docker with Compose v2
- Free ports: 8080 (API)
- Node.js 20 is only needed to run tests outside Docker

## Run

```bash
DAY1_SALT=<salt> docker compose up -d --build
curl http://localhost:8080/health      # {"status":"ok"}
```

`DAY1_SALT` is the only required setting. It is read verbatim from the environment (no trimming) and is
never written to a file or log. If it is missing or empty the API and worker exit immediately.
`.env.example` only holds a placeholder; copy it to `.env` if you prefer a file.

| Service | Port | Notes |
|---|---|---|
| api | 8080 | HTTP + WebSocket (`/ws`) |
| worker | - | outbox relay, receipt uploads, reservation sweeper |
| postgres | internal | `postgres:16-alpine` |
| redis | internal | `redis:7-alpine` |
| minio | internal | optional, see Troubleshooting |

### Reset

```bash
curl -X POST http://localhost:8080/audit/v1/reset            # default seed
curl -X POST http://localhost:8080/audit/v1/reset -H 'Content-Type: application/json' \
     -d '{"seed":7,"stock":{"var-jersey-001-M":3}}'
```

Reset restores the seeded state (sports, tenants, demo users, store, demo league) in under 10 seconds
and is safe to call repeatedly.

## Demo accounts

Password for every account: `Nascon@26`

| Email | Role | Tenant | Scope |
|---|---|---|---|
| admin@nascon.test | super_admin | - | whole platform |
| organizer@nascon.test | league_organizer | tenant-alpha | league-alpha-1 |
| manager@nascon.test | club_manager | tenant-alpha | club-alpha-1 |
| player@nascon.test | player | tenant-alpha | player-alpha-1 (club-alpha-1) |
| viewer@nascon.test | viewer | tenant-alpha | read only |

## Tests

```bash
./run_tests.sh
```

Uses local Node 20 when present, otherwise a `node:20-alpine` container. Unit tests need nothing else.
Integration tests use a real Postgres (`DATABASE_URL`, default `postgres://nascon:nascon@localhost:5433/nascon`)
and skip themselves when it is unreachable, for example:

```bash
docker run -d --name test-pg -e POSTGRES_USER=nascon -e POSTGRES_PASSWORD=nascon -e POSTGRES_DB=nascon \
  -p 5433:5432 postgres:16-alpine
```

Per-area line coverage is written to `COVERAGE.json` by `backend/scripts/coverage-to-json.js`.

## Mobile app server address

In the app's Settings screen set the server to `http://<laptop-LAN-IP>:8080` (use the laptop's Wi-Fi
address from `ipconfig` / `ip addr`; `10.0.2.2` only works from the Android emulator). The app starts in
**demo mode** (built-in fake data, no server needed): in Settings turn **Use demo data** off to use the real
backend. The phone and
laptop must be on the same network, and the laptop firewall must allow inbound TCP 8080. Use the
"Test connection" button, which calls `GET /api/v1/ping`.

## Architecture

- **API** (Node 20, Express 4, plain JavaScript): `/audit/v1/*` for the graded contract, `/api/v1/*`
  for the mobile app, `/ws` for live updates, `/health`.
- **PostgreSQL** is the system of record. SQL is raw and parameterised. Match events and roster audit
  rows are append-only (enforced by triggers); scores and standings are always rebuilt from the log.
- **Outbox**: state changes write an outbox row in the same transaction. The **worker** relays rows to a
  **Redis Stream** (`domain-events`, consumer group `workers`) and to pub/sub channels `rt:{topic}`,
  which the API fans out to WebSocket subscribers.
- **Redis** also backs the `/api/v1` rate limiter (100 requests per rolling 10 s per socket IP, with an
  in-memory fallback if Redis is unavailable) and read caches for the mobile API.
- **MinIO** stores receipt PDFs (`receipts/{order_id}.pdf`); checkout never depends on it.
- **Concurrency**: per-match row locks serialise event appends; checkout takes an advisory lock per
  `order_id` and reserves stock with conditional updates in sorted `variant_id` order; a sweeper in the
  worker releases expired reservations.
- Pure logic lives in `backend/src/domain/<area>` (fixtures, scoring, standings, store, roster, rbac,
  crypto) and is unit-tested without a database.

### Adding a sport (no code changes)

Sports are data. Either `POST /audit/v1/sports` with a definition, or add an object to
`backend/seed/sports.json` (seeded at startup and on reset). A sport declares a `scoring_model`
(`event`, `innings` or `round`), `periods`, `scoring_events`, `draw_allowed`, `overtime`, `tie_break_order`
and `roster` limits. Code only branches on `scoring_model` and config values; sport and event names never
appear in `backend/src`.

## Assumptions taken (no organizer reply)

1. Seeded `scoring_events` keys use underscores (`two_pointer`, `dot_ball`, `no_ball`, `round_win`...).
2. RBAC probe on a non-existent id returns 404 (for every role that passes the role check, including
   super admin); a foreign-tenant id that exists returns 403.
3. `POST /events` with a `team_id` that is not a participant returns 400 before any state check (409).
4. Reusing a roster `request_id` with a different body returns 409 `DUPLICATE_REQUEST_ID`.
5. In offline sync, `INVALID_BASE_VERSION` is evaluated before rule rejections for the same action.
6. Round-robin withdrawals when two teams meet and only one has passed its `after_round`: resolved by the
   rules in `backend/src/domain/fixtures/roundRobin.js` (this case had no organizer reply).
7. NRR tie-break comparisons use the value rounded to 3 decimals.
8. `match_init` with an unknown `league_id` returns 404 `LEAGUE_NOT_FOUND`.
9. Login lockout is 5 failures for 60 s (`LOGIN_LOCK_SECONDS`), not 15 minutes.

## Troubleshooting

- **Port 8080 already in use**: stop whatever holds it, or map another host port for the `api` service.
- **`/health` returns 503**: the API is still migrating/seeding; it normally becomes healthy within a
  few seconds of the containers starting.
- **MinIO**: the upstream `minio/minio` image is no longer published on Docker Hub, so the `minio` service
  is opt-in (`docker compose --profile storage up -d`). Without it, receipts are still created, signed,
  stored in the database and returned in the checkout response; only the object-store copy is skipped.
- **Observed cold boot** (no images or volumes, clean clone): about 83 s to build the images and about
  88 s until `/health` returned 200.
- Logs: `docker compose logs api worker`.
