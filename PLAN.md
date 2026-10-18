# NaSCon '26 Part I — Module-by-Module Execution Plan

Companion to `DESIGN.md` (contracts and algorithms). This file says **what to build in which order, what every module must handle, and when it is done.**

Clock: now ≈ 12:25. Hard lock 15:00 (final push ≤ 15:05). Judges from 15:15.
Push windows still open: **≤ 12:45**, 12:45–13:45, 13:45–14:45, final ≤ 15:00.

---

## PART 0 — Rules both developers follow

1. **Freeze the mobile API contract now** (§B0). The mobile developer builds against it with fake data from minute one. The backend implements it by 13:50.
2. Every module ends with: tests pass → commit → push. Never hold work locally for more than 40 minutes.
3. Backend developer runs **two Claude Code sessions in parallel**:
   - **Session A (stateful core):** M0 → M1 → M2 → M3 → M4 → M5 → M7 → M8 → M9.
   - **Session B (pure algorithms, no DB, separate folder `src/domain/fixtures/`):** M6 fixtures from 12:45. It cannot conflict with Session A because it touches only its own folder and its tests.
4. Every scenario listed under a module becomes a Jest test. This is what produces ≥ 85 % coverage per area (that is worth 10 % of each backend area).
5. When something is ambiguous, follow the decision in `DESIGN.md` §17 and move on. Do not stall.

---

## PART A — BACKEND (one developer + Claude Code)

### Timeline

| Time | Session A | Session B | Push |
|---|---|---|---|
| 12:25–12:45 | M0 Skeleton | — | **push ≤ 12:45** |
| 12:45–13:10 | M1 Sports + Events + Score | M6a Round-robin + Single elim | |
| 13:10–13:30 | M2 Store / Checkout | M6b Swiss (blossom) | |
| 13:30–13:45 | M3 RBAC + JWT + Rate limit | M6c Double elim | **push ≤ 13:45** |
| 13:45–14:05 | M4 Mobile API `/api/v1` + WebSocket + demo seed | M6 tests to 85 % | |
| 14:05–14:25 | M5 Roster | — | |
| 14:25–14:40 | M7 Standings | — | **push ≤ 14:45** |
| 14:40–14:50 | M8 Offline sync | — | |
| 14:50–15:00 | M9 Coverage, COVERAGE.json, TRACEABILITY, README, clean-machine run | — | **final ≤ 15:00** |

Why this order: M0–M3 contain everything the grader touches first (health, reset, error format, signatures) and the highest-certainty points (checkout and RBAC are mechanical). M4 is moved early because the mobile app (14 points) depends on it. Standings depend on the event store from M1.

---

### M0 — Skeleton & infrastructure (blocks everything)

**Build:** docker-compose (postgres, redis, minio, api, worker), Dockerfile, config loader, DB pool + transaction helper with retry, migrations, seed, `/health`, `/reset`, body parser, error handler, `canonical()` + `sign()` + `lotsKey()`, 404 fallback, worker stub (outbox relay).

**Scenarios to handle**
- `DAY1_SALT` read verbatim (spaces or symbols preserved, no trim); missing → process exits with a clear log.
- Postgres not ready yet → API retries the connection, does not crash.
- api and worker start at the same time → migrations guarded by an advisory lock.
- `/health` before the seed completes → 503; after → 200 `{"status":"ok"}`.
- `/reset` with no body, `{}`, `{"seed":7}`, `{"stock":{"var-jersey-001-M":0}}`.
- `/reset` with bad values: `seed:"x"` or `seed:1.5` → 400; stock `-1` or `"2"` → 400; unknown variant key → 404 `VARIANT_NOT_FOUND`.
- `/reset` twice in a row → identical state; sequences restart (`au-0001`, `auth_1`).
- Malformed JSON → 400 `VALIDATION_ERROR` with `fields:["body"]`; a top-level array or string body → 400.
- Body > 1 MB → 413 `PAYLOAD_TOO_LARGE`; exactly 1 MB → allowed.
- Missing or wrong `Content-Type` → still parsed as JSON.
- Unknown route → 404 JSON error body (never HTML).
- Any thrown error → JSON error body, never a stack trace, never HTML.
- Signature test vectors 1 and 2 from Appendix A pass.

**Done when:** `DAY1_SALT=x docker compose up -d --build` works from a fresh clone, `/health` is 200 within 120 s, both vectors pass → **push**.

