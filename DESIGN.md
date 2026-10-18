# NaSCon '26 — Part I Backend System Design (Binding Build Spec for Claude Code)

> Source of truth: the NaSCon '26 Part I specification. Where this design and the spec differ, **the spec wins**.
> Every `/audit/v1/*` shape below is compared literally by a Python autograder: field names, types, status codes, error codes, signatures.

---

## 0. Decisions (final)

| Concern | Decision | Reason |
|---|---|---|
| Runtime | Node.js 20 LTS, **Express 4**, plain JavaScript (CommonJS) | Team choice; no compile step = no build failure risk in Docker |
| Validation | **zod** (strict types, unknown keys ignored via `.passthrough()`/strip) | Exact 400 `details.fields` generation |
| DB | **PostgreSQL 16**, driver `pg` (node-postgres), **raw parameterised SQL, no ORM** | Full control of locks/transactions; Prisma needs engines + codegen (Docker build risk) |
| Cache / rate limit / pub-sub | **Redis 7**, client `ioredis` | One component serves 4 roles |
| Internal queue | **Redis Streams** (consumer group `workers`) fed by a **transactional outbox** table | Approved; no extra broker container; outbox = no lost events |
| Object store | **MinIO** (`minio` npm client) — receipt PDFs | Required in compose ("object store") |
| PDF | **pdfkit** (pure JS, standard Helvetica) | Spec allows PDF libraries; WeasyPrint/Puppeteer are heavy and slow to build |
| Realtime | **WebSocket** via `ws` on the same HTTP server, fan-out through Redis pub/sub | Kotlin OkHttp has native WS; bidirectional subscribe; scales across instances |
| Auth | **Custom JWT**, `jsonwebtoken`, HS256 pinned, `bcryptjs` passwords | Keycloak too heavy; pure JS (no native build) |
| Logging | `pino` | Fast structured logs |
| Security headers | `helmet`, `x-powered-by` off | OWASP API8 |
| Tests | **Jest** + supertest, istanbul coverage | Per-area coverage → COVERAGE.json |
| Architecture | **Modular monolith API + 1 worker container** | One backend developer; one deployable; stateless so it scales horizontally |

### Mobile (Kotlin) — for the mobile developer
| Concern | Use |
|---|---|
| UI | Jetpack Compose + Material 3, minSdk 29 |
| Local cache | **Room** (fixtures, standings, clubs, products tables) |
| Offline queue | **Room** table `pending_actions` storing actions in the exact 5.14 format |
| Background sync | **WorkManager** (constraint `NetworkType.CONNECTED`) + visible "Sync now" button |
| Network | Retrofit + OkHttp + kotlinx.serialization; OkHttp WebSocket for live score |
| Connectivity indicator | `ConnectivityManager.NetworkCallback` → StateFlow → always-visible banner |
| Token storage | `EncryptedSharedPreferences` (Android Keystore-backed MasterKey) |
| Server address | Settings screen, stored in DataStore; default `http://10.0.2.2:8080`; `network_security_config` allows cleartext |
| Release APK | `release` buildType signed with debug signingConfig → `mobile/release/app-release.apk` |

---

## 1. Architecture

```
                 ┌──────────────────── docker compose ────────────────────┐
 Grader/App ──►  │  api :8080 (Express + ws)                              │
                 │   ├─ middleware: requestId → bodyLimit(1MB) → json     │
                 │   │   → rateLimit(/api/v1 only) → auth(where needed)   │
                 │   ├─ routes/audit/*   (autograder contract)            │
                 │   ├─ routes/api/v1/*  (mobile production API)          │
                 │   ├─ services/* (transactions, locking, idempotency)   │
                 │   ├─ domain/*  (PURE functions: fixtures, projection,  │
                 │   │            standings, roster rules, rbac, pricing) │
                 │   └─ repos/*   (SQL only)                              │
                 │  worker (same image, `node src/worker.js`)             │
                 │   ├─ outbox relay → Redis Stream `domain-events`       │
                 │   ├─ consumer: receipt PDF → MinIO, order notifications │
                 │   └─ sweeper: expired stock reservations, loan returns │
                 │  postgres:16-alpine   redis:7-alpine   minio           │
                 └────────────────────────────────────────────────────────┘
```

**Layering rule:** `domain/` contains zero I/O and zero sport/event names. Every scoring/standings decision branches only on `scoring_model` (`event` | `innings` | `round`) and on config values. This both avoids the −8 penalty and makes `domain/` fully unit-testable without a DB (needed for ≥85% coverage).

**Scalability / reliability**
- API is stateless → N replicas possible; all state in PostgreSQL; rate limiting and WS fan-out in Redis.
- Transactional outbox: every domain event row is inserted **in the same transaction** as the state change; the worker relays to Redis Streams (`XADD`), marks `published_at`. Redis outage → events wait in the table, nothing lost.
- Redis outage → rate limiter falls back to in-process sliding window; `/health` does not depend on Redis or MinIO.
- MinIO outage → checkout still succeeds (PDF is returned inline; upload is async via worker, retried).
- Postgres pool: `max: 30`, `statement_timeout: 4000ms`, `idle_in_transaction_session_timeout: 5000ms`.
- Transactions retried up to 3× on `40001` (serialization) / `40P01` (deadlock).
- Lock ordering is fixed everywhere (see §6) → no deadlocks.

---

## 2. Docker / Repository

### Repo root
```
docker-compose.yml   .env.example   README.md   TRACEABILITY.md   DESIGN.md
run_tests.sh   COVERAGE.json   tests/   backend/   mobile/   mobile/release/app-release.apk
.gitignore  (.env, node_modules, coverage)
```

### docker-compose.yml requirements
- Services: `postgres` (16-alpine, healthcheck `pg_isready`), `redis` (7-alpine, healthcheck `redis-cli ping`), `minio` (pinned tag, `server /data`), `api`, `worker`.
- `api` and `worker`: `build: ./backend`, `depends_on: {postgres: service_healthy, redis: service_healthy}`, `api` ports `8080:8080`.
- Env on api and worker: `DAY1_SALT: ${DAY1_SALT}` (exact), `DATABASE_URL`, `REDIS_URL`, `MINIO_*` with inline defaults in compose (`${VAR:-default}`) so **only DAY1_SALT** is needed.
- Pin every image tag (no `latest`). Commit `package-lock.json`; Dockerfile uses `npm ci --omit=dev`, `node:20-alpine`, non-root user.
- No host volumes, no manual steps. Compose creates its own named volumes.

### Startup sequence (api)
1. Read `process.env.DAY1_SALT` **as-is** (no trim). If undefined/empty → log fatal and exit (spec requires it).
2. Derive JWT secret = `HMAC_SHA256(DAY1_SALT, "jwt-signing-key")` (nothing extra committed or required).
3. Connect to Postgres with retry (1s × 60).
4. Run idempotent migrations (`CREATE TABLE IF NOT EXISTS …`) under `pg_advisory_lock(1)` (api and worker may start together).
5. Run the baseline seed (same function as `/reset`) if `tenants` is empty.
6. Create MinIO bucket (non-fatal, retried in background).
7. Listen on 8080. `/health` returns 200 only after steps 3–5 are done.

### .env.example
`DAY1_SALT=change-me`, `DATABASE_URL`, `REDIS_URL`, `MINIO_ENDPOINT`, `MINIO_PORT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`, `MINIO_BUCKET`, `PORT=8080`, `LOG_LEVEL`. Real salt goes only in a git-ignored `.env`.

---

## 3. Global HTTP behaviour (applies to every route)

### Middleware order
1. `requestId`
2. Body parser: `express.json({ limit: '1mb', type: () => true, strict: false })`
   - `entity.too.large` → **413 `PAYLOAD_TOO_LARGE`**
   - JSON syntax error → **400 `VALIDATION_ERROR`** `details.fields: ["body"]`
   - Body that is not a JSON object (array/string/number) on routes expecting an object → 400.
   - Empty body allowed where the spec says optional (`/reset`, probes, `/rbac-matrix`).
