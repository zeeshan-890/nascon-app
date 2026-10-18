# Live contract audit

Probed `http://localhost:8080` after `POST /audit/v1/reset` with `{"seed":7}` (200). Health stayed `200 {"status":"ok"}`.

Demo logins that returned 200 with password `Nascon@26`: `organizer@nascon.test` (used for most reads), `manager@nascon.test`, `viewer@nascon.test`, `admin@nascon.test`, `player@nascon.test`. Access JWT `expires_in` is the integer `43200`. JSON decoding in the app uses `AppJson` (`ignoreUnknownKeys = true`, no `coerceInputValues`), so a present JSON `null` does not fall back to a Kotlin default.

Pagination shape (leagues, fixtures, clubs, products): the body is a bare JSON array. There is no `next`, `items`, or `offset` envelope. `limit` and `offset` are query parameters. The total is the `X-Total-Count` header. `GET /api/v1/leagues?limit=1&offset=1` returned one row (the second league) with `X-Total-Count: 3`.

Times are UTC with no milliseconds, for example `2026-10-17T09:26:04Z`.

Verdicts: **MATCH** (no change), **FIXED-IN-APP** (Kotlin should change), **BACKEND-DEFECT** (see `BACKEND_ISSUES.md`).

## Auth

### `POST /api/v1/auth/login` — `LoginResponse` / `UserDto` / `TenantDto` — MATCH

`organizer@nascon.test` returned `access_token`, `refresh_token`, `expires_in` (int; Kotlin `Long` accepts it), `user`, and `tenant`.

Tenant branding on the organizer (and on `GET /me`): `logo_url` `/static/logo/tenant-alpha.png`, `primary_color` `#0B5FFF`, `secondary_color` `#FFB800`.

Nulls the DTOs already allow: admin `tenant` is JSON `null`; `user.tenant_id`, `club_id`, and `player_id` are null when the role has no scope; `league_ids` is `[]` or a string array.

Wrong password (`organizer@nascon.test` / `wrong-password`) is 401:

```json
{"error":{"code":"UNAUTHENTICATED","message":"Email or password is incorrect","details":{}}}
```

That matches `ErrorEnvelope`. `details` is `{}`. No `attempts_left`.

### `POST /api/v1/auth/refresh` — `RefreshResponse` — MATCH

200 body is only `access_token`, `refresh_token`, `expires_in` (int). Reusing a rotated token, or the token after logout, is 401 `UNAUTHENTICATED`.

### `POST /api/v1/auth/logout` — `JsonObject` — MATCH

200 body is `{}`.

### `GET /api/v1/me` — no Kotlin type; nested objects match `UserDto` and `TenantDto` — FIXED-IN-APP

`LeagueApi` does not declare this route. The live body is `{ "user": UserDto, "tenant": TenantDto | null }` with the same branding fields as login. Missing bearer is 401 `UNAUTHENTICATED`.

Suggested fix: add `MeResponse(user: UserDto, tenant: TenantDto? = null)` and `GET api/v1/me`. Do not make `logo_url` or the colour fields non-null beyond what `TenantDto` already does (`logoUrl` is already nullable; colours were non-null strings on `tenant-alpha`).

## Sports

### `GET /api/v1/sports` — no list DTO — BACKEND-DEFECT

404 `NOT_FOUND` / `Route not found`. `LeagueApi` only calls `GET api/v1/sports/{id}`, which works. No Kotlin change until a list route exists.

### `GET /api/v1/sports/{id}` — `SportConfigDto` — MATCH

`football` and `cricket` return `sport_id`, `scoring_model`, `scoring_events` (object), `draw_allowed`. `discipline` is absent, so the nullable default applies. Extra keys `name`, `roster`, `periods`, `overtime`, `tie_break_order` are dropped by `ignoreUnknownKeys`. Football scoring events: `{"goal":{"points":1}}`.

## Leagues and fixtures

