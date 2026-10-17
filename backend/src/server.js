const config = require('./config');
const logger = require('./logger');

if (!config.hasSalt()) {
  logger.fatal('DAY1_SALT is missing or empty');
  process.exit(1);
}

const pool = require('./db/pool');
const { migrate, waitForDb } = require('./db/migrate');
const createApp = require('./app');
const { createHttpServer } = require('./httpServer');

async function main() {
  await waitForDb();
  await migrate();
  const { rows } = await pool.query('SELECT count(*)::int AS n FROM tenants');
  if (rows[0].n === 0) {
    const { reset } = require('./services/resetService');
    await reset({});
  }
  await require('./services/rbacService').refreshPolicies();
  config.state.ready = true;

  const server = createHttpServer(createApp());
  const hub = require('./realtime/hub').createHub(server);
  server.listen(config.port, '0.0.0.0', () => logger.info(`api listening on ${config.port}`));
  const shutdown = () => {
    hub.close().catch(() => {});
    server.close(() => pool.end().finally(() => process.exit(0)));
    setTimeout(() => process.exit(0), 5000).unref();
  };
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
}

main().catch((err) => {
  logger.fatal({ err: err.message }, 'boot failed');
  process.exit(1);
});