3. Rate limiter mounted **only** on `/api/v1` (see §11). `/audit/v1/*`, `/health`, `/ws` exempt.
4. Routers.
5. 404 fallback → `{"error":{"code":"NOT_FOUND","message":"Route not found","details":{}}}`.
6. Central error handler.

### Error body (always)
```json
{ "error": { "code": "UPPER_SNAKE", "message": "plain text", "details": {} } }
```
- `details` is always an object; `VALIDATION_ERROR` always has `details.fields: [..]` (dotted paths, e.g. `payload.team_id`).
- Implement `class AppError(status, code, message, details)`. Handler never leaks stack traces.
- Postgres error mapping (defence in depth, never a 5xx for client input): `22P02`,`22007`,`22008`,`22003`,`23502` → 400; `23505` → re-read & resolve as idempotency/exists (409); `23514` (check) → 409 per context; `40001`/`40P01` → retry.

### Validation order (every endpoint)
**400 (schema/types/values) → 404 (referenced id missing) → 409/402 (business rule).** First failure wins. Idempotency replay check happens right after schema validation (before 404/409) so an identical replay always returns 200.

### Primitive validators (shared)
- `Id`: string, regex `^[A-Za-z0-9_.:-]{1,64}$` (never contains `|`). Used for every grader id: event_id, match_id, team ids, league_id, order_id, variant_id, player_id, squad_id, club_id, tenant_id, request_id, action_id, device_id. Path params validated the same way (bad format → 400).
- `SportId`: `^[a-z0-9_-]{2,40}$`. `EventKey`: `^[a-z0-9_]{1,40}$`.
- `Int`: `Number.isInteger` (rejects `"3"`, `3.5`, `true`). `Bool`: real booleans only.
- `Date`: `^\d{4}-\d{2}-\d{2}$` **and** a real calendar date (reject `2026-02-30`).
- `Timestamp`: `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$` and valid.
- Server timestamps output as `YYYY-MM-DDTHH:MM:SSZ` (strip milliseconds).
- Free text (`name`, `reason`, `customer_id`, `requested_by`): string, 1–200 chars, stored and returned verbatim (quotes, `<script>` are plain data). **`customer_id` is free text, not an Id** (spec: stored/returned as plain data).
- SQL: parameterised only. Never string-concatenate input into SQL.

### Status codes
200 read / idempotent replay · 201 created/applied first time · 400 · 401 · 402 · 403 · 404 · 409 · 413 · 429.

---

## 4. Signatures & canonical JSON (`domain/crypto.js`)

```
canonical(value):
  null/true/false → as is
  number → JSON.stringify(n)  (integers print without decimal point)
  string → JSON.stringify(s)  (non-ASCII NOT escaped — same as Python ensure_ascii=False)
  array  → "[" + items.map(canonical).join(",") + "]"
  object → keys sorted by Unicode CODE POINT (compare via Array.from(key) code points,
           NOT default UTF-16 sort) → "{" + `${JSON.stringify(k)}:${canonical(v)}` joined "," + "}"
sign(id, payload) = hex(HMAC_SHA256(key = utf8(DAY1_SALT), msg = utf8(id + "|" + canonical(payload))))
lotsKey(seed, leagueId, teamId) = hex(SHA256(utf8(`${seed}|${leagueId}|${teamId}`)))
```
- Unit tests MUST include Appendix A vector 1 (`demo-salt`, `evt-1` → `972a8b55…c501`) and vector 2 (lots keys for t1,t2,t3).
- Event signature payload = the **request's `payload` object exactly as received** (including unknown inner keys). Store `payload_canonical TEXT` and `signature` at insert time; never recompute from JSONB (JSONB reorders keys).
- Receipt signature payload = `receipt.payload` exactly as returned; store the canonical text alongside.

---

## 5. Data model (PostgreSQL)

All tables `CREATE TABLE IF NOT EXISTS`. Text PKs = grader ids stored verbatim (case-sensitive).

### Platform / tenancy / auth
- `tenants(tenant_id PK, name, logo_url, primary_color, secondary_color)`
- `users(user_id PK, email UNIQUE, password_hash, role CHECK IN ('super_admin','league_organizer','club_manager','player','viewer'), tenant_id NULL, league_ids TEXT[], club_id NULL, player_id NULL, failed_logins INT, locked_until TIMESTAMPTZ)`
- `refresh_tokens(token_hash PK, user_id, expires_at, revoked_at)`
- `role_policies(role, action, scope, PRIMARY KEY(role, action))` — the RBAC policy matrix as data (see §12).
- `settings(key PK, value JSONB)` — `last_reset_seed`.

### Sports & competition structure
- `sports(sport_id PK, name, scoring_model, config JSONB, created_at)` — config is the full 5.6 object (seed sports use the same shape).
- `seasons(season_id PK, tenant_id, name, start_date, end_date)`
- `leagues(league_id PK, tenant_id NULL, sport_id FK, season_id NULL, kind ('league','division','knockout','hybrid'), parent_league_id NULL, config JSONB)`
  - config: `points{win,draw,loss}`, `tie_break_order`, `qualification_spots`, `lots_seed`, `transfer_window{open,close}`, `max_squad_size`, `required_positions`, `promotion{count,to}`, `relegation{count,to}`, `hybrid{groups, advance_per_group}`.
- `league_teams(league_id, team_id, PRIMARY KEY(league_id, team_id))`
- `venues(venue_id PK, tenant_id, name, lat, lng)`, `venue_availability(venue_id, weekday, opens, closes)`

### Matches & event store
- `matches(match_id PK, league_id NULL, sport_id, home_team_id, away_team_id, origin ('fixture','init'), version INT NOT NULL DEFAULT 0, admin_status NULL, scheduled_at NULL, venue_id NULL, round INT NULL, bracket_slot JSONB NULL)`
  - `version` = number of events = cached append counter **used only for sequencing/locking**; scores are never stored.
  - Index `(league_id)`.
- `match_events(event_id PK, match_id FK, sequence INT, type, payload JSONB, payload_canonical TEXT, signature CHAR(64), request_canonical TEXT, compensates TEXT NULL, created_at, UNIQUE(match_id, sequence))`
  - Partial unique index `ON match_events(compensates) WHERE compensates IS NOT NULL` → a target can be compensated only once (DB-level race protection).
  - **Append-only trigger**: `BEFORE UPDATE OR DELETE … RAISE EXCEPTION` (reset uses `TRUNCATE`, which does not fire row triggers).

### Clubs & roster
- `clubs(club_id PK, tenant_id, name, venue_lat, venue_lng, home_venue_name, socials JSONB, logo_url)`
- `club_staff(club_id, name, role ('coach','manager','physio'))`
- `club_season_stats(club_id, season_id, played, won, drawn, lost, scored, conceded)`
- `squads(squad_id PK, league_id, club_id, tenant_id, name)` index `(league_id)`
- `players(player_id PK, name, position)`
- `registrations(league_id, player_id, squad_id NULL, parent_squad_id NULL, registration_type ('initial','transfer','loan','release'), start_date NULL, loan_end_date NULL, role, PRIMARY KEY(league_id, player_id))` — one active registration per player per league (FR-2.2).
- `roster_initial(league_id, player_id, squad_id, role)` — the setup snapshot (used for as_of replay).
- `roster_live_matches(league_id, match_id, home_squad_id, away_squad_id)`
- `roster_audit(audit_seq BIGSERIAL, audit_id TEXT UNIQUE, league_id, player_id, action, from_squad_id, to_squad_id, effective_date DATE, state, who, "when" TIMESTAMPTZ, why, loan_audit_id NULL)`
  - Unique index `ON roster_audit(loan_audit_id) WHERE action='loan_return'` → loan return written exactly once even under concurrent history calls.
  - Append-only trigger as above. Index `(player_id, audit_seq)`.
- `roster_requests(request_id PK, request_canonical TEXT, status_code INT, response JSONB)`

