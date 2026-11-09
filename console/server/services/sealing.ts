import { prisma } from '../lib/db';

export type SealingStatus = {
  builds: number;
  victims: number;
  salts: number;
  pending: number;
  degraded: boolean;
};

/**
 * Cosmetic reseal is not enough: the panel must know whether any row still
 * holds plaintext. The count is cheap (prefix check) and drives `/health`.
 */
export async function sealingStatus(): Promise<SealingStatus> {
  const [builds, victims, salts] = await Promise.all([
    prisma.build.count({ where: { NOT: { secret: { startsWith: 'v1.' } } } }),
    prisma.victim.count({
      where: {
        OR: [
          { NOT: { deviceTokenEnc: { startsWith: 'v1.' } } },
          { AND: [{ prevTokenEnc: { not: null } }, { NOT: { prevTokenEnc: { startsWith: 'v1.' } } }] },
          { AND: [{ pendingTokenEnc: { not: null } }, { NOT: { pendingTokenEnc: { startsWith: 'v1.' } } }] },
          { AND: [{ keySaltEnc: { not: null } }, { NOT: { keySaltEnc: { startsWith: 'v1.' } } }] }
        ]
      }
    }),
    prisma.victimKeyEpoch.count({ where: { NOT: { saltEnc: { startsWith: 'v1.' } } } })
  ]);
  const pending = builds + victims + salts;
  return { builds, victims, salts, pending, degraded: pending > 0 };
}
