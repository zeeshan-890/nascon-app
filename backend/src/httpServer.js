const http = require('http');

// Timeouts sit above typical load-balancer idle timeouts (slowloris / keep-alive hygiene).
function configureServer(server) {
  server.keepAliveTimeout = 65000;
  server.headersTimeout = 66000;
  server.requestTimeout = 30000;
  return server;
}

function createHttpServer(app) {
  return configureServer(http.createServer(app));
}

module.exports = { createHttpServer, configureServer };
