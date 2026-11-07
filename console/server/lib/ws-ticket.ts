import { randomBytes } from 'node:crypto';

/** Single-use control-channel tickets, memory only. See contract/v1/ws.md §0. */
export const TICKET_TTL_MS = 30_000;
const MAX_LIVE_PER_DEVICE = 4;
/** Consumed/expired tickets stay resolvable long enough to sign a 4401 bye. */
const GRACE_MS = 60_000;

export type Ticket = {
  value: string;
  deviceId: string;
  nonce: string;
  expiresAt: number;
  createdAt: number;
};

export type ConsumeResult =
  | { ok: true; ticket: Ticket }
  | { ok: false; reason: 'unknown' | 'expired' | 'reused' | 'evicted' | 'device'; ticket?: Ticket };

const live = new Map<string, Ticket>();
const dead = new Map<string, { ticket: Ticket; reason: 'expired' | 'reused' | 'evicted' | 'device' }>();

function sweep(now: number): void {
  for (const [value, ticket] of live) {
    if (ticket.expiresAt <= now) {
      live.delete(value);
      dead.set(value, { ticket, reason: 'expired' });
    }
  }
  for (const [value, entry] of dead) {
    if (entry.ticket.expiresAt + GRACE_MS <= now) dead.delete(value);
  }
}

/** Issue one ticket, evicting the oldest still-live ticket of that device. */
export function issueTicket(deviceId: string): Ticket {
  const now = Date.now();
  sweep(now);
  const own = Array.from(live.values()).filter((ticket) => ticket.deviceId === deviceId);
  while (own.length >= MAX_LIVE_PER_DEVICE) {
    const oldest = own.shift()!;
    live.delete(oldest.value);
    dead.set(oldest.value, { ticket: oldest, reason: 'evicted' });
  }
  const ticket: Ticket = {
    value: randomBytes(32).toString('base64url'),
    deviceId,
    nonce: randomBytes(16).toString('base64url'),
    expiresAt: now + TICKET_TTL_MS,
    createdAt: now
  };
  live.set(ticket.value, ticket);
  return ticket;
}

/** Consume atomically. A foreign device also burns the ticket. */
export function consumeTicket(value: string, deviceId: string): ConsumeResult {
  const now = Date.now();
  sweep(now);
  const ticket = live.get(value);
  if (!ticket) {
    const past = dead.get(value);
    return { ok: false, reason: past?.reason ?? 'unknown', ticket: past?.ticket };
  }
  live.delete(value);
  if (ticket.expiresAt <= now) {
    dead.set(value, { ticket, reason: 'expired' });
    return { ok: false, reason: 'expired', ticket };
  }
  if (ticket.deviceId !== deviceId) {
    dead.set(value, { ticket, reason: 'device' });
    return { ok: false, reason: 'device', ticket };
  }
  dead.set(value, { ticket, reason: 'reused' });
  return { ok: true, ticket };
}

/** Test-only view. */
export function liveTicketCount(deviceId?: string): number {
  const tickets = Array.from(live.values());
  return deviceId ? tickets.filter((ticket) => ticket.deviceId === deviceId).length : tickets.length;
}
