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
