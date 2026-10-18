// GET /api/v1/matches/{match_id}/stream — Server-Sent Events twin of ws://host/ws/matches/{match_id}.
// No auth. Snapshot first, then (when Last-Event-ID is sent) the frames missed, then live frames.
const { sendError } = require('../lib/errors');
const { open } = require('../realtime/streams');

const HEARTBEAT_MS = 15000;
const MAX_QUEUED = 1024 * 1024;

module.exports = function realtimeRoutes(router) {
  router.get('/api/v1/matches/:id/stream', async (req, res, next) => {
    try {
      const stream = await open('match', req.params.id);
      if (!stream) return sendError(res, 404, 'MATCH_NOT_FOUND', 'Match not found');
      const last = Number.parseInt(req.get('last-event-id'), 10);
      res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache, no-transform',
        Connection: 'keep-alive',
        'X-Accel-Buffering': 'no',
        'Content-Encoding': 'identity',
        'Access-Control-Allow-Origin': '*', // public, read-only, no credentials
        'Cross-Origin-Resource-Policy': 'cross-origin',
      });
      if (req.socket) { req.socket.setTimeout(0); req.socket.setNoDelay(true); }
      res.write('retry: 3000\n\n');
      let closed = false;
      const sink = {
        write(entry) {
          if (closed || res.writableEnded) return false;
          if (res.writableLength > MAX_QUEUED) { // slow consumer: drop instead of buffering without bound
            closed = true;
            res.end();
            return false;
          }
          res.write(entry.sse);
          return true;
        },
        close() { closed = true; res.end(); },
      };
      const beat = setInterval(() => { if (!closed && !res.writableEnded) res.write(': keep-alive\n\n'); }, HEARTBEAT_MS);
      const cleanup = () => { closed = true; clearInterval(beat); stream.leave(sink); };
      req.on('close', cleanup);
      res.on('close', cleanup);
      return await stream.join(sink, { afterSequence: Number.isInteger(last) ? last : null });
    } catch (err) {
      return next(err);
    }
  });
};
