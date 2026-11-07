import 'dotenv/config';
import { prisma } from './lib/db';
import { processArtifact } from './services/artifact';

let running = true;

async function tick(): Promise<void> {
  const artifacts = await prisma.artifact.findMany({ where: { status: 'queued' }, orderBy: { createdAt: 'asc' }, take: 4 });
  for (const artifact of artifacts) await processArtifact(artifact.id);

  await prisma.upload.updateMany({ where: { state: 'open', expiresAt: { lt: new Date() } }, data: { state: 'expired' } });
  await prisma.task.updateMany({
    where: { status: 'running', leaseUntil: { lt: new Date(Date.now() - 60_000) }, attempts: { lt: 5 } },
    data: { status: 'queued', leaseUntil: null }
  });
  await prisma.task.updateMany({
    where: { status: 'running', leaseUntil: { lt: new Date(Date.now() - 60_000) }, attempts: { gte: 5 } },
    data: { status: 'expired', finishedAt: new Date(), error: 'lease expired 5 times' }
  });
  await prisma.victim.updateMany({
    where: { status: 'online', lastHeartbeatAt: { lt: new Date(Date.now() - 90_000) } },
    data: { status: 'stale' }
  });
  await prisma.victim.updateMany({
    where: { status: 'stale', lastHeartbeatAt: { lt: new Date(Date.now() - 15 * 60_000) } },
    data: { status: 'offline' }
  });
  await prisma.session.deleteMany({ where: { expiresAt: { lt: new Date(Date.now() - 30 * 86_400_000) } } });
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
