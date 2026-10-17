const { send, errorBody } = require('../http/respond');

function notFound(req, res) {
  send(res, 404, errorBody('NOT_FOUND', 'Route not found'));
}

module.exports = notFound;
module.exports.notFound = notFound;
