# Contract additions: league management (leagueadmin)

The backend only has read endpoints for leagues (`GET /api/v1/leagues`, `/leagues/:id/fixtures`, `/leagues/:id/standings`).
The mobile app (package `ui/leagueadmin`, `data/leagueadmin`) needs the endpoints below. All paths are under the
authenticated API, JSON, snake_case. Errors use the standard `{ "error": { "code", "message" } }` body.
Unknown league id: 404 `LEAGUE_NOT_FOUND`. Invalid body: 422 `VALIDATION_ERROR`.

The leagues list and league detail screens call the REAL `GET /api/v1/leagues` and merge the result with
`GET /api/v1/admin/leagues` (matched by `league_id`). If the admin list is missing the real leagues are still shown.

Roles: `super_admin` = any league. `league_organizer` = only leagues in the caller's `league_ids`.
Date fields are ISO `yyyy-MM-dd`.

| Method | Path | Request | Response | Roles |
|---|---|---|---|---|
| GET | `/api/v1/sports` | none | `[{ sport_id, name }]` | any signed-in |
| GET | `/api/v1/admin/leagues` | none | `[AdminLeague]` | super_admin (all), league_organizer (own only) |
| GET | `/api/v1/admin/leagues/{id}` | none | `AdminLeague` | super_admin, league_organizer (own) |
| POST | `/api/v1/admin/leagues` | `AdminLeagueRequest` | `AdminLeague` (201). Status `pending` for an organizer, `active` for a super admin | super_admin, league_organizer (league.create) |
| POST | `/api/v1/admin/leagues/{id}/approve` | none | `AdminLeague` (status `active`). 409 `ILLEGAL_TRANSITION` unless the league is `pending` | super_admin only (league.approve) |
| POST | `/api/v1/admin/leagues/{id}/reject` | none | `AdminLeague` (status `rejected`). Same 409 rule | super_admin only (league.approve) |
| PATCH | `/api/v1/admin/leagues/{id}` | `AdminLeagueRequest` | `AdminLeague` | super_admin, league_organizer (own) |
| GET | `/api/v1/leagues/{id}/teams` | none | `[{ team_id, name }]` | super_admin, league_organizer (own) |
| POST | `/api/v1/leagues/{id}/teams` | `{ name }` (1-40 chars) | `{ team_id, name }` (201). 409 `LEAGUE_NOT_ACTIVE`, `DUPLICATE_TEAM`, `TOO_MANY_TEAMS` (max 40) | super_admin, league_organizer (own) |
| DELETE | `/api/v1/leagues/{id}/teams/{team_id}` | none | `{ removed: true }`. 409 `TEAM_HAS_FIXTURES` once the team is scheduled | super_admin, league_organizer (own) |
| POST | `/api/v1/leagues/{id}/fixtures/generate` | see `fixturegen.md` | `{ created, league_id }` (201). Teams must belong to the league; league must be `active` | super_admin, league_organizer (own) |
| GET | `/api/v1/leagues/{id}/seasons` | none | `[Season]` | super_admin, league_organizer (own) |
| POST | `/api/v1/leagues/{id}/seasons` | `SeasonRequest` | `Season` | super_admin only (season.write) |
| GET | `/api/v1/leagues/{id}/divisions` | none | `[Division]` | super_admin, league_organizer (own) |
| POST | `/api/v1/leagues/{id}/divisions` | `{ name }` | `Division` | super_admin, league_organizer (own) |
| GET | `/api/v1/leagues/{id}/promotion-rules` | none | `{ spots_up, spots_down }` | super_admin, league_organizer (own) |
| PUT | `/api/v1/leagues/{id}/promotion-rules` | `{ spots_up, spots_down }` (>= 0) | same | super_admin, league_organizer (own) |
| GET | `/api/v1/leagues/{id}/standings-rules` | none | `StandingsRules` | super_admin, league_organizer (own) |
| PUT | `/api/v1/leagues/{id}/standings-rules` | `StandingsRules` | same | super_admin, league_organizer (own) |

## Shapes

`AdminLeague`: `league_id`, `name`, `sport_id`, `season` (string or null), `format`
(`round_robin | swiss | single_elimination | double_elimination | hybrid`), `status` (`pending | active | rejected`; the app also understands `draft | finished`),
`team_count`, `player_count`, `match_count`, `division_count`, `transfer_window_opens`, `transfer_window_closes`
(dates or null), `max_squad_size` (int).

`AdminLeagueRequest`: `name` (1-60 chars), `sport_id`, `format`, `transfer_window_opens?`, `transfer_window_closes?`
(both or neither; closes after opens), `max_squad_size` (1-99).

`Season`: `season_id`, `name`, `start_date`, `end_date`, `status` (`upcoming | current | finished`),
`league_count`, `division_count`, `promotion_applied` (bool).

`SeasonRequest`: `name`, `start_date`, `end_date` (must be after start_date), `copy_from_season_id?`, `apply_promotion` (bool).

`Division`: `division_id`, `name`, `tier` (1 = top), `team_count`, `top_teams: [{ name, points }]` (up to 2).

`StandingsRules`: `points_win`, `points_draw`, `points_loss` (0-9, win >= draw >= loss), `qualification_spots` (0-32),
`tiebreaks` (ordered, at least one of `head_to_head | goal_difference | goals_for | wins | fair_play`).

## Not covered by this app
`POST /audit/v1/leagues` is the graded fixture-seeding route and is not used by the app.
Fixture generation is a separate feature (route `NewRoutes.genFormat(leagueId)`).

## Approval workflow
An organizer's new league is `pending`: it appears only in that organizer's `GET /api/v1/admin/leagues` and in the super admin's list.
`GET /api/v1/leagues` (what viewers, players and managers see) returns `active` leagues only, so a league becomes visible to viewers once a super admin approves it.
The backend adds the new league id to the organizer's `league_ids`, so a refreshed token owns it; the app also adds it to its stored session at once.
