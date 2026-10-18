# Contract additions: rosterplus

## GET /api/v1/players/{id}/stats  (NOT on the backend yet)
Used by the My stats screen (D02). Roles: player (own id only), club_manager of the player's club, league_organizer of the player's league, super_admin.

Response 200:
```json
{
  "player_id": "p-1",
  "matches_played": 14,
  "events": [ { "event_type": "<sport scoring event key>", "count": 9 } ],
  "discipline_points": 1
}
```
`event_type` values come from the sport config; the app never hard-codes them (underscores shown as spaces).
The app shows a "Not available on this server yet" state when the route answers 404 "Route not found".

## Existing endpoint used as is
`POST /api/v1/clubs/:id/roster/add` (club.roster.write, own club). Body: request_id, league_id, player_id, squad_id, name, position, role (captain|vice_captain|starter|substitute), effective_date (YYYY-MM-DD, today), reason, requested_by. 201 -> {request_id, status:"accepted", state, registration, audit_entry}. Errors: 400 VALIDATION_ERROR, 403, 404 LEAGUE_NOT_FOUND / SQUAD_NOT_FOUND, 409 PLAYER_ALREADY_REGISTERED / TRANSFER_WINDOW_CLOSED / SQUAD_FULL / CROSS_TENANT_TRANSFER.
