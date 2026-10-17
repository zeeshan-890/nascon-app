const { send } = require('../../../http/respond');
const { pageOf } = require('../../../services/readModelService');

const wrap = (fn) => async (req, res, next) => {
  try {
    await fn(req, res);
  } catch (err) {
    next(err);
  }
};

// Bare JSON array plus the total in X-Total-Count; limit/offset apply to in-memory lists.
function sendList(req, res, items, total = items.length) {
  res.set('X-Total-Count', String(total));
  send(res, 200, items);
}

function slicePage(req, items) {
  const { limit, offset } = pageOf(req.query);
  return items.slice(offset, offset + limit);
}

module.exports = { wrap, sendList, slicePage };
