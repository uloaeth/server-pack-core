import type { ZodError } from 'zod';

export class HttpError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public retryAfter = 0,
    public data: Record<string, unknown> = {}
  ) {
    super(message);
  }
}

export const bad = (code: string, message: string, status = 400) => new HttpError(status, code, message);
export const unauth = (code = 'unauthorized') => new HttpError(401, code, 'authentication required');
export const forbidden = (code = 'forbidden') => new HttpError(403, code, 'not allowed');
export const notFound = (what = 'resource') => new HttpError(404, 'not_found', `${what} not found`);

/**
 * `instanceof` breaks when a loader (tsx under Node 20) evaluates the same
 * module twice: the route's HttpError and the handler's are different
 * constructors. Shape check keeps 404/401/429 typed in every loader.
 */
export function isHttpError(error: unknown): error is HttpError {
  if (typeof error !== 'object' || error === null) return false;
  const candidate = error as { status?: unknown; code?: unknown };
  return typeof candidate.status === 'number' && typeof candidate.code === 'string';
}

export function isZodError(error: unknown): error is ZodError {
  return typeof error === 'object' && error !== null && (error as { name?: string }).name === 'ZodError';
}