### `GET /api/v1/leagues` — `List<LeagueDto>` — MATCH for fields; pagination cap is BACKEND-DEFECT

Rows have `league_id`, `name`, `sport_id`, and `season` (`"2026/27"` or JSON `null`). `LeagueDto.season` is already `String?`. The body is a bare array, which matches `List`. See pagination defect: `limit=150` was not clamped to 100.

### `GET /api/v1/leagues/{id}/fixtures` — `List<FixtureDto>` — MATCH

Six fixtures. Fields match the DTO, including int scores and int `version` (Kotlin `Long`). `scheduled_at` is UTC `Z` with no fractional seconds. `venue_name` is a string on every seeded row.

Live `status` values: `upcoming`, `in_progress`, `completed`. The DTO default `"scheduled"` is unused because the key is always present. `statusKind` already maps `upcoming` and `scheduled`, so no decoder change.

`limit=1&offset=0` and `offset=1` return different single rows and `X-Total-Count: 6`. `limit=250` returned all 6 (too few rows to show the cap; the leagues probe did).

## Matches

### `GET /api/v1/matches/{id}` — `MatchDetailDto` / `TeamScoreDto` / `MatchEventDto` / `PinnedProductDto` — MATCH

`demo-m-live`, `demo-m-1`, and `demo-m-5` decode into the DTO. Extra keys `scheduled_at` and `venue_name` are ignored.

- `status`: `in_progress` | `completed` | `upcoming` (same vocabulary as fixtures).
- `outcome`: `pending` while the match is open. `winner_team_id` and `tiebreak_winner_team_id` are null or a team id. Both are already nullable.
- Football `home` / `away`: `wickets`, `balls`, and `overs` are JSON `null` (already `Int?` / `JsonElement?`). `fair_play` is an int. `score` is an int. No cricket fixture was seeded, so a string `overs` value was not observed; `JsonElement?` would accept one.
- Events include `sequence`, `event_id`, `type`, `payload`, `signature` (64-char hex), `compensated` (boolean), `compensated_by` (null or an event id).
- After `POST` compensation of `audit-sync-goal`, that score event was `compensated: true`, `compensated_by: "audit-comp-sync"`. The compensation event itself stayed `compensated: false`.
- `pinned_products`: `product_id`, `name`, `price_minor` (int; Kotlin `Long`), `image_url`.

### `POST /api/v1/matches/{id}/events` — `MatchEventResponse` — MATCH

A valid score returned 201 `{event_id, match_id, type, sequence, match_version, signature}`. Compensation with `payload.compensates` and `payload.reason` returned the same shape. Viewer token on this route is 403 `FORBIDDEN` with `details: {}`, which matches `ErrorEnvelope`.

## Standings

### `GET /api/v1/leagues/{id}/standings` — `StandingsDto` / `StandingRowDto` — MATCH

`league-alpha-1`: `qualification_spots` 2, `sport_id` `football`, `team_names` is a string map. Each row has `rank`, the W/D/L counters, `points`, `fair_play`, `resolved_by` (null on this table), and `status` `still_possible`. `qualStatus` already maps `still_possible`. The DTO default `"active"` is unused.

`diff` is a JSON integer (`3`, not `3.0`). `StandingRowDto.diff` is `Double`. kotlinx.serialization 1.7.3 reads a JSON integer into `Double`, so this does not fail. `h2h` is an object of objects, for example `h2h["demo-team-1"]["demo-team-2"] = {played, points, scored, conceded}`, which matches `JsonObject`.

## Clubs and roster

### `GET /api/v1/clubs` — `List<ClubSummaryDto>` — MATCH

`club_id`, `name`, `logo_url` (string path). Bare array plus `X-Total-Count`.

### `GET /api/v1/clubs/{id}` — `ClubDto` — FIXED-IN-APP

