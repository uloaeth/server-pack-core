import 'dotenv/config';
import { prisma } from '../server/lib/db';
import { openSecretLoose, sealSecret } from '../server/lib/crypto';
import { sealingStatus } from '../server/services/sealing';

/**
 * Run once with MASTER_KEY=new and MASTER_KEY_OLD=old, then drop the old key.
 * The exit code is non-zero while any row would remain in plaintext: a silent
 * partial reseal is the failure mode this script exists to remove.
 */
async function main() {
  let builds = 0;
  let victims = 0;
  let salts = 0;

  for (const build of await prisma.build.findMany({ select: { id: true, secret: true } })) {
    if (build.secret.startsWith('v1.')) continue;
    await prisma.build.update({ where: { id: build.id }, data: { secret: sealSecret(openSecretLoose(build.secret)) } });
    builds += 1;
  }
  for (const victim of await prisma.victim.findMany({
    select: { id: true, deviceTokenEnc: true, prevTokenEnc: true, pendingTokenEnc: true, keySaltEnc: true }
  })) {
    const data: Record<string, string> = {};
    if (!victim.deviceTokenEnc.startsWith('v1.')) data['deviceTokenEnc'] = sealSecret(openSecretLoose(victim.deviceTokenEnc));
    if (victim.prevTokenEnc && !victim.prevTokenEnc.startsWith('v1.')) data['prevTokenEnc'] = sealSecret(openSecretLoose(victim.prevTokenEnc));
    if (victim.pendingTokenEnc && !victim.pendingTokenEnc.startsWith('v1.')) data['pendingTokenEnc'] = sealSecret(openSecretLoose(victim.pendingTokenEnc));
    if (victim.keySaltEnc && !victim.keySaltEnc.startsWith('v1.')) data['keySaltEnc'] = sealSecret(openSecretLoose(victim.keySaltEnc));
    if (Object.keys(data).length === 0) continue;
    await prisma.victim.update({ where: { id: victim.id }, data });
    victims += 1;
  }
  for (const epoch of await prisma.victimKeyEpoch.findMany({ select: { id: true, saltEnc: true } })) {
    if (epoch.saltEnc.startsWith('v1.')) continue;
    await prisma.victimKeyEpoch.update({ where: { id: epoch.id }, data: { saltEnc: sealSecret(openSecretLoose(epoch.saltEnc)) } });
    salts += 1;
  }

  const status = await sealingStatus();
  console.log(`resealed builds=${builds} victims=${victims} salts=${salts} pending=${status.pending}`);
  if (status.degraded) {
    console.error(`reseal incomplete: builds=${status.builds} victims=${status.victims} salts=${status.salts}`);
    process.exitCode = 1;
  }
}

main().finally(() => prisma.$disconnect());
