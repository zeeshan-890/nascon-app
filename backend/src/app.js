const express = require('express');
const helmet = require('helmet');
const logger = require('./logger');
const { jsonBody } = require('./middleware/bodyParser');
const notFound = require('./middleware/notFound');
const errorHandler = require('./middleware/errorHandler');
const hardening = require('./middleware/hardening');
const { createLimiter } = require('./middleware/rateLimit');

function createApp(opts = {}) {
  const app = express();
  app.disable('x-powered-by');
  app.use(helmet());
  app.use((req, res, next) => {
    const start = process.hrtime.bigint();
    res.on('finish', () => {
      const ms = Number(process.hrtime.bigint() - start) / 1e6;
      logger.info(`${req.method} ${req.path} ${res.statusCode} ${ms.toFixed(1)}ms`);
    });
    next();
  });
  // Only /api/v1 is rate limited; /audit/v1, /health are exempt.
  app.use('/api/v1', opts.limiter || createLimiter());
  app.use(jsonBody);
  app.use(hardening);
  app.use(require('./routes/health'));
  app.use(require('./routes/static'));
  app.use('/audit/v1', require('./routes/audit'));
  app.use('/api/v1', require('./routes/api/v1'));
  app.use(notFound);
  app.use(errorHandler);
  return app;
}

module.exports = createApp;
