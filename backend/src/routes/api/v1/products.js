const { Router } = require('express');
const { authenticate } = require('../../../middleware/auth');
const { guard } = require('../../../middleware/guard');
const { send } = require('../../../http/respond');
const { openResource, productResolver } = require('../../../http/resources');
const { tenantScope } = require('../../../domain/rbac/engine');
const read = require('../../../services/readModelService');
const { wrap, sendList, slicePage } = require('./util');

const router = Router();

router.get('/products', authenticate, guard('product.read', openResource), wrap(async (req, res) => {
  const all = await read.products(tenantScope(req.principal));
  sendList(req, res, slicePage(req, all), all.length);
}));

router.get('/products/:id', authenticate, guard('product.read', productResolver()), wrap(async (req, res) => {
  send(res, 200, await read.productDetail(req.params.id));
}));

module.exports = router;
