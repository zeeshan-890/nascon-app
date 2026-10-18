# Backend issues

Live probes against `http://localhost:8080` after `POST /audit/v1/reset` `{"seed":7}`. Three defects. Login, refresh, logout, `/me` branding, sport-by-id, fixtures, match score/events (including signatures and `compensated`), standings, club detail for a club that has a venue, roster transfer, player history, the product list, checkout idempotency, the order JSON, the PDF stream, sync, WebSocket message types, close code 4401, 401, 403, and 429 `Retry-After` matched PLAN.md B0 or DESIGN.md.

The 120 temporary leagues used for the pagination probe were deleted afterward (`league_id` like `audit-page-%`).

## 1. Product detail route is missing

- Endpoint: `GET /api/v1/products/demo-prod-cap` (also `GET /api/v1/products/prod-jersey-001`)
- Request: `Authorization: Bearer` organizer access token. No body.
- Actual: `404`

```json
{"error":{"code":"NOT_FOUND","message":"Route not found","details":{}}}
```

- Expected: DESIGN.md §15 `GET /products/{id}` with that product's variants and stock (the same fields the list already returns for one product). DESIGN.md §21 records this route as not implemented. PLAN.md B0 only freezes the list, and `GET /api/v1/products` does return variants and stock.

## 2. Sports list route is missing

- Endpoint: `GET /api/v1/sports`
- Request: `Authorization: Bearer` organizer access token. No body.
- Actual: `404`

```json
{"error":{"code":"NOT_FOUND","message":"Route not found","details":{}}}
```

- Expected: DESIGN.md §15 `GET /sports`. `GET /api/v1/sports/football` and `GET /api/v1/sports/cricket` return 200 and the sport config. PLAN.md B0 only freezes `GET /api/v1/sports/{id}`.

## 3. Page size is not capped at 100

- Endpoint: `GET /api/v1/leagues?limit=150` and `GET /api/v1/leagues` (no query)
- Request: organizer bearer, after 120 extra `tenant-alpha` leagues existed (122 leagues in that tenant).
- Actual:
  - `limit=150` → `200`, `X-Total-Count: 122`, JSON array length **122**
  - no `limit` → `200`, `X-Total-Count: 122`, JSON array length **122**
  - `limit=100` → `200`, array length 100, `X-Total-Count: 122`
- Expected: DESIGN.md §15 says `/api/v1` lists are paginated with `limit ≤ 100`. A `limit` of 150 should return at most 100 rows (or 400). The default page should also stay within that cap. The body has no `next` cursor; paging is `limit`/`offset` plus `X-Total-Count`. That header shape is fine. The cap is not.

`GET /api/v1/leagues/league-alpha-1/fixtures?limit=250` also returned 200 rather than 400, but that league only has 6 fixtures, so the over-100 cap is demonstrated on the leagues list above. Fixtures, clubs, and products use the same `limit`/`offset` helper.
