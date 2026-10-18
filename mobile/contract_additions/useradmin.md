# Contract additions: user administration

The backend has no user-management endpoints today (only `POST /api/v1/auth/login|refresh|logout` and `GET /api/v1/me`).
The mobile app (`data/useradmin`, `ui/useradmin`) expects the following. All require a bearer token and the
`super_admin` role; any other role gets `403 FORBIDDEN`. Errors use the standard envelope
`{"error":{"code","message","details"}}`. JSON is snake_case.

Until these exist the app shows "Not available on this server yet" (it detects 404 "Route not found", 405 and 501).
Demo mode uses an in-memory fake with the same behaviour.

Register `GET /api/v1/admin/users/options` before `GET /api/v1/admin/users/:id`.

## User object
```
{ "user_id": "u-ayesha", "email": "ayesha@club.pk", "name": "Ayesha Khan",
  "role": "super_admin|league_organizer|club_manager|player|viewer",
  "tenant_id": "tenant-alpha"|null, "tenant_name": "Alpha Sports"|null,
  "club_id": "club-alpha-2"|null, "club_name": "Alpha Club 2"|null,
  "league_ids": ["league-alpha-1"], "league_names": ["Alpha Premier League"],
  "active": true, "last_sign_in_at": "2026-10-17T09:14:00Z"|null }
```

## Endpoints
| Method and path | Request | Response | Notes |
|---|---|---|---|
| `GET /api/v1/admin/users?query=&role=` | query: case-insensitive match on name or email; role: one role string | `{ "items": [User], "total": n }` | sorted by name |
| `GET /api/v1/admin/users/options` | none | `{ "clubs": [{id,name,tenant_id}], "leagues": [{id,name,tenant_id}] }` | choices for assignment |
| `GET /api/v1/admin/users/{id}` | none | User | 404 `USER_NOT_FOUND` |
| `POST /api/v1/admin/users` | `{ name, email, temporary_password (min 8), role, club_id?, league_ids? }` | User (201) | 409 `EMAIL_TAKEN` if the email exists; 422 `VALIDATION_ERROR` |
| `PATCH /api/v1/admin/users/{id}/role` | `{ role, club_id?, league_ids? }` | User | 422 `VALIDATION_ERROR` |
| `POST /api/v1/admin/users/{id}/deactivate` | none | User with `active=false` | revoke refresh tokens; user can no longer sign in; history kept |
| `POST /api/v1/admin/users/{id}/activate` | none | User with `active=true` | |

Validation: role must be one of the five roles; `club_manager` requires `club_id`; `league_organizer` requires at least one
`league_ids`; `super_admin` has no tenant. Reject deactivating or changing the role of the calling admin's own account
(`403 FORBIDDEN`); the app also blocks this on the device.
