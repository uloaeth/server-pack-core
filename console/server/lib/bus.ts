import { EventEmitter } from 'node:events';
import { Redis } from 'ioredis';
import { env } from './env';

export type LiveEvent = {
  type: 'victim.heartbeat' | 'victim.new' | 'task.update' | 'artifact.ready' | 'event.new' | 'timeline.new' | 'wallet.detected' | 'wallet.closed' | 'notification' | 'panic';
  victimId?: string;
  at: number;
  data: Record<string, unknown>;
};

const CHANNEL = 'spc:live';
const local = new EventEmitter();
local.setMaxListeners(500);
let pub: Redis | null = null;
let sub: Redis | null = null;

export function initBus(): void {
  if (!env.REDIS_URL) return;
  pub = new Redis(env.REDIS_URL, { lazyConnect: true, maxRetriesPerRequest: 2 });
  sub = new Redis(env.REDIS_URL, { lazyConnect: true, maxRetriesPerRequest: 2 });
  pub.connect().catch(() => (pub = null));
  sub.connect().catch(() => (sub = null));
  sub.on('message', (_channel, payload) => {
    try {
      local.emit('live', JSON.parse(payload) as LiveEvent);
    } catch {
      // ignore malformed
    }
  });
  sub.subscribe(CHANNEL).catch(() => undefined);
}

export async function publish(event: LiveEvent): Promise<void> {
  if (pub) {
    try {
      await pub.publish(CHANNEL, JSON.stringify(event));
    } catch {
      // fall through to local
    }
  }
  local.emit('live', event);
}

export function subscribe(listener: (event: LiveEvent) => void): () => void {
  local.on('live', listener);
  return () => local.off('live', listener);
}