---

### M1 — Sports, leagues, event store, score projection (areas: scoring + feeds standings)

**Build:** `POST /sports`, `POST /leagues`, `POST /events`, `GET /score/{id}`, the pure projection, event-rule checks, outbox event + realtime publish hook.

**Scenarios to handle**
- **Sports**
  - Each of the 3 models valid → 201 with echoed config.
  - Duplicate → 409 `SPORT_EXISTS`.
  - Bad `sport_id` (uppercase, 1 character, 41 characters, SQL-injection text) → 400.
  - Even `best_of`, `best_of: 0`, empty `scoring_events`, bad event key, wrong value shape for the model → 400.
  - `tie_break_order` with a repeat or unknown token → 400; with `draw_lots` omitted → stored with it appended.
  - roster `min > max` → 400.
  - `details.fields` lists **all** bad fields.
  - A mystery sport is usable immediately in `/events`, `/leagues` and `/standings`.
- **Leagues**
  - Sport missing → 404.
  - League exists → 409 `LEAGUE_EXISTS`; any `match_id` already used → 409 `MATCH_EXISTS`.
  - Fixture team not in `teams`, home == away, 1 team or 41 teams, duplicate teams → 400.
  - `spots` 0 or > n → 400.
  - `lots_seed` default comes from the last reset seed.
- **Events: state machine**
  - `match_init` → creates the stream; second `match_init` → 409 `MATCH_EXISTS`; identical replay of the same `event_id` → 200.
  - `match_start` on `upcoming` → ok; on `in_progress` or `completed` → 409 `ILLEGAL_TRANSITION`.
  - `score`, `discipline`, `tiebreak_winner` on `upcoming` or `completed` → 409 `MATCH_NOT_IN_PROGRESS`.
  - `match_end` on `upcoming` → 409 `ILLEGAL_TRANSITION`.
  - `match_end` with a level score and draw not allowed: no tiebreak → 409 `TIE_NOT_ALLOWED`; with an active tiebreak → ok.
  - Events on a league fixture work without `match_init` (version starts at 0).
- **Events: validation**
  - `team_id` not a participant → 400.
  - Unknown `event_type` → 400 `UNKNOWN_EVENT_TYPE`.
  - Unknown match → 404 `MATCH_NOT_FOUND`; unknown sport in `match_init` → 404 `SPORT_NOT_FOUND`.
  - Wrong payload types (for example `points:"-1"`) → 400.
- **Compensation**
  - Of score, discipline and tiebreak events → ok, including after `match_end`.
  - Of a compensation, `match_start` or `match_init` → 409 `INVALID_COMPENSATION`.
  - Twice → 409; target in another match → 409; target missing → 404 `EVENT_NOT_FOUND`.
  - Two compensations of the same target in parallel → exactly one succeeds.
- **Model limits**
  - Innings: legal balls reach `overs × balls` → next score → 409 `INNINGS_COMPLETE`; wickets reach the limit → 409.
  - Innings: wides and no-balls add runs but not balls.
  - Innings: a compensated ball no longer counts toward the limit.
  - Round model: a team reaches `(best_of+1)/2` → any further score → 409 `SERIES_ALREADY_DECIDED`.
- **Idempotency**
  - Same `event_id` with the same body → 200 with the identical stored response.
  - Same `event_id` with a different body, or used in another match → 409 `DUPLICATE_EVENT_ID`.
  - Two identical requests in parallel → one 201, one 200.
- **Projection**
  - `overs` formatted as `"3.2"`.
  - `wickets`, `balls` and `overs` are null for non-innings models.
  - Outcomes covered: `pending`, `home_win`, `away_win`, `draw`, and `undecided` (tiebreak compensated after `match_end`).
  - The latest active tiebreak wins.
  - `compensated` and `compensated_by` flags are correct.
  - The `events` list includes everything, in sequence order.
  - `version` equals the event count.
  - The signature of every listed event equals the one returned at append time.
  - Non-ASCII text in a payload (for example `reason:"گول"`) signs exactly like Python's reference.

**Done when:** all scenarios above are unit-tested (projection and rules are pure) and the integration happy path passes.

---

### M2 — Store, checkout, inventory (area: store, NFR-3)

