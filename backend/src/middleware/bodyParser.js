const express = require('express');

const strictParser = express.json({ limit: '1mb', type: () => true, strict: false });

// RBAC probes must reach authentication before any body problem is reported, so syntax
// errors and non-object bodies are ignored there. The size limit still applies (413).
const TOLERANT_PATH = /^\/audit\/v1\/rbac(-matrix)?(\/|$)/i;

function jsonBody(req, res, next) {
  if (!TOLERANT_PATH.test(req.path)) return strictParser(req, res, next);
  req.bodyIgnored = true;
  return strictParser(req, res, (err) => {
    if (err && err.type === 'entity.too.large') return next(err);
    req.body = {};
    return next();
  });
}

module.exports = jsonBody;
module.exports.jsonBody = jsonBody;
module.exports.TOLERANT_PATH = TOLERANT_PATH;
