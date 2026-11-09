import type { FastifyReply, FastifyRequest } from 'fastify';
import type { Victim, Build } from '@prisma/client';
import { prisma } from '../lib/db';
import { hmac, openSecretLoose, unb64u, verifyVictim, victimCanonical } from '../lib/crypto';
import { claimNonce } from '../lib/nonce';
import { HttpError, unauth } from '../lib/errors';
import { promoteExpired } from '../services/victim';

declare module 'fastify' {
  interface FastifyRequest {
    rawBody: Buffer;
    victim?: Victim & { build: Build };
  }
}

const SKEW = 300;

function verifyWhich(tokens: Array<[string, string | null]>, method: string, path: string, timestamp: string, nonce: string, body: Buffer, signature: string): string | null {
  for (const [kind, token] of tokens) {
    if (token && verifyVictim(token, method, path, timestamp, nonce, body, signature)) return kind;
  }
  return null;
}

export async function authenticateVictim(request: FastifyRequest, _reply: FastifyReply): Promise<void> {
  const id = request.headers['x-victim-id'];
  const timestamp = request.headers['x-timestamp'];
  const nonce = request.headers['x-nonce'];
  const signature = request.headers['x-signature'];
  if (
    typeof id !== 'string' ||
    typeof timestamp !== 'string' ||
    typeof nonce !== 'string' ||
    typeof signature !== 'string'
  ) {
    throw unauth('bad_signature');
  }
  if (nonce.length < 16) throw unauth('bad_nonce');
  const now = Math.floor(Date.now() / 1000);
  if (Math.abs(now - Number(timestamp)) > SKEW) throw unauth('clock_skew');

  let victim = await prisma.victim.findUnique({ where: { id }, include: { build: true } });
  if (!victim) throw unauth('unknown_victim');
  if (victim.revokedAt || victim.build.revokedAt) throw new HttpError(403, 'revoked', 'victim revoked');
  if (!(await claimNonce(id, nonce))) throw new HttpError(409, 'replay', 'nonce already used');

  // Lazy promotion: no cron is required for an expired unverified enroll.
  const promoted = promoteExpired(victim);
  if (promoted) {
    // Never overwrite a token a concurrent confirmed enroll just rotated.
    await prisma.victim.updateMany({
      where: { id, deviceTokenEnc: victim.deviceTokenEnc, pendingTokenEnc: victim.pendingTokenEnc },
      data: promoted
    });
    victim = await prisma.victim.findUnique({ where: { id }, include: { build: true } }) ?? victim;
  }

  const active = openSecretLoose(victim.deviceTokenEnc);
  const nowMs = Date.now();
  const previous = victim.prevTokenEnc && victim.prevTokenExpiresAt && victim.prevTokenExpiresAt.getTime() > nowMs
    ? openSecretLoose(victim.prevTokenEnc)
    : null;
  const pending = victim.pendingTokenEnc && victim.pendingTokenExpiresAt && victim.pendingTokenExpiresAt.getTime() > nowMs
    ? openSecretLoose(victim.pendingTokenEnc)
    : null;

  const path = request.url.split('?')[0]!;
  const body = request.rawBody ?? Buffer.alloc(0);
  const matched = verifyWhich([['active', active], ['previous', previous], ['pending', pending]],
    request.method, path, timestamp, nonce, body, signature);
  if (!matched) {
    const expected = hmac(unb64u(active), victimCanonical(request.method, path, timestamp, nonce, body)).toString('base64url');
    request.log.warn({ victim: id, method: request.method, path, timestamp, nonce, expected, got: signature, body: body.length }, 'victim signature mismatch');
    throw unauth('bad_signature');
  }

  // Only the agent that already held the identity confirms. A pending token
  // signing for itself must not evict the real agent during the grace window.
  if (matched !== 'pending' && victim.identityState !== 'confirmed') {
    const confirmed = await prisma.victim.update({
      where: { id },
      data: { identityState: 'confirmed', graceUntil: null, pendingTokenEnc: null, pendingTokenExpiresAt: null },
      include: { build: true }
    });
    await prisma.event.create({
      data: { victimId: id, kind: 'enroll_confirmed', data: { via: matched, ip: request.clientIp } }
    }).catch(() => undefined);
    victim = confirmed;
  }
  request.victim = victim;
}
