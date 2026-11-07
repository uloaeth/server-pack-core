import { Redis } from 'ioredis';
import { env } from './env';

const TTL_MS = 900_000;
const seen = new Map<string, number>();

let redis: Redis | null = null;
if (env.REDIS_URL) {
  redis = new Redis(env.REDIS_URL, { lazyConnect: true, maxRetriesPerRequest: 2 });
  redis.connect().catch(() => (redis = null));
}

function sweep(): void {
  if (seen.size < 5000) return;
  const now = Date.now();
  for (const [key, at] of seen) if (now - at > TTL_MS) seen.delete(key);
}

/** true when the nonce is fresh; false on replay. */
export async function claimNonce(id: string, nonce: string): Promise<boolean> {
  const key = `${id}:${nonce}`;
  if (redis) {
    try {
      const ok = await redis.set(`spc:n:${key}`, '1', 'PX', TTL_MS, 'NX');
      if (ok !== 'OK') return false;
    } catch {
      // fall back to memory
    }
  }
  sweep();
  if (seen.has(key)) return false;
  seen.set(key, Date.now());
  return true;
}
