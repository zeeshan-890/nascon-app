# Part II interfaces

The Part II skeleton lives in `backend/day2/` (CommonJS, like the rest of the backend). Other modules plug in
without touching shared code.

**Ownership:** only Person 1 edits `backend/package.json`, `backend/package-lock.json` and the files in
`backend/day2/lib/`. Everyone else adds files in `backend/day2/routes/` and `backend/day2/reset/`.

## Folder rules

- `backend/day2/routes/<module>.js` is mounted automatically. Export an express `Router` that uses **absolute**
  paths (`router.get('/audit/v1/qr/:id', ...)`), or export `function (router) { ... }` to register routes on the
  router you are given. Files named `index.js` or starting with `_` are skipped. Every request served by these
  routes is marked `req.day2 = true`; errors thrown or passed to `next(err)` there are turned into
  `{ "error": { "code", "message", "details" } }` by the Part II error middleware. Throw `ApiError` for expected errors.
- `backend/day2/reset/<module>.js` may export `reset(ctx)` and/or `seed(ctx)` (async). `POST /audit/v1/reset` runs
  them inside its open transaction: first every `reset(ctx)` in alphabetical file order, then every `seed(ctx)` in
  alphabetical file order. `ctx = { client, seed, stock }`; use `ctx.client` for all SQL so it rolls back with the
  reset. Same skip rules as routes.

## Exports

### `backend/day2/lib/sign.js`
Key is `DAY2_SALT` (UTF-8 bytes, read at call time, never trimmed). Message is `entityId + "|" + canonical(payload)`.
Output is 64 lowercase hex characters.
- `canonical(obj) -> string` — keys sorted by code point at every level, no spaces, non-ASCII not escaped. Throws
  `TypeError` for non-integer numbers, `undefined`, functions and bigint.
- `sign(entityId, payload) -> hex` — throws `ApiError(500)` (no salt in the message) when `DAY2_SALT` is missing or empty.
- `signStreamKey({ action, player_id, status, stream_key })` — entity = `stream_key`.
- `signCostream({ active_host, handoff_occurred, layout, participant_count, session_id, status })` — entity = `session_id`; `active_host` may be `null`.
- `signQr({ entity_id, short_url, target_route })` — entity = `entity_id`.
- `signCartMerge({ cart_merged, guest_token, registered_user_id, total_items, write_rejection_code })` — entity = `guest_token`.
- `signModeration({ chat_room_id, flagged_count, kicked_users, queue_depth })` — entity = `chat_room_id`.

### `backend/day2/lib/errors.js`
- `class ApiError(status, code, message, details = {})`
- `sendError(res, status, code, message, details = {})` — writes `{ error: { code, message, details } }`.
- `markDay2` (middleware) and `errorMiddleware` (error middleware, mounted by the route loader). Malformed JSON ->
  400 `VALIDATION_ERROR` (`details.fields: ["body"]`), body over 1 MB -> 413 `PAYLOAD_TOO_LARGE`, anything unexpected ->
  generic 500 `INTERNAL_ERROR`. Errors from non-Part II routes are passed on unchanged.

### `backend/day2/lib/guestGuard.js`
- `getGuestToken(req) -> string | null` — a `Bearer` token (case-insensitive word, extra spaces fine) or an
  `X-Guest-Token` header that starts with `guest_`.
- `guestWriteGuard(req, res, next)` — mounted globally in `src/app.js` before body parsing, rate limiting and JWT
  checks. POST/PUT/PATCH/DELETE from a guest -> 403 `GUEST_WRITE_FORBIDDEN`, `details: { guest_token }`.
- `guestWriteAllowList` — array (empty) of exact paths or RegExps that later modules may push onto.

### `backend/day2/lib/metrics.js`
- `inc(name, n = 1)`, `set(name, v)`, `get(name)` — `nascon_active_ws_connections`, `nascon_active_streams_total`,
  `nascon_stream_bitrate_kbps`, `nascon_dlq_depth`, `nascon_qr_scans_total` all start at 0.

### `backend/day2/lib/locks.js`
- `withLock(key, fn) -> Promise` — one `fn` at a time per key (in-process queue + Postgres advisory lock). The lock
  is released when `fn` settles; errors are rethrown.
- `once(key, fn) -> Promise` — runs `fn` at most once per key and gives every caller the same result. A failed run
  is forgotten so it can be retried. The memo is per process.

### `backend/day2/routes/index.js`
- `mount(app, dir = __dirname)` — called once from `src/app.js`, after the Phase I routes.

### `backend/day2/reset/index.js`
- `run(ctx, dir = __dirname)` — called once from `src/services/resetService.js`.

## Match status mapping (Day 1 -> Part II)

Day 1 already uses the Part II names, so no translation layer is needed. A match's status is **derived**, never stored
as a single column:

