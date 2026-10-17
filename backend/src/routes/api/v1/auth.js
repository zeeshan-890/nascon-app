const { Router } = require('express');
const { z } = require('zod');
const { send } = require('../../../http/respond');
const { parseOr400, requireObjectBody } = require('../../../http/validators');
const { authenticate } = require('../../../middleware/auth');
const { clientIp } = require('../../../middleware/rateLimit');
const auth = require('../../../services/authService');

const router = Router();
router.use((req, res, next) => {
  res.set('Cache-Control', 'no-store');
  res.set('Pragma', 'no-cache');
  next();
});

const LoginBody = z.object({
  email: z.string().min(1).max(254),
  password: z.string().min(1).max(200),
});
const TokenBody = z.object({ refresh_token: z.string().min(1).max(200) });

const wrap = (fn) => async (req, res, next) => {
  try {
    await fn(req, res);
  } catch (err) {
    next(err);
  }
};

router.post('/auth/login', wrap(async (req, res) => {
  const { email, password } = parseOr400(LoginBody, requireObjectBody(req.body));
  send(res, 200, await auth.login({ email, password, ip: clientIp(req) }));
}));

router.post('/auth/refresh', wrap(async (req, res) => {
  const { refresh_token: token } = parseOr400(TokenBody, requireObjectBody(req.body));
  send(res, 200, await auth.refresh(token));
}));

router.post('/auth/logout', wrap(async (req, res) => {
  const { refresh_token: token } = parseOr400(TokenBody, requireObjectBody(req.body));
  send(res, 200, await auth.logout(token));
}));

router.get('/me', authenticate, wrap(async (req, res) => {
  send(res, 200, await auth.me(req.principal));
}));

module.exports = router;
