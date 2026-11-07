import { prisma } from './db';
import { DEDUP_WINDOW } from './ws-protocol';

export type Direction = 'c2a' | 'a2c';

export async function loadCursors(victimId: string): Promise<{ in: number; out: number }> {
  const row = await prisma.victim.findUnique({ where: { id: victimId }, select: { wsSeqIn: true, wsSeqOut: true } });
  return { in: Number(row?.wsSeqIn ?? 0), out: Number(row?.wsSeqOut ?? 0) };
}

export async function saveSeq(victimId: string, direction: Direction, seq: number): Promise<void> {
  await prisma.victim.update({
    where: { id: victimId },
    data: direction === 'c2a' ? { wsSeqIn: BigInt(seq) } : { wsSeqOut: BigInt(seq) }
  });
}

/** true when the id is new; false when it is already in the dedup window. */
export async function remember(victimId: string, direction: Direction, frameId: string, seq: number): Promise<boolean> {
  const inserted = await prisma.wsSeen.createMany({
    data: [{ victimId, direction, frameId, seq: BigInt(seq) }],
    skipDuplicates: true
  });
  return inserted.count > 0;
}

/** Keep at most DEDUP_WINDOW ids per direction. */
export async function pruneSeen(victimId: string, direction: Direction): Promise<void> {
  const keep = await prisma.wsSeen.findMany({
    where: { victimId, direction },
    orderBy: { id: 'desc' },
    take: DEDUP_WINDOW,
    select: { id: true }
  });
  if (keep.length < DEDUP_WINDOW) return;
  const cutoff = keep[keep.length - 1]!.id;
  await prisma.wsSeen.deleteMany({ where: { victimId, direction, id: { lt: cutoff } } });
}

export function floor(lastIn: number): number {
  return Math.max(0, lastIn - DEDUP_WINDOW);
}
