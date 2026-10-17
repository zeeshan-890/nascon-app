const pino = require('pino');
const config = require('./config');

module.exports = pino({ level: process.env.NODE_ENV === 'test' ? 'silent' : config.logLevel });