**Build:** checkout service (advisory lock, reserve transaction, mock gateway, commit or release transaction), receipt payload, signature and PDF, inventory route, reservation sweeper in the worker, order state machine, outbox events.

**Scenarios to handle**
- **Concurrency and stock**
  - 20 parallel buyers on stock 1 → exactly one 201, nineteen 409 `OUT_OF_STOCK`, then `stock 0`, `reserved 0`.
  - Stock 5 with 20 parallel buyers of quantity 1 → five 201.
  - A multi-item order where the second item lacks stock → 409 naming that `variant_id`, and the first item's stock is unchanged.
  - Two orders locking the same two variants in opposite request order → no deadlock (sorted locking).
- **Mock gateway**
  - `tok_declined` → 402 `PAYMENT_DECLINED`, stock restored and buyable again.
  - `tok_capture_fail` → 402 `PAYMENT_CAPTURE_FAILED`, authorization voided, stock restored.
  - Unknown token → 400.
- **Idempotency**
  - Identical replay of a successful order → 200 with the identical body (same signature and PDF), no extra stock taken.
  - Replay of a declined order → the same 402.
  - Same `order_id` with a different body → 409 `DUPLICATE_ORDER_ID`.
  - The same `order_id` fired 5 times in parallel → one charge.
- **Validation order**
  - Bad quantity (0, 11, 1.5) → 400; 0 or 11 items → 400; duplicate `variant_id`s → 400.
  - Unknown variant → 404, and this is checked **before** the discount code.
  - Bad discount code → 400 `INVALID_DISCOUNT_CODE`.
- **Money and receipt**
  - `WELCOME10` on 450000 → discount 45000, total 405000; odd subtotal → floor rounding.
  - `customer_id` containing quotes or `<script>` → stored and echoed verbatim; the PDF does not crash.
  - Receipt signature recomputed from `receipt.payload` matches.
  - The PDF starts with `%PDF`, and its text contains the `order_id` and `PKR 4500.00`.
  - A total of 405000 prints as `PKR 4050.00`, with no thousands separator.
- **Infrastructure**
  - Inventory for an unknown variant → 404.
  - The worker receives `order.status_changed`; the PDF is uploaded to MinIO; checkout still works if MinIO is down.
  - The sweeper releases an orphaned `held` reservation after it expires.

---

### M3 — RBAC, JWT, rate limit, security (area: rbac)

**Build:** user seed with a bcrypt hash computed once, JWT sign/verify, policy matrix table + engine, `/rbac-matrix`, the 7 probe routes, `/api/v1/ping`, the Redis sliding-window limiter with in-memory fallback, helmet, login throttling.

**Scenarios to handle**
- **Matrix and tenancy**
  - All matrix cells: 5 roles × each probe target = about 50 assertions as one table-driven test.
  - Foreign-tenant IDs → 403 (never 404).
  - P6 on own and other clubs in the same tenant → 200 for every role, including viewer.
  - P5: manager on `player-alpha-1` → 200, on `player-alpha-2` → 403; player on self → 200, on others → 403.
  - Probes ignore the body completely (`{}`, garbage, or empty).
- **Token failures → 401 `UNAUTHENTICATED`**
  - No header; `Bearer` with an empty token; `Basic …`.
  - Tampered signature; `alg:none` token; expired token; a token signed with a different secret; a non-JWT string.
- **Rate limit**
  - 150 pings in 5 s → requests 1–100 get 200, 101–150 get 429 with `Retry-After`.
  - 11 s later → 200.
  - `/audit/*` at 200 requests/s → never 429.
  - The limiter is keyed by socket IP; `X-Forwarded-For` is ignored.
- **Injection and response hygiene**
  - Injection strings in any ID → 400, never 5xx.
  - No `password_hash` in any response.

---

### M4 — Mobile API `/api/v1`, WebSocket, demo seed (unblocks the app)

**Build:** the contract in §B0: login, refresh, me, list and detail routes as thin wrappers over the M1/M2 services, `/sync` (stub until M8, then the real service), WebSocket hub with Redis pub/sub, demo seed.

**Demo seed (amendment to DESIGN.md §7.2)**

