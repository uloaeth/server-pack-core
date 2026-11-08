import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import { randomUUID } from 'node:crypto';
import { prisma } from '../lib/db';
import { env } from '../lib/env';
import { audit } from '../lib/audit';
import { bad, HttpError, unauth } from '../lib/errors';
import { hashPassword, passwordPolicy, verifyPassword } from '../lib/password';
import { newTotpSecret, totpUri, verifyTotp } from '../lib/totp';
import {
  clearSessionCookie,
  readPending,
  sessionHash,
  setPendingCookie,
  setSessionCookie,
  signPending,
  signSession,
  PENDING_COOKIE
} from '../lib/jwt';

const loginSchema = z.object({ email: z.string().email(), password: z.string().min(1), totp: z.string().optional() });
const codeSchema = z.object({ code: z.string().min(6).max(8) });

async function issueSession(operatorId: string, role: string, ip: string, userAgent: string, reply: import('fastify').FastifyReply) {
  const sid = randomUUID();
  const token = await signSession(operatorId, role, sid);
  await prisma.session.create({
    data: {
      id: sid,
      operatorId,
      tokenHash: sessionHash(token),
      ip,
      userAgent: userAgent.slice(0, 300),
      expiresAt: new Date(Date.now() + env.JWT_TTL_MIN * 60_000)
    }
  });
  setSessionCookie(reply, token);
  reply.clearCookie(PENDING_COOKIE, { path: '/' });
  return sid;
}

