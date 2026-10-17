function send(res, status, obj) {
  return res.status(status).type('application/json').json(obj);
}

function errorBody(code, message, details = {}) {
  return { error: { code, message, details } };
}

module.exports = { send, errorBody };