| Part II status | How it is produced on Day 1 |
|---|---|
| `upcoming` | no `match_start` event and no admin status (also the state of a fresh league fixture) |
| `in_progress` | a `match_start` event exists and no `match_end` (`POST /audit/v1/events`, or `PATCH /api/v1/matches/{id}/status` to `in_progress`) |
| `completed` | a `match_end` event exists (`POST /audit/v1/events`, or the admin route to `completed`) |
| `postponed` | `matches.admin_status = 'postponed'` — set only by `PATCH /api/v1/matches/{id}/status` (from `upcoming` or `in_progress`; back to `upcoming` clears it) |
| `abandoned` | `matches.admin_status = 'abandoned'` — admin route only, from `in_progress` |
| `cancelled` | `matches.admin_status = 'cancelled'` — admin route only, from `upcoming` or `postponed` |

Effective status = the admin status when one is set, otherwise the status derived from the event log
(`readModelService.effectiveStatus`). Legal admin transitions are in `backend/src/domain/schedule/statusMachine.js`:
`upcoming -> in_progress | postponed | cancelled`, `postponed -> upcoming | cancelled`,
`in_progress -> completed | abandoned | postponed`; `completed`, `abandoned` and `cancelled` are terminal.
`GET /audit/v1/score/{match_id}` reports only the event-derived status (`upcoming | in_progress | completed`).