### Store
- `products(product_id PK, tenant_id, club_id NULL, name, category ('apparel','accessory','digital'), description, image_url)`
- `variants(variant_id PK, product_id FK, size, color, price_minor INT, currency 'PKR', stock INT CHECK (stock >= 0), reserved INT CHECK (reserved >= 0))`
- `price_schedules(variant_id, price_minor, effective_from TIMESTAMPTZ)` — effective price = latest schedule ≤ now, else `variants.price_minor`.
- `discount_codes(code PK, percent INT, active_from, active_to, tenant_id NULL)` — seed `WELCOME10` = 10.
- `orders(order_id PK, customer_id, tenant_id, status, request_canonical TEXT, subtotal_minor, discount_minor, total_minor, response_status INT, response JSONB, receipt_object_key, created_at, paid_at)`
- `order_items(order_id, variant_id, quantity, unit_price_minor)`
- `reservations(reservation_id BIGSERIAL, order_id, variant_id, quantity, status ('held','committed','released'), expires_at)` partial index `WHERE status='held'`
- `payments(payment_id, order_id, authorization_id, capture_id, state ('authorized','captured','declined','capture_failed','voided','refunded'), token, created_at)`
- `order_status_history(order_id, from_status, to_status, at, actor)`
- `match_pins(match_id, product_id, pinned_at)`

### Infra
- `outbox(id BIGSERIAL, aggregate_type, aggregate_id, event_type, payload JSONB, created_at, published_at NULL)` partial index `WHERE published_at IS NULL`
- `offline_actions(device_id, action_id, result JSONB, PRIMARY KEY(device_id, action_id))`

---

## 6. Concurrency, transactions, locking (race-condition design)

| Operation | Isolation & locking | Why |
|---|---|---|
| Append match event | `BEGIN; SELECT … FROM matches WHERE match_id=$1 FOR UPDATE;` load events; rebuild projection; validate; `INSERT match_events(sequence=version+1)`; `UPDATE matches SET version=version+1`; outbox insert; `COMMIT` | Serialises per match → gap-free sequence, rules checked on latest state; `UNIQUE(match_id,sequence)` as backstop |
| `match_init` | `INSERT INTO matches … ON CONFLICT DO NOTHING RETURNING` then append event in same tx | Two concurrent inits → exactly one wins, other gets 409 `MATCH_EXISTS` (or 200 if identical event_id replay) |
| Duplicate event_id race | Global PK on `event_id`; on `23505` → re-read stored event, compare `request_canonical` → 200 replay or 409 `DUPLICATE_EVENT_ID` | Correct under concurrency |
| Double compensation race | Partial unique index on `compensates` + match row lock | Exactly one succeeds; other → 409 `INVALID_COMPENSATION` |
| Checkout | See §10: session advisory lock on `order_id`; conditional `UPDATE variants SET stock=stock-q, reserved=reserved+q WHERE variant_id=$1 AND stock>=q`; items locked in **sorted variant_id order** | Zero oversell, no deadlocks, CHECK constraint as last-line guard |
| Roster transfer | `SELECT … FROM leagues WHERE league_id=$1 FOR UPDATE` then all checks + writes in one tx | Squad counts/position minimums consistent under concurrency |
| Loan return materialisation | Unique index on `loan_audit_id` + `INSERT … ON CONFLICT DO NOTHING` | Never duplicated |
| Roster/order idempotency | PK on request_id/order_id + canonical compare | Replay → 200, different body → 409 |
| Offline sync | `pg_advisory_xact_lock(hashtext('dev:'||device_id))`; each action in its **own** transaction via the same services | One failing action never rolls back others; same-device syncs serialised |
| Reset | `pg_advisory_lock(42)`; single tx `TRUNCATE … RESTART IDENTITY CASCADE`; seed; commit | Repeatable identical state < 10 s |
| Global lock order | league → match → variants(sorted) → orders | Deadlock-free |

**Read-side optimisation (no N+1):**
- `/score`: 2 queries (match + events ordered by sequence).
- `/standings`: 3 queries (league, matches of league, all events `WHERE match_id = ANY($1) ORDER BY match_id, sequence`) → project in memory.
- History: 2–3 queries.
- Mobile read APIs (`/api/v1/...` fixtures, clubs, products, standings) cached in Redis (`cache:league:{id}:standings`, TTL 30 s) and invalidated by the outbox consumer on relevant domain events. **Audit routes never use the cache** — they always rebuild from the log (spec).

---

## 7. Sports, reset, health

### 7.1 `GET /health`
`SELECT 1` + `SELECT 1 FROM tenants LIMIT 1` → 200 `{"status":"ok"}`; otherwise 503 with error body.

### 7.2 `POST /audit/v1/reset`
- Body optional. `seed`: int (default 42). `stock`: object, keys must be existing seeded variant ids, values int ≥ 0.
  - non-int seed / negative or non-int stock → 400; unknown variant key → 404 `VARIANT_NOT_FOUND`.
- Tx: truncate **everything** (matches, events, leagues, rosters, audit, requests, orders, items, reservations, payments, offline_actions, custom sports, outbox, pins, users, refresh tokens, clubs, …) then seed:
  - tenants `tenant-alpha` "Alpha Sports", `tenant-beta` "Beta Athletics" (with logo/colors).
  - sports football, basketball, cricket, esports from `seed/sports.json` (**data file, same schema as 5.6**).
  - store: `prod-jersey-001` (tenant-alpha), variants `var-jersey-001-M` / `-L`, price 450000 PKR, stock from request (defaults M=1, L=5), reserved 0; discount `WELCOME10`.
  - RBAC scenario: leagues `league-alpha-1`, `league-alpha-2` (tenant-alpha), `league-beta-1` (tenant-beta); clubs `club-alpha-1`, `club-alpha-2`, `club-beta-1`; players `player-alpha-1`(club-alpha-1), `player-alpha-2`(club-alpha-2), `player-beta-1`(club-beta-1).
  - 5 demo users (§12), password `Nascon@26` — **bcrypt hash computed once at boot and reused** (reset stays fast).
  - `settings.last_reset_seed = seed`.
  - Flush Redis keys `cache:*` (not rate-limit keys).
- Response 200 exactly:
```json
{"status":"reset_complete","seed":42,"sports":["football","basketball","cricket","esports"],
 "tenants":["tenant-alpha","tenant-beta"],"stock":{"var-jersey-001-M":1,"var-jersey-001-L":5}}
```

### 7.3 Seed sports (`seed/sports.json`)
Event keys use underscores (key regex forbids spaces):
- football: event, periods {count 2, duration 45}, `goal:{points:1}`, draw true, overtime none, roster 11–25, `{gk:1}`.
- basketball: event, {4,12}, `free_throw:1`, `two_pointer:2`, `three_pointer:3`, draw false, overtime extra_time 5, roster 5–15, `{}`.
- cricket: innings {innings_per_team 1, overs 20, balls 6, wickets 10}; `dot_ball{0,legal,¬w}`, `single{1}`, `two{2}`, `four{4}`, `six{6}`, `wide{1,not legal}`, `no_ball{1,not legal}`, `wicket{0,legal,wicket}`; draw true; super_over; roster 11–18 `{wicketkeeper:1}`.
- esports: round {best_of 3}, `round_win:{rounds:1}`, draw false, none, roster 5–8, `{}`.
- tie_break_order = full default list for all.
> ⚠ Exact seeded event keys are an organizer clarification item (§17).

### 7.4 `POST /audit/v1/sports`
- Validate per 5.6 table; collect **all** bad fields into `details.fields`.
  - periods by model; `best_of` odd ≥ 1; scoring_events ≥ 1 key, key regex, value shape by model; overtime.policy enum; tie_break_order items valid, **no repeats**; roster min ≤ max, required_positions values int ≥ 0 (keys non-empty strings).
