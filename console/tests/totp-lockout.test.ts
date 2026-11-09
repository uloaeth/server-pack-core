import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import Fastify from 'fastify';
import cookie from '@fastify/cookie';
import { ZodError } from 'zod';
import { prisma } from '../server/lib/db';
import { hashPassword } from '../server/lib/password';
import { newTotpSecret } from '../server/lib/totp';
import { HttpError } from '../server/lib/errors';
import { authRoutes } from '../server/routes/auth';

async function app() {
  const server = Fastify();
  server.addContentTypeParser('application/json', { parseAs: 'buffer' }, (_request, body, done) => {
    try {
      done(null, JSON.parse((body as Buffer).toString('utf8') || '{}'));
    } catch (error) {
      done(error as Error);
    }
  });
  await server.register(cookie);
  server.setErrorHandler((error, _request, reply) => {
    if (error instanceof HttpError) {
      return reply.code(error.status).send({ error: error.code, message: error.message });
    }
    if (error instanceof ZodError) return reply.code(422).send({ error: 'validation' });
    return reply.code(500).send({ error: 'internal' });
  });
  await server.register(authRoutes);
  return server;
}

test('five wrong TOTP codes lock the account and a good pair stays locked', async () => {
  const email = `totp-${randomUUID()}@spc.local`;
  const operator = await prisma.operator.create({
    data: {
      email,
      passwordHash: await hashPassword('Local-Test-123'),
      role: 'admin',
      totpSecret: newTotpSecret(),
      totpEnabled: true
    }
  });
  const server = await app();
  try {
    for (let attempt = 1; attempt <= 5; attempt += 1) {
      const response = await server.inject({
        method: 'POST',
        url: '/auth/login',
        payload: { email, password: 'Local-Test-123', totp: '111111' }
      });
      assert.equal(response.statusCode, attempt < 5 ? 401 : 429, `attempt ${attempt}`);
      if (attempt < 5) assert.equal(response.json().error, 'totp_required');
      else assert.equal(response.json().error, 'locked');
    }
    const locked = await prisma.operator.findUniqueOrThrow({ where: { id: operator.id } });
    assert.ok(locked.lockedUntil && locked.lockedUntil > new Date());

    const whileLocked = await server.inject({
      method: 'POST',
      url: '/auth/login',
      payload: { email, password: 'Local-Test-123', totp: '111111' }
    });
    assert.equal(whileLocked.statusCode, 429);
    assert.equal(whileLocked.json().error, 'locked');

    await prisma.operator.update({ where: { id: operator.id }, data: { lockedUntil: new Date(Date.now() - 1000) } });
    const audit = await prisma.auditLog.count({ where: { entityId: operator.id, action: 'login.totp_fail' } });
    assert.equal(audit, 5);
  } finally {
    await server.close();
    await prisma.session.deleteMany({ where: { operatorId: operator.id } });
    await prisma.operator.delete({ where: { id: operator.id } });
  }
});

test('a correct TOTP resets the counter and issues a session', async () => {
  const email = `totp-ok-${randomUUID()}@spc.local`;
  const operator = await prisma.operator.create({
    data: {
      email,
      passwordHash: await hashPassword('Local-Test-123'),
      role: 'admin',
      totpSecret: newTotpSecret(),
      totpEnabled: true,
      failedAttempts: 3
    }
  });
  const server = await app();
  try {
    const { authenticator } = await import('otplib');
    const secret = (await prisma.operator.findUniqueOrThrow({ where: { id: operator.id } })).totpSecret;
    const code = authenticator.generate(secret);
    const response = await server.inject({
      method: 'POST',
      url: '/auth/login',
      payload: { email, password: 'Local-Test-123', totp: code }
    });
    assert.equal(response.statusCode, 200);
    assert.equal(response.json().ok, true);
    const row = await prisma.operator.findUniqueOrThrow({ where: { id: operator.id } });
    assert.equal(row.failedAttempts, 0);
    assert.equal(row.lockedUntil, null);
  } finally {
    await server.close();
    await prisma.session.deleteMany({ where: { operatorId: operator.id } });
    await prisma.operator.delete({ where: { id: operator.id } });
  }
});

