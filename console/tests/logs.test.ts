import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { FastifyInstance } from 'fastify';

let app: FastifyInstance;

before(async () => {
  const dir = await mkdtemp(join(tmpdir(), 'spc-logs-'));
  process.env.LOG_DIR = dir;
  await writeFile(join(dir, 'api.log'), [
    JSON.stringify({ level: 30, msg: 'incoming', cookie: 'spc=secret-cookie', authorization: 'Bearer abc123', token: 'tok-1' }),
    JSON.stringify({ level: 50, msg: 'boom', error: 'agent_offline' }),
    JSON.stringify({ level: 40, msg: 'slow', url: '/api/v1/tasks?token=tok-2' })
  ].join('\n'));
  const Fastify = (await import('fastify')).default;
  const { miscRoutes } = await import('../server/routes/misc');
  const { ZodError } = await import('zod');
  const { isHttpError } = await import('../server/lib/errors');
  app = Fastify();
  app.setErrorHandler((error, _request, reply) => {
    const status = error.name === 'ZodError' ? 422 : isHttpError(error) ? error.status : 500;
    return reply.code(status).send({ error: 'request_error', message: error.message });
  });
  await app.register(miscRoutes);
});

test('logs list, filter and redact without leaking tokens or cookies', async () => {
  const all = await app.inject({ method: 'GET', url: '/logs?file=api.log' });
  assert.equal(all.statusCode, 200);
  const body = all.json() as { items: Array<{ level: string; text: string }>; total: number };
  assert.equal(body.total, 3);
  assert.equal(body.items.some((row) => row.text.includes('secret-cookie')), false);
  assert.equal(body.items.some((row) => row.text.includes('abc123')), false);

  const errors = await app.inject({ method: 'GET', url: '/logs?file=api.log&level=error' });
  assert.equal(errors.statusCode, 200);
  assert.equal((errors.json() as { items: unknown[] }).items.length, 1);

  const text = await app.inject({ method: 'GET', url: '/logs?file=api.log&q=slow' });
  assert.equal((text.json() as { items: unknown[] }).items.length, 1);

  const download = await app.inject({ method: 'GET', url: '/logs/download?file=api.log&level=error' });
  assert.equal(download.statusCode, 200);
  assert.match(download.body, /agent_offline/);
});

test('a missing log is a typed 404 and a bad name is rejected', async () => {
  const missing = await app.inject({ method: 'GET', url: '/logs?file=nope.log' });
  assert.equal(missing.statusCode, 404);
  const bad = await app.inject({ method: 'GET', url: '/logs?file=../etc/passwd' });
  assert.equal(bad.statusCode, 422);
});

after(async () => { await app.close(); });