- Store config with `tie_break_order` normalised (default full list; append `draw_lots` if omitted).
- Exists → 409 `SPORT_EXISTS`. Response 201 `{"sport_id","status":"created","config":{…stored…}}`.
- Immediately usable everywhere (sports read from DB per request; optional in-process cache invalidated on insert).

---

## 8. Fixtures — `POST /audit/v1/fixtures` (pure, stores nothing) — `domain/fixtures/*`

Common validation: `format` enum; `teams` array of unique Ids; size limits RR 2–40, swiss 2–64, single 2–128, double 3–128 → else 400. `params` optional object.

### 8.1 Round robin (circle / Berger method)
- `n` teams; if odd add `null` (BYE) → `m` even; rounds per leg `m−1`; fix position 0, rotate the rest.
- Pair `(arr[i], arr[m−1−i])`. Home/away: for the fixed team alternate by round parity; for other pairs the team at index `i` is home when round is even, swapped when odd (Berger table). **Unit-test property for every n in 2..40, legs 1 & 2:** each team once per round (or bye), exactly one bye per leg for odd n, each unordered pair `legs` times, each ordered pair once when legs=2, `|home−away| ≤ 1` per leg.
- Leg 2 = leg 1 with home/away swapped, round numbers continue globally.
- Pairs including BYE are removed; the team goes to `byes`. `match_id = RR-L{leg}-R{round}-M{idx}` idx from 1 after removing bye.
- Withdrawals: each `{team_id, after_round}`; unknown team, duplicate team, non-int, or `after_round` outside `0..round_count` (round_count = total rounds over all legs) → 400. `withdrawal_policy` enum, default `forfeit_remaining`.
  - `forfeit_remaining`: `wA = round > afterA` (false if A not withdrawn), same for B. Both → `voided`; one → `forfeit`, `awarded_to` = other; none → `scheduled`.
  - `void_all`: any fixture with a withdrawn team → `voided`, `awarded_to:null`.
  - Fixtures never removed; byes unaffected.
- Response: `format, legs, team_count, round_count, rounds[{round, matches[{match_id, leg, home, away, status, awarded_to}], byes}], summary{total, scheduled, forfeit, voided}`.

### 8.2 Swiss
- Params: `total_rounds` int 1..n−1 (default `ceil(log2 n)`), `round` int 1..total_rounds (default 1), `history` exactly rounds `1..round−1` in order (missing/extra/out of order → 400). Each history round: `round`, `bye` (null or team in list), `matches[{home, away, result ∈ home|away|draw}]`; teams must exist, no team twice in a round → 400.
- Scores: win 1, draw 0.5, loss 0, bye 1. Buchholz = Σ current scores of opponents actually played (bye adds 0).
- `standings` = before this round, sorted score desc, buchholz desc, seed index asc; `rank` 1..n. Output floats (`1.0` serialises as `1` in JS — numerically equal).
- **Round 1 (exact):** `m=floor(n/2)`; odd n → last seed bye; pair seed i with seed i+m (home = lower seed number).
- **Rounds ≥ 2:**
  1. Bye candidates (odd n): teams without a previous bye, ordered by score asc, then **largest seed index first**. Even n → no bye.
  2. For each candidate in order: remaining teams; build graph with edges only between pairs that have **not met**; weight = `BIG − 2·|scoreA − scoreB|` (integers). Run **hand-written Edmonds' blossom maximum-weight matching with max-cardinality** (O(n³), port of the standard Galil algorithm — written by us, no library). If matching is perfect → minimum Σ|diff| among rematch-free perfect matchings → done.
  3. None perfect for any candidate → 409 `NO_VALID_PAIRING` `details:{round}`.
  - Home = higher-ranked team. `match_id = SW-R{round}-M{i}` ordered by best ranked team.
  - Unit test the matcher against brute force for random n ≤ 10.
- Response: `format, round, total_rounds, pairings[{match_id, home, away}], bye (team or null), standings[{rank, team_id, score, buchholz}]`.

### 8.3 Single elimination
- `B` = next pow2 ≥ n; `order(2)=[1,2]`, `order(2k)` = for s in order(k): `[s, 2k+1−s]`.
- R1 match i takes entries (2i−1, 2i); lower seed home; seed > n missing → `bye:true, away:null, winner = present team` (loser null), propagated immediately.
- Rounds = log2 B; `R{r}M{i}`; `winner_to = {R{r+1}M{ceil(i/2)}, slot: odd→home, even→away}`; final `winner_to:null`.
- MATCH = `match_id, bracket:"main", round, index, home, away, home_seed, away_seed, bye, winner, loser, winner_to, loser_to`. Seeds propagate with teams (null while unknown).
- `third_place_match:true` and B ≥ 4 → match `TP` (`bracket:"third_place"`, round = final round, index 1); semifinal loser_to `{TP, home}` / `{TP, away}`. (B = 2 → no semifinals → no TP.) TP placement: separate entry in the last round's `matches` array after the final.
- `results`: process in bracket (topological) order regardless of input order; unknown `match_id`, or winner not a current participant → 400 `VALIDATION_ERROR`. Fill winner/loser, propagate to winner_to and loser_to.

### 8.4 Double elimination
- WR = single-elim construction with ids `WR{r}M{i}`, `bracket:"winners"`, k = log2 B.
- LR rounds 1..2(k−1); rounds 2j−1 and 2j each have `B/2^(j+1)` matches; ids `LR{r}M{i}`, `bracket:"losers"`, `loser_to:null`.
  - LR1 M i ← losers of WR1 M(2i−1) (home) and WR1 M(2i) (away).
  - LR(2j) M i: home ← winner LR(2j−1) M i; away ← loser of WR(j+1) M i.
  - LR(2j+1) M i: home ← winner LR(2j) M(2i−1); away ← winner LR(2j) M(2i).
  - Final LR(2k−2) M1: away ← loser of WR final ✓ (spec), its winner → GF1.away.
  - WR r loser_to: r=1 → LR1; r ≥ 2 → LR(2r−2) away slot ✓ (recommended routing).
- `GF1` (`bracket:"grand_final"`): home ← WR final winner, away ← LR final winner.
- `bracket_reset:true` → `GF2` with `conditional:true`, home/away = GF1's teams; GF1 `winner_to:{GF2,"home"}`, `loser_to:{GF2,"away"}` (every slot fed by exactly one source). `required`: null until GF1 has a result; then `true` iff GF1 winner == GF1.away (losers champion), else `false`. All other matches carry `conditional:false, required:null`? → only GF2 carries `conditional/required`; GF1 has `conditional:false`.
- WR1 byes exactly as single elim. Losers-bracket slots fed by a bye's "missing loser" are dead; a match with one live team and one dead slot auto-advances its live team.
- Results processed in topological order; validation as 8.3.
- Response: `format, bracket_size, bracket_reset, winners[{round, matches}], losers[{round, matches}], grand_final[GF1, GF2?]`.
- Unit test as a graph: counts (losers total = B−2), every non-terminal has winner_to, every WR has loser_to, every slot except WR1 has exactly one feeder.

---

## 9. Leagues, events, score, standings