`club-alpha-1` matches the DTO: venue `{name, lat, lng}` with numeric coordinates, `socials` object, `staff[{name, role}]`, `stats[{season, played, won, drawn, lost, scored, conceded}]`, `roster[{player_id, name, position, role, squad_id, league_id, on_loan, audit_version}]`, `squads[{squad_id, name, club_id}]`. Seeded positions are strings (`gk`, and so on), not null.

`GET /api/v1/clubs/club-beta-1` as admin is 200 with:

```json
"venue": {"name": null, "lat": null, "lng": null}
```

`socials` is `{}`, `roster` and `staff` and `stats` are empty, `logo_url` is a string. `VenueDto.lat` and `lng` are already nullable. `VenueDto.name` is `String = ""`. `AppJson` does not coerce null to that default, so decoding `ClubDto` throws on `venue.name`.

Organizer `GET /api/v1/clubs/club-beta-1` is 403 `FORBIDDEN` (tenant scope). That part matches `ErrorEnvelope`.

Suggested fix: `VenueDto.name` as `String? = null`.

### `POST /api/v1/clubs/{id}/roster/transfer` — `RosterTransferResponse` — MATCH

Manager transfer of `demo-pl-1-8` from `demo-sq-alpha-1` to `demo-sq-alpha-2` (`effective_date` `2026-10-17`) returned 201:

`request_id`, `status` `accepted`, `state` `active`, `blocking_match_id` null, `registration` object, `audit_entry` object. Both objects are `JsonObject`, so inner nulls (`parent_squad_id`, `loan_end_date`) are fine. `audit_entry.when` is `2026-10-17T09:58:33Z`.

### `GET /api/v1/players/{id}/history` — `PlayerHistoryDto` / `HistoryEntryDto` — MATCH

Body includes an extra `as_of` (`2026-10-17`), which is ignored. `parent_squad_id` null is already nullable. After the transfer, `history[0]` has `audit_id`, `action`, `from_squad_id`, `to_squad_id`, `effective_date`, `state`, `who`, `when`, `why`. Extra `player_id` and `league_id` on the entry are ignored. `loan_end_date` is omitted, so the nullable default applies. Viewer reading another player is 403 `FORBIDDEN`.

## Store

### `GET /api/v1/products` — `List<ProductDto>` / `VariantDto` — MATCH

Each product has `product_id`, `name`, `category` (string), `image_url`, and `variants[{variant_id, size, color, price_minor, stock}]`. `price_minor` and `stock` are ints (`Long` / `Int` in Kotlin). `demo-var-ebook` has `size: null` and `color: null`. Jersey variants have `color: null`. Those fields are already nullable. `limit=1&offset=1` returns one product and `X-Total-Count: 4`.

### `GET /api/v1/products/{id}` — no Kotlin method — BACKEND-DEFECT

`GET /api/v1/products/demo-prod-cap` and `GET /api/v1/products/prod-jersey-001` are 404 `Route not found`. The list payload already carries variants and stock, which is what `ProductDto` models. Do not add a detail call until the route exists.

### `POST /api/v1/checkout` — `CheckoutResponse` / `ReceiptDto` — FIXED-IN-APP (default only)

`order_id` `ord-audit-1`, one cap, `discount_code` `WELCOME10`, `payment.token` `tok_success`:

- First call 201, identical replay 200, same body. A different item list with the same `order_id` is 409 `DUPLICATE_ORDER_ID`.
- Body fields match: `order_id`, `status` `paid`, `currency` `PKR`, `items` (objects: `variant_id`, `quantity`, `unit_price_minor`), money ints, `payment` `{authorization_id, capture_id, state}`, `receipt` `{payload, signature, pdf_base64}`.
- `CheckoutResponse.currency` defaults to `"USD"`. The server always sends `"PKR"`, so the default is not applied today. Change the default to `"PKR"` so a missing key cannot be shown as dollars.
- Request `customer_id` `"ignored-customer"` is not echoed. `receipt.payload.customer_id` is the JWT user id (`user-viewer`). That does not affect decoding (`payload` is `JsonObject`).

