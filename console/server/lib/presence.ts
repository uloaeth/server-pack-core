/**
 * Presence is heartbeat-first but a Discord-only agent has no API heartbeat:
 * its last real message is the liveness signal. Bridge pings are outbound and
 * never count, so a dead agent goes stale after the same 90 s window.
 */
export const STALE_MS = 90_000;

export function lastSignal(heartbeat: Date | string | number, agentMessage: Date | string | number): number {
  return Math.max(new Date(heartbeat).getTime(), new Date(agentMessage).getTime());
}

export function isOnline(heartbeat: Date | string | number, agentMessage: Date | string | number, now = Date.now()): boolean {
  const signal = lastSignal(heartbeat, agentMessage);
  return Number.isFinite(signal) && now - signal < STALE_MS;
}
