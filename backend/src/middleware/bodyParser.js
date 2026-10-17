const express = require('express');

const jsonBody = express.json({ limit: '1mb', type: () => true, strict: false });

module.exports = jsonBody;
module.exports.jsonBody = jsonBody;
