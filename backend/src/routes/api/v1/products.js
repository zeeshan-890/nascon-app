const { Router } = require('express');
const { authenticate } = require('../../../middleware/auth');
const { guard } = require('../../../middleware/guard');
const { openResource } = require('../../../http/resources');
const { tenantScope } = require('../../../domain/rbac/engine');
const read = require('../../../services/readModelService');
const { wrap, sendList, slicePage } = require('./util');

const router = Router();

router.get('/products', authenticate, guard('product.read', openResource), wrap(async (req, res) => {
  const all = await read.products(tenantScope(req.principal));
  sendList(req, res, slicePage(req, all), all.length);
}));

module.exports = router;