export const authRoutes: FastifyPluginAsync = async (app) => {
  app.post('/auth/login', {
    config: { rateLimit: { max: 10, timeWindow: '1 minute' } }
  }, async (request, reply) => {
    const input = loginSchema.parse(request.body);
    const operator = await prisma.operator.findUnique({ where: { email: input.email.toLowerCase() } });
    if (!operator || operator.disabled) throw unauth('bad_credentials');
    if (operator.lockedUntil && operator.lockedUntil > new Date()) {
      const retry = Math.ceil((operator.lockedUntil.getTime() - Date.now()) / 1000);
      throw new HttpError(429, 'locked', `account locked for ${retry}s`, retry);
    }
    if (!(await verifyPassword(operator.passwordHash, input.password))) {
      const fails = operator.failedAttempts + 1;
      const lock = fails >= env.LOGIN_MAX_FAILS;
      await prisma.operator.update({
        where: { id: operator.id },
        data: {
          failedAttempts: lock ? 0 : fails,
          lockedUntil: lock ? new Date(Date.now() + env.LOGIN_LOCK_MIN * 60_000) : null
        }
      });
      await audit(prisma, { action: 'login.fail', entity: 'operator', entityId: operator.id, ip: request.clientIp });
      if (lock) throw new HttpError(429, 'locked', `too many failures, locked ${env.LOGIN_LOCK_MIN} min`, env.LOGIN_LOCK_MIN * 60);
      throw unauth('bad_credentials');
    }

    if (!operator.totpEnabled) {
      const secret = operator.totpSecret || newTotpSecret();
      if (!operator.totpSecret) await prisma.operator.update({ where: { id: operator.id }, data: { totpSecret: secret } });
      setPendingCookie(reply, await signPending(operator.id));
      await audit(prisma, { action: 'login.totp_setup', entity: 'operator', entityId: operator.id, ip: request.clientIp });
      return reply.send({ setup: true, secret, uri: totpUri(operator.email, secret) });
    }

    if (!input.totp || !verifyTotp(operator.totpSecret, input.totp)) {
      const fails = operator.failedAttempts + 1;
      const lock = fails >= env.LOGIN_MAX_FAILS;
      await prisma.operator.update({
        where: { id: operator.id },
        data: {
          failedAttempts: lock ? 0 : fails,
          lockedUntil: lock ? new Date(Date.now() + env.LOGIN_LOCK_MIN * 60_000) : null
        }
      });
      await audit(prisma, { action: 'login.totp_fail', entity: 'operator', entityId: operator.id, ip: request.clientIp });
      if (lock) throw new HttpError(429, 'locked', `too many TOTP failures, locked ${env.LOGIN_LOCK_MIN} min`, env.LOGIN_LOCK_MIN * 60);
      throw unauth('totp_required');
    }
    await prisma.operator.update({ where: { id: operator.id }, data: { failedAttempts: 0, lockedUntil: null, lastLoginAt: new Date(), lastLoginIp: request.clientIp } });
    await issueSession(operator.id, operator.role, request.clientIp, request.headers['user-agent'] ?? '', reply);
    await audit(prisma, { action: 'login.ok', entity: 'operator', entityId: operator.id, ip: request.clientIp });
    return reply.send({ ok: true, role: operator.role });
  });

  app.post('/auth/totp/enable', async (request, reply) => {
    const operatorId = await readPending(request);
    if (!operatorId) throw unauth('pending_expired');
    const { code } = codeSchema.parse(request.body);
    const operator = await prisma.operator.findUnique({ where: { id: operatorId } });
    if (!operator) throw unauth('pending_expired');
    if (!verifyTotp(operator.totpSecret, code)) {
      const fails = operator.failedAttempts + 1;
      const lock = fails >= env.LOGIN_MAX_FAILS;
      await prisma.operator.update({
        where: { id: operator.id },
        data: {
          failedAttempts: lock ? 0 : fails,
          lockedUntil: lock ? new Date(Date.now() + env.LOGIN_LOCK_MIN * 60_000) : null
        }
      });
      if (lock) throw new HttpError(429, 'locked', `too many TOTP failures, locked ${env.LOGIN_LOCK_MIN} min`, env.LOGIN_LOCK_MIN * 60);
      throw unauth('bad_totp');
    }
    await prisma.operator.update({
      where: { id: operator.id },
      data: { totpEnabled: true, lastLoginAt: new Date(), lastLoginIp: request.clientIp }
    });
    await issueSession(operator.id, operator.role, request.clientIp, request.headers['user-agent'] ?? '', reply);
    await audit(prisma, { action: 'totp.enable', entity: 'operator', entityId: operator.id, ip: request.clientIp });
    return reply.send({ ok: true, role: operator.role });
  });

  app.post('/auth/logout', async (request, reply) => {
    if (request.claims) {
      await prisma.session.updateMany({ where: { id: request.claims.sid }, data: { revokedAt: new Date() } });
      await audit(prisma, { action: 'logout', entity: 'operator', entityId: request.operator?.id, ip: request.clientIp });
    }
    clearSessionCookie(reply);
    return reply.send({ ok: true });
  });

  app.get('/auth/me', async (request) => {
    const operator = request.operator!;
    return {
      id: operator.id,
      email: operator.email,
      role: operator.role,
      totpEnabled: operator.totpEnabled,
      lastLoginAt: operator.lastLoginAt
    };
  });

  app.post('/auth/password', async (request) => {
    const input = z.object({ current: z.string(), next: z.string() }).parse(request.body);
    const operator = request.operator!;
    if (!(await verifyPassword(operator.passwordHash, input.current))) throw unauth('bad_credentials');
    const policy = passwordPolicy(input.next);
    if (policy) throw bad('weak_password', policy, 422);
    await prisma.operator.update({ where: { id: operator.id }, data: { passwordHash: await hashPassword(input.next) } });
    await prisma.session.updateMany({ where: { operatorId: operator.id, revokedAt: null }, data: { revokedAt: new Date() } });
    await audit(prisma, { action: 'password.change', entity: 'operator', entityId: operator.id, ip: request.clientIp });
    return { ok: true, relogin: true };
  });

  app.get('/auth/sessions', async (request) => {
    const rows = await prisma.session.findMany({
      where: { operatorId: request.operator!.id, revokedAt: null, expiresAt: { gt: new Date() } },
      orderBy: { createdAt: 'desc' }
    });
    return { items: rows.map((row) => ({
      id: row.id,
      ip: row.ip,
      userAgent: row.userAgent,
      createdAt: row.createdAt,
      expiresAt: row.expiresAt,
      current: row.id === request.claims?.sid
    })) };
  });

  app.delete('/auth/sessions/:id', async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    await prisma.session.updateMany({ where: { id, operatorId: request.operator!.id }, data: { revokedAt: new Date() } });
    await audit(prisma, { action: 'session.revoke', entity: 'session', entityId: id, ip: request.clientIp });
    return { ok: true };
  });
};