### 9.1 `POST /audit/v1/leagues`
- 400: league_id Id; sport_id string; teams 2–40 unique Ids; fixtures array of `{match_id, home, away}` with Ids, home ≠ away, both in teams, match_ids unique within the request; `points` ints (default 3/1/0); `tie_break_order` valid no repeats (default = sport's); `qualification.spots` required int 1..teams; `lots_seed` int optional.
- 404 `SPORT_NOT_FOUND` → 409 `LEAGUE_EXISTS` → 409 `MATCH_EXISTS` (any fixture match_id already in `matches`).
- One tx: insert league (lots_seed default = `settings.last_reset_seed`, else 42), league_teams, matches (`origin='fixture'`, version 0, status upcoming).
- 201 `{"league_id","status":"created","team_count","fixture_count"}`.

### 9.2 Projection (`domain/projection.js`, pure)
Input: sport config + match row + ordered events. Steps:
1. Build `compensatedBy` map from compensation events.
2. Active = not compensated and not a compensation.
3. Status: `upcoming` → after active? No — status events (`match_start`, `match_end`) are never compensable, so status = last of match_start/match_end present (`completed` if match_end, `in_progress` if match_start, else `upcoming`).
4. Per team totals by model:
   - event: `score += scoring_events[e].points`
   - innings: `score += runs`; if `legal_ball` → `balls++`; if `wicket` → `wickets++`; `overs = floor(balls/bpo) + "." + (balls % bpo)`
   - round: `score += rounds`
   - `fair_play += discipline.points`
5. `tiebreak_winner_team_id` = team of the **latest active** `tiebreak_winner`, else null.
6. `winner_team_id` = higher score; level → tiebreak winner; else null.
7. `outcome`: not completed → `pending`; winner exists → `home_win`/`away_win`; level & `draw_allowed` → `draw`; else `undecided`.
8. Output events list: `{sequence, event_id, type, payload, signature, compensated, compensated_by}`.
9. `wickets/balls/overs` null for non-innings models. `league_id` null for stand-alone matches.

### 9.3 `POST /audit/v1/events`
Schema 400: event_id Id, match_id Id, `type` ∈ {match_init, match_start, score, discipline, tiebreak_winner, match_end, compensation}, payload object with per-type shape:
- match_init `{sport_id, home_team_id(Id), away_team_id(Id), league_id?(Id)}`, home ≠ away
- match_start/match_end `{}` (extra keys ignored)
- score `{team_id, event_type}` · discipline `{team_id, points:int}` · tiebreak_winner `{team_id}` · compensation `{compensates:Id, reason:string}`

Then (inside the match row lock, see §6):
1. **Idempotency**: event_id exists → identical `request_canonical` (canonical of `{event_id, match_id, type, payload}`) → 200 stored response; else 409 `DUPLICATE_EVENT_ID`.
2. match_init: sport missing → 404 `SPORT_NOT_FOUND`; `league_id` given & missing → 404 `LEAGUE_NOT_FOUND`; stream exists → 409 `MATCH_EXISTS`.
3. Other types: stream missing → 404 `MATCH_NOT_FOUND`.
4. 400 checks needing the match: `team_id` not home/away → 400 `VALIDATION_ERROR` (`payload.team_id`); score `event_type` not in sport → 400 `UNKNOWN_EVENT_TYPE`.
5. compensation: target missing everywhere → 404 `EVENT_NOT_FOUND`; target in other match, target type not in {score, discipline, tiebreak_winner}, or already compensated → 409 `INVALID_COMPENSATION`. Allowed in any status.
6. State rules: match_start needs upcoming else 409 `ILLEGAL_TRANSITION`; score/discipline/tiebreak_winner need in_progress else 409 `MATCH_NOT_IN_PROGRESS`; match_end needs in_progress else `ILLEGAL_TRANSITION`, and level score + !draw_allowed + no active tiebreak winner → 409 `TIE_NOT_ALLOWED`.
7. Model limits (score only, active events only): innings → team `balls ≥ ipt·opi·bpo` or `wickets ≥ ipt·wpi` → 409 `INNINGS_COMPLETE`; round → any team `rounds ≥ (best_of+1)/2` → 409 `SERIES_ALREADY_DECIDED`.
8. Append: `sequence = version+1`, signature = `sign(event_id, payload)`, store response, outbox `match.event_appended` (+ `match.status_changed` for start/end) → realtime broadcast.
9. 201 `{event_id, match_id, type, sequence, match_version, signature}`.

> Ordering note: step 4 (400 needing the match) runs after 404 and before 409, following the global 400→404→409 rule (organizer clarification §17).

### 9.4 `GET /audit/v1/score/{match_id}`
Bad id → 400; missing → 404 `MATCH_NOT_FOUND`; else projection (always rebuilt from log). `version` = event count.

### 9.5 `GET /audit/v1/standings/{league_id}` (`domain/standings.js`, pure)
- Missing → 404 `LEAGUE_NOT_FOUND`.
- Played = completed and outcome ≠ undecided; everything else = remaining (incl. undecided, in_progress, upcoming).
- Per team: played, won (incl. tiebreak wins), drawn, lost, scored, conceded, points, fair_play (active discipline events in completed+counted matches? → **all completed league matches**), diff:
  - event: scored − conceded · round: rounds won − rounds lost
  - innings: NRR = runs_scored/overs_faced − runs_conceded/overs_bowled; overs = legal_balls / bpo (decimal); innings all-out (wickets ≥ wickets_per_innings·innings_per_team) counts as full `overs_per_innings·innings_per_team`; overs bowled = opponent's overs faced; zero overs → term 0; **round to 3 decimals**.
- h2h[a][b] for every ordered pair a≠b: `{played, points, scored, conceded}` (zeros when never met).
- Ordering (exact):
  1. Sort by points desc → groups of equal points.
  2. `resolve(group)`: for criterion in `tie_break_order` + forced trailing `draw_lots`: compute values over the group (h2h_points / h2h_diff over matches **among current group members only**; overall_diff, scored, fair_play over all played matches; draw_lots = lotsKey ascending is best). If not all equal → split into sub-groups by value (best first), each sub-group of size > 1 → `resolve` again from the first criterion; record the splitting criterion between adjacent teams; stop.
  3. `resolved_by` of a team = criterion that separated it from the next team in its points group; null if not tied or last in its group.
  - Compare numbers with an epsilon of 1e-9 (NRR values compared after the same 3-dp rounding used in output).
- Qualification (points only): `max(T) = points + W·R(T)`, k = spots. clinched if `|{X≠T : max(X) ≥ points(T)}| < k`; else eliminated if `|{X≠T : points(X) > max(T)}| ≥ k`; else still_possible.
- Response: `{league_id, sport_id, qualification_spots, table[{rank, team_id, played, won, drawn, lost, scored, conceded, diff, points, fair_play, status, resolved_by}], h2h}`.
- Compensation after qualification (edge case 5) works automatically: everything recomputed from logs every call.

---

## 10. Store — checkout & inventory (NFR-3)

### `POST /audit/v1/checkout`
1. **400**: order_id Id; customer_id free text 1–200; items 1–10, each `{variant_id Id, quantity int 1..10}`, unique variant_ids; discount_code null or string; payment.token string.
   - Unknown token value → 400 `VALIDATION_ERROR` (`payment.token`).
2. Acquire **session advisory lock** on `hashtextextended(order_id)` using a dedicated pool client (held through step 7, released in `finally`).
3. **Idempotency**: order exists → identical `request_canonical` → return stored `response` with **200** if stored status was 201, otherwise the stored status (e.g. 402); different body → 409 `DUPLICATE_ORDER_ID`.
4. **404** `VARIANT_NOT_FOUND` (first missing in request order, `details.variant_id`).
5. **400** `INVALID_DISCOUNT_CODE` if code not null and not an active code.
6. **Tx1 (reserve)**: for items sorted by variant_id: `UPDATE variants SET stock=stock-$q, reserved=reserved+$q WHERE variant_id=$v AND stock >= $q RETURNING price`. Any 0-row result → `ROLLBACK` → 409 `OUT_OF_STOCK` `details:{variant_id}` (nothing reserved). Else insert `orders(status 'pending')`, `order_items` (unit price from price schedule), `reservations(held, expires_at now()+60s)`; `COMMIT`.
7. **Mock gateway** (`services/paymentGateway.js`, in-process, deterministic):
   - `tok_success` → authorize ok (`auth_{n}`), capture ok (`cap_{n}`); ids from a Postgres sequence reset by `/reset`.
   - `tok_declined` → authorize fails.
   - `tok_capture_fail` → authorize ok, capture fails → void authorization.
   - Also supports `refund(capture_id)` for the production refund flow.
8. **Tx2**:
   - Success: `UPDATE variants SET reserved=reserved-$q` (sorted), reservations → committed, order → `paid`, payments row `captured`, status history, build receipt payload `{order_id, customer_id, items[{variant_id, quantity, unit_price_minor}], subtotal_minor, discount_minor, total_minor, currency:"PKR", paid_at}`, signature, PDF (pdfkit, in-memory), store full response, outbox `order.status_changed` (pending→paid) + `receipt.generated` (worker uploads PDF to MinIO `receipts/{order_id}.pdf`). → **201**.
   - Failure: `UPDATE variants SET stock=stock+$q, reserved=reserved-$q`, reservations → released, order → `cancelled` (payment_failed), payment row, outbox event, store response → **402** `PAYMENT_DECLINED` / `PAYMENT_CAPTURE_FAILED`. Released stock purchasable again.
9. Money: `subtotal = Σ q·unit`, `discount = floor(subtotal*10/100)` (integer math), `total = subtotal − discount`.
10. PDF content (text-extractable, Helvetica): title, `Order: {order_id}`, customer (non-printable/non-Latin-1 chars replaced with `?` for the PDF only), item lines, `Total: PKR {major}.{minor2}` where major = `Math.floor(total/100)`, minor = `String(total%100).padStart(2,'0')` → e.g. `PKR 4500.00`. Must start with `%PDF`.
11. Crash safety: worker sweeper every 5 s releases `held` reservations past `expires_at` (stock += q, reserved −= q) — guarantees `reserved` returns to 0.

Burst: 20 concurrent → row lock on the variant serialises the conditional UPDATE → exactly one 201, nineteen 409, `stock 0`, `reserved 0`, all within milliseconds.

### `GET /audit/v1/inventory/{variant_id}`
`{variant_id, stock, reserved}`; missing → 404 `VARIANT_NOT_FOUND`.

### Order lifecycle (FR-2.3, production API)
State machine: `pending → paid | cancelled`; `paid → packed | refunded | cancelled`; `packed → shipped | refunded`; `shipped → delivered`; `delivered → refunded`. Illegal → 409 `ILLEGAL_TRANSITION`. Each transition writes `order_status_history` + outbox `order.status_changed` → Redis Stream. Refund calls mock gateway refund and restocks (non-digital items).

---

## 11. Rate limiting (exact to spec)

- Scope: **only** `/api/v1/*` (includes `/api/v1/ping`). Exempt: `/audit/v1/*`, `/health`, `/ws`.
- Limit: **100 requests per rolling 10 s per client IP**. Client IP = `req.socket.remoteAddress` (do **not** trust `X-Forwarded-For` — spoofable, OWASP API4).
- Algorithm: Redis sliding-window log via one Lua script (atomic):
  ```
  ZREMRANGEBYSCORE key 0 (now-10000)
  count = ZCARD key
  if count < 100: ZADD key now uniqueMember; PEXPIRE key 10000; return {1, 0}
  else: oldest = ZRANGE key 0 0 WITHSCORES; retry_ms = oldest+10000-now; return {0, retry_ms}
  ```
  Only **allowed** requests are recorded → after the burst, 11 s later the window is empty → 200 again ✓.
- Denied → 429 `RATE_LIMITED`, header `Retry-After: ceil(retry_ms/1000)` (min 1), `details:{retry_after_seconds}`.
- Also set `X-RateLimit-Limit`, `X-RateLimit-Remaining`.
- Redis down → identical in-memory sliding window fallback.
- Grader probe: 150 req in 5 s → first 100 → 200 `{"pong":true}`, next 50 → 429 (≥ 40 ✓).
- Extra (OWASP API2): `/api/v1/auth/login` additionally 10 attempts / 5 min per IP+email, account lock 15 min after 5 failures.

---

## 12. Auth & RBAC (FR-5.1, §5.13)

### Tokens
- Access JWT HS256, `exp` = 12 h (≥ 1 h required), claims: `sub`, `role`, `tenant_id`, `league_ids`, `club_id`, `player_id`, `iat`, `exp`, `jti`.
- Verify with `jwt.verify(token, secret, { algorithms: ['HS256'] })` + require `exp` present. `alg:none`, tampered signature, expired, malformed, missing header, non-Bearer → **401 `UNAUTHENTICATED`**.
- Production API: `/api/v1/auth/login` (email+password → access + refresh), `/auth/refresh` (rotating, hashed in DB), `/auth/logout` (revoke).

### Demo users (seeded every startup and reset)
| email | role | scope |
|---|---|---|
| admin@nascon.test | super_admin | platform |
| organizer@nascon.test | league_organizer | tenant-alpha, leagues [league-alpha-1] |
| manager@nascon.test | club_manager | tenant-alpha, club club-alpha-1 |
| player@nascon.test | player | tenant-alpha, player player-alpha-1 (club-alpha-1) |
| viewer@nascon.test | viewer | tenant-alpha |
Password `Nascon@26`.

### `POST /audit/v1/rbac-matrix`
Mint a token per role from the seeded users; return `tokens{super_admin, league_organizer, club_manager, player, viewer}` and the exact `context` object of 5.13.

### Policy matrix (stored in `role_policies`, evaluated by `domain/rbac.js`)
| action | super_admin | league_organizer | club_manager | player | viewer |
|---|---|---|---|---|---|
| tenant.create (P1) | any | — | — | — | — |
| league.fixtures.write (P2) | any | own_league | — | — | — |
| club.roster.write (P3) | any | — | own_club | — | — |
| club.products.write (P4) | any | — | own_club | — | — |
| player.update (P5) | any | — | own_club_player | self | — |
| club.read (P6) | any | same_tenant | same_tenant | same_tenant | same_tenant |
| platform.config.read (P7) | any | — | — | — | — |

### Probe evaluation order
1. Authenticate → 401.
2. Role has no policy for the action → **403 `FORBIDDEN`**.
3. Scope `any` → 200 `{"authorized":true}`.
4. Resolve resource (league / club / player) from DB → missing → **404** (`LEAGUE_NOT_FOUND` / `NOT_FOUND`-family code per resource; clubs use `NOT_FOUND`, players `PLAYER_NOT_FOUND`).
5. Tenant mismatch → 403 (never 404 for existing foreign ids). Scope check (own_league ∈ league_ids, own_club = club_id, own_club_player = player.club_id == club_id, self = player_id) → 200 or 403.
- Probes never validate or read the body, never mutate.
- Same policy engine guards every production `/api/v1` route (BOLA/BFLA protection) — tenant filter is applied in every repository query (`WHERE tenant_id = $tenant`) for non-super-admins.
- Negative tests: one Jest test per cell of the matrix + 401 cases (no header, tampered, alg none, expired).

---

## 13. Roster (FR-2.2, §5.11) — `domain/roster.js` (pure rules) + `services/rosterService.js`

### `POST /audit/v1/roster/setup`
- 400: league `{league_id, tenant_id, sport_id, transfer_window{open, close} (open ≤ close), max_squad_size?, required_positions?}`; squads `[{squad_id, club_id, tenant_id, name}]` unique; players `[{player_id, name, position, squad_id (must be a listed squad or null), role ∈ captain|vice_captain|starter|substitute}]` unique; matches_in_progress `[{match_id, home_squad_id, away_squad_id}]` (squads must be listed).
- 404 `SPORT_NOT_FOUND`.
- Tx: lock league row; delete that league's squads/registrations/roster_initial/roster_live_matches/roster_audit/roster_requests rows; upsert league (defaults from sport roster config), squads, players, registrations (`initial`), roster_initial, live matches. Tenants/clubs need not pre-exist (stored verbatim).
- 201 `{"league_id","status":"configured","squad_counts":{every squad: count}}`.

### `POST /audit/v1/roster/transfer` — checks in EXACT order
1. 400 `VALIDATION_ERROR`: ids, type enum, dates valid, `to_squad_id` required unless release, `loan_end_date` required and `> effective_date` for loan, `from ≠ to`, reason/requested_by strings.
   - Idempotency: `request_id` exists → identical body → 200 stored; different → 409 `DUPLICATE_REQUEST_ID`.
2. 404: `LEAGUE_NOT_FOUND` → `PLAYER_NOT_FOUND` → `SQUAD_NOT_FOUND` (from, then to; squads must belong to this league).
3. 409 `CROSS_TENANT_TRANSFER` if from or to squad tenant ≠ league tenant.
4. Materialise any loan returns due (`loan_end_date < effective_date`), then 409 `NOT_IN_SQUAD` if player's current squad ≠ from.
5. transfer/loan: effective_date outside `[open, close]` inclusive → 409 `TRANSFER_WINDOW_CLOSED`.
6. Player currently on loan (active loan covering effective_date) → 409 `PLAYER_ON_LOAN`.
7. transfer/loan: count(to) + 1 > max_squad_size → 409 `SQUAD_FULL`. Counts: current members (loaned-in counted at borrower, loaned-out not at lender).
8. Required count for the player's position in from squad after removal < minimum → 409 `MIN_POSITION_VIOLATION`.
- Effects (one tx under league lock): transfer → squad = to, role substitute, type transfer, start = effective_date; loan → squad = borrower, parent = lender, type loan, loan_end set, role substitute; release → squad null, registration null in response.
- Edge case 6: player's from squad is home/away in `roster_live_matches` → `state:"deferred_until_match_end"`, `blocking_match_id` = that match; still accepted; else `active`, null.
- Audit entry `au-%04d` from a sequence (reset restarts), `who = requested_by`, `when` = server time, `why = reason`. Outbox `roster.changed`.
- 201 `{request_id, status:"accepted", state, blocking_match_id, registration, audit_entry}`.

### `GET /audit/v1/roster/history/{player_id}?as_of=YYYY-MM-DD`
- Bad as_of → 400; unknown player → 404 `PLAYER_NOT_FOUND`. Default as_of = server UTC date.
- Materialise loan returns with `loan_end_date < as_of` (entry: action `loan_return`, from = borrower, to = lender, effective_date = loan_end+1, state active, who `system`, why `Loan ended`), once only (unique index).
- `current_squad_id` / `parent_squad_id` = replay of `roster_initial` + audit entries with `effective_date ≤ as_of`.
- `history` oldest-first. `league_id` = player's league (most recent setup).

---

## 14. Offline sync (§5.14, NFR-4) — `services/offlineSyncService.js`

- Envelope 400: device_id Id; actions array 1–100; each `{action_id Id, type ∈ score_event|roster_transfer, queued_at Timestamp, base_version int ≥ 0, payload object}`. Inner payload problems are **per-action** rejections (`VALIDATION_ERROR`), not a 400.
- Device advisory lock; stable sort by `queued_at` (ties: array order); `processed_order` = 1..n in processing order; results returned in **request order**.
- Per action (own transaction, calls the exact same services as `/events` and `/roster/transfer`):
  1. `(device_id, action_id)` already processed → `duplicate / skipped_duplicate`.
  2. score_event whose event_id exists with identical request → `duplicate`; roster_transfer whose request_id exists with identical body → `duplicate`.
  3. Referenced id unknown → `rejected / server_wins / *_NOT_FOUND`.
  4. `base_version > current version` → `rejected / server_wins / INVALID_BASE_VERSION`.
  5. Service rule failure → `rejected / server_wins / <that code>` (incl. `DUPLICATE_EVENT_ID`).
  6. Applied: `base_version == current` → `appended`; `<` → `rebased_append`.
  - current version: score_event = match event count; roster_transfer = player's audit entry count (after loan-return materialisation).
  - `server_version` = version after processing (unchanged for rejected/duplicate; 0 if entity unknown).
  - `signature` = event signature for applied score_event, else null.
  - Store result in `offline_actions` (status for later = duplicate).
- 200 `{device_id, results[...], summary{applied, rejected, duplicate}}`. Re-sending → all duplicate, nothing changes.
- Production route `/api/v1/sync` → same service (authenticated).

---

## 15. Production API `/api/v1` (powers the app; covers FR items not graded by script)

All JWT-protected (except login, ping), RBAC-checked, tenant-scoped, rate limited, paginated (`limit ≤ 100`).
- `GET /ping` → `{"pong":true}` (public, rate limited).
- Auth: `POST /auth/login`, `/auth/refresh`, `/auth/logout`, `GET /me` (role, tenant branding: logo, colors, name).
- Sports: `GET /sports`, `POST /sports` (super admin).
- Seasons/leagues/divisions/tournaments (FR-1.2): CRUD; promotion/relegation config; hybrid → `POST /leagues/{id}/knockout` seeds a single-elim bracket from group standings.
- Fixtures (FR-1.3): `POST /leagues/{id}/fixtures/generate {format, params}` (persists matches using §8), `GET /leagues/{id}/fixtures`.
- Match admin: `PATCH /matches/{id}/status` — state machine Upcoming→In-Progress|Postponed|Cancelled; Postponed→Upcoming; In-Progress→Completed|Abandoned|Postponed; terminal: Completed, Abandoned, Cancelled; illegal → 409 `ILLEGAL_TRANSITION`; broadcast over WS.
- Reschedule: `PATCH /matches/{id}/schedule {scheduled_at, venue_id}` → conflict detector returns 409 `SCHEDULE_CONFLICT` with `details.conflicts[{type ∈ venue_double_booking|team_overlap|player_overlap|min_rest|venue_unavailable, entity_ids[], match_ids[]}]`. Postponing a bracket match clears downstream slots fed by it and re-runs conflict detection.
- Scoring: `POST /matches/{id}/events` (same service as audit), `GET /matches/{id}/score`.
- Standings: `GET /leagues/{id}/standings` (cached).
- Clubs (FR-2.1): `GET /clubs/{id}` (info, venue coords, socials, staff, roster, season stats), `PATCH /clubs/{id}`.
- Roster: `POST /clubs/{id}/roster/transfer` (same service), `GET /players/{id}/history`.
- Store: `GET /products`, `GET /products/{id}`, `POST /checkout` (same service), `GET /orders/{id}`, `GET /orders/{id}/receipt.pdf` (streamed from MinIO; fallback to stored base64), `PATCH /orders/{id}/status`, `POST /orders/{id}/refund`.
- Pins: `POST /matches/{id}/pins {product_id}` → WS `product.pinned` to match subscribers.
- Sync: `POST /sync`.

### Realtime (`/ws?token=…`)
- Authenticate on upgrade (401 → close 4401). Client sends `{"subscribe":"match:m1"}` / `league:…`; server checks read permission.
- Messages: `match.event_appended`, `match.status_changed`, `product.pinned`, `standings.updated`.
- Fan-out via Redis pub/sub channel `rt:{topic}` so any API replica can deliver. Heartbeat ping 25 s.

---

## 16. Security checklist (OWASP API Top 10 / Mobile)
- API1 BOLA: every resource access passes `rbac.check(user, action, resource)` + tenant filter in SQL.
- API2 Broken auth: bcrypt, HS256 pinned, exp required, refresh rotation, login throttling + lockout.
- API3 BOPLA / mass assignment: zod picks only allowed fields; explicit response DTOs (no password_hash ever).
- API4 Resource consumption: 1 MB body, rate limit, pagination caps, statement timeout, max 100 actions/sync, max 128 bracket teams.
- API5 BFLA: policy matrix per route.
- API6 Sensitive flows: idempotency on checkout; stock CHECK constraints.
- API7 SSRF: no user-supplied URLs fetched.
- API8 Misconfig: helmet, no stack traces, no `x-powered-by`, non-root container, pinned images.
- API9 Inventory: only documented routes; 404 JSON for others.
- API10 Unsafe consumption: mock gateway only.
- Injection: parameterised SQL only; strings returned as plain JSON data.
- Secrets: salt only from env; `.env` git-ignored; `.env.example` placeholder.
- Mobile: Keystore-backed encrypted token storage, no tokens in logs, cleartext only for the configured LAN host.

---

## 17. Clarifications to submit to organizers in writing (before 13:00)
1. Exact `scoring_events` keys for the seeded sports (`two_pointer`, `dot_ball`, `no_ball`, … with underscores?).
2. RBAC probe on a **non-existent** id: 404 or 403? Does super-admin get 200 or 404?
3. `/events`: when `team_id` is not a participant **and** the match is not in progress, which wins — 400 or 409?
4. Roster `request_id` replay with a different body — which error code?
5. Offline sync precedence between `INVALID_BASE_VERSION` and a rule rejection for the same action.
6. Round-robin `forfeit_remaining` when both teams are withdrawn but only one has passed its `after_round`.
7. NRR tie-break comparisons: rounded (3 dp) or full precision?
8. `match_init` with an unknown `league_id`: 404 `LEAGUE_NOT_FOUND` or accepted?

---

## 18. Testing, coverage, traceability
- `backend/src/domain/**` = pure logic per area folder: `fixtures/`, `scoring/`, `standings/`, `store/`, `roster/`, `rbac/` → Jest unit tests in `tests/unit/<area>/`, no DB needed.
- Integration tests (`tests/integration/`, need compose up) for concurrency burst, idempotency, RBAC matrix, rate limit — skipped automatically when `DATABASE_URL` unreachable.
- `run_tests.sh`: if `node` exists → `cd backend && npm ci && npx jest --coverage`; else `docker run --rm -v $PWD:/w -w /w/backend node:20-alpine sh -c "npm ci && npx jest --coverage"`. Then `scripts/coverage-to-json` writes `COVERAGE.json` `{fixtures, scoring, standings, store, roster, rbac}` from `coverage-summary.json` per area folder (line %). Declared numbers must equal the real ones (−2 pp tolerance).
- Must-have tests: Appendix A vectors; RR properties n=2..40 legs 1/2 + withdrawals; Swiss round-1 exactness, rematch-free optimality vs brute force, NO_VALID_PAIRING; single-elim n=6 example; double-elim graph checks n=3..17 + GF2 required; projection for all 3 models + compensation + undecided; standings 3-way mini-league, resolved_by, draw_lots vector, clinched/eliminated, NRR all-out; roster each of the 8 checks + loan return once + deferred; pricing/discount floor; RBAC every matrix cell + 401 cases; rate limiter window maths.
- `TRACEABILITY.md`: table FR-1.1, FR-1.2, FR-1.3, FR-1.4, FR-2.1, FR-2.2, FR-2.3, FR-5.1, NFR-3, NFR-4, NFR-5, NFR-7 → source files → test files.
- `README.md`: `DAY1_SALT=<salt> docker compose up -d --build`, health URL, run tests, the 5 demo accounts, mobile server-address setting.

---

## 19. Backend source layout
```
backend/
  Dockerfile  package.json  package-lock.json
  seed/sports.json
  src/
    server.js  worker.js  app.js  config.js
    db/ pool.js  migrate.js  schema.sql  tx.js (withTransaction + retry)
    middleware/ bodyParser.js  rateLimit.js  auth.js  errors.js
    http/ AppError.js  validators.js (zod primitives)
    domain/
      crypto.js  canonical.js
      fixtures/ roundRobin.js  swiss.js  blossom.js  singleElim.js  doubleElim.js
      scoring/ projection.js  eventRules.js
      standings/ standings.js  qualification.js
      store/ pricing.js  orderStateMachine.js  receiptPdf.js
      roster/ rules.js  replay.js
      rbac/ policy.js
    repos/ sports.js matches.js events.js leagues.js roster.js store.js users.js outbox.js offline.js
    services/ resetService.js eventService.js standingsService.js checkoutService.js paymentGateway.js
              rosterService.js rbacService.js offlineSyncService.js scheduleConflicts.js realtime.js
    routes/ health.js audit/*.js api/v1/*.js
tests/ unit/<area>/*.test.js  integration/*.test.js
```

---

## 20. Build order (push at least once in every window: 11:45–12:45, 12:45–13:45, 13:45–14:45; final ≤ 15:05)
1. Skeleton: compose, Dockerfile, migrations, seed, `/health`, `/reset`, error handler, body limit, canonical+sign with vector tests → **push**.
2. Sports + leagues + events + score projection.
3. Checkout + inventory + mock gateway + PDF + outbox/worker.
4. RBAC matrix + probes + JWT + rate limiter + `/api/v1/ping` → **push**.
5. Roster setup/transfer/history.
6. Standings.
7. Fixtures: round robin, single elim → Swiss (blossom) → double elim → **push**.
8. Offline sync.
9. `/api/v1` production routes + WebSocket for the app.
10. Tests to ≥ 85 % per area, COVERAGE.json, TRACEABILITY.md, README → **final push ≤ 15:00**.

---

## 21. Deviations from design

Refinements made while building that supersede the text above:

- **Checkout** uses a single pooled connection per request for the advisory lock and both transactions (not a separate lock client). Only 201 and 402 results are stored for replay; 400/404/409 results are not, so the same `order_id` can succeed later.
- **Receipt PDF** uses the built-in Courier font (not Helvetica) with one text call per line, so text extractors return ids intact. Non-Latin-1 characters become `?` in the PDF only.
- **Login lockout** is 5 failures for 60 s (`LOGIN_LOCK_SECONDS`), not 15 minutes. Auth routes were built in M3.
- **MinIO** is opt-in via the compose `storage` profile because the pinned upstream image is no longer published. The api and worker never depend on it.
- **Schema**: `response` columns are `json` (not `jsonb`) to preserve key order; `role_policies` is `(role, action, scope)`; mobile read/write actions were added to the policy table without changing the seven probe rows.
- **Rate limiter** keeps a short circuit-breaker on Redis failures (2 s) before falling back to the in-memory window; the Redis command timeout is 200 ms.
- **RBAC probes** run authentication and the role check before validating path ids, and tolerate malformed bodies on `/audit/v1/rbac*` so authentication always runs first.
- **Admin API (FR-1.2, FR-1.3, FR-2.1, FR-2.3)** is implemented under `/api/v1` with these refinements:
  - Policy rows were added without touching P1..P7: `season.read`, `league.write`, `sport.write`; `season.write` and `league.create` gained `league_organizer / same_tenant`. Fixture generation and knockout seeding reuse `league.fixtures.write` (organizer of the league or super admin). `topUpDefaults()` inserts missing default rows at boot (`ON CONFLICT DO NOTHING`), so an older database picks them up without a reset.
  - `leagues.parent_league_id` was added to the schema (it was in the data model but not in the DDL). An organizer who creates a league is appended to `users.league_ids` and owns it from the next login or refresh.
  - Generated match ids are `<league_id>:<generator id>` (generator ids such as `R1M1` are not unique across leagues). A league may hold at most one set of fixtures; `replace:true` is allowed only while no match has events (409 `FIXTURES_EXIST` with `details.has_events`).
  - Only matches with known participants are stored (`matches.home_team_id`/`away_team_id` are NOT NULL): no byes, no voided matches, no later bracket rounds, no unplayed swiss rounds. Winners are not advanced automatically.
  - Hybrid leagues deal their teams into `hybrid.groups` groups by position (`index % groups`); `fixtures/generate` creates one round robin per group (`bracket_slot.group`). `POST /leagues/{id}/knockout` needs every group match completed (409 `STANDINGS_UNRESOLVED`), seeds group winners first, then runners-up (by points, difference, scored, group), and stores the bracket in the same league with `bracket_slot.stage = 'knockout'`; those matches are excluded from the league table. A plain `league` uses one group and `qualification_spots` qualifiers.
  - Replays: `POST /seasons`, `POST /leagues` and `POST /sports` with the same id and an identical body answer 200 with the stored resource; a different body is 409. `GET /clubs/{id}/stats` was not added (the stats are in `GET /clubs/{id}`).
- **Still not implemented**: automatic advancement of winners into later bracket rounds and the creation of later swiss rounds.
