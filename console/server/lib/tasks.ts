import { prisma } from './db';

export type LinkVictim = {
  id: string;
  hostname: string;
  discord?: { channelId: string; missingAt: Date | null } | null;
};

export type LinkError = { status: 422; code: string; message: string; victim_ids: string[] };

/**
 * Tasks only leave through a live Discord link. An ignored channel is treated
 * as unlinked (the sweep skips it) and a missing channel needs a recreate or a
 * re-link before the task can be dispatched.
 */
export function taskLinkError(victims: LinkVictim[], ignored: Set<string>): LinkError | null {
  const unlinked = victims.filter((victim) => !victim.discord || ignored.has(victim.discord.channelId));
  if (unlinked.length > 0) {
    return {
      status: 422,
      code: 'discord_unlinked',
      message: `no Discord link: ${unlinked.map((victim) => victim.hostname).join(', ')}`,
      victim_ids: unlinked.map((victim) => victim.id)
    };
  }
  const missing = victims.filter((victim) => victim.discord?.missingAt);
  if (missing.length > 0) {
    return {
      status: 422,
      code: 'discord_channel_missing',
      message: `channel missing: ${missing.map((victim) => victim.hostname).join(', ')}`,
      victim_ids: missing.map((victim) => victim.id)
    };
  }
  return null;
}

/** Scheduled work waits at most an hour; after that it is expired, not run. */
export const QUEUE_TTL_MS = 3_600_000;

export async function expireQueued(victimId?: string): Promise<void> {
  await prisma.task.updateMany({
    where: { ...(victimId ? { victimId } : {}), status: 'queued', notBefore: { lt: new Date(Date.now() - QUEUE_TTL_MS) } },
    data: { status: 'expired', error: 'not_before ttl 1h', result: {}, finishedAt: new Date(), leaseUntil: null }
  }).catch(() => undefined);
}