### `GET /api/v1/orders/{id}` — `OrderDetailDto` — MATCH

`order_id`, `status` `paid`, `items` without `name` (nullable default), money fields, `currency` `PKR`, `paid_at` UTC. `created_at` and `status_history` are absent; both have defaults. Another user's token is 403 `FORBIDDEN`.

### `GET /api/v1/orders/{id}/receipt.pdf` — `ResponseBody` — MATCH

200, `Content-Type: application/pdf`, `Content-Disposition: inline; filename="receipt-ord-audit-1.pdf"`, body starts with `%PDF-1.3` (2075 bytes). It is not JSON. MinIO is not in the default compose profile; the PDF still streamed (stored fallback).

## Sync

### `POST /api/v1/sync` — `SyncResponse` — MATCH

One rejected action and one applied score:

`device_id`, `results[{action_id, status, resolution, error_code, processed_order, server_version, signature}]`, `summary {applied, rejected, duplicate}`.

`signature` and `error_code` are null on the rows that should be null (`String?`). Applied row: `status` `applied`, `resolution` `appended`, signature hex. Replay of the same device actions: both `duplicate` / `skipped_duplicate`, `summary.duplicate` 2. `queued_at` must be `YYYY-MM-DDTHH:MM:SSZ` (no milliseconds).

## WebSocket `/ws?token=`

### Frames — `RealtimeMessage` / `parseFrame` — MATCH

Bad token and empty token: the socket closes with code **4401**, reason `unauthorized`.

`subscribe` ack is `{"type":"subscribed","topic":"match:demo-m-live"}`. `unsubscribe` is accepted and sends no frame. An unknown topic is `{"type":"error","topic":"nope","code":"NOT_FOUND"}`. `parseFrame` drops those because they are not in `KNOWN_TYPES`. That matches the current parser.

Data frames observed after subscribe:

| type | shape |
|---|---|
| `match.event_appended` | `{type, match_id, data:{match_id, event_id, sequence, version, event_type}}` |
| `match.status_changed` | `{type, match_id, data:{match_id, status}}` with `postponed` then `upcoming` |
| `product.pinned` | `{type, match_id, data:{match_id, product_id}}` |
| `standings.updated` | `{type, data:{league_id}}` — no top-level `match_id` |

`RealtimeMessage.matchId` is nullable, and `data` is `JsonObject?`, so `standings.updated` parses. `league_id` is inside `data`.

## Failures

### 401 and 403 — `ErrorEnvelope` — MATCH

Wrong password: 401 `UNAUTHENTICATED`, `details: {}`. Missing bearer on `/me`: 401. Viewer scoring, viewer reading another player's history, organizer reading `club-beta-1`, and organizer reading another user's order: 403 `FORBIDDEN`, `details: {}`.

### 429 — `ErrorEnvelope` plus header — FIXED-IN-APP

After a quiet window, 100 `GET /api/v1/ping` calls returned 200 `{"pong":true}`. Request 101 was 429 with:

- Header `Retry-After: 10`
- Headers `X-RateLimit-Limit: 100`, `X-RateLimit-Remaining: 0`
- Body `{"error":{"code":"RATE_LIMITED","message":"Too many requests. Try again later.","details":{"retry_after_seconds":10}}}`

The burst was stopped on the first 429. The body matches DESIGN §11 and `ErrorEnvelope`. `retryAfterSeconds` / `lockedForSeconds` only read `retryAfterSeconds`, `retryAfter`, and `retry_after`. They do not read `retry_after_seconds`, and `ApiError` does not carry the `Retry-After` header, so the app treats this 429 as having no delay.

Suggested fix: also accept details key `retry_after_seconds` (integer) in `retryAfterSeconds` and `lockedForSeconds`.
