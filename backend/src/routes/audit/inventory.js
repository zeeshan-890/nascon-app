const { Router } = require('express');
const pool = require('../../db/pool');
const AppError = require('../../http/AppError');
const { send } = require('../../http/respond');
const { Id } = require('../../http/validators');
const store = require('../../repos/store');

const router = Router();

router.get('/inventory/:variant_id', async (req, res, next) => {
  try {
    const id = req.params.variant_id;
    if (!Id.safeParse(id).success) {
      throw new AppError(400, 'VALIDATION_ERROR', 'Request validation failed', { fields: ['variant_id'] });
    }
    const row = await store.getInventory(pool, id);
    if (!row) throw new AppError(404, 'VARIANT_NOT_FOUND', 'Variant not found', { variant_id: id });
    send(res, 200, { variant_id: row.variant_id, stock: row.stock, reserved: row.reserved });
  } catch (err) {
    next(err);
  }
});

module.exports = router;
