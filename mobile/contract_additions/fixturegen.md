# Fixture generator: contract additions

The mobile fixture generator (screens F09 to F18) uses the existing, unauthenticated generator for every preview
and needs ONE new endpoint to persist a schedule.

## Existing endpoint used as is

`POST /audit/v1/fixtures` (not under `/api/v1`, no auth, not rate limited). Request `{ format, teams, params }`
with `format` one of `round_robin`, `swiss`, `single_elim`, `double_elim`. The app sends:

| format | params the app sends |
| --- | --- |
| round_robin | `legs` (1 or 2), `withdrawal_policy` (only when withdrawals exist), `withdrawals[{team_id, after_round}]` |
| swiss | `total_rounds`, `round`, `history[{round, bye?, matches[{home, away, result}]}]` (result: home, away, draw) |
| single_elim | `third_place_match`, `results[{match_id, winner}]` |
| double_elim | `bracket_reset`, `results[{match_id, winner}]` |

Seeding is the order of `teams` (seed 1 first). The hybrid flow (F18) builds a `single_elim` request from the top N of the
league table. Errors are read through the normal error envelope (`VALIDATION_ERROR` 400, `NO_VALID_PAIRING` 409).

## New endpoint: save a generated schedule into a league

`POST /api/v1/leagues/{id}/fixtures/generate`

Roles: `super_admin` (any league), `league_organizer` (own league only). Same rule as `match.manage`.

Request body = the generator request plus `save: true` and two optional scheduling hints:

```json
{
  "format": "round_robin",
  "teams": ["team-1", "team-2", "team-3", "team-4"],
  "params": { "legs": 2 },
  "save": true,
  "start_date": "2026-10-24",
  "match_days": [6, 7]
}
```

* `start_date`: ISO date (yyyy-MM-dd). The first round is played on the first match day on or after it.
* `match_days`: ISO weekday numbers (1 = Monday ... 7 = Sunday). Each following round goes to the next match day.
* For swiss the request describes ONE round (`params.round` and `params.history`); the server saves only that round's pairings.
* For brackets, `params.results` may be present; matches with a known result should be saved as completed or ignored (the app
  only uses results to preview how the bracket advances).

The server runs the same pure generator on the body, creates one fixture per playable match (skip byes, voided matches and the
conditional second grand final) in the league's current season, and answers `201`:

```json
{ "created": 28, "league_id": "league-alpha-1" }
```

Errors: `400 VALIDATION_ERROR` (same field list as the generator), `409 NO_VALID_PAIRING`, `403 FORBIDDEN`,
`404 LEAGUE_NOT_FOUND`. Until the server has this route the app shows "Not available on this server yet" after tapping Save;
previews keep working.
