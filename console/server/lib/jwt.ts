import { SignJWT, jwtVerify, type JWTPayload } from 'jose';
import type { FastifyReply, FastifyRequest } from 'fastify';
import { env } from './env';
import { sha256hex } from './crypto';

const key = new TextEncoder().encode(env.JWT_SECRET);
export const COOKIE = 'spc_session';
export const PENDING_COOKIE = 'spc_pending';

export type Claims = JWTPayload & {
  sub: string;
  role: 'admin' | 'operator' | 'viewer';
  sid: string;
};

export async function signSession(operatorId: string, role: string, sid: string): Promise<string> {
  return new SignJWT({ role, sid })
    .setProtectedHeader({ alg: 'HS256' })
    .setSubject(operatorId)
    .setIssuedAt()
    .setExpirationTime(`${env.JWT_TTL_MIN}m`)
    .sign(key);
}

export async function verifySession(token: string): Promise<Claims | null> {
  try {
    const { payload } = await jwtVerify(token, key, { algorithms: ['HS256'] });
    if (!payload.sub || typeof payload.sid !== 'string' || typeof payload.role !== 'string') return null;
    return payload as Claims;
  } catch {
    return null;
  }
}

export const sessionHash = (token: string): string => sha256hex(token);

export function setSessionCookie(reply: FastifyReply, token: string): void {
  reply.setCookie(COOKIE, token, {
    httpOnly: true,
    sameSite: 'strict',
    secure: env.isProd,
    path: '/',
    maxAge: env.JWT_TTL_MIN * 60
  });
}

export function clearSessionCookie(reply: FastifyReply): void {
  reply.clearCookie(COOKIE, { path: '/' });
}

export async function signPending(operatorId: string): Promise<string> {
  return new SignJWT({ purpose: 'totp' })
    .setProtectedHeader({ alg: 'HS256' })
    .setSubject(operatorId)
    .setIssuedAt()
    .setExpirationTime('10m')
    .sign(key);
}

export async function readPending(request: FastifyRequest): Promise<string | null> {
  const token = request.cookies[PENDING_COOKIE];
  if (!token) return null;
  try {
    const { payload } = await jwtVerify(token, key, { algorithms: ['HS256'] });
    return payload.purpose === 'totp' && payload.sub ? payload.sub : null;
  } catch {
    return null;
  }
}

export function setPendingCookie(reply: FastifyReply, token: string): void {
  reply.setCookie(PENDING_COOKIE, token, {
    httpOnly: true,
    sameSite: 'strict',
    secure: env.isProd,
    path: '/',
    maxAge: 600
  });
}