The judge's tasks need real data, and the default stock is only 1 jersey. Run this seed at startup and at the end of every `/reset`. All IDs are prefixed `demo-` so they never collide with grader IDs.
- **league-alpha-1** (football)
  - 4 teams: `demo-team-1`..`demo-team-4`, named via club records.
  - 6 fixtures; 3 completed (with events), 1 `in_progress` (`demo-m-live`, for the scoring judge), 2 upcoming.
  - Standings are therefore non-trivial.
  - `transfer_window` 2026-10-01..2026-10-31, so today (Oct 17) is open.
  - `max_squad_size` 25.
- **Roster scenario on league-alpha-1**
  - Squads `demo-sq-alpha-1` (club-alpha-1) and `demo-sq-alpha-2` (club-alpha-2).
  - 8 players each, including `player-alpha-1`, with 2 goalkeepers each, so transfers never break the minimum and squads are never full.
- **Extra products** (tenant-alpha)
  - `demo-prod-cap` and `demo-prod-scarf`, stock 100 each, so judge purchases always succeed.
  - One "digital" item.
- **Club profiles** for club-alpha-1 and club-alpha-2: venue coordinates, socials, staff (coach, manager, physio), one historical season of stats.
- **Pinned product:** `demo-prod-cap` pinned on `demo-m-live`.

**Scenarios to handle**
- Login wrong password → 401 with a plain message; the 6th failed attempt → locked (429).
- Token expired → the app refreshes the token; refresh revoked → 401 → the app sends the user back to login.
- A list endpoint for a league in another tenant → 403.
- Empty lists return `[]` (never 404).
- WebSocket:
  - Connect without a token → closed with code 4401.
  - Subscribe to a foreign match → error message.
  - A score event appended through any route → pushed within 1 s.
  - A product pin → pushed.

---

### M5 — Roster, transfers, loans, history (area: roster)

**Build:** setup, transfer (the 8 ordered checks), loan-return materialisation, `as_of` replay, idempotency.

**Scenarios to handle**
- **The 8 checks in order** — each check has a test where it is the **first** failure while later ones would also fail. This proves the order. The checks: validation, league/player/squad not found, cross-tenant, not in squad, window closed, on loan, squad full, minimum position.
- **Dates and windows**
  - `effective_date` on the window's open day and its close day → allowed (inclusive); one day outside → 409.
  - A release outside the window → allowed.
  - Loan `end <= effective` → 400; loan without an end date → 400.
  - Transfer with `to == from` → 400; release with `to_squad_id` given → ignored.
- **Loans**
  - Player counts at the borrower and not at the lender.
  - Lender drops below its position minimum because of a loan → 409.
  - Transfer, loan or release of a player on loan → `PLAYER_ON_LOAN`, or `NOT_IN_SQUAD` if `from` is the lender. Check 4 runs before check 6.
  - History `as_of` before the loan → lender; during → borrower with `parent` set; after → lender with exactly one `loan_return` entry, even after 3 calls or parallel calls.
- **Effects and audit**
  - Captain transferred out → the old squad's captain slot is vacant; the player is a substitute in the new squad.
  - Player in `matches_in_progress` → accepted with `deferred_until_match_end` and `blocking_match_id`.
  - Rejected requests write no audit entry.
  - Replay → 200 with an identical body; different body → 409.
  - `audit_id` runs `au-0001…`; `who`/`why` are echoed verbatim; `when` uses the `Z` format with no milliseconds.
- **Setup**
  - Re-running setup for the same league wipes that league only.
  - Squad counts are correct; defaults come from the sport's roster config.

---

### M6 — Fixtures (area: fixtures — 4 algorithms, the largest single area). Session B, pure code.

**M6a — Round-robin**
- Property tests for every n from 2 to 40, legs 1 and 2:
  - Rounds per leg: n−1 (even n) or n (odd n); ⌊n/2⌋ matches per round.
  - One bye per round for odd n, and exactly one bye per team per leg.
  - Each pair meets `legs` times; with legs = 2 each ordered (home, away) pair occurs once.
  - Home/away difference ≤ 1 per team per leg.
  - Global round numbering; `match_id` format `RR-L{leg}-R{round}-M{index}`.
- Withdrawals:
  - `after_round` 0 → every fixture of that team is forfeit (or voided).
  - `after_round` = `round_count` → no effect.
  - Two withdrawn teams meeting → voided.
  - Unknown team, `after_round` −1 or > `round_count`, or the same team twice → 400.
  - `summary` counts are correct; byes are untouched.

