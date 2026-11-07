export const API = '';

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API}/api/console${path}`, {
    ...init,
    headers: { 'content-type': 'application/json', ...(init?.headers ?? {}) },
    credentials: 'same-origin'
  });
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as { message?: string; error?: string };
    throw new ApiError(response.status, body.error ?? 'http', body.message ?? response.statusText);
  }
  const type = response.headers.get('content-type') ?? '';
  if (type.includes('application/json')) return (await response.json()) as T;
  return (await response.text()) as T;
}

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}
