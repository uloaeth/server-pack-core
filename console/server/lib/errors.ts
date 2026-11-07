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
