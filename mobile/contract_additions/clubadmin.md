# Club administration: contract additions

None of these endpoints exist on the backend today. The mobile app calls them through
`data/clubadmin/ClubAdminApi.kt`; in demo mode `FakeClubAdminApi` answers. On a live server the
current "Route not found" reply makes the app show "Not available on this server yet".

All routes need a signed-in user. Policy `club.write`: `super_admin` (any club) and `club_manager`
(own club only). Other roles get 403 `FORBIDDEN`; an unknown club is 404 `CLUB_NOT_FOUND`,
an unknown product 404 `PRODUCT_NOT_FOUND`, an unknown variant 404 `VARIANT_NOT_FOUND`,
bad input 400 `VALIDATION_ERROR` (standard error envelope).

Existing routes the app already uses for reading: `GET /api/v1/clubs/{id}` (name, logo_url, venue,
socials, staff are prefilled from it) and `GET /api/v1/products`.

## PATCH /api/v1/clubs/{id}
Request:
```json
{ "name": "Alpha Lions FC", "logo_url": "https://..." | null,
  "venue": { "name": "Alpha Stadium", "lat": 31.52, "lng": 74.35 } | null,
  "socials": { "instagram": "@alphalionsfc" } }
```
Rules: name required, at most 80 chars; lat -90..90 and lng -180..180 (both or neither); socials values are strings.
Response 200: the full club object, same shape as `GET /api/v1/clubs/{id}`.

## PUT /api/v1/clubs/{id}/staff
Replaces the whole staff list. Request: `{ "staff": [ { "name": "Sofia Marin", "role": "Head Coach" } ] }`
(name required, role at most 40 chars, at most 50 entries). Response 200: `{ "staff": [...] }`.

## GET /api/v1/clubs/{id}/products
Response 200: array of
```json
{ "product_id": "...", "name": "...", "category": "apparel", "image_url": null, "active": true,
  "variants": [ { "variant_id": "...", "size": "S", "color": "Blue", "price_minor": 4999, "stock": 20 } ] }
```
Includes inactive products (the public `GET /api/v1/products` should list only active ones).

## POST /api/v1/clubs/{id}/products
Request: `{ "name", "category", "image_url", "active", "variants": [ { "size", "color", "price_minor", "stock" } ] }`.
Rules: name required; at least one variant (at most 30); `price_minor` integer > 0; `stock` integer >= 0;
size+color unique within the product. Response 201: the product as above, with generated ids.

## PATCH /api/v1/clubs/{id}/products/{product_id}
Same body as POST. Variants carrying a `variant_id` are updated (the app does NOT change their stock here;
the server should ignore `stock` for existing variants); variants without one are created (with the given
starting stock); existing variants missing from the list are removed or deactivated. Response 200: the product.

## PATCH /api/v1/clubs/{id}/products/{product_id}/variants/{variant_id}/stock
Request: `{ "stock": 12 }` (integer 0..100000, absolute value, not a delta). Response 200: the variant
`{ "variant_id", "size", "color", "price_minor", "stock" }`. Should update the same row
`GET /audit/v1/inventory/{variant_id}` reads.