**M6a — Single elimination**
- Seed order `order(8) = [1,8,4,5,2,7,3,6]` exactly; the n = 6 example matches the spec exactly.
- Byes propagate immediately; n = 2 → one match.
- `results` given out of order are still applied; a winner who is not a participant → 400; an unknown `match_id` → 400.
- Third-place match: present only when B ≥ 4; semifinal `loser_to` points to TP.

**M6b — Swiss**
- Round 1 is exact for even and odd n.
- History validation: a missing round, extra round, or wrong order → 400.
- `score` and `buchholz` are exact (byes contribute 0 to opponents' Buchholz).
- Standings order: score, then Buchholz, then seed.
- Bye goes to the lowest-scoring team without a bye; ties go to the largest index.
- If the remaining teams can't be paired, the bye moves to the next candidate.
- Rematch-free and minimum total score difference — verified against brute force for random n ≤ 10.
- Constructed impossible case → 409 `NO_VALID_PAIRING` with `details.round`.
- n = 64 completes in under 100 ms.

**M6c — Double elimination**
- n from 3 to 17: losers-bracket round counts and match counts per round; total losers matches = B−2.
- Graph checks:
  - Every non-final match has a `winner_to`; every winners-bracket match has a `loser_to`.
  - Every slot except WR1 has exactly one feeder.
  - WR final loser → losers final, away slot; losers final winner → GF1 away slot.
- GF2 `required`:
  - Without reset: no GF2.
  - With reset before GF1 has a result: `null`.
  - With reset, winners champion wins GF1: `false`.
  - With reset, losers champion wins GF1: `true`.
- Byes in WR1 match single elim; a match whose other source is dead auto-advances its team.

---

### M7 — Standings (area: standings)

**Scenarios to handle**
- Points use custom `win/draw/loss` values.
- Ties:
  - 2-way tie decided by head-to-head points.
  - 3-way tie where the mini-league splits 1 + 2, and the pair then restarts from the first criterion (the restart-on-split rule).
  - All criteria equal → `draw_lots` decides, matching the Appendix A vector 2 order.
- `resolved_by` correct for every team in a tied group; null for untied teams and the last team in a group.
- Match states:
  - An undecided match (compensated tiebreak) counts as remaining, not played.
  - A win decided by tiebreak counts as a win.
- Differential by sport model:
  - Round model: map differential.
  - Innings model: net run rate, with an all-out innings counted as full overs and rounded to 3 decimals; zero overs → no division error.
- Head-to-head matrix: every ordered pair is present, with zeros when the teams never met.
- Qualification status:
  - Clinched, eliminated and still-possible cases for 4 teams with 2 spots.
  - Status flips after a compensation on a completed match (edge case 5).
- A league with no completed matches → all zeros, ordered by draw lots.
- A mystery sport league works end to end.

---

### M8 — Offline sync

**Scenarios to handle**
- Actions out of `queued_at` order → processed sorted; results returned in request order; `processed_order` correct.
- `base_version` equal → `appended`; lower → `rebased_append`; higher → `INVALID_BASE_VERSION`.
- Rule failure → the matching code; unknown match or player → the matching `*_NOT_FOUND`.
- The same `event_id` already present with an identical body → `duplicate`; with a different body → rejected `DUPLICATE_EVENT_ID`.
- Re-sending the whole request → every action duplicate, state unchanged.
- One bad action does not roll back the others.
- 0 or 101 actions, or a bad `queued_at` → 400; a bad inner payload → per-action `VALIDATION_ERROR`.
- `server_version` and `signature` follow the rules in DESIGN.md §14.

### M9 — Release hygiene (do not skip — the penalties are large)

1. Fresh clone into a new folder → `DAY1_SALT=<salt> docker compose up -d --build` → `/health` 200 → run a smoke script against all endpoints.
2. `./run_tests.sh` → copy the real per-area line coverage into COVERAGE.json (never declare more than the real figure).
3. TRACEABILITY.md mapping FR-1.1, 1.2, 1.3, 1.4, 2.1, 2.2, 2.3, 5.1 and NFR-3, 4, 5, 7.
4. README: run command, the 5 demo accounts, tests, mobile server-address setting.
5. Grep `backend/src` for `football|basketball|cricket|esports|\bgoal\b|two_pointer|three_pointer|free_throw|dot_ball|no_ball|round_win` → must return nothing. (`wicket`/`wickets` are spec schema field names and are allowed.)
6. `git grep <salt>` → must return nothing.
7. Final push before 15:00.

---

## PART B — MOBILE (Kotlin, one developer)

### B0 — Frozen API contract (backend implements in M4)

All calls send `Authorization: Bearer <access>` except login and ping. Errors use the standard `{error:{code,message,details}}` body.

| Call | Response (key fields) |
|---|---|
| `POST /api/v1/auth/login {email,password}` | `access_token, refresh_token, expires_in, user{user_id,email,role,tenant_id,league_ids[],club_id,player_id}, tenant{tenant_id,name,logo_url,primary_color,secondary_color}` |
| `POST /api/v1/auth/refresh {refresh_token}` | same token fields |
| `GET /api/v1/leagues` | `[{league_id,name,sport_id,season}]` |
| `GET /api/v1/leagues/{id}/fixtures` | `[{match_id,home_team_id,home_name,away_team_id,away_name,status,scheduled_at,venue_name,home_score,away_score,version}]` |
| `GET /api/v1/matches/{id}` | the `/audit/v1/score` projection + `pinned_products[]` |
| `POST /api/v1/matches/{id}/events` | same body/response as `/audit/v1/events` |
| `GET /api/v1/leagues/{id}/standings` | same as `/audit/v1/standings` + `team_names{}` |
| `POST /api/v1/auth/logout {refresh_token}` | `{}` |
| `GET /api/v1/sports/{id}` | sport config (used to generate scoring buttons) |
| `GET /api/v1/clubs/{id}` | `club_id,name,tenant_id,logo_url,venue{name,lat,lng},socials{},staff[{name,role}],stats[],roster[{player_id,name,position,role,squad_id,league_id,on_loan,audit_version}],squads[{squad_id,name,club_id}]` |
| `POST /api/v1/clubs/{id}/roster/transfer` | same as `/audit/v1/roster/transfer` |
| `GET /api/v1/products` | `[{product_id,name,category,image_url,variants[{variant_id,size,color,price_minor,stock}]}]` |
| `POST /api/v1/checkout` | same as `/audit/v1/checkout` |
| `GET /api/v1/orders/{id}/receipt.pdf` | PDF bytes |
| `POST /api/v1/sync` | same as `/audit/v1/offline-sync` |
| `GET /api/v1/ping` | `{pong:true}` (connectivity probe) |
| `WS /ws?token=…` | send `{"subscribe":"match:{id}"}` → receive `{type, match_id, data}` |

### Mobile timeline

| Time | Module | Uses backend? |
|---|---|---|
| 12:25–12:50 | F0 Foundation | no (fake repository) |
| 12:50–13:10 | F1 Login, session, role-aware home, navigation | fake |
| 13:10–13:30 | F2 Room cache + repository + connectivity banner | fake |
| 13:30–13:55 | F3 Fixtures, match detail (live), F4 standings | switch to real API at ~13:50 |
| 13:55–14:15 | F5 Club profile, F6 Store + receipt | real |
| 14:15–14:35 | F7 Roster management, F8 scoring entry, F9 offline queue + sync screen | real |
| 14:35–14:45 | **Build release APK, commit, push** (safety copy) | |
| 14:45–14:55 | F10 HCI polish pass, rebuild APK, push | |
| 15:00–15:15 | Install the APK on the demo phone/emulator; rehearse the 3 judge tasks | |

### F0 — Foundation
- Compose + Material 3; `minSdk 29`; one `Activity`; Navigation Compose with a bottom bar (Fixtures, Standings, Store, Club/Profile) plus a Settings screen.
- Theme driven by tenant colors, with a default theme for super admin. Fixed type scale (body ≥ 16 sp) and spacing tokens (8 dp grid). One shared button style.
- Settings screen: server URL (default `http://10.0.2.2:8080`), with a "Test connection" button that calls `/ping`.
- `network_security_config` allowing cleartext HTTP.
- Retrofit/OkHttp with an auth interceptor (attach token; on 401 refresh once, then log out).
- One error mapper turns `error.code` into plain language (table in F10).
- `release` buildType signed with the debug key, so `assembleRelease` gives an installable APK.

### F1 — Login & role-aware home
- **Scenarios:**
  - Empty email or password → inline errors.
  - Wrong password → "Email or password is incorrect".
  - Locked account → "Too many attempts, try again in a few minutes".
  - Server unreachable → "Can't reach the server — check the address in Settings" with a link to Settings.
  - Loading spinner on the button; the button is disabled while loading.
  - Tokens are stored in EncryptedSharedPreferences, never in logs.
  - **App reopened offline with a saved session → goes straight to home** (the judge force-closes the app while offline).
  - Logout asks for confirmation and clears tokens and private cache.
- **Role-aware home:**

  | Role | Sees |
  |---|---|
  | super_admin | everything, read-only plus admin actions |
  | organizer | their league, fixtures, scoring entry |
  | manager | their club, roster management, store |
  | player | their fixtures, their club profile, store |
  | viewer | fixtures, standings, clubs, store (no write buttons at all) |

  Hide actions a role can't use; don't just disable them.

### F2 — Local persistence & connectivity (NFR-4 core)
- Room entities: `LeagueEntity`, `FixtureEntity`, `MatchEntity` (projection JSON + version), `StandingsEntity` (JSON per league), `ClubEntity` (JSON), `ProductEntity`, `PendingActionEntity`, `SyncResultEntity`.
- Repository pattern: return Room data immediately, refresh from the network, write the result to Room. The UI observes Room flows, so cached screens open instantly offline.
- Connectivity state:
  - `NetworkCallback` combined with a `/ping` reachability check every 15 s gives Online / Offline / Server unreachable / Syncing.
  - Always-visible banner at the top on every screen, with icon **and** text (never color alone), and a pending-count badge.
  - "Last updated 2 min ago" label on cached screens.

### F3 — Fixtures & match detail
- **Fixtures list:**
  - Grouped by status, with a status chip (icon + text) and the time.
  - Tapping a fixture opens the match.
  - Empty state: "No matches yet"; pull to refresh.
- **Match detail:**
  - The score is the largest element on screen.
  - Event timeline: compensated events struck through and labelled "Corrected".
  - Pinned product card with a "Buy" button that goes to the store variant.
  - Live updates over WebSocket; reconnect with backoff; when offline, show the cached score marked "offline".
- **Scenarios:** match not found → friendly message plus a back button; WebSocket drops → falls back to polling every 10 s.

### F4 — Standings
- League picker. Table columns: rank, team, P, W, D, L, Diff, Pts.
- Qualification status shown as a text badge ("Qualified", "Out", "In contention") plus an icon.
- Horizontal scroll inside the table on 360 dp screens; header row stays fixed.
- Cached offline.

### F5 — Club profile
- Logo, name, venue (name + coordinates, with an "Open in Maps" intent), socials, staff list, roster grouped by position with role badges, season stats.
- Cached offline. Empty roster → "No players registered yet".

### F6 — Store & receipt
- Catalogue grid → product page → variant picker (size/color chips, showing stock and "Out of stock" text) → cart (persisted in Room) → checkout.
- Checkout: discount-code field, totals shown in PKR (`PKR 4,500.00` for display), a confirmation dialog, then the result.
- The app generates the `order_id` (UUID) **once per checkout attempt** and reuses it on retry, so a network blip never charges twice.
- Mock payment token picker (Success / Declined / Capture fails), labelled clearly as a demo payment.
- Result messages:

  | Code | Message |
  |---|---|
  | 409 `OUT_OF_STOCK` | "Sorry, this item just sold out." |
  | 402 `PAYMENT_DECLINED` | "Your payment was declined. No money was taken." |
  | 400 `INVALID_DISCOUNT_CODE` | "That discount code isn't valid." |

- Receipt: download the PDF to app storage → render **in-app with `PdfRenderer`** (no dependency on a PDF viewer being installed) → share button.
- Offline: the Buy button is disabled with the explanation "Checkout needs an internet connection". Payments are never queued.

### F7 — Roster management (Club Manager)
- Roster list → choose a player → action sheet: Transfer / Loan / Release / Add.
- Form: destination squad picker, date pickers (default today), loan end date (must be after start, validated inline), reason.
- Confirmation dialog for release and transfer.
- The request is written to the **pending queue** (F9), whether online or offline — one code path. When online, the queue flushes immediately.

### F8 — Scoring entry (League Organizer)
- On an in-progress match:
  - Buttons generated **from the sport's `scoring_events` config** (never hard-coded), one per team.
  - Large tap targets (≥ 56 dp).
  - Each timeline event has an "Undo / Correct" action → confirmation → compensation event.
- Start-match and end-match buttons follow the state machine.
- Every action goes through the queue with a UUID `event_id` and `base_version` = the cached match version.
- Score updates show optimistically, marked "pending".

### F9 — Offline queue & sync (desk check)
- `PendingActionEntity` stores the exact 5.14 action: `action_id` (UUID), `type`, `queued_at` (UTC `Z` format), `base_version`, `payload`. `device_id` is a UUID created once and kept in preferences.
  - Score action: `base_version` = the match's cached `version`.
  - Roster action: `base_version` = that player's audit count from the club payload.
- Sync triggers:
  - A WorkManager one-time job when the network becomes available.
  - Each time the app comes to the foreground.
  - A "Sync now" button.
- Sync status screen (reached from the banner): pending list, then the results list after a sync.
  - Applied → "Saved ✓".
  - Rejected → plain reason, for example `SQUAD_FULL` → "The destination squad is already full, so this transfer was not made."
  - Duplicate → "Already saved earlier."
- The queue is cleared after a sync; results are kept for review. Afterwards the cache is refreshed from the server (server wins).
- **Desk-check rehearsal** (must pass):
  1. Open fixtures, standings and a club while online.
  2. Switch to airplane mode → the banner says "Offline" and all three screens open from cache.
  3. As organizer, add a score → shown as pending. As manager, transfer a player → shown as pending.
  4. Force-close the app and reopen it while offline → still logged in, both actions still pending.
  5. Turn the network on → it syncs automatically → per-action results shown → the queue is empty.

### F10 — HCI polish (10 judge points)
- **Navigation:** bottom bar with labels and icons; back always works; no dead ends; every list item is tappable with a ripple effect.
- **Consistency:** one color scheme from the tenant, one type scale, one button style; the primary action (Buy, Save) is a filled button and secondary actions are outlined.
- **Feedback:** loading indicators, a snackbar on success, a confirmation dialog before risky actions (release, compensation, checkout); no raw codes or "Error 500" anywhere.
- **Error message table:**

  | Code | Message |
  |---|---|
  | `UNAUTHENTICATED` | "Please sign in again" |
  | `FORBIDDEN` | "You don't have permission to do that" |
  | `TRANSFER_WINDOW_CLOSED` | "The transfer window is closed for that date" |
  | `PLAYER_ON_LOAN` | "This player is on loan and can't be moved" |
  | `MIN_POSITION_VIOLATION` | "This would leave the squad without enough {position} players" |
  | `NOT_IN_SQUAD` | "This player isn't in that squad anymore" |
  | `MATCH_NOT_IN_PROGRESS` | "This match isn't live, so scores can't be added" |
  | `INVALID_COMPENSATION` | "That event was already corrected" |
  | `RATE_LIMITED` | "Too many requests — try again in a few seconds" |
  | unknown or 5xx | "Something went wrong on the server. Please try again." |

- **Accessibility:**
  - `contentDescription` on every icon.
  - Tap targets ≥ 48 dp; text ≥ 14 sp (body 16 sp); WCAG AA contrast.
  - Status is never shown by color alone.
  - Supports font scaling.
- **Robustness:**
  - Test on 360×640 dp and in landscape.
  - `rememberSaveable` state so rotation keeps forms.
  - No text overflow (use ellipsis and scrolling).
  - Empty state on every list.

### F11 — APK delivery
- `./gradlew assembleRelease` → copy to `mobile/release/app-release.apk` → commit → push **by 14:45**, then again after the polish pass.
- Install it on the demo device in advance.
- Settings must show the laptop's LAN IP before the judge arrives; the phone and laptop must be on the same Wi-Fi.
- The laptop firewall must allow port 8080.

---

## PART C — Integration checkpoints (both developers)

| Time | Checkpoint |
|---|---|
| 13:50 | M4 is live on the backend laptop; mobile switches from fake to the real API; test with the emulator at `10.0.2.2`. |
| 14:35 | End-to-end rehearsal of judge tasks A, B and C on the real backend. |
| 14:50 | Freeze features; bug fixes only. |
| 15:00 | Final push (code + APK + docs). |
| 15:10 | `docker compose up` on the demo laptop, app installed, logged out, demo seed fresh (`POST /audit/v1/reset`). |
