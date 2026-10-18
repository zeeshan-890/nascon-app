# Contract additions: club orders (agent ordersplus)

## GET /api/v1/clubs/{club_id}/orders   (NOT in backend yet)
Roles: super_admin (any club), club_manager (own club). Tenant/club scoped.
Response 200: JSON array, newest first preferred (the app also sorts):
`[{"order_id": "...", "status": "paid", "total_minor": 405000, "currency": "PKR", "paid_at": "2026-10-17T06:20:00Z", "customer_name": "Sara Khan" (optional), "item_count": 1}]`

## Existing endpoints used for real (no change needed)
- PATCH /api/v1/orders/{id}/status  body `{"status": "packed"}` -> `{order_id, from, to}` (order.manage; 409 ILLEGAL_TRANSITION)
- POST  /api/v1/orders/{id}/refund -> `{order_id, from, to}` (order.manage)
- GET /api/v1/orders/{id} items carry no product name; the app shows "Item" when `name` is absent. Adding `name` to items would improve the detail screen.
- The mockup's refund "Reason" field is not sent: the refund endpoint takes no body.
