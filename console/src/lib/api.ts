export const API = '';

let redirecting = false;

function sendToLogin(): void {
  if (redirecting) return;
  redirecting = true;
  const next = encodeURIComponent(`${location.pathname}${location.search}`);
  location.assign(`/login?expired=1&next=${next}`);
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  if (init?.body !== undefined && init.body !== null && !headers.has('content-type')) {
    headers.set('content-type', 'application/json');
  }
  const response = await fetch(`${API}/api/console${path}`, {
    ...init,
    headers,
    credentials: 'same-origin'
  });
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith('/auth/')) sendToLogin();
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

export function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}
