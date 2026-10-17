# NaSCon '26 Multi-Sport League Platform

A platform for managing fixtures, scoring, standings, and rosters for NaSCon '26's multi-sport league.

## Run

```bash
DAY1_SALT=<salt> docker compose up -d --build
curl localhost:8080/health   # {"status":"ok"}
```

The API listens on port 8080. `POST /audit/v1/reset` restores the seeded state.

## Demo accounts

Password for every account: `Nascon@26`

| Email | Role |
|---|---|
| admin@nascon.test | super_admin |
| organizer@nascon.test | league_organizer (tenant-alpha, league-alpha-1) |
| manager@nascon.test | club_manager (tenant-alpha, club-alpha-1) |
| player@nascon.test | player (tenant-alpha, player-alpha-1) |
| viewer@nascon.test | viewer (tenant-alpha) |

## Tests

```bash
./run_tests.sh
```
