const express = require('express');
const helmet = require('helmet');
const logger = require('./logger');
const { jsonBody } = require('./middleware/bodyParser');
const notFound = require('./middleware/notFound');
const errorHandler = require('./middleware/errorHandler');

function createApp() {
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
  app.use(jsonBody);
  app.use(require('./routes/health'));
  app.use('/audit/v1', require('./routes/audit'));
  app.use(notFound);
  app.use(errorHandler);
  return app;
}

module.exports = createApp;
