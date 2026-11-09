import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { prisma } from '../server/lib/db';
import { randomToken, sha256hex } from '../server/lib/crypto';
import { applyTaskResult } from '../server/lib/task-result';

// The marker frame (`http_result`) closes a task before the real, signed HTTP
// body (with upload ids) arrives. If that body is then thrown away, the task
// keeps `{http_result:true}` and the artifact never links to it.
async function seed() {
  const build = await prisma.build.create({
    data: {
      name: `result-test-${randomUUID()}`,
      version: '0.0.0',
      buildId: randomUUID(),
      tokenHash: sha256hex(randomToken(32)),
      secret: randomToken(32),
      config: {}
    }
  });
  const victim = await prisma.victim.create({
    data: {
      buildId: build.id,
      hostname: 'result-host',
      os: 'linux',
      username: 'tester',
      deviceTokenEnc: 'v1.00.00.00'
    }
  });
  return { build, victim };
}

test('a real result replaces its own http_result marker and links the artifact', async () => {
  const { build, victim } = await seed();
  try {
    const task = await prisma.task.create({
      data: {
        victimId: victim.id,
        name: 'file_pull',
        params: { path: '/etc/hostname' },
        status: 'done',
        result: { artifactId: 'x', http_result: true },
        finishedAt: new Date()
      }
    });
    const uploadId = randomUUID();
    const upload = await prisma.upload.create({
      data: {
        id: uploadId,
        victimId: victim.id,
        module: 'file_pull',
        totalSize: 8,
        chunkCount: 1,
        sha256: 'a'.repeat(64),
        path: '/tmp/does-not-exist.spc',
        expiresAt: new Date(Date.now() + 3_600_000)
      }
    });
    const artifact = await prisma.artifact.create({
      data: {
        victimId: victim.id,
        uploadId: upload.id,
        module: 'file_pull',
        bytes: 8,
        sha256: 'a'.repeat(64),
        containerPath: '/tmp/does-not-exist.spc',
        status: 'ready'
      }
    });

    const outcome = await applyTaskResult(victim.id, task.id, {
      status: 'ok',
      error: null,
      result: { artifactId: artifact.id, upload_id: uploadId, path: '/etc/hostname' },
      artifacts: [{ upload_request: uploadId }]
    });
    assert.equal(outcome, 'applied');

    const stored = await prisma.task.findUniqueOrThrow({ where: { id: task.id } });
    assert.deepEqual(stored.result, { artifactId: artifact.id, upload_id: uploadId, path: '/etc/hostname' });
    assert.equal(stored.status, 'done');

    const linked = await prisma.artifact.findUniqueOrThrow({ where: { id: artifact.id } });
    assert.equal(linked.taskId, task.id);
    assert.equal(linked.victimId, victim.id);
  } finally {
    await prisma.victim.deleteMany({ where: { id: victim.id } });
    await prisma.build.deleteMany({ where: { id: build.id } });
  }
});

test('a duplicate marker does not overwrite a stored real result', async () => {
  const { build, victim } = await seed();
  try {
    const task = await prisma.task.create({
      data: {
        victimId: victim.id,
        name: 'file_pull',
        params: {},
        status: 'done',
        result: { upload_id: 'u1' },
        finishedAt: new Date()
      }
    });
    const outcome = await applyTaskResult(victim.id, task.id, {
      status: 'ok',
      error: null,
      result: { http_result: true },
      artifacts: []
    });
    assert.equal(outcome, 'duplicate');
    const stored = await prisma.task.findUniqueOrThrow({ where: { id: task.id } });
    assert.deepEqual(stored.result, { upload_id: 'u1' });
  } finally {
    await prisma.victim.deleteMany({ where: { id: victim.id } });
    await prisma.build.deleteMany({ where: { id: build.id } });
  }
});
