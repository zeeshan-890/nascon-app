# Platform admin: contract additions

All endpoints below are **super_admin only** (anyone else gets `403 FORBIDDEN`). They sit under `/api/v1`, use the usual
auth header and the usual error envelope `{ "error": { "code", "message", "details" } }`.
Client: `data/platformadmin/PlatformAdminApi.kt`. Demo behaviour: `FakePlatformAdminApi`.

## Endpoints that already exist on the backend (used for real)

| Method | Path | Used by |
|---|---|---|
| GET | `/api/v1/clubs` | G07 all clubs (via `clubsListRepository`) |
| GET | `/api/v1/sports/{id}` | G10 sport settings: full definition (name, scoring_model, periods, scoring_events, draw_allowed, overtime, tie_break_order, roster). The app expects the shape in `backend/seed/sports.json` |
| GET | `/health` (NOT under /api/v1, public) | G16 -> `{ "status": "ok" }`, or 503 `NOT_READY` while starting |
| GET | `/api/v1/ping` | G16 -> `{ "pong": true }` |

## New endpoints

### POST /api/v1/admin/clubs
Request: `{ "name": string, "tenant_id": string, "venue_name": string, "city": string|null }`
Response 201: `{ "club_id", "name", "tenant_id", "venue_name", "city" }`
Errors: `422 VALIDATION_ERROR`, `404 TENANT_NOT_FOUND`, `409 CLUB_EXISTS` (case-insensitive name already used in the tenant).

### GET /api/v1/admin/sports
Response: array of full sport definitions (same shape as `GET /sports/{id}`). Without it the app falls back to the sports
its leagues use (read-only).

### PUT /api/v1/admin/sports/{id}
Replaces the whole sport definition (the app sends the full object, including every scoring event).
Request/response: sport definition. Scoring events are `scoring_events: { "<snake_case_key>": { "<points|runs|rounds>": number, ...other fields kept as-is, "label": string (optional), "needs_player": boolean (optional) } }`.
Event key rule: `^[a-z][a-z0-9]*(_[a-z0-9]+)*$`, max 40 chars. Roster: `0 <= min_squad_size <= max_squad_size`.
Errors: `422 VALIDATION_ERROR`, `404 SPORT_NOT_FOUND`. Should write an audit entry (`platform.sport_updated`).

### GET /api/v1/admin/access
Response: `{ "policies": [ { "role", "action", "scope" } ] }` (same rows as `DEFAULT_POLICIES` in `backend/src/domain/rbac/defaultPolicies.js`).
Scopes: `any`, `same_tenant`, `own_league`, `own_club`, `own_club_player`, `self`. A role/action pair with no row means "not allowed".

### PUT /api/v1/admin/access
Request/response: `{ "policies": [...] }` (full replacement).
Errors: `422 VALIDATION_ERROR` for unknown roles, or when it would remove any `super_admin` / `any` row (lock-out guard).
Should write an audit entry (`platform.access_updated`).

### GET /api/v1/admin/audit?actor=&action=&from=&to=&limit=&offset=
- `actor`: actor user id. `action`: full action key (`score.corrected`) or a category (text before the first dot: `score`, `roster`, `user`, `fixture`, `order`, `platform`). `from` / `to`: inclusive `YYYY-MM-DD` days (server time zone UTC).
- Newest first. Response: `{ "items": [AuditEntry], "total": number }` (`total` = all matches, for paging).

AuditEntry: `{ "id", "at" (ISO-8601 instant), "actor_id", "actor_name", "actor_role", "action", "target", "summary", "request_id", "reason": string|null, "before": object|null, "after": object|null }`.
`summary` is plain language ("Score corrected"). `before` / `after` are flat or nested JSON snapshots of the changed fields.

### GET /api/v1/admin/audit/{id}
Response: one AuditEntry. `404 NOT_FOUND` when unknown.
