import type { FastifyRequest, FastifyReply } from 'fastify';
import type { Operator } from '@prisma/client';
import { verifySession, COOKIE, type Claims } from '../lib/jwt';
import { prisma } from '../lib/db';
import { forbidden, unauth } from '../lib/errors';
import { allowlisted, clientIp } from '../lib/ip';
import { env } from '../lib/env';
import { audit } from '../lib/audit';

declare module 'fastify' {
  interface FastifyRequest {
    operator?: Operator;
    claims?: Claims;
    clientIp: string;
  }
}

export const ROLE_RANK = { viewer: 1, operator: 2, admin: 3 } as const;
export type Role = keyof typeof ROLE_RANK;

export function requireRole(min: Role) {
  return async (request: FastifyRequest) => {
    const operator = request.operator;
    if (!operator) throw unauth();
    if (ROLE_RANK[operator.role] < ROLE_RANK[min]) {
      await audit(prisma, { operatorId: operator.id, action: 'rbac.deny', entity: 'role', ip: request.clientIp, data: { required: min, method: request.method, path: request.url } });
      throw forbidden('role_too_low');
    }
  };
}

export async function authenticate(request: FastifyRequest): Promise<void> {
  request.clientIp = clientIp(request.headers, request.socket.remoteAddress ?? '', env.TRUST_PROXY);
  if (!allowlisted(request.clientIp, env.allowlist)) throw forbidden('ip_not_allowlisted');
  const token = request.cookies[COOKIE];
  if (!token) throw unauth();
  const claims = await verifySession(token);
  if (!claims) throw unauth('bad_session');
  const session = await prisma.session.findUnique({
    where: { id: claims.sid },
    include: { operator: true }
  });
  if (!session || session.revokedAt || session.expiresAt < new Date()) throw unauth('session_expired');
  if (session.operator.disabled) throw forbidden('operator_disabled');
  request.operator = session.operator;
  request.claims = claims;
}

/** Mutating console calls must come from the console origin. */
export async function sameOrigin(request: FastifyRequest, reply: FastifyReply): Promise<FastifyReply | void> {
  if (['GET', 'HEAD', 'OPTIONS'].includes(request.method)) return;
  if (request.url.startsWith('/api/v1/')) return; // agent HMAC covers these
  const origin = request.headers.origin;
  if (!origin) return;
  const allowed = (env.CORS_ORIGIN || env.PUBLIC_URL).split(',').map((s) => s.trim());
  if (!allowed.includes(origin)) {
    return reply.code(403).send({ error: 'bad_origin', message: 'origin not allowed' });
  }
}
