import { randomToken, sealSecret, openSecretLoose } from '../lib/crypto';
import { prisma } from '../lib/db';
import { audit } from '../lib/audit';

export type IdentityState = 'confirmed' | 'unverified' | 'grace' | 'revoked';
export type EnrollPath = 'bootstrap' | 'confirmed' | 'replace' | 'unverified';

export type IdentityRow = {
  id: string;
  revokedAt: Date | null;
  identityState: string;
  deviceTokenEnc: string;
  prevTokenEnc: string | null;
  prevTokenExpiresAt: Date | null;
  pendingTokenEnc: string | null;
  pendingTokenExpiresAt: Date | null;
  graceUntil: Date | null;
  keyEpoch: number;
  keyScheme: number;
  keySaltEnc: string | null;
};

/**
 * Pure identity decision. Token validity is decided by the caller (it already
 * verified the HMAC); here we only choose the state transition.
 */
export function enrollDecision(
  existing: Pick<IdentityRow, 'revokedAt' | 'identityState' | 'pendingTokenEnc'> | null,
  options: { tokenValid: boolean; graceHours: number; now?: number }
): EnrollPath {
  const now = options.now ?? Date.now();
  if (!existing) return 'bootstrap';
  // A revoked row is only revived by a successful enroll; a token, when the
  // agent still has one, always takes the confirmed path.
  if (existing.revokedAt || existing.identityState === 'revoked') {
    return options.tokenValid ? 'confirmed' : 'bootstrap';
  }
  if (options.tokenValid) return 'confirmed';
  if (options.graceHours <= 0) return 'replace';
  return 'unverified';
}

export type TokenWindows = {
  active: string | null;
  previous: string | null;
  pending: string | null;
};

/** Tokens that are inside their grace window and may sign a request. */
export function usableTokens(
  row: Pick<IdentityRow, 'deviceTokenEnc' | 'prevTokenEnc' | 'prevTokenExpiresAt' | 'pendingTokenEnc' | 'pendingTokenExpiresAt'>,
  now = Date.now()
): TokenWindows {
  const unexpired = (value: string | null, until: Date | null): string | null => {
    if (!value) return null;
    if (until && until.getTime() <= now) return null;
    return value;
  };
  return {
    active: (() => {
      try {
        return openSecretLoose(row.deviceTokenEnc);
      } catch {
        return null;
      }
    })(),
    previous: unexpired(row.prevTokenEnc, row.prevTokenExpiresAt),
    pending: unexpired(row.pendingTokenEnc, row.pendingTokenExpiresAt)
  };
}

export function openToken(sealed: string | null): string | null {
  if (!sealed) return null;
  try {
    return openSecretLoose(sealed);
  } catch {
    return null;
  }
}

export function tokenWindows(row: Parameters<typeof usableTokens>[0]): TokenWindows {
  return {
    ...usableTokens(row),
    previous: openToken(row.prevTokenEnc && row.prevTokenExpiresAt && row.prevTokenExpiresAt.getTime() > Date.now() ? row.prevTokenEnc : null),
    pending: openToken(row.pendingTokenEnc && row.pendingTokenExpiresAt && row.pendingTokenExpiresAt.getTime() > Date.now() ? row.pendingTokenEnc : null)
  };
}

export type TokenKind = 'active' | 'previous' | 'pending' | null;

/** `pending` authenticates but must never confirm the identity. */
export function classifyToken(
  row: Pick<IdentityRow, 'deviceTokenEnc' | 'prevTokenEnc' | 'prevTokenExpiresAt' | 'pendingTokenEnc' | 'pendingTokenExpiresAt'>,
  token: string | undefined,
  now = Date.now()
): TokenKind {
  if (!token) return null;
  for (const [kind, sealed, until] of [
    ['active', row.deviceTokenEnc, null] as const,
    ['previous', row.prevTokenEnc, row.prevTokenExpiresAt] as const,
    ['pending', row.pendingTokenEnc, row.pendingTokenExpiresAt] as const
  ]) {
    if (!sealed || (until && until.getTime() <= now)) continue;
    try {
      if (openSecretLoose(sealed) === token) return kind;
    } catch {
      // try the next slot
    }
  }
  return null;
}

export function expiry(now: number, hours: number): Date | null {
  return hours > 0 ? new Date(now + hours * 3_600_000) : null;
}

/** Moves an expired pending token to active. Pure, so it is trivially testable. */
export function promoteExpired(
  row: Pick<IdentityRow, 'identityState' | 'pendingTokenEnc' | 'pendingTokenExpiresAt'>,
  now = Date.now()
): { deviceTokenEnc: string; identityState: 'unverified'; pendingTokenEnc: null; pendingTokenExpiresAt: null } | null {
  if (!row.pendingTokenEnc || !row.pendingTokenExpiresAt) return null;
  if (row.pendingTokenExpiresAt.getTime() > now) return null;
  return {
    deviceTokenEnc: row.pendingTokenEnc,
    identityState: 'unverified',
    pendingTokenEnc: null,
    pendingTokenExpiresAt: null
  };
}

/** Salt for one epoch, falling back to the active column for epoch 1. */
export async function saltForEpoch(victimId: string, epoch: number): Promise<Buffer | null> {
  const stored = await prisma.victimKeyEpoch.findUnique({
    where: { victimId_epoch: { victimId, epoch } },
    select: { saltEnc: true }
  });
  if (stored) return Buffer.from(openSecretLoose(stored.saltEnc), 'base64url');
  if (epoch !== 1) return null;
  const victim = await prisma.victim.findUnique({ where: { id: victimId }, select: { keySaltEnc: true, keyScheme: true } });
  if (!victim?.keySaltEnc || victim.keyScheme !== 2) return null;
  return Buffer.from(openSecretLoose(victim.keySaltEnc), 'base64url');
}

/**
 * Explicit admin rotation: new epoch, new salt, keep the previous two so old
 * outbox entries and in-flight artifacts can still close.
 */
export async function rotateKey(
  victimId: string,
  operatorId?: string,
  ip?: string
): Promise<{ epoch: number; salt: string }> {
  return prisma.$transaction(async (tx) => {
    const victim = await tx.victim.findUniqueOrThrow({ where: { id: victimId } });
    if (victim.keyScheme !== 2) throw new Error('key_scheme_1');
    const epoch = victim.keyEpoch + 1;
    const salt = randomToken(32);
    const saltEnc = sealSecret(salt);
    await tx.victimKeyEpoch.create({ data: { victimId, epoch, saltEnc } });
    await tx.victim.update({ where: { id: victimId }, data: { keyEpoch: epoch, keySaltEnc: saltEnc } });
    const retired = await tx.victimKeyEpoch.findMany({
      where: { victimId },
      orderBy: { epoch: 'desc' },
      skip: 3,
      select: { id: true }
    });
    if (retired.length > 0) {
      await tx.victimKeyEpoch.updateMany({
        where: { id: { in: retired.map((row) => row.id) } },
        data: { retiredAt: new Date() }
      });
      await tx.victimKeyEpoch.deleteMany({ where: { id: { in: retired.map((row) => row.id) } } });
    }
    await audit(tx, {
      operatorId,
      action: 'victim.rotate_key',
      entity: 'victim',
      entityId: victimId,
      ip,
      data: { epoch, retired: retired.length }
    });
    return { epoch, salt };
  });
}