**Needs (for the QR module, which must resolve a match's current target):** `postponed`, `abandoned` and `cancelled`
exist only in `matches.admin_status`, not in the score projection. A QR resolver must read
`readModelService.effectiveStatus(match.admin_status, projectedStatus)` (or replicate: admin status wins). The audit
grader can only reach those three states through the admin route (a super_admin / own-league organizer token on
`PATCH /api/v1/matches/{id}/status`), so a hidden test that needs `postponed` must use that route.

## Base seed owned by M0

After `POST /audit/v1/reset` (and on first boot): `player-beta-2` in `club-beta-1`, and `match-m1` — football,
`league-alpha-1`, home `club-alpha-1`, away `club-alpha-2`, `in_progress`, 1 - 0 — created from real signed events
(`match-m1-seed-start`, `match-m1-seed-home-1`) so a later score event gives 2 - 0, `match_end` completes it and
standings stay correct. The reset response shape is unchanged.

## Health, web mount, limits and the guest guard (M0 step 2)

- **`GET /health`** (`backend/day2/health.js`): `200 {"status":"ok"}` only when Postgres, Redis and the baseline seed
  (and, when `MEDIA_HOST` is set, the media server) are reachable. Otherwise `503 {"status":"degraded","failing":[...]}`
  naming only the failing dependency (`postgres`, `seed`, `redis`, `media`). While the app is still booting it keeps the
  Phase I `503 NOT_READY` envelope. Each check has a hard 1 s timeout and the verdict is cached for about 2 s.
- **Media health switch:** set the environment variable **`MEDIA_HOST`** (host name of the media server, e.g.
  `mediamtx`) to require the media server. The check is a TCP connect to `MEDIA_HOST:8888` (override with
  `MEDIA_HEALTH_PORT`). Unset = media not required. **Needs (compose):** set `MEDIA_HOST` on the `api` service once the
  media container exists.
- **Web mount** (`backend/day2/routes/web.js`): serves the top-level `web/` folder at `/web/*` and returns
  `web/index.html` for `/web/matches/:id/{preview,live,vod,notice}`, `/web/players/:id`, `/web/clubs/:id`,
  `/web/costream/:id` and any other `/web/*` path without a file extension (HTML is `no-store`, assets
  `max-age=60`, no cookies, no redirects, dotfiles and path traversal denied). A missing asset file (path with an
  extension) is `404`. If `web/index.html` does not exist a tiny placeholder is served (never written to disk).
  Override the folder with `WEB_DIR`. **Needs (compose/Dockerfile):** the Docker build context is `./backend`, so
  `web/` is not inside the image; the compose session must copy or mount `web/` (and set `WEB_DIR` if the path differs).
- **Rate limit:** the 100 requests / 10 s per-IP limiter covers `/api/v1/*` only. `/audit/*`, `/q/*`, `/web/*`,
  `/metrics` and `/health` are exempt (tested with 300 rapid requests).
- **Body limits:** bodies over 1 MB -> `413 PAYLOAD_TOO_LARGE`; malformed JSON -> `400 VALIDATION_ERROR`
  `details.fields ["body"]`. Both come from the Phase I handler, which already returns the standard envelope on every
  path, so Part II paths use it unchanged. A route must itself reject an empty body, a non-object JSON body or a wrong
  content type with `400 VALIDATION_ERROR` (`details.fields`); a valid JSON object is parsed whatever its content type.
- **Guest guard:** runs first in the app. A `Bearer` token (any case, extra spaces, trailing space) or `X-Guest-Token`
  starting with `guest_` on POST/PUT/PATCH/DELETE -> `403 GUEST_WRITE_FORBIDDEN`, message
  `Anonymous guest tokens cannot perform write mutations.`, `details.guest_token`. It answers before body parsing, the rate
  limiter and JWT verification, so a guest token on a write never produces a `401`. GET/HEAD/OPTIONS are never blocked.
  A guest GET on a route that needs a member JWT gets that route's normal `401`; on an unknown route the normal `404`.
  **Allow-list rule:** a module that must let guests write (for example the guest cart or the guest session route) pushes
  an exact path string or a RegExp onto `guestWriteAllowList` from its own route file; never edit `guestGuard.js`.

## How to add a module

1. **Routes:** create `backend/day2/routes/<module>.js`. Export an express `Router` using absolute paths
   (`router.get('/audit/v1/qr/:id', ...)`), or `module.exports = (router) => { router.get(...) }`. The loader wraps your
   routes with `markDay2`, so anything thrown or passed to `next(err)` becomes `{ "error": { code, message, details } }`
   through the Part II error middleware; throw `ApiError(status, code, message, details)` for expected errors. Phase I
   handlers are untouched.
2. **Reset/seed:** create `backend/day2/reset/<module>.js` exporting `reset(ctx)` and/or `seed(ctx)`. They run inside the
   reset transaction: all `reset(ctx)` hooks first, then all `seed(ctx)` hooks, alphabetical by file name. `ctx =
   { client, seed, stock }` — use `ctx.client` for every query so a failure rolls the whole reset back. Reset already
   truncates **every** table in the `public` schema, so your own `day2_*` tables are emptied automatically; your seed only
   has to insert. Clear any Redis keys you own inside `reset()` (the reset service already clears `cache:*`, `lt:*` and
   `rl:*`).
3. **Tables:** create them with `CREATE TABLE IF NOT EXISTS` from your own `ensureSchema()`. Because reset truncates
   `public`, call `ensureSchema()` at the start of both your `reset()` and your first request.

## Route names agreed with the frontend (do not change without telling Person 1)

- Guest session: `POST /api/v1/guest/session` (owned by the QR/guest module).
- Realtime: `ws://<host>/ws/matches/{id}` and `GET /api/v1/matches/{id}/stream` (owned by the realtime step).
- Co-stream read: `GET /api/v1/costream/{session_id}`.

## Realtime channels (NFR-1) — `backend/day2/realtime/`, `backend/day2/routes/realtime.js`

All read-only channels are **unauthenticated**: a guest connects with no token. A token (member JWT, `guest_...` token or
junk) may be sent as `?token=` or `Authorization: Bearer` and is accepted and ignored for reads. The Day 1 hub
(`ws://<host>/ws?token=<jwt>`, `{"subscribe":"match:<id>"}`) is unchanged; both hubs share one upgrade dispatcher in
`src/realtime/hub.js`.

### URLs

| Channel | URL |
|---|---|
| Match, WebSocket | `ws://<host>/ws/matches/{match_id}` |
| Match, SSE | `GET /api/v1/matches/{match_id}/stream` (`text/event-stream`) |
| Co-stream / chat room, WebSocket | `ws://<host>/ws/costream/{session_id}` |

### Message envelope (every WebSocket text frame and every SSE `data:` line)

```json
{ "type": "match.event_appended", "match_id": "demo-m-live", "sequence": 14, "ts": "2026-10-18T07:41:02.118Z",
  "data": { "match_id": "demo-m-live", "event_id": "e-123", "sequence": 14, "version": 14, "event_type": "score",
            "status": "in_progress",
            "home": { "team_id": "demo-team-1", "score": 3 }, "away": { "team_id": "demo-team-4", "score": 1 },
            "home_score": 3, "away_score": 1 } }
```

- `type`, `match_id`, `data.match_id/event_id/sequence/version/event_type` are exactly the Day 1 `rt:match:*` payload;
  the score, status and `ts` fields are additive.
- Top-level **`sequence`**: integer, strictly increasing per match stream (events, status changes and reset snapshots all
  advance it); use it to order frames and as the SSE event id. **`data.sequence`** is the Day 1 match event number
  (1, 2, 3, ...) of the appended event. `ts` is the server time (ISO 8601 UTC) when the frame was produced.
- Types: `snapshot`, `match.event_appended`, `match.status_changed` (`data: { match_id, status }`; the status changes to
  `in_progress`, `completed`, `postponed`, `abandoned`, `cancelled`, or back to `upcoming`/`in_progress`).
- Delivery: events of one match are delivered in sequence order, never twice, and the score in successive frames never
  goes backwards. Both delivery paths (direct publish right after the database commit, and the worker's outbox relay as
  fallback) feed the same de-duplicating stream.

### Snapshot (first frame after connect, and again after `POST /audit/v1/reset`)

```json
{ "type": "snapshot", "match_id": "demo-m-live", "sequence": 14, "ts": "2026-10-18T07:41:00.002Z",
  "data": { "match_id": "demo-m-live", "status": "in_progress",
            "home": { "team_id": "demo-team-1", "score": 2 }, "away": { "team_id": "demo-team-4", "score": 1 },
            "home_score": 2, "away_score": 1, "version": 13, "league_id": "league-alpha-1" } }
```
A client that (re)connects between events is correct immediately. After a reset every connected client (WebSocket and
SSE) receives a fresh snapshot with a higher `sequence`.

### Co-stream / chat room: `ws://<host>/ws/costream/{session_id}`

Snapshot first: `{ "type":"snapshot", "session_id", "sequence", "ts", "data": { "session_id", "status", "active_host",
"host_player_id", "layout", "participants", "active_participants", "handoff_occurred", "last_chat_seq",
"recent_messages": [ { "seq", "message_id", "sender", "text", "flagged", "created_at" } ] } }` (last 50 messages).
Then live frames, same envelope with `session_id` instead of `match_id`:
`chat.message` (`data: { session_id, seq, message_id, sender, text, flagged?, created_at? }` — **`data.seq` is the room
sequence number**), `participant.joined`, `participant.left`, `host.handoff`, `session.status`.
**Needs (co-stream and chat modules):** publish with `require('../realtime').publishRoom(session_id, type, data)` (fire
and forget, never throws; de-duplicated by `data.message_id` / `data.event_id`) or `PUBLISH rt:costream:<session_id>
{"type":"...","data":{...}}` on Redis. Other publishing helpers in `backend/day2/realtime/index.js`:
`publishMatchEvent`, `publishMatchStatus`.

### Server-Sent Events: `GET /api/v1/matches/{match_id}/stream`

No auth. Headers: `Content-Type: text/event-stream; charset=utf-8`, `Cache-Control: no-cache, no-transform`,
`Connection: keep-alive`, `X-Accel-Buffering: no`, `Content-Encoding: identity` (never compressed). A comment line
`: keep-alive` is sent every 15 s. The first block is the snapshot (`event: snapshot`, no `id`). Every later block is
`id: <sequence>\nevent: <type>\ndata: <envelope json>`, the id equal to the envelope's top-level `sequence`. Send
`Last-Event-ID: <n>` on reconnect: after the snapshot the server replays buffered frames with `sequence > n` (buffer: the
last 200 frames per match, in memory), then continues live. `retry: 3000` is advertised.
Unknown match: `404 {"error":{"code":"MATCH_NOT_FOUND","message":"Match not found","details":{}}}`.

### Close codes and error frames

| Code | Meaning |
|---|---|
| `4404` | unknown match / session (reason `match not found` / `session not found`) |
| `1008` | more than 20 client messages per second |
| `1009` | client message larger than 4096 bytes |
| `1011` | internal error while subscribing |
| `1001` | server shutting down |

Client-to-server messages (all optional): `{"type":"ping"}` -> `{"type":"pong","ts"}`; `{"type":"resync"}` -> a fresh
snapshot; `{"subscribe":"match:<id>"}` / `{"unsubscribe":"match:<id>"}` (also `room:<id>`) add or drop streams, at most
10 per connection (`{"type":"error","code":"TOO_MANY_SUBSCRIPTIONS"}`). A write attempt (`chat`, `score`, `follow`, ...)
from a connection without a member token is answered with `{"type":"error","code":"GUEST_WRITE_FORBIDDEN"}` and is
not applied; the connection stays open. A member token gets `{"type":"error","code":"WRITE_NOT_SUPPORTED"}` (use REST).
Malformed JSON -> `{"type":"error","code":"INVALID_MESSAGE"}`.

### Hygiene

Server pings every 25 s and terminates sockets that did not pong; a connection whose send queue exceeds 1 MiB is
terminated (slow consumer); subscriptions are removed on close. `nascon_active_ws_connections` (GET /metrics) counts
Day 1 and Part II sockets: +1 on connect, -1 exactly once on close or error, never below 0, set to 0 when the hub is
closed. Measured latency (25 clients, in-process, direct publish): p50 35 ms, p95 61 ms, max 63 ms from sending the
request; p95 8 ms after the HTTP response. Probe: `tests/integration/day2.realtime.nfr1.test.js`
(`NFR1_BASE_URL=http://localhost:8080 npx jest ...` against the compose stack).
